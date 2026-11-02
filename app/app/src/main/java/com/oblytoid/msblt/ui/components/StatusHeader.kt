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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oblytoid.msblt.data.bluetooth.ConnectionState

@Composable
fun StatusHeader(
    connectionState: ConnectionState,
    isSimulationMode: Boolean,
    onToggleSimulation: (Boolean) -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val statusColor = when {
        isSimulationMode -> Color(0xFF00B0FF)
        connectionState is ConnectionState.Connected -> Color(0xFF00E676)
        connectionState is ConnectionState.Connecting -> Color(0xFFFFAB00)
        connectionState is ConnectionState.Error -> Color(0xFFFF1744)
        else -> Color(0xFF757575)
    }

    val statusText = when {
        isSimulationMode -> "Simulação Ativa (ESP32 Gateway Virtual)"
        connectionState is ConnectionState.Connected -> "Conectado: ${connectionState.deviceName}"
        connectionState is ConnectionState.Connecting -> "Conectando a ${connectionState.deviceName}..."
        connectionState is ConnectionState.Error -> connectionState.message
        else -> "Desconectado do ESP32 Gateway"
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
//                Row(verticalAlignment = Alignment.CenterVertically) {
//                    Box(
//                        modifier = Modifier
//                            .size(14.dp)
//                            .clip(CircleShape)
//                            .background(statusColor)
//                    )
//                    Spacer(modifier = Modifier.width(10.dp))
//                    Text(
//                        text = if (isSimulationMode) "MODO SIMULAÇÃO" else "GATEWAY BLUETOOTH",
//                        style = MaterialTheme.typography.labelLarge,
//                        fontWeight = FontWeight.Bold,
//                        color = MaterialTheme.colorScheme.onSurfaceVariant
//                    )
//                }
//
//                Row(verticalAlignment = Alignment.CenterVertically) {
//                    Text(
//                        text = "Simular",
//                        fontSize = 12.sp,
//                        color = MaterialTheme.colorScheme.onSurfaceVariant,
//                        modifier = Modifier.padding(end = 6.dp)
//                    )
//                    Switch(
//                        checked = isSimulationMode,
//                        onCheckedChange = onToggleSimulation,
//                        colors = SwitchDefaults.colors(
//                            checkedThumbColor = MaterialTheme.colorScheme.primary,
//                            checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
//                        )
//                    )
//                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (connectionState is ConnectionState.Connected && !isSimulationMode) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = onDisconnect,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Desconectar")
                    }
                }
            }
        }
    }
}
