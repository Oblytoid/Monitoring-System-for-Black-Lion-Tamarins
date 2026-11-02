package com.oblytoid.msblt.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oblytoid.msblt.data.bluetooth.CrcStatus
import com.oblytoid.msblt.data.bluetooth.DownloadProgress
import com.oblytoid.msblt.data.bluetooth.DownloadStatus
import com.oblytoid.msblt.data.bluetooth.SdFileItem
import java.util.Locale

@Composable
fun SdFileCard(
    fileItem: SdFileItem,
    isDownloading: Boolean,
    onStartDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = fileItem.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatFileSize(fileItem.sizeBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = fileItem.dateFormatted,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Button(
                onClick = onStartDownload,
                enabled = !isDownloading,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Baixar SD")
            }
        }
    }
}

@Composable
fun DownloadProgressCard(
    progress: DownloadProgress,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (progress.status == DownloadStatus.IDLE) return

    val statusBgColor = when (progress.crcStatus) {
        CrcStatus.VALID -> Color(0xFF1B5E20)
        CrcStatus.INVALID -> Color(0xFFB71C1C)
        CrcStatus.UNKNOWN -> MaterialTheme.colorScheme.primaryContainer
    }

    val statusTextColor = when (progress.crcStatus) {
        CrcStatus.VALID -> Color.White
        CrcStatus.INVALID -> Color.White
        CrcStatus.UNKNOWN -> MaterialTheme.colorScheme.onPrimaryContainer
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Transferência: ${progress.filename}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )

                if (progress.status == DownloadStatus.DOWNLOADING) {
                    OutlinedButton(
                        onClick = onCancel,
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Cancelar", fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            LinearProgressIndicator(
                progress = { progress.progressPercent / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surface
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${String.format(Locale.US, "%.1f", progress.progressPercent)}% (${formatFileSize(progress.receivedBytes)} / ${formatFileSize(progress.totalBytes)})",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "${String.format(Locale.US, "%.1f", progress.speedKbps)} KB/s",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(statusBgColor, shape = RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                val crcMessage = when (progress.status) {
                    DownloadStatus.DOWNLOADING -> "Recebendo dados via Bluetooth..."
                    DownloadStatus.VERIFYING -> "Verificando integridade CRC32..."
                    DownloadStatus.COMPLETED -> "✅ Sucesso! Checksum CRC32 Válido (${progress.calculatedCrc})"
                    DownloadStatus.FAILED -> "❌ Erro: ${progress.errorMessage ?: "Checksum CRC32 Inválido"}"
                    DownloadStatus.CANCELLED -> "⚠️ Download cancelado."
                    DownloadStatus.IDLE -> ""
                }

                Text(
                    text = crcMessage,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = statusTextColor
                )
            }
        }
    }
}

private fun formatFileSize(sizeBytes: Long): String {
    if (sizeBytes <= 0) return "0 B"
    val kb = sizeBytes / 1024f
    return if (kb < 1024) {
        String.format(Locale.US, "%.1f KB", kb)
    } else {
        val mb = kb / 1024f
        String.format(Locale.US, "%.2f MB", mb)
    }
}
