package com.oblytoid.msblt.ui.screens

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oblytoid.msblt.data.bluetooth.BluetoothDeviceModel
import com.oblytoid.msblt.data.bluetooth.ConnectionState

@Composable
fun GatewayScreen(
    connectionState: ConnectionState,
    pairedDevices: List<BluetoothDeviceModel>,
    scannedDevices: List<BluetoothDeviceModel>,
    isSimulationMode: Boolean,
    onStartDiscovery: () -> Unit,
    onStopDiscovery: () -> Unit,
    onConnectDevice: (BluetoothDeviceModel) -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
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
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "MSBLT Gateway",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Conecte seu smartphone ao Gateway para se comunicar via LoRa com o leitor e realizar a transferência dos logs de passagem dos animais.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Dispositivos Pareados",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Button(
                    onClick = onStartDiscovery,
                    enabled = !isSimulationMode,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Buscar Novos")
                }
            }
        }

        if (pairedDevices.isEmpty()) {
            item {
                Text(
                    text = "Nenhum gateway pareado encontrado. Clique em 'Buscar Novos' para procurar dispositivos Bluetooth próximos.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(pairedDevices) { device ->
                DeviceCard(
                    device = device,
                    connectionState = connectionState,
                    isSimulationMode = isSimulationMode,
                    onConnect = { onConnectDevice(device) },
                    onDisconnect = onDisconnect
                )
            }
        }

        if (scannedDevices.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Dispositivos Encontrados Proximidade",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            items(scannedDevices) { device ->
                DeviceCard(
                    device = device,
                    connectionState = connectionState,
                    isSimulationMode = isSimulationMode,
                    onConnect = { onConnectDevice(device) },
                    onDisconnect = onDisconnect
                )
            }
        }
    }
}

@Composable
private fun DeviceCard(
    device: BluetoothDeviceModel,
    connectionState: ConnectionState,
    isSimulationMode: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val isConnected = connectionState is ConnectionState.Connected && connectionState.address == device.address
    val isConnecting = connectionState is ConnectionState.Connecting && connectionState.deviceName == (device.name ?: device.address)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isConnected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name ?: "ESP32 Gateway",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = device.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            when {
                isConnected -> {
                    OutlinedButton(onClick = onDisconnect) {
                        Text("Desconectar")
                    }
                }
                isConnecting -> {
                    CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                }
                else -> {
                    Button(
                        onClick = onConnect,
                        enabled = !isSimulationMode
                    ) {
                        Text("Conectar")
                    }
                }
            }
        }
    }
}
