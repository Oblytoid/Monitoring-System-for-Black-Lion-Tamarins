/*
  ==============================================================================
  MSBLT - Firmware do Gateway Portatil (ESP32 comum: DevKit V1 / WROOM-32)
  ==============================================================================
  Ponte entre o app (Bluetooth Classic SPP) e o no leitor (radio LoRa E220-900T22D).
  Os arquivos ficam no microSD do NO LEITOR; o gateway apenas repassa (UC-02).

  App -> Gateway (Bluetooth)          Gateway -> No leitor (radio)
    LIST_FILES                   ->     CMD_LIST
    START_DOWNLOAD:<arquivo>     ->     CMD_READ:<arquivo>
    CANCEL_DOWNLOAD              ->     CMD_CANCEL

  No leitor -> Gateway (radio)        Gateway -> App (Bluetooth)
    FILE_ITEM / FILE_HEADER /    ->     repassados sem alteracao
    CHUNK / FILE_END / ERROR
    (o gateway confirma cada quadro com CMD_OK:<H|indice|E> e
     descarta quadros repetidos, para nao corromper o arquivo no app)

  IMPORTANTE: ESP32 comum. O ESP32-S3 nao tem Bluetooth Classic.

  Ligacoes do radio LoRa (mesmas do no leitor):
    VCC -> 3V3   GND -> GND   TXD -> RX2 (16)   RXD -> TX2 (17)
    M0 -> D25    M1 -> D33    AUX -> D32

  Arduino IDE: placa "ESP32 Dev Module"
  Monitor Serial: 115200 (digite "help" - da para testar sem o celular)
  ==============================================================================
*/

#include "BluetoothSerial.h"

#if !defined(CONFIG_BT_ENABLED) || !defined(CONFIG_BLUEDROID_ENABLED)
#error "Bluetooth Classic nao habilitado. Use a placa 'ESP32 Dev Module' (ESP32 comum, nao S3)."
#endif

#define DEVICE_NAME "ESP32_GATEWAY_ANIMAIS"
#define LED_PIN     2

const int LORA_RX  = 16;
const int LORA_TX  = 17;
const int LORA_M0  = 25;
const int LORA_M1  = 33;
const int LORA_AUX = 32;

const unsigned long TIMEOUT_NO_MS = 15000;   // sem resposta do no leitor -> erro

BluetoothSerial SerialBT;
bool isClientConnected = false;
bool okLora = false;

enum Estado { LIVRE, LISTANDO, BAIXANDO };
Estado estado = LIVRE;
bool pedidoDoApp = true;        // false quando o pedido veio do Monitor Serial (teste)
unsigned long ultimoContatoMs = 0;
int ultimoIdx = -2;             // -2 nada repassado, -1 cabecalho repassado, >=0 ultimo bloco
bool fimRepassado = false;
int itensListados = 0;
unsigned long inicioDownloadMs = 0;
String linhaRadio = "";

// ==============================================================================
// CRC32 de cada quadro do radio
static const uint32_t crcTable[16] = {
  0x00000000, 0x1DB71064, 0x3B6E20C8, 0x26D930AC, 0x76DC4190, 0x6B6B51F4, 0x4DB26158, 0x5005713C,
  0xEDB88320, 0xF00F9344, 0xD6D6A3E8, 0xCB61B38C, 0x9B64C2B0, 0x86D3D2D4, 0xA00AE278, 0xBDBDF21C
};

String crcTexto(const String &t) {
  uint32_t crc = 0xFFFFFFFF;
  const uint8_t *d = (const uint8_t*)t.c_str();
  for (size_t i = 0; i < t.length(); i++) {
    crc = (crc >> 4) ^ crcTable[(crc & 0x0F) ^ (d[i] & 0x0F)];
    crc = (crc >> 4) ^ crcTable[(crc & 0x0F) ^ (d[i] >> 4)];
  }
  char h[9];
  snprintf(h, sizeof(h), "%08X", (unsigned)~crc);
  return String(h);
}

unsigned long quadrosCorrompidos = 0;

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
void radioEnviar(const String &l) {
  esperarAux(2000);
  Serial2.print(l + "*" + crcTexto(l) + "\n");
  Serial2.flush();
  unsigned long t = millis();
  while (digitalRead(LORA_AUX) == HIGH && millis() - t < 50) delay(1);
  esperarAux(3000);
  delay(40);
}

// ---------- Lista de arquivos (com nova tentativa se vier incompleta) ----------
const unsigned long SILENCIO_LISTA_MS = 5000;  // sem quadros durante a lista -> pede de novo
const int MAX_TENTATIVAS_LISTA = 3;
String nomesListados[40];
int tentativasLista = 0;
bool jaListado(const String &nome) {
  for (int i = 0; i < itensListados; i++) if (nomesListados[i] == nome) return true;
  return false;
}

void paraApp(const String &l) {
  if (pedidoDoApp && isClientConnected) SerialBT.println(l);
}

void voltarLivre() {
  estado = LIVRE;
  digitalWrite(LED_PIN, isClientConnected ? HIGH : LOW);
}

// ==============================================================================
// Mensagens vindas do no leitor
// ==============================================================================
void tratarRadio(String l) {
  // Descarta lixo recebido antes da mensagem (ruido do radio)
  const char* prefixos[] = {"FILE_ITEM:", "LIST_END", "FILE_HEADER:", "CHUNK:", "FILE_END:", "ERROR:"};
  int inicio = -1;
  for (const char* pr : prefixos) {
    int p = l.indexOf(pr);
    if (p >= 0 && (inicio < 0 || p < inicio)) inicio = p;
  }
  if (inicio < 0) { if (l.length()) Serial.println("[RADIO] ignorado (ruido)"); return; }
  if (inicio > 0) l = l.substring(inicio);

  // Confere o CRC32 do quadro; se nao bater, descarta sem confirmar (o no reenvia)
  int estrela = l.lastIndexOf('*');
  String recebido = estrela >= 0 ? l.substring(estrela + 1, estrela + 9) : "";
  String calculado = estrela >= 0 ? crcTexto(l.substring(0, estrela)) : "";
  if (estrela < 0 || !recebido.equalsIgnoreCase(calculado)) {
    quadrosCorrompidos++;
    String amostra = l.length() > 60 ? l.substring(0, 30) + "..." + l.substring(l.length() - 25) : l;
    Serial.printf("  [RADIO] quadro corrompido descartado (total: %lu): \"%s\" (CRC calculado %s)\n",
                  quadrosCorrompidos, amostra.c_str(), calculado.c_str());
    return;
  }
  l = l.substring(0, estrela);   // daqui em diante, conteudo sem o CRC (igual ao que o app espera)

  ultimoContatoMs = millis();

  if (l.startsWith("FILE_ITEM:")) {
    String nome = l.substring(10, l.indexOf(':', 10));
    if (!jaListado(nome) && itensListados < 40) {   // repetidos (de uma nova tentativa) nao vao ao app
      nomesListados[itensListados++] = nome;
      paraApp(l);
      Serial.println("  " + l);
    }
  }
  else if (l.startsWith("LIST_END")) {
    if (estado != LISTANDO) return;
    int esperado = l.indexOf(':') >= 0 ? l.substring(l.indexOf(':') + 1).toInt() : itensListados;
    if (itensListados < esperado && tentativasLista < MAX_TENTATIVAS_LISTA) {
      tentativasLista++;
      Serial.printf("[LISTA] chegaram %d de %d arquivo(s) - pedindo de novo (%d/%d)\n",
                    itensListados, esperado, tentativasLista, MAX_TENTATIVAS_LISTA);
      ultimoContatoMs = millis();
      radioEnviar("CMD_LIST");
      return;
    }
    Serial.printf("[LISTA] %d de %d arquivo(s) recebidos\n", itensListados, esperado);
    if (itensListados < esperado) paraApp("ERROR:Lista incompleta (" + String(itensListados) + " de " + String(esperado) + "). Tente novamente.");
    voltarLivre();
  }
  else if (l.startsWith("FILE_HEADER:")) {
    if (estado == BAIXANDO && ultimoIdx == -2) {
      paraApp(l);
      ultimoIdx = -1;
      Serial.println("[DOWNLOAD] " + l);
    }
    radioEnviar("CMD_OK:H");
  }
  else if (l.startsWith("CHUNK:")) {
    int p = l.indexOf(':', 6);
    if (p < 0) return;
    int idx = l.substring(6, p).toInt();
    if (estado == BAIXANDO && idx == ultimoIdx + 1) {
      paraApp(l);
      ultimoIdx = idx;
      Serial.printf("  bloco %d recebido e repassado\n", idx + 1);
      digitalWrite(LED_PIN, idx % 2);
    } else if (idx <= ultimoIdx) {
      Serial.printf("  bloco %d repetido (descartado)\n", idx + 1);
    } else {
      return;   // fora de ordem: nao confirma, o no reenvia
    }
    radioEnviar("CMD_OK:" + String(idx));
  }
  else if (l.startsWith("FILE_END:")) {
    if (estado == BAIXANDO && !fimRepassado) {
      paraApp(l);
      fimRepassado = true;
      Serial.printf("[DOWNLOAD] concluido em %.1f s (quadros corrompidos descartados: %lu)\n",
                    (millis() - inicioDownloadMs) / 1000.0, quadrosCorrompidos);
      voltarLivre();
    }
    radioEnviar("CMD_OK:E");
  }
  else if (l.startsWith("ERROR:")) {
    paraApp(l);
    Serial.println("[NO LEITOR] " + l);
    voltarLivre();
  }
}

void processarRadio() {
  while (Serial2.available()) {
    char c = Serial2.read();
    if (c == '\n') { linhaRadio.trim(); tratarRadio(linhaRadio); linhaRadio = ""; }
    else if (c >= 32 && c <= 126) { linhaRadio += c; if (linhaRadio.length() > 300) linhaRadio = ""; }
    // bytes fora do ASCII imprimivel (ruido) sao descartados
  }
  // Lista parada no meio (LIST_END perdido): pede de novo antes de desistir
  if (estado == LISTANDO && itensListados > 0 && millis() - ultimoContatoMs > SILENCIO_LISTA_MS
      && tentativasLista < MAX_TENTATIVAS_LISTA) {
    tentativasLista++;
    Serial.printf("[LISTA] sem resposta - pedindo de novo (%d/%d)\n", tentativasLista, MAX_TENTATIVAS_LISTA);
    ultimoContatoMs = millis();
    radioEnviar("CMD_LIST");
  }
  if (estado != LIVRE && millis() - ultimoContatoMs > TIMEOUT_NO_MS) {
    Serial.println("[ERRO] no leitor nao respondeu");
    paraApp("ERROR:No leitor nao respondeu (fora de alcance ou desligado)");
    voltarLivre();
  }
}

// ==============================================================================
// Pedidos (do app ou do Monitor Serial)
// ==============================================================================
void pedirLista(bool doApp) {
  pedidoDoApp = doApp;
  estado = LISTANDO;
  itensListados = 0;
  tentativasLista = 0;
  ultimoContatoMs = millis();
  Serial.println("[LISTA] solicitando ao no leitor...");
  radioEnviar("CMD_LIST");
}

void pedirArquivo(String nome, bool doApp) {
  nome.trim();
  pedidoDoApp = doApp;
  estado = BAIXANDO;
  ultimoIdx = -2;
  fimRepassado = false;
  ultimoContatoMs = millis();
  inicioDownloadMs = millis();
  Serial.println("[DOWNLOAD] solicitando " + nome + " ao no leitor...");
  radioEnviar("CMD_READ:" + nome);
}

void cancelar() {
  radioEnviar("CMD_CANCEL");
  Serial.println("[DOWNLOAD] cancelado");
  voltarLivre();
}

// ==============================================================================
// Bluetooth (app)
// ==============================================================================
void btCallback(esp_spp_cb_event_t event, esp_spp_cb_param_t *param) {
  if (event == ESP_SPP_SRV_OPEN_EVT) {
    isClientConnected = true;
    digitalWrite(LED_PIN, HIGH);
    Serial.println("\n[BLUETOOTH] Smartphone CONECTADO");
  } else if (event == ESP_SPP_CLOSE_EVT) {
    isClientConnected = false;
    digitalWrite(LED_PIN, LOW);
    Serial.println("\n[BLUETOOTH] Smartphone DESCONECTADO");
  }
}

void processarComandoBT(String cmd) {
  cmd.trim();
  if (!cmd.length()) return;
  Serial.println("\n[APP] >> " + cmd);
  if (cmd == "LIST_FILES") pedirLista(true);
  else if (cmd.startsWith("START_DOWNLOAD:")) pedirArquivo(cmd.substring(15), true);
  else if (cmd == "CANCEL_DOWNLOAD") cancelar();
  else SerialBT.println("ERROR:Comando nao reconhecido pelo Gateway");
}

// ==============================================================================
// Monitor Serial (testes sem o celular)
// ==============================================================================
void processarSerial(String in) {
  in.trim();
  if (!in.length()) return;
  if (in.equalsIgnoreCase("help") || in.equalsIgnoreCase("ajuda")) {
    Serial.println("\n--- COMANDOS ---");
    Serial.println("  status           : estado do Bluetooth e do radio");
    Serial.println("  list             : pede a lista de arquivos ao no leitor");
    Serial.println("  baixar <arquivo> : baixa um arquivo do no leitor (ex: baixar LOG_MICOS_20261025.CSV)");
    Serial.println("  cancelar         : cancela o download em andamento");
    Serial.println("----------------\n");
  } else if (in.equalsIgnoreCase("status")) {
    Serial.printf("[STATUS] BT: %s | LoRa: %s | Estado: %s | Quadros corrompidos: %lu\n",
                  isClientConnected ? "CONECTADO" : "aguardando", okLora ? "OK" : "--",
                  estado == LIVRE ? "livre" : (estado == LISTANDO ? "listando" : "baixando"),
                  quadrosCorrompidos);
  } else if (in.equalsIgnoreCase("list")) pedirLista(false);
  else if (in.startsWith("baixar ")) pedirArquivo(in.substring(7), false);
  else if (in.equalsIgnoreCase("cancelar")) cancelar();
  else Serial.println("Digite 'help' para ver os comandos.");
}

// ==============================================================================
void setup() {
  pinMode(LED_PIN, OUTPUT);
  digitalWrite(LED_PIN, LOW);
  Serial.begin(115200);
  delay(1000);

  Serial.println("\n==============================");
  Serial.println(" MSBLT - Gateway Portatil");
  Serial.println("==============================");

  pinMode(LORA_M0, OUTPUT);
  pinMode(LORA_M1, OUTPUT);
  pinMode(LORA_AUX, INPUT_PULLDOWN);
  digitalWrite(LORA_M0, LOW);
  digitalWrite(LORA_M1, LOW);
  Serial2.begin(9600, SERIAL_8N1, LORA_RX, LORA_TX);
  delay(200);
  okLora = digitalRead(LORA_AUX) == HIGH;
  Serial.printf("[LoRa] %s\n", okLora ? "OK (AUX = 1)" : "SEM RESPOSTA (AUX = 0)");

  SerialBT.register_callback(btCallback);
  if (SerialBT.begin(DEVICE_NAME)) Serial.printf("[BLUETOOTH] visivel como \"%s\"\n", DEVICE_NAME);
  else Serial.println("[BLUETOOTH] FALHA ao iniciar");

  Serial.println("\nDigite 'help' para ver os comandos.\n");
}

void loop() {
  processarRadio();
  if (SerialBT.available()) processarComandoBT(SerialBT.readStringUntil('\n'));
  if (Serial.available())   processarSerial(Serial.readStringUntil('\n'));

  if (!isClientConnected && estado == LIVRE) {
    static unsigned long ult = 0;
    if (millis() - ult > 1000) { ult = millis(); digitalWrite(LED_PIN, !digitalRead(LED_PIN)); }
  }
}
