package com.oblytoid.msblt.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.oblytoid.msblt.ui.components.StatusHeader
import com.oblytoid.msblt.ui.screens.AnimalLogsScreen
import com.oblytoid.msblt.ui.screens.GatewayScreen
import com.oblytoid.msblt.ui.screens.ProtocolDocsScreen
import com.oblytoid.msblt.ui.screens.SdFilesScreen

enum class AppTab(val title: String) {
    GATEWAY("Gateway"),
    SD_FILES("Arquivos SD"),
    LOGS("Registros")
//    DOCS("Protocolo TCC")
}

@Composable
fun MainScreen(
    viewModel: BluetoothViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(AppTab.GATEWAY) }

    val connectionState by viewModel.connectionState.collectAsState()
    val isSimulationMode by viewModel.isSimulationMode.collectAsState()
    val pairedDevices by viewModel.pairedDevices.collectAsState()
    val scannedDevices by viewModel.scannedDevices.collectAsState()
    val sdFiles by viewModel.sdFiles.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val savedLocalFiles by viewModel.savedLocalFiles.collectAsState()
    val selectedLocalFile by viewModel.selectedLocalFile.collectAsState()
    val animalLogs by viewModel.animalLogRecords.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        label = {
                            Text(
                                text = tab.title,
                                fontWeight = if (selectedTab == tab) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        icon = {}
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            StatusHeader(
                connectionState = connectionState,
                isSimulationMode = isSimulationMode,
                onToggleSimulation = { viewModel.toggleSimulationMode(it) },
                onDisconnect = { viewModel.disconnect() }
            )

            when (selectedTab) {
                AppTab.GATEWAY -> {
                    GatewayScreen(
                        connectionState = connectionState,
                        pairedDevices = pairedDevices,
                        scannedDevices = scannedDevices,
                        isSimulationMode = isSimulationMode,
                        onStartDiscovery = { viewModel.startDiscovery() },
                        onStopDiscovery = { viewModel.stopDiscovery() },
                        onConnectDevice = { viewModel.connectToDevice(it) },
                        onDisconnect = { viewModel.disconnect() }
                    )
                }
                AppTab.SD_FILES -> {
                    SdFilesScreen(
                        sdFiles = sdFiles,
                        downloadProgress = downloadProgress,
                        savedLocalFiles = savedLocalFiles,
                        onRequestSdFiles = { viewModel.requestSdFileList() },
                        onStartDownload = { viewModel.startDownloadFile(it) },
                        onCancelDownload = { viewModel.cancelDownload() },
                        onSelectLocalFile = {
                            viewModel.loadAnimalLogsFromFile(it)
                            selectedTab = AppTab.LOGS
                        },
                        onExportLocalFile = { file ->
                            viewModel.shareFile(context, file)
                        }
                    )
                }
                AppTab.LOGS -> {
                    AnimalLogsScreen(
                        records = animalLogs,
                        searchQuery = searchQuery,
                        onSearchQueryChange = { viewModel.setSearchQuery(it) },
                        onExportLogFile = {
                            viewModel.shareFile(context)
                        },
                        selectedFileName = selectedLocalFile?.name
                    )
                }
//                AppTab.DOCS -> {
//                    ProtocolDocsScreen()
//                }
            }
        }
    }
}
