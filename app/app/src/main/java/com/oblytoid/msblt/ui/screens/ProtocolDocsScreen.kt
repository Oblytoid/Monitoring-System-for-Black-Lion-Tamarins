package com.oblytoid.msblt.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
//Usar apenas para fim de teste!!
//Apenas para ter uma ideia enquanto testa no pc, não usar na versão final para o celulares
@Composable
fun ProtocolDocsScreen(
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Arquitetura e Protocolo de Transmissão TCC",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Solução de IoT: Nó Leitor (SD Card) ➔ LoRa ➔ ESP32 Gateway ➔ Bluetooth SPP ➔ App Mobile com separação em colunas para Excel e verificação CRC32.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "1. Especificação do Formato CSV (Delimitador ';' para Excel)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = """
                        • Cabeçalho do CSV:
                          sep=;
                          RFID;TIPO_EVENTO;DATA_HORA;TEMPERATURA;UMIDADE

                        • Exemplo de Linhas:
                          982000012389102;ENTRADA;25/09/2026 07:03:00;36.5°C;58%
                          982000012389103;SAÍDA;25/09/2026 07:03:07;36.1°C;56%
                    """.trimIndent(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 18.sp
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "2. Código C++ Arduino para ESP32 Gateway",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                val sampleEsp32Code = """
                    #include "BluetoothSerial.h"

                    BluetoothSerial SerialBT;

                    void setup() {
                      Serial.begin(115200);
                      SerialBT.begin("ESP32_GATEWAY_ANIMAIS");
                      Serial.println("ESP32 Gateway com suporte a colunas Excel pronto!");
                    }

                    void loop() {
                      if (SerialBT.available()) {
                        String request = SerialBT.readStringUntil('\n');
                        request.trim();

                        if (request == "LIST_FILES") {
                          SerialBT.println("FILE_ITEM:LOG_20260925.CSV:24500:25/09/2026:7C3A19B2");
                        } 
                        else if (request.startsWith("START_DOWNLOAD:")) {
                          String filename = request.substring(15);
                          simulateFileDownload(filename);
                        }
                      }
                    }

                    void simulateFileDownload(String filename) {
                      SerialBT.printf("FILE_HEADER:%s:1024:2:7C3A19B2\n", filename.c_str());
                      delay(100);

                      // Chunk 0 (Usa ponto e vírgula ';' para dividir em colunas no Excel)
                      SerialBT.println("CHUNK:0:c2VwPTsKUkZJRDtUSVBPX0VWRU5UTztEQVRBX0hPUkE7VEVNUEVSQVRVUkE7VU1JREFERQo5ODIwMDAwMTIzODkxMDM7U0HDjURBOzI1LzA5LzIwMjYgMDc6MDM6MDc7MzYuMUM7NTYlCg==");
                      delay(200);

                      SerialBT.printf("FILE_END:%s:7C3A19B2\n", filename.c_str());
                    }
                """.trimIndent()

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1E1E1E), shape = RoundedCornerShape(8.dp))
                        .padding(12.dp)
                        .horizontalScroll(rememberScrollState())
                ) {
                    Text(
                        text = sampleEsp32Code,
                        color = Color(0xFFD4D4D4),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}
