package com.oblytoid.msblt.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.oblytoid.msblt.data.bluetooth.AndroidBluetoothController
import com.oblytoid.msblt.data.bluetooth.AnimalLogRecord
import com.oblytoid.msblt.data.bluetooth.BluetoothController
import com.oblytoid.msblt.data.bluetooth.BluetoothDeviceModel
import com.oblytoid.msblt.data.bluetooth.ConnectionState
import com.oblytoid.msblt.data.bluetooth.CrcStatus
import com.oblytoid.msblt.data.bluetooth.DownloadProgress
import com.oblytoid.msblt.data.bluetooth.DownloadStatus
import com.oblytoid.msblt.data.bluetooth.SdFileItem
import com.oblytoid.msblt.data.bluetooth.TerminalMessage
import com.oblytoid.msblt.data.protocol.Crc32Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BluetoothViewModel(
    application: Application
) : AndroidViewModel(application) {

    val bluetoothController: BluetoothController = AndroidBluetoothController(application)

    val connectionState: StateFlow<ConnectionState> = bluetoothController.connectionState
    val scannedDevices: StateFlow<List<BluetoothDeviceModel>> = bluetoothController.scannedDevices
    val pairedDevices: StateFlow<List<BluetoothDeviceModel>> = bluetoothController.pairedDevices

    private val _isSimulationMode = MutableStateFlow(false)
    val isSimulationMode: StateFlow<Boolean> = _isSimulationMode.asStateFlow()

    private val _sdFiles = MutableStateFlow<List<SdFileItem>>(emptyList())
    val sdFiles: StateFlow<List<SdFileItem>> = _sdFiles.asStateFlow()

    private val _downloadProgress = MutableStateFlow(DownloadProgress())
    val downloadProgress: StateFlow<DownloadProgress> = _downloadProgress.asStateFlow()

    private val _terminalMessages = MutableStateFlow<List<TerminalMessage>>(emptyList())
    val terminalMessages: StateFlow<List<TerminalMessage>> = _terminalMessages.asStateFlow()

    private val _savedLocalFiles = MutableStateFlow<List<File>>(emptyList())
    val savedLocalFiles: StateFlow<List<File>> = _savedLocalFiles.asStateFlow()

    private val _selectedLocalFile = MutableStateFlow<File?>(null)
    val selectedLocalFile: StateFlow<File?> = _selectedLocalFile.asStateFlow()

    private val _animalLogRecords = MutableStateFlow<List<AnimalLogRecord>>(emptyList())
    val animalLogRecords: StateFlow<List<AnimalLogRecord>> = _animalLogRecords.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private var activeDownloadBuffer = ByteArrayOutputStream()
    private var downloadStartTime = 0L
    private var simulationJob: Job? = null

    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    init {
        observeIncomingBluetoothData()
        refreshLocalFiles()
        addSystemMessage("Sistema inicializado. Conecte ao ESP32 Gateway ou ative a simulação.")
    }

    private fun observeIncomingBluetoothData() {
        viewModelScope.launch {
            bluetoothController.incomingDataFlow.collect { rawLine ->
                addTerminalMessage(rawLine, isFromUser = false)
                processIncomingProtocolLine(rawLine)
            }
        }
    }

    fun toggleSimulationMode(enabled: Boolean) {
        _isSimulationMode.value = enabled
        if (enabled) {
            bluetoothController.disconnect()
            addSystemMessage("Modo de Simulação ativado! Simulando ESP32 Gateway conectado.")
            _sdFiles.value = listOf(
                SdFileItem("LOG_20260925.CSV", 24500L, "25/09/2026 14:30", "7C3A19B2"),
                SdFileItem("LOG_20260924.CSV", 18200L, "24/09/2026 18:45", "4A82F10E"),
                SdFileItem("LOG_PASSAGENS_FAZENDA.CSV", 42100L, "23/09/2026 09:12", "91D0E3B7")
            )
        } else {
            addSystemMessage("Modo de Simulação desativado.")
            _sdFiles.value = emptyList()
        }
    }

    fun startDiscovery() {
        bluetoothController.startDiscovery()
    }

    fun stopDiscovery() {
        bluetoothController.stopDiscovery()
    }

    fun refreshPairedDevices() {
        bluetoothController.refreshPairedDevices()
    }

    fun connectToDevice(device: BluetoothDeviceModel) {
        if (_isSimulationMode.value) {
            _isSimulationMode.value = false
        }
        bluetoothController.connectToDevice(device)
    }

    fun disconnect() {
        bluetoothController.disconnect()
    }

    fun requestSdFileList() {
        addSystemMessage("Solicitando lista de arquivos no SD do Leitor...")
        if (_isSimulationMode.value) {
            _sdFiles.value = listOf(
                SdFileItem("LOG_20260925.CSV", 24500L, "25/09/2026 14:30", "7C3A19B2"),
                SdFileItem("LOG_20260924.CSV", 18200L, "24/09/2026 18:45", "4A82F10E"),
                SdFileItem("LOG_PASSAGENS_FAZENDA.CSV", 42100L, "23/09/2026 09:12", "91D0E3B7")
            )
            addSystemMessage("Lista de arquivos atualizada (Simulado).")
        } else {
            sendRawCommand("LIST_FILES\n")
        }
    }

    fun startDownloadFile(sdFile: SdFileItem) {
        activeDownloadBuffer.reset()
        downloadStartTime = System.currentTimeMillis()

        _downloadProgress.value = DownloadProgress(
            filename = sdFile.name,
            totalBytes = sdFile.sizeBytes,
            status = DownloadStatus.DOWNLOADING,
            crcStatus = CrcStatus.UNKNOWN,
            expectedCrc = sdFile.crc32
        )

        addSystemMessage("Iniciando download de ${sdFile.name}...")

        if (_isSimulationMode.value) {
            simulateFileStreaming(sdFile)
        } else {
            sendRawCommand("START_DOWNLOAD:${sdFile.name}\n")
        }
    }

    fun cancelDownload() {
        simulationJob?.cancel()
        if (!_isSimulationMode.value) {
            sendRawCommand("CANCEL_DOWNLOAD\n")
        }
        _downloadProgress.update {
            it.copy(
                status = DownloadStatus.CANCELLED,
                errorMessage = "Download cancelado pelo usuário"
            )
        }
        addSystemMessage("Download cancelado.")
    }

    private fun processIncomingProtocolLine(line: String) {
        when {
            line.startsWith("FILE_ITEM:") -> {
                // Format: FILE_ITEM:LOG_20260925.CSV:14520:25/09/2026:3A2F1B4C
                val parts = line.substringAfter("FILE_ITEM:").split(":")
                if (parts.size >= 2) {
                    val name = parts[0]
                    val size = parts[1].toLongOrNull() ?: 0L
                    val date = parts.getOrNull(2) ?: "N/A"
                    val crc = parts.getOrNull(3) ?: ""
                    val item = SdFileItem(name, size, date, crc)
                    _sdFiles.update { current ->
                        current.filterNot { it.name == name } + item
                    }
                }
            }
            line.startsWith("FILE_HEADER:") -> {
                // Format: FILE_HEADER:<filename>:<totalBytes>:<totalChunks>:<crc32>
                val parts = line.substringAfter("FILE_HEADER:").split(":")
                if (parts.size >= 2) {
                    val name = parts[0]
                    val size = parts[1].toLongOrNull() ?: 0L
                    val totalChunks = parts.getOrNull(2)?.toIntOrNull() ?: 0
                    val crc = parts.getOrNull(3) ?: ""

                    _downloadProgress.update {
                        it.copy(
                            filename = name,
                            totalBytes = size,
                            totalChunks = totalChunks,
                            status = DownloadStatus.DOWNLOADING,
                            expectedCrc = crc
                        )
                    }
                }
            }
            line.startsWith("CHUNK:") -> {
                // Format: CHUNK:<seq>:<base64Data>
                val parts = line.substringAfter("CHUNK:").split(":", limit = 2)
                if (parts.size == 2) {
                    val seq = parts[0].toIntOrNull() ?: 0
                    val base64Data = parts[1]
                    try {
                        val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                        activeDownloadBuffer.write(bytes)

                        val received = activeDownloadBuffer.size().toLong()
                        val total = _downloadProgress.value.totalBytes
                        val elapsedSec = (System.currentTimeMillis() - downloadStartTime) / 1000f
                        val speedKbps = if (elapsedSec > 0) (received / 1024f) / elapsedSec else 0f
                        val percent = if (total > 0) (received.toFloat() / total.toFloat()) * 100f else 0f

                        _downloadProgress.update {
                            it.copy(
                                currentChunk = seq,
                                receivedBytes = received,
                                speedKbps = speedKbps,
                                progressPercent = percent
                            )
                        }
                    } catch (e: Exception) {
                        addSystemMessage("Erro ao decodificar chunk $seq: ${e.message}")
                    }
                }
            }
            line.startsWith("FILE_END:") -> {
                // Format: FILE_END:<filename>:<expectedCrc32>
                val parts = line.substringAfter("FILE_END:").split(":")
                val expectedCrc = parts.getOrNull(1) ?: _downloadProgress.value.expectedCrc
                finalizeDownloadAndVerify(expectedCrc)
            }
            line.startsWith("ERROR:") -> {
                val errorMsg = line.substringAfter("ERROR:")
                _downloadProgress.update {
                    it.copy(
                        status = DownloadStatus.FAILED,
                        errorMessage = errorMsg
                    )
                }
                addSystemMessage("Erro retornado do Gateway: $errorMsg")
            }
        }
    }

    private fun finalizeDownloadAndVerify(expectedCrc: String) {
        _downloadProgress.update { it.copy(status = DownloadStatus.VERIFYING) }

        val downloadedBytes = activeDownloadBuffer.toByteArray()
        val calculatedCrc = Crc32Utils.calculateCrc32(downloadedBytes)
        val isCrcValid = Crc32Utils.verifyCrc32(downloadedBytes, expectedCrc)

        val crcStatus = if (isCrcValid) CrcStatus.VALID else CrcStatus.INVALID

        if (isCrcValid) {
            saveFileToLocalDirectory(_downloadProgress.value.filename, downloadedBytes)
            _downloadProgress.update {
                it.copy(
                    status = DownloadStatus.COMPLETED,
                    crcStatus = crcStatus,
                    calculatedCrc = calculatedCrc,
                    progressPercent = 100f
                )
            }
            addSystemMessage("Download concluído com sucesso! Checksum CRC32 Válido ($calculatedCrc).")
        } else {
            _downloadProgress.update {
                it.copy(
                    status = DownloadStatus.FAILED,
                    crcStatus = crcStatus,
                    calculatedCrc = calculatedCrc,
                    errorMessage = "Falha de Integridade: CRC32 calculado ($calculatedCrc) difere do esperado ($expectedCrc)"
                )
            }
            addSystemMessage("ERRO: Checksum inválido ($calculatedCrc vs $expectedCrc). Tente novamente.")
        }
    }

    private fun simulateFileStreaming(sdFile: SdFileItem) {
        simulationJob?.cancel()
        simulationJob = viewModelScope.launch(Dispatchers.Default) {
            val sampleCsvBuilder = StringBuilder()
            sampleCsvBuilder.append("sep=;\n")
            sampleCsvBuilder.append("RFID;TIPO_EVENTO;DATA_HORA;TEMPERATURA;UMIDADE\n")
            val tags = listOf("982000012389102", "982000012389103", "982000012389104", "982000012389105", "982000012389106")
            val eventTypes = listOf("ENTRADA", "SAÍDA")

            for (i in 1..220) {
                val tag = tags[i % tags.size]
                val event = eventTypes[i % eventTypes.size]
                val hour = String.format(Locale.US, "%02d:%02d:%02d", (6 + (i % 12)), (i * 3) % 60, (i * 7) % 60)
                val temp = String.format(Locale.US, "%.1f°C", 36.0 + (i % 25) * 0.1)
                val hum = String.format(Locale.US, "%d%%", 55 + (i % 20))

                sampleCsvBuilder.append("$tag;$event;25/09/2026 $hour;$temp;$hum\n")
            }

            val simulatedData = sampleCsvBuilder.toString().toByteArray(Charsets.UTF_8)
            val computedCrc = Crc32Utils.calculateCrc32(simulatedData)
            val chunkSize = 512
            val totalChunks = (simulatedData.size + chunkSize - 1) / chunkSize

            _downloadProgress.update {
                it.copy(
                    totalBytes = simulatedData.size.toLong(),
                    totalChunks = totalChunks,
                    expectedCrc = computedCrc
                )
            }

            for (chunkIdx in 0 until totalChunks) {
                if (_downloadProgress.value.status == DownloadStatus.CANCELLED) break

                val start = chunkIdx * chunkSize
                val end = minOf(start + chunkSize, simulatedData.size)
                val chunkBytes = simulatedData.copyOfRange(start, end)
                val base64Chunk = Base64.encodeToString(chunkBytes, Base64.NO_WRAP)

                val chunkLine = "CHUNK:$chunkIdx:$base64Chunk"
                withContext(Dispatchers.Main) {
                    processIncomingProtocolLine(chunkLine)
                }
                delay(80) // Simulate Bluetooth stream latency
            }

            if (_downloadProgress.value.status != DownloadStatus.CANCELLED) {
                val endLine = "FILE_END:${sdFile.name}:$computedCrc"
                withContext(Dispatchers.Main) {
                    processIncomingProtocolLine(endLine)
                }
            }
        }
    }

    private fun saveFileToLocalDirectory(filename: String, data: ByteArray) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dir = getApplication<Application>().getExternalFilesDir(null)
                    ?: getApplication<Application>().filesDir
                val localFile = File(dir, filename)

                val rawContent = String(data, Charsets.UTF_8)
                val formattedExcelBytes = Crc32Utils.formatToExcelCompatibleCsv(rawContent)

                localFile.writeBytes(formattedExcelBytes)

                refreshLocalFiles()
                loadAnimalLogsFromFile(localFile)
            } catch (e: Exception) {
                addSystemMessage("Erro ao salvar arquivo localmente: ${e.message}")
            }
        }
    }

    fun refreshLocalFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = getApplication<Application>().getExternalFilesDir(null)
                ?: getApplication<Application>().filesDir
            val files = dir.listFiles { _, name -> name.endsWith(".csv", ignoreCase = true) }?.toList() ?: emptyList()
            _savedLocalFiles.value = files.sortedByDescending { it.lastModified() }

            if (_animalLogRecords.value.isEmpty() && files.isNotEmpty()) {
                loadAnimalLogsFromFile(files.first())
            }
        }
    }

    fun loadAnimalLogsFromFile(file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _selectedLocalFile.value = file
                val content = file.readText(Charsets.UTF_8)
                val records = Crc32Utils.parseAnimalLogCsv(content)
                _animalLogRecords.value = records
                addSystemMessage("Carregados ${records.size} registros de passagem do arquivo ${file.name}.")
            } catch (e: Exception) {
                addSystemMessage("Erro ao carregar log ${file.name}: ${e.message}")
            }
        }
    }

    fun shareFile(context: Context, fileToShare: File? = _selectedLocalFile.value) {
        val targetFile = fileToShare ?: _selectedLocalFile.value ?: _savedLocalFiles.value.firstOrNull()
        if (targetFile == null || !targetFile.exists()) {
            addSystemMessage("Nenhum arquivo de log salvo disponível para exportação.")
            return
        }

        try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                targetFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Registro de Passagens - ${targetFile.name}")
                putExtra(Intent.EXTRA_TEXT, "Segue em anexo o arquivo CSV com os registros de passagens dos animais (${targetFile.name}).")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(shareIntent, "Exportar Registro CSV via")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)

            addSystemMessage("Menu de compartilhamento aberto para ${targetFile.name}.")
        } catch (e: Exception) {
            addSystemMessage("Erro ao exportar arquivo: ${e.localizedMessage}")
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun sendRawCommand(commandText: String) {
        if (commandText.isBlank()) return

        val formattedCmd = if (!commandText.endsWith("\n")) "$commandText\n" else commandText
        addTerminalMessage(formattedCmd.trimEnd(), isFromUser = true)

        if (!_isSimulationMode.value) {
            bluetoothController.sendData(formattedCmd)
        } else {
            addSystemMessage("Comando enviado no modo de simulação.")
        }
    }

    fun clearTerminalMessages() {
        _terminalMessages.value = emptyList()
    }

    private fun addTerminalMessage(text: String, isFromUser: Boolean) {
        val timestamp = timeFormatter.format(Date())
        val msg = TerminalMessage(text, timestamp, isFromUser)
        _terminalMessages.update { current ->
            (current + msg).takeLast(200) // Keep last 200 messages
        }
    }

    private fun addSystemMessage(text: String) {
        val timestamp = timeFormatter.format(Date())
        val msg = TerminalMessage(text, timestamp, isFromUser = false, isSystemMessage = true)
        _terminalMessages.update { current ->
            (current + msg).takeLast(200)
        }
    }

    override fun onCleared() {
        super.onCleared()
        bluetoothController.release()
    }
}
