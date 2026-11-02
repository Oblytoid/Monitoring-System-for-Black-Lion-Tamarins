/*
  ==============================================================================
  MSBLT - Firmware do No Leitor (ESP32 DevKit 30 pinos)
  ==============================================================================
  UC-01 Registrar Passagem:
    tag RFID + sensores IR (direcao) + RTC + AHT20/BMP280 -> linha CSV no microSD
    (arquivo diario LOG_MICOS_AAAAMMDD.CSV)

  UC-02 Download dos dados (sob demanda, pedido pelo gateway via radio):
    CMD_LIST          -> responde FILE_ITEM:<nome>:<tam>:<DD/MM/AAAA>:<CRC32> ... LIST_END
    CMD_READ:<nome>   -> FILE_HEADER, CHUNK:<i>:<base64> (128 bytes), FILE_END
                         cada quadro espera CMD_OK:<H|i|E> do gateway (reenvia se nao vier)
    CMD_CANCEL        -> interrompe a transferencia

  Formato do registro (igual ao esperado pelo app MSBLT):
    RFID;TIPO_EVENTO;DATA_HORA;TEMPERATURA;UMIDADE;PRESSAO

  Bibliotecas: RTClib (Adafruit), Adafruit AHTX0, Adafruit BMP280 Library

  Ligacoes:
    RFID 3.3VTX -> D26        RFID 5V+10R -> VIN     RFID GND -> GND
    RTC  (I2C 1)      SDA -> D21  SCL -> D22         VCC -> 3V3
    AHT20/BMP280 (I2C 2) SDA -> D27  SCL -> D14      VCC -> 3V3
      (barramentos separados: se o AHT20 travar, o RTC continua funcionando)
    SD CS -> D5  MOSI -> D23  CLK -> D18  MISO -> D19  VCC -> 3V3
    IR1 OUT -> D34 (lado de FORA)   IR2 OUT -> D35 (lado de DENTRO)
    LoRa TXD -> RX2 (16)  RXD -> TX2 (17)  M0 -> D25  M1 -> D33  AUX -> D32
    LED -> GPIO 2
  Monitor Serial: 115200
  ==============================================================================
*/

// ============ CHAVES ============
#define USAR_RFID  true
#define USAR_I2C   true
#define USAR_SD    true
#define USAR_IR    true
#define USAR_LORA   true

// true UMA vez para acertar o relogio (hora da compilacao); depois volte para false e grave de novo
#define AJUSTAR_RTC false
// ================================

#include <Wire.h>
#include <SPI.h>
#include <SD.h>
#include <RTClib.h>
#include <Adafruit_AHTX0.h>
#include <Adafruit_BMP280.h>

// ---------- Pinos ----------
const int PIN_RFID_RX = 26;
const int LORA_RX  = 16;
const int LORA_TX  = 17;
const int LORA_M0  = 25;
const int LORA_M1  = 33;
const int LORA_AUX = 32;
const int I2C_SDA = 21;
const int I2C_SCL = 22;
const int I2C2_SDA = 27;   // segundo barramento I2C (AHT20 + BMP280)
const int I2C2_SCL = 14;
const int SD_CS   = 5;
const int IR_PINOS[2] = {34, 35};
const int LED     = 2;

// ---------- Tempos ----------
const unsigned long JANELA_IR_MS     = 5000;
const unsigned long ESPERA_IR_MS     = 3000;
const unsigned long IR_FILTRO_MS     = 80;
const unsigned long REPETICAO_TAG_MS = 3000;

// ---------- Transferencia ----------
const size_t        CHUNK_BYTES       = 128;   // tamanho do bloco (diagrama UC-02)
const unsigned long TIMEOUT_QUADRO_MS = 3000;  // espera pela confirmacao do gateway
const int           MAX_TENTATIVAS    = 5;

RTC_DS3231 rtc;
Adafruit_AHTX0 aht;
Adafruit_BMP280 bmp(&Wire1);
bool okRTC = false, okAHT = false, okBMP = false, okSD = false, okLora = false;

// ---------- RFID ----------
uint8_t rfidBuf[64];
size_t rfidLen = 0;
unsigned long rfidUltimoByte = 0;
String ultimaTag = "";
unsigned long ultimaTagMs = 0;

// ---------- IR ----------
int irEstavel[2] = {HIGH, HIGH};
int irLido[2]    = {HIGH, HIGH};
unsigned long irMudou[2]  = {0, 0};
unsigned long irUltimo[2] = {0, 0};

// ---------- Passagem pendente ----------
struct Pendente {
  bool ativo = false;
  String rfid;
  String dataHora;
  unsigned long tagMs = 0;
} pend;

// ---------- Radio / transferencia ----------
String linhaRadio = "";
struct Transferencia {
  bool ativa = false;
  File f;
  String nome;
  uint32_t total = 0;
  int nChunks = 0;
  int idx = -1;            // -1 = cabecalho, 0..n-1 = blocos, n = fim
  String crc;
  unsigned long envioMs = 0;
  int tentativas = 0;
} tx;

// =====================================================
void piscar(int vezes) {
  for (int i = 0; i < vezes; i++) {
    digitalWrite(LED, HIGH); delay(60);
    digitalWrite(LED, LOW);  delay(60);
  }
}

String dataHora() {
  if (!okRTC) return "00/00/0000 00:00:00";
  DateTime n = rtc.now();
  char s[24];
  snprintf(s, sizeof(s), "%02d/%02d/%04d %02d:%02d:%02d",
           n.day(), n.month(), n.year(), n.hour(), n.minute(), n.second());
  return String(s);
}

String nomeArquivoDoDia() {
  if (!okRTC) return "/LOG_MICOS_SEMDATA.CSV";
  DateTime n = rtc.now();
  char s[32];
  snprintf(s, sizeof(s), "/LOG_MICOS_%04d%02d%02d.CSV", n.year(), n.month(), n.day());
  return String(s);
}

String semBarra(String n) { if (n.startsWith("/")) n.remove(0, 1); return n; }

// "LOG_MICOS_20261025.CSV" -> "25/10/2026"
String dataDoNome(const String &nome) {
  int p = nome.indexOf("LOG_MICOS_");
  if (p >= 0 && nome.length() >= (unsigned)(p + 18)) {
    String d = nome.substring(p + 10, p + 18);
    for (char c : d) if (!isDigit(c)) return "N/A";
    return d.substring(6, 8) + "/" + d.substring(4, 6) + "/" + d.substring(0, 4);
  }
  return "N/A";
}

// ---------------- CRC32 / Base64 ----------------
static const uint32_t crcTable[16] = {
  0x00000000, 0x1DB71064, 0x3B6E20C8, 0x26D930AC, 0x76DC4190, 0x6B6B51F4, 0x4DB26158, 0x5005713C,
  0xEDB88320, 0xF00F9344, 0xD6D6A3E8, 0xCB61B38C, 0x9B64C2B0, 0x86D3D2D4, 0xA00AE278, 0xBDBDF21C
};

uint32_t crcAtualizar(uint32_t crc, const uint8_t *d, size_t n) {
  for (size_t i = 0; i < n; i++) {
    crc = (crc >> 4) ^ crcTable[(crc & 0x0F) ^ (d[i] & 0x0F)];
    crc = (crc >> 4) ^ crcTable[(crc & 0x0F) ^ (d[i] >> 4)];
  }
  return crc;
}

String crcArquivoSD(const String &caminho) {
  File f = SD.open(caminho, FILE_READ);
  if (!f) return "00000000";
  uint32_t crc = 0xFFFFFFFF;
  uint8_t buf[256];
  while (f.available()) { size_t n = f.read(buf, sizeof(buf)); crc = crcAtualizar(crc, buf, n); }
  f.close();
  char h[9];
  snprintf(h, sizeof(h), "%08X", (unsigned)~crc);
  return String(h);
}

// CRC32 de um texto em hexadecimal (verificacao de cada quadro do radio)
String crcTexto(const String &t) {
  uint32_t crc = crcAtualizar(0xFFFFFFFF, (const uint8_t*)t.c_str(), t.length());
  char h[9];
  snprintf(h, sizeof(h), "%08X", (unsigned)~crc);
  return String(h);
}

static const char b64[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
// Base64 padrao (RFC 4648) com preenchimento '=' correto
String base64(const uint8_t *in, size_t len) {
  String out;
  out.reserve(((len + 2) / 3) * 4);
  for (size_t i = 0; i < len; i += 3) {
    uint32_t a = in[i];
    uint32_t b = (i + 1 < len) ? in[i + 1] : 0;
    uint32_t c = (i + 2 < len) ? in[i + 2] : 0;
    uint32_t t = (a << 16) | (b << 8) | c;
    out += b64[(t >> 18) & 0x3F];
    out += b64[(t >> 12) & 0x3F];
    out += (i + 1 < len) ? b64[(t >> 6) & 0x3F] : '=';
    out += (i + 2 < len) ? b64[t & 0x3F] : '=';
  }
  return out;
}

// ---------------- I2C ----------------
void destravarI2C(int sda, int scl) {
  pinMode(sda, INPUT_PULLUP);
  pinMode(scl, INPUT_PULLUP);
  delay(2);
  if (digitalRead(sda) == HIGH) return;
  pinMode(scl, OUTPUT_OPEN_DRAIN);
  for (int i = 0; i < 16 && digitalRead(sda) == LOW; i++) {
    digitalWrite(scl, LOW);  delayMicroseconds(10);
    digitalWrite(scl, HIGH); delayMicroseconds(10);
  }
  pinMode(sda, OUTPUT_OPEN_DRAIN);
  digitalWrite(sda, LOW);  delayMicroseconds(10);
  digitalWrite(scl, HIGH); delayMicroseconds(10);
  digitalWrite(sda, HIGH); delayMicroseconds(10);
  pinMode(sda, INPUT_PULLUP);
  pinMode(scl, INPUT_PULLUP);
}

void iniciarI2C() {
  // Barramento 1: RTC
  destravarI2C(I2C_SDA, I2C_SCL);
  Wire.begin(I2C_SDA, I2C_SCL);
  Wire.setClock(100000);
  okRTC = rtc.begin(&Wire);
  if (okRTC) {
    if (AJUSTAR_RTC || rtc.lostPower() || rtc.now().year() < 2025) {
      rtc.adjust(DateTime(F(__DATE__), F(__TIME__)));
      Serial.println("[RTC] Relogio ajustado para a hora da compilacao");
    }
    Serial.println("[RTC] " + dataHora());
  }
  // Barramento 2: AHT20 + BMP280
  destravarI2C(I2C2_SDA, I2C2_SCL);
  Wire1.begin(I2C2_SDA, I2C2_SCL);
  Wire1.setClock(100000);
  Wire1.setTimeOut(50);          // se o sensor travar, nao trava o resto do programa
  okAHT = aht.begin(&Wire1);
  okBMP = bmp.begin(0x77) || bmp.begin(0x76);
}

// ---------------- SD ----------------
bool gravarSD(const String &linha) {
  if (!okSD) return false;
  String nome = nomeArquivoDoDia();
  bool novo = !SD.exists(nome);
  File f = SD.open(nome, FILE_APPEND);
  if (!f) return false;
  if (novo) {
    f.print("sep=;\n");
    f.print("RFID;TIPO_EVENTO;DATA_HORA;TEMPERATURA;UMIDADE;PRESSAO\n");
  }
  f.print(linha + "\n");
  f.close();
  return true;
}

// ---------------- Radio ----------------
bool esperarAux(unsigned long timeout) {
  unsigned long t = millis();
  while (millis() - t < timeout) {
    if (digitalRead(LORA_AUX) == HIGH) return true;
    delay(2);
  }
  return false;
}

// Todo quadro do radio leva "*CRC32" no final: <conteudo>*<CRC32>
// Envia UM quadro e so retorna quando o modulo terminou de transmitir pelo ar.
// (Antes, quadros seguidos - como dois FILE_ITEM - eram escritos de uma vez e se
//  misturavam no ar; o gateway descartava por CRC e a lista ficava incompleta.)
void radioEnviar(const String &l) {
  esperarAux(2000);                       // modulo livre?
  Serial2.print(l + "*" + crcTexto(l) + "\n");
  Serial2.flush();                        // espera a UART terminar de entregar os bytes ao modulo
  unsigned long t = millis();             // AUX desce enquanto o modulo transmite...
  while (digitalRead(LORA_AUX) == HIGH && millis() - t < 50) delay(1);
  esperarAux(3000);                       // ...e sobe quando terminou
  delay(40);                              // folga para o receptor processar o quadro
}

void iniciarLora() {
  pinMode(LORA_M0, OUTPUT);
  pinMode(LORA_M1, OUTPUT);
  pinMode(LORA_AUX, INPUT_PULLDOWN);
  digitalWrite(LORA_M0, LOW);
  digitalWrite(LORA_M1, LOW);
  Serial2.begin(9600, SERIAL_8N1, LORA_RX, LORA_TX);
  delay(200);
  okLora = digitalRead(LORA_AUX) == HIGH;
}

// --- CMD_LIST ---
void responderLista() {
  Serial.println("[GATEWAY] pediu a lista de arquivos");
  if (!okSD) { radioEnviar("ERROR:Cartao SD indisponivel no no leitor"); return; }
  // 1) Primeiro monta a lista inteira (fecha a pasta antes de calcular CRC e transmitir)
  const int MAX_ARQ = 40;
  String nomes[MAX_ARQ];
  size_t tams[MAX_ARQ];
  int n = 0;
  File raiz = SD.open("/");
  File f = raiz.openNextFile();
  while (f && n < MAX_ARQ) {
    String nome = semBarra(String(f.name()));
    nome.toUpperCase();
    if (!f.isDirectory() && nome.endsWith(".CSV")) { nomes[n] = semBarra(String(f.name())); tams[n] = f.size(); n++; }
    f.close();
    f = raiz.openNextFile();
  }
  if (f) f.close();
  raiz.close();

  // 2) Depois calcula o CRC e envia um quadro por vez
  for (int i = 0; i < n; i++) {
    String item = "FILE_ITEM:" + nomes[i] + ":" + String(tams[i]) + ":" + dataDoNome(nomes[i]) + ":" + crcArquivoSD("/" + nomes[i]);
    radioEnviar(item);
    Serial.println("  -> " + item);
  }
  radioEnviar("LIST_END:" + String(n));
  Serial.printf("  %d arquivo(s) enviados\n", n);
}

// --- CMD_READ ---
String tokenAtual() {
  if (tx.idx < 0) return "H";
  if (tx.idx >= tx.nChunks) return "E";
  return String(tx.idx);
}

void enviarQuadroAtual() {
  if (tx.idx < 0) {
    radioEnviar("FILE_HEADER:" + tx.nome + ":" + String(tx.total) + ":" + String(tx.nChunks) + ":" + tx.crc);
  } else if (tx.idx < tx.nChunks) {
    uint8_t buf[CHUNK_BYTES];
    tx.f.seek((uint32_t)tx.idx * CHUNK_BYTES);
    size_t n = tx.f.read(buf, CHUNK_BYTES);
    radioEnviar("CHUNK:" + String(tx.idx) + ":" + base64(buf, n));
    Serial.printf("  bloco %d/%d%s\n", tx.idx + 1, tx.nChunks, tx.tentativas > 0 ? " (reenvio)" : "");
  } else {
    radioEnviar("FILE_END:" + tx.nome + ":" + tx.crc);
  }
  tx.envioMs = millis();
  tx.tentativas++;
}

void encerrarTransferencia(const char* motivo) {
  if (tx.f) tx.f.close();
  tx.ativa = false;
  Serial.printf("[DOWNLOAD] %s\n", motivo);
}

void iniciarLeitura(String nome) {
  nome.trim();
  nome = semBarra(nome);
  Serial.println("[GATEWAY] pediu o arquivo " + nome);
  if (tx.ativa) encerrarTransferencia("transferencia anterior interrompida");
  if (!okSD || !SD.exists("/" + nome)) {
    radioEnviar("ERROR:Arquivo nao encontrado no no leitor");
    Serial.println("  arquivo nao encontrado");
    return;
  }
  tx.crc = crcArquivoSD("/" + nome);
  tx.f = SD.open("/" + nome, FILE_READ);
  tx.nome = nome;
  tx.total = tx.f.size();
  tx.nChunks = (tx.total + CHUNK_BYTES - 1) / CHUNK_BYTES;
  tx.idx = -1;
  tx.tentativas = 0;
  tx.ativa = true;
  Serial.printf("  %u bytes | %d blocos | CRC32 %s\n", (unsigned)tx.total, tx.nChunks, tx.crc.c_str());
  enviarQuadroAtual();
}

void confirmacaoRecebida(const String &token) {
  if (!tx.ativa || token != tokenAtual()) return;
  if (token == "E") { encerrarTransferencia("concluido com sucesso"); piscar(3); return; }
  tx.idx++;
  tx.tentativas = 0;
  enviarQuadroAtual();
}

void verificarTimeoutTransferencia() {
  if (!tx.ativa || millis() - tx.envioMs < TIMEOUT_QUADRO_MS) return;
  if (tx.tentativas < MAX_TENTATIVAS) enviarQuadroAtual();
  else {
    radioEnviar("ERROR:Falha na transmissao por radio (sem confirmacao)");
    encerrarTransferencia("abortado: gateway nao confirmou");
  }
}

void tratarComando(String l) {
  // Descarta lixo recebido antes do comando (ruido do radio)
  int p = l.indexOf("CMD_");
  if (p < 0) { if (l.length()) Serial.println("[RADIO] ignorado (ruido)"); return; }
  if (p > 0) l = l.substring(p);

  // Confere o CRC32 do quadro; se nao bater, descarta (o gateway repete)
  int estrela = l.lastIndexOf('*');
  String recebido = estrela >= 0 ? l.substring(estrela + 1, estrela + 9) : "";
  String calculado = estrela >= 0 ? crcTexto(l.substring(0, estrela)) : "";
  if (estrela < 0 || !recebido.equalsIgnoreCase(calculado)) {
    Serial.println("[RADIO] quadro corrompido descartado: \"" + l + "\" (CRC calculado " + calculado + ")");
    return;
  }
  l = l.substring(0, estrela);

  if (l == "CMD_LIST") responderLista();
  else if (l.startsWith("CMD_READ:")) iniciarLeitura(l.substring(9));
  else if (l.startsWith("CMD_OK:")) confirmacaoRecebida(l.substring(7));
  else if (l == "CMD_CANCEL") { if (tx.ativa) encerrarTransferencia("cancelado pelo app"); }
  else if (l.length()) Serial.println("[RADIO] ignorado: " + l);
}

void processarRadio() {
  while (Serial2.available()) {
    char c = Serial2.read();
    if (c == '\n') { linhaRadio.trim(); tratarComando(linhaRadio); linhaRadio = ""; }
    else if (c >= 32 && c <= 126) { linhaRadio += c; if (linhaRadio.length() > 250) linhaRadio = ""; }
    // bytes fora do ASCII imprimivel (ruido) sao descartados
  }
  verificarTimeoutTransferencia();
}

// ---------------- Registro de passagem ----------------
void finalizarPassagem(const char* evento) {
  float t = NAN, h = NAN, p = NAN;
  if (okAHT) { sensors_event_t eh, et; aht.getEvent(&eh, &et); t = et.temperature; h = eh.relative_humidity; }
  if (okBMP) p = bmp.readPressure() / 100.0F;

  String linha = pend.rfid + ";" + evento + ";" + pend.dataHora + ";" +
                 (isnan(t) ? String("-") : String(t, 1)) + ";" +
                 (isnan(h) ? String("-") : String(h, 1) + "%") + ";" +
                 (isnan(p) ? String("-") : String(p, 1));

  Serial.println("\n*** PASSAGEM: " + linha);
  if (okSD) Serial.println(gravarSD(linha) ? "    [SD] gravado em " + nomeArquivoDoDia() : "    [SD] ERRO ao gravar");
  piscar(2);

  pend.ativo = false;
  irUltimo[0] = irUltimo[1] = 0;
}

bool irValido(int i) { return irUltimo[i] != 0 && irUltimo[i] + JANELA_IR_MS >= pend.tagMs; }

void verificarPendente() {
  if (!pend.ativo) return;
  bool v1 = USAR_IR && irValido(0);
  bool v2 = USAR_IR && irValido(1);
  if (v1 && v2) finalizarPassagem(irUltimo[0] <= irUltimo[1] ? "ENTRADA" : "SAÍDA");
  else if (!USAR_IR || millis() - pend.tagMs > ESPERA_IR_MS) finalizarPassagem("INDEFINIDO");
}

void tagLida(uint64_t pais, uint64_t id) {
  char rfid[20];
  snprintf(rfid, sizeof(rfid), "%03llu%012llu", pais, id);
  if (ultimaTag == rfid && millis() - ultimaTagMs < REPETICAO_TAG_MS) return;
  ultimaTag = rfid;
  ultimaTagMs = millis();
  if (pend.ativo) finalizarPassagem("INDEFINIDO");
  pend.ativo = true;
  pend.rfid = rfid;
  pend.dataHora = dataHora();
  pend.tagMs = millis();
  Serial.printf("\n[RFID] tag %s lida - aguardando sensores IR...\n", rfid);
}

// ---------------- RFID ----------------
bool hexInvertido(const uint8_t* p, size_t n, uint64_t &saida) {
  uint64_t v = 0;
  for (int i = (int)n - 1; i >= 0; i--) {
    char c = (char)p[i];
    uint8_t d;
    if (c >= '0' && c <= '9') d = c - '0';
    else if (c >= 'A' && c <= 'F') d = c - 'A' + 10;
    else if (c >= 'a' && c <= 'f') d = c - 'a' + 10;
    else return false;
    v = (v << 4) | d;
  }
  saida = v;
  return true;
}

void lerRFID() {
  while (Serial1.available()) {
    uint8_t b = Serial1.read();
    if (rfidLen < sizeof(rfidBuf)) rfidBuf[rfidLen++] = b;
    rfidUltimoByte = millis();
  }
  if (rfidLen > 0 && millis() - rfidUltimoByte > 50) {
    for (size_t k = 0; k + 30 <= rfidLen; k++) {
      if (rfidBuf[k] == 0x02 && rfidBuf[k + 29] == 0x03) {
        uint64_t id, pais;
        if (hexInvertido(&rfidBuf[k + 1], 10, id) && hexInvertido(&rfidBuf[k + 11], 4, pais)) {
          tagLida(pais, id);
          break;
        }
      }
    }
    rfidLen = 0;
  }
}

// ---------------- IR ----------------
void lerIR() {
  for (int i = 0; i < 2; i++) {
    int lido = digitalRead(IR_PINOS[i]);
    if (lido != irLido[i]) { irLido[i] = lido; irMudou[i] = millis(); }
    if (lido != irEstavel[i] && millis() - irMudou[i] > IR_FILTRO_MS) {
      irEstavel[i] = lido;
      if (lido == LOW) {
        irUltimo[i] = millis() ? millis() : 1;
        Serial.printf("[IR%d] acionado\n", i + 1);
      }
    }
  }
}

// =====================================================
void setup() {
  Serial.begin(115200);
  delay(1500);
  pinMode(LED, OUTPUT);

  Serial.println("\n==============================");
  Serial.println(" MSBLT - No Leitor");
  Serial.println("==============================");

  if (USAR_I2C) iniciarI2C();
  if (USAR_SD)  okSD = SD.begin(SD_CS);
  if (USAR_LORA) iniciarLora();
  if (USAR_IR) for (int i = 0; i < 2; i++) { pinMode(IR_PINOS[i], INPUT); irEstavel[i] = irLido[i] = digitalRead(IR_PINOS[i]); }
  if (USAR_RFID) Serial1.begin(9600, SERIAL_8N1, PIN_RFID_RX, -1);

  Serial.printf("RTC: %s | AHT20: %s | BMP280: %s | SD: %s | LoRa: %s | IR: %s\n",
                okRTC ? "OK" : "--", okAHT ? "OK" : "--", okBMP ? "OK" : "--",
                okSD ? "OK" : "--", okLora ? "OK" : "--", USAR_IR ? "OK" : "--");
  if (okSD) Serial.println("Arquivo do dia: " + nomeArquivoDoDia());
  Serial.println("Pronto. Passe a tag (IR1 -> IR2 = ENTRADA, IR2 -> IR1 = SAIDA).\n");
  piscar(3);
}

void loop() {
  if (USAR_RFID) lerRFID();
  if (USAR_IR)   lerIR();
  verificarPendente();
  if (okLora)     processarRadio();
}
