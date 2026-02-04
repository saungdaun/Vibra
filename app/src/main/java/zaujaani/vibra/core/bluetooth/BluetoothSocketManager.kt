package zaujaani.vibra.core.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

object BluetoothSocketManager {

    private val uuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var connectJob: Job? = null
    private var listenJob: Job? = null
    private var sendJob: Job? = null

    private val isConnecting = AtomicBoolean(false)
    private val isConnected = AtomicBoolean(false)
    private val isChannelActive = AtomicBoolean(false)

    private val _receivedData = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 10000 // 🔥 PERBESAR BUFFER UNTUK STREAMING
    )
    val receivedData: SharedFlow<String> = _receivedData.asSharedFlow()

    private val sendChannel = Channel<String>(Channel.UNLIMITED)

    // 🔥 VARIABLE BARU UNTUK STREAMING
    private val _rawDataStream = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 10000
    )
    val rawDataStream: SharedFlow<ByteArray> = _rawDataStream.asSharedFlow()

    init {
        startSendProcessor()
        startRawDataProcessor()
    }

    fun connect(device: BluetoothDevice) {
        if (isConnecting.get()) {
            Log.d("BluetoothSocketManager", "⚠️ Already connecting, skipping")
            return
        }

        if (isConnected.get()) {
            Log.d("BluetoothSocketManager", "⚠️ Already connected, skipping")
            return
        }

        isConnecting.set(true)

        connectJob = scope.launch {
            try {
                val deviceName = try {
                    device.name ?: device.address
                } catch (e: SecurityException) {
                    device.address
                }

                Log.d("BluetoothSocketManager", "🔗 Connecting to $deviceName...")

                val context = BluetoothGateway.getContext()

                if (!hasRequiredPermissions(context)) {
                    BluetoothStateMachine.updateSafe(
                        ConnectionState.Error("Missing Bluetooth permissions")
                    )
                    return@launch
                }

                try {
                    if (ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.BLUETOOTH_SCAN
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        try {
                            BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
                        } catch (e: NoSuchMethodError) {
                            Log.w("BluetoothSocketManager", "cancelDiscovery not available")
                        }
                    }
                } catch (e: SecurityException) {
                    Log.w("BluetoothSocketManager", "Cannot cancel discovery", e)
                }

                disconnectInternal()

                socket = try {
                    @Suppress("DEPRECATION")
                    device.createRfcommSocketToServiceRecord(uuid)
                } catch (e: SecurityException) {
                    BluetoothStateMachine.updateSafe(
                        ConnectionState.Error("Cannot create socket: Permission denied")
                    )
                    return@launch
                } catch (e: IOException) {
                    BluetoothStateMachine.updateSafe(
                        ConnectionState.Error("Cannot create socket: ${e.message}")
                    )
                    return@launch
                }

                try {
                    withTimeout(10000) {
                        socket?.connect()
                    }
                } catch (e: TimeoutCancellationException) {
                    throw IOException("Connection timeout (10s)")
                }

                inputStream = socket?.inputStream
                outputStream = socket?.outputStream

                startListening()
                startRawStreaming() // 🔥 MULAI STREAMING RAW DATA

                BluetoothReconnectEngine.remember(device)
                BluetoothReconnectEngine.start()

                isConnected.set(true)
                isChannelActive.set(true)
                BluetoothStateMachine.updateSafe(ConnectionState.Connected(deviceName))

                Log.d("BluetoothSocketManager", "✅ Connected successfully to $deviceName")

            } catch (e: TimeoutCancellationException) {
                BluetoothStateMachine.updateSafe(
                    ConnectionState.Error("Connection timeout (10s)")
                )
                Log.e("BluetoothSocketManager", "⏱️ Connection timeout", e)
            } catch (e: SecurityException) {
                BluetoothStateMachine.updateSafe(
                    ConnectionState.Error("Permission error: ${e.message}")
                )
                Log.e("BluetoothSocketManager", "🔒 Permission error", e)
            } catch (e: IOException) {
                BluetoothStateMachine.updateSafe(
                    ConnectionState.Disconnected
                )
                Log.e("BluetoothSocketManager", "🔌 IO Error", e)
            } catch (e: Exception) {
                BluetoothStateMachine.updateSafe(
                    ConnectionState.Error("Unexpected error: ${e.message}")
                )
                Log.e("BluetoothSocketManager", "❌ Unexpected error", e)
            } finally {
                isConnecting.set(false)
            }
        }
    }

    private fun hasRequiredPermissions(context: android.content.Context): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED &&
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.BLUETOOTH_SCAN
                        ) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH
                ) == PackageManager.PERMISSION_GRANTED &&
                        (ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED ||
                                ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                ) == PackageManager.PERMISSION_GRANTED)
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun startListening() {
        listenJob?.cancel()
        listenJob = scope.launch {
            Log.d("BluetoothSocketManager", "🎧 Start listening for data...")
            val buffer = ByteArray(4096)
            var bufferPos = 0

            try {
                while (isActive && isConnected.get()) {
                    val bytesRead = inputStream?.read(buffer, bufferPos, buffer.size - bufferPos)

                    if (bytesRead == -1) {
                        throw IOException("Stream closed")
                    }

                    if (bytesRead != null && bytesRead > 0) {
                        bufferPos += bytesRead

                        var start = 0
                        for (i in 0 until bufferPos) {
                            if (buffer[i] == '\n'.code.toByte() || buffer[i] == '\r'.code.toByte()) {
                                if (i > start) {
                                    val line = String(buffer, start, i - start).trim()
                                    if (line.isNotEmpty()) {
                                        _receivedData.emit(line)
                                    }
                                }
                                start = i + 1
                            }
                        }

                        if (start < bufferPos) {
                            System.arraycopy(buffer, start, buffer, 0, bufferPos - start)
                            bufferPos -= start
                        } else {
                            bufferPos = 0
                        }
                    }

                    delay(1)
                }
            } catch (e: IOException) {
                Log.d("BluetoothSocketManager", "📵 Connection lost (expected on disconnect)")
                if (isConnected.get()) {
                    BluetoothStateMachine.updateSafe(ConnectionState.Disconnected)
                    disconnectInternal()
                }
            } catch (e: Exception) {
                Log.e("BluetoothSocketManager", "Listen error: ${e.message}", e)
                disconnectInternal()
            }
        }
    }

    // 🔥 FUNGSI BARU UNTUK STREAMING RAW DATA
    private fun startRawStreaming() {
        scope.launch {
            Log.d("BluetoothSocketManager", "📡 Starting raw data streaming...")
            val buffer = ByteArray(1024)

            try {
                while (isActive && isConnected.get()) {
                    val bytesRead = inputStream?.read(buffer)

                    if (bytesRead == -1) {
                        throw IOException("Stream closed")
                    }

                    if (bytesRead != null && bytesRead > 0) {
                        val data = buffer.copyOf(bytesRead)
                        _rawDataStream.emit(data)

                        // Konversi ke string untuk debugging
                        val text = String(data).trim()
                        if (text.isNotEmpty()) {
                            Log.d("BluetoothSocketManager", "📥 RAW: $text")
                        }
                    }

                    delay(10)
                }
            } catch (e: IOException) {
                Log.d("BluetoothSocketManager", "Raw streaming stopped")
            } catch (e: Exception) {
                Log.e("BluetoothSocketManager", "Raw streaming error", e)
            }
        }
    }

    private fun startRawDataProcessor() {
        scope.launch {
            rawDataStream.collect { rawData ->
                try {
                    val text = String(rawData).trim()
                    if (text.isNotEmpty()) {
                        // Otomatis tambahkan ke receivedData juga
                        _receivedData.emit(text)
                    }
                } catch (e: Exception) {
                    Log.e("BluetoothSocketManager", "Error processing raw data", e)
                }
            }
        }
    }

    private fun startSendProcessor() {
        sendJob?.cancel()
        sendJob = scope.launch {
            Log.d("BluetoothSocketManager", "📤 Send processor started")
            while (isActive) {
                try {
                    val command = sendChannel.receive()

                    if (!isConnected.get()) {
                        Log.w("BluetoothSocketManager", "Not connected, skipping: $command")
                        continue
                    }

                    if (!isChannelActive.get()) {
                        Log.w("BluetoothSocketManager", "Channel not active, skipping: $command")
                        continue
                    }

                    val context = BluetoothGateway.getContext()
                    if (!hasRequiredPermissions(context)) {
                        BluetoothStateMachine.updateSafe(
                            ConnectionState.Error("Lost Bluetooth permission while sending")
                        )
                        break
                    }

                    val commandWithNewline = "$command\n"
                    outputStream?.write(commandWithNewline.toByteArray())
                    outputStream?.flush()

                    Log.d("BluetoothSocketManager", "📤 Sent: $command")

                    delay(50)

                } catch (e: CancellationException) {
                    Log.d("BluetoothSocketManager", "🚫 Send processor cancelled")
                    break
                } catch (e: Exception) {
                    Log.e("BluetoothSocketManager", "Send error: ${e.javaClass.simpleName}: ${e.message}")
                    if (e is IOException) {
                        disconnectInternal()
                        break
                    }
                }
            }
        }
    }

    fun sendCommand(command: String) {
        scope.launch {
            try {
                val connected = isConnected.get() && socket?.isConnected == true
                val channelActive = isChannelActive.get()

                Log.d("BluetoothSocketManager",
                    "Send attempt: connected=$connected, channelActive=$channelActive, command=$command")

                if (connected && channelActive) {
                    sendChannel.send(command)
                    Log.d("BluetoothSocketManager", "✅ Command sent to channel: $command")
                } else {
                    Log.w("BluetoothSocketManager",
                        "Cannot send: connected=$connected, channelActive=$channelActive, command=$command")
                }
            } catch (e: Exception) {
                Log.e("BluetoothSocketManager", "Failed to queue command: ${e.message}", e)
            }
        }
    }

    private fun disconnectInternal() {
        Log.d("BluetoothSocketManager", "🛑 Starting disconnectInternal")

        isChannelActive.set(false)
        isConnected.set(false)
        isConnecting.set(false)

        listenJob?.cancel()
        listenJob = null

        try {
            inputStream?.close()
        } catch (_: Exception) {
        }

        try {
            outputStream?.close()
        } catch (_: Exception) {
        }

        try {
            socket?.close()
        } catch (_: Exception) {
        }

        inputStream = null
        outputStream = null
        socket = null

        Log.d("BluetoothSocketManager", "🛑 Disconnected and cleaned up")
    }

    fun disconnect() {
        Log.d("BluetoothSocketManager", "🛑 Disconnect called")
        connectJob?.cancel()

        scope.launch {
            BluetoothReconnectEngine.stop()
            disconnectInternal()
            BluetoothStateMachine.updateSafe(ConnectionState.Disconnected)
        }
    }

    fun isConnected(): Boolean {
        return isConnected.get() && isChannelActive.get() && socket?.isConnected == true
    }

    fun getConnectionStatus(): String {
        return when {
            isConnected.get() -> "CONNECTED"
            isConnecting.get() -> "CONNECTING"
            else -> "DISCONNECTED"
        }
    }
}