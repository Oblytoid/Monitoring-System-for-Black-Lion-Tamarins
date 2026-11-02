package com.oblytoid.msblt.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oblytoid.msblt.data.bluetooth.DownloadProgress
import com.oblytoid.msblt.data.bluetooth.DownloadStatus
import com.oblytoid.msblt.data.bluetooth.SdFileItem
import com.oblytoid.msblt.ui.components.DownloadProgressCard
import com.oblytoid.msblt.ui.components.SdFileCard
import java.io.File
import java.util.Locale

@Composable
fun SdFilesScreen(
    sdFiles: List<SdFileItem>,
    downloadProgress: DownloadProgress,
    savedLocalFiles: List<File>,
    onRequestSdFiles: () -> Unit,
    onStartDownload: (SdFileItem) -> Unit,
    onCancelDownload: () -> Unit,
    onSelectLocalFile: (File) -> Unit,
    onExportLocalFile: (File) -> Unit,
    modifier: Modifier = Modifier
) {
    val isDownloading = downloadProgress.status == DownloadStatus.DOWNLOADING ||
            downloadProgress.status == DownloadStatus.VERIFYING

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Cartão SD do Leitor",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Button(
                            onClick = onRequestSdFiles,
                            enabled = !isDownloading,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Solicitar Arquivos")
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Solicite a lista de arquivos .CSV armazenados no cartão SD do leitor em campo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (downloadProgress.status != DownloadStatus.IDLE) {
            item {
                DownloadProgressCard(
                    progress = downloadProgress,
                    onCancel = onCancelDownload
                )
            }
        }

        item {
            Text(
                text = "Arquivos Remotos Disponíveis (${sdFiles.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
        }

        if (sdFiles.isEmpty()) {
            item {
                Text(
                    text = "Nenhum arquivo remotos carregado. Clique em 'Solicitar Arquivos' acima.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(sdFiles) { fileItem ->
                SdFileCard(
                    fileItem = fileItem,
                    isDownloading = isDownloading,
                    onStartDownload = { onStartDownload(fileItem) }
                )
            }
        }

        item {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                text = "Arquivos Salvos Localmente no Smartphone (${savedLocalFiles.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
        }

        if (savedLocalFiles.isEmpty()) {
            item {
                Text(
                    text = "Nenhum arquivo salvo localmente ainda.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(savedLocalFiles) { localFile ->
                LocalFileItemCard(
                    file = localFile,
                    onSelect = { onSelectLocalFile(localFile) },
                    onExport = { onExportLocalFile(localFile) }
                )
            }
        }
    }
}

@Composable
private fun LocalFileItemCard(
    file: File,
    onSelect: () -> Unit,
    onExport: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${String.format(Locale.US, "%.1f", file.length() / 1024f)} KB - Salvo no Smartphone com sucesso",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = onSelect,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text("Ver", fontSize = 12.sp)
                }

                Button(
                    onClick = onExport,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text("📤 Exportar", fontSize = 12.sp)
                }
            }
        }
    }
}
