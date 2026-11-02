package com.oblytoid.msblt.data.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

interface BluetoothController {
    val scannedDevices: StateFlow<List<BluetoothDeviceModel>>
    val pairedDevices: StateFlow<List<BluetoothDeviceModel>>
    val connectionState: StateFlow<ConnectionState>
    val incomingDataFlow: SharedFlow<String>

    fun startDiscovery()
    fun stopDiscovery()
    fun refreshPairedDevices()
    fun connectToDevice(device: BluetoothDeviceModel)
    fun disconnect()
    fun sendData(message: String): Boolean
    fun release()
}

@Suppress("DEPRECATION")
@SuppressLint("MissingPermission")
class AndroidBluetoothController(
    private val context: Context
) : BluetoothController {

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val _scannedDevices = MutableStateFlow<List<BluetoothDeviceModel>>(emptyList())
    override val scannedDevices: StateFlow<List<BluetoothDeviceModel>> = _scannedDevices.asStateFlow()

    private val _pairedDevices = MutableStateFlow<List<BluetoothDeviceModel>>(emptyList())
    override val pairedDevices: StateFlow<List<BluetoothDeviceModel>> = _pairedDevices.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _incomingDataFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val incomingDataFlow: SharedFlow<String> = _incomingDataFlow.asSharedFlow()

    private var currentSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var readerJob: Job? = null

    // Standard SPP UUID for ESP32 Bluetooth Serial
    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    val rssi: Int =
                        intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()

                    device?.let {
                        try {
                            val newDevice = BluetoothDeviceModel(
                                name = it.name ?: "Desconhecido",
                                address = it.address,
                                rssi = rssi,
                                isPaired = it.bondState == BluetoothDevice.BOND_BONDED
                            )
                            _scannedDevices.update { currentList ->
                                if (currentList.none { d -> d.address == newDevice.address }) {
                                    currentList + newDevice
                                } else {
                                    currentList
                                }
                            }
                        } catch (e: SecurityException) {
                            // Missing permission safely handled
                        }
                    }
                }
            }
        }
    }

    init {
        refreshPairedDevices()
    }

    override fun refreshPairedDevices() {
        try {
            bluetoothAdapter?.bondedDevices?.let { bonded ->
                val list = bonded.map { device ->
                    BluetoothDeviceModel(
                        name = device.name ?: "MSLBT Gateway",
                        address = device.address,
                        isPaired = true
                    )
                }
                _pairedDevices.value = list
            }
        } catch (e: SecurityException) {
            // Permission catch
        }
    }

    override fun startDiscovery() {
        try {
            refreshPairedDevices()
            _scannedDevices.value = emptyList()
            val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
            context.registerReceiver(bluetoothReceiver, filter)
            bluetoothAdapter?.startDiscovery()
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.Error("Erro ao iniciar busca: ${e.message}")
        }
    }

    override fun stopDiscovery() {
        try {
            context.unregisterReceiver(bluetoothReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver not registered
        }
        try {
            bluetoothAdapter?.cancelDiscovery()
        } catch (e: SecurityException) {
            // Permission catch
        }
    }

    override fun connectToDevice(device: BluetoothDeviceModel) {
        stopDiscovery()
        _connectionState.value = ConnectionState.Connecting(device.name ?: device.address)

        scope.launch {
            try {
                val bluetoothDevice = bluetoothAdapter?.getRemoteDevice(device.address)
                if (bluetoothDevice == null) {
                    _connectionState.value = ConnectionState.Error("Dispositivo não encontrado")
                    return@launch
                }

                currentSocket = bluetoothDevice.createRfcommSocketToServiceRecord(sppUuid)
                bluetoothAdapter?.cancelDiscovery()

                currentSocket?.connect()
                inputStream = currentSocket?.inputStream
                outputStream = currentSocket?.outputStream

                _connectionState.value = ConnectionState.Connected(
                    deviceName = device.name ?: "MSBLT Gateway",
                    address = device.address
                )

                startListening()
            } catch (e: Exception) {
                disconnect()
                _connectionState.value = ConnectionState.Error("Falha na conexão: ${e.localizedMessage ?: "Erro de socket"}")
            }
        }
    }

    private fun startListening() {
        readerJob?.cancel()
        readerJob = scope.launch {
            val buffer = ByteArray(1024)
            val stringBuilder = StringBuilder()

            while (currentSocket?.isConnected == true) {
                try {
                    val bytesRead = inputStream?.read(buffer) ?: -1
                    if (bytesRead > 0) {
                        val receivedText = String(buffer, 0, bytesRead, Charsets.UTF_8)
                        stringBuilder.append(receivedText)

                        while (stringBuilder.contains("\n")) {
                            val lineEndIndex = stringBuilder.indexOf("\n")
                            val line = stringBuilder.substring(0, lineEndIndex).trimEnd('\r')
                            stringBuilder.delete(0, lineEndIndex + 1)
                            if (line.isNotEmpty()) {
                                _incomingDataFlow.emit(line)
                            }
                        }
                    } else if (bytesRead == -1) {
                        break
                    }
                } catch (e: IOException) {
                    break
                }
            }
            withContext(Dispatchers.Main) {
                if (_connectionState.value is ConnectionState.Connected) {
                    _connectionState.value = ConnectionState.Disconnected
                }
            }
        }
    }

    override fun sendData(message: String): Boolean {
        val out = outputStream ?: return false
        return try {
            out.write(message.toByteArray(Charsets.UTF_8))
            out.flush()
            true
        } catch (e: IOException) {
            _connectionState.value = ConnectionState.Error("Erro no envio: ${e.message}")
            false
        }
    }

    override fun disconnect() {
        readerJob?.cancel()
        try {
            inputStream?.close()
            outputStream?.close()
            currentSocket?.close()
        } catch (e: IOException) {
            // Socket close exception ignored
        } finally {
            inputStream = null
            outputStream = null
            currentSocket = null
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    override fun release() {
        stopDiscovery()
        disconnect()
    }
}
