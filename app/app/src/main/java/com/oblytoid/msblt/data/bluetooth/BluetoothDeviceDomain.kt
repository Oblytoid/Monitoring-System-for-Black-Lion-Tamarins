package com.oblytoid.msblt.data.bluetooth

data class BluetoothDeviceModel(
    val name: String?,
    val address: String,
    val rssi: Int = 0,
    val isPaired: Boolean = false
)

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    data class Connecting(val deviceName: String) : ConnectionState()
    data class Connected(val deviceName: String, val address: String) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

data class SdFileItem(
    val name: String,
    val sizeBytes: Long,
    val dateFormatted: String,
    val crc32: String = ""
)

enum class DownloadStatus {
    IDLE,
    DOWNLOADING,
    VERIFYING,
    COMPLETED,
    FAILED,
    CANCELLED
}

enum class CrcStatus {
    UNKNOWN,
    VALID,
    INVALID
}

data class DownloadProgress(
    val filename: String = "",
    val totalBytes: Long = 0L,
    val receivedBytes: Long = 0L,
    val currentChunk: Int = 0,
    val totalChunks: Int = 0,
    val speedKbps: Float = 0f,
    val progressPercent: Float = 0f,
    val status: DownloadStatus = DownloadStatus.IDLE,
    val crcStatus: CrcStatus = CrcStatus.UNKNOWN,
    val expectedCrc: String = "",
    val calculatedCrc: String = "",
    val errorMessage: String? = null
)

data class AnimalLogRecord(
    val tagId: String,
    val eventType: String, // ENTRADA ou SAÍDA
    val timestamp: String,
    val temperature: String,
    val humidity: String,
    val rawEntry: String
)

data class TerminalMessage(
    val text: String,
    val timestamp: String,
    val isFromUser: Boolean,
    val isSystemMessage: Boolean = false
)
