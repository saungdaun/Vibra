package zaujaani.vibra.core.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import timber.log.Timber
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import zaujaani.vibra.BuildConfig
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
        extraBufferCapacity = 10000
    )
    val receivedData: SharedFlow<String> = _receivedData.asSharedFlow()

    private val sendChannel = Channel<String>(Channel.UNLIMITED)

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
            Timber.tag("BluetoothSocketManager").d("⚠️ Already connecting, skipping")
            return
        }

        if (isConnected.get()) {
            Timber.tag("BluetoothSocketManager").d("⚠️ Already connected, skipping")
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

                Timber.tag("BluetoothSocketManager").i("🔗 Connecting to $deviceName...")

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
                            // Gunakan adapter dari BluetoothGateway untuk menghindari deprecation
                            val adapter = BluetoothGateway.getBluetoothAdapter()
                            adapter?.cancelDiscovery()
                        } catch (e: NoSuchMethodError) {
                            Timber.tag("BluetoothSocketManager").w("cancelDiscovery not available")
                        }
                    }
                } catch (e: SecurityException) {
                    Timber.tag("BluetoothSocketManager").w(e, "Cannot cancel discovery")
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
                startRawStreaming()

                BluetoothReconnectEngine.remember(device)
                BluetoothReconnectEngine.start()

                isConnected.set(true)
                isChannelActive.set(true)
                BluetoothStateMachine.updateSafe(ConnectionState.Connected(deviceName))

                Timber.tag("BluetoothSocketManager").i("✅ Connected successfully to $deviceName")

            } catch (e: TimeoutCancellationException) {
                BluetoothStateMachine.updateSafe(
                    ConnectionState.Error("Connection timeout (10s)")
                )
                Timber.tag("BluetoothSocketManager").e(e, "⏱️ Connection timeout")
            } catch (e: SecurityException) {
                BluetoothStateMachine.updateSafe(
                    ConnectionState.Error("Permission error: ${e.message}")
                )
                Timber.tag("BluetoothSocketManager").e(e, "🔒 Permission error")
            } catch (e: IOException) {
                BluetoothStateMachine.updateSafe(
                    ConnectionState.Disconnected
                )
                Timber.tag("BluetoothSocketManager").e(e, "🔌 IO Error")
            } catch (e: Exception) {
                BluetoothStateMachine.updateSafe(
                    ConnectionState.Error("Unexpected error: ${e.message}")
                )
                Timber.tag("BluetoothSocketManager").e(e, "❌ Unexpected error")
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
            Timber.tag("BluetoothSocketManager").i("🎧 Start listening for data...")
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
                Timber.tag("BluetoothSocketManager").d("📵 Connection lost (expected on disconnect)")
                if (isConnected.get()) {
                    BluetoothStateMachine.updateSafe(ConnectionState.Disconnected)
                    disconnectInternal()
                }
            } catch (e: Exception) {
                Timber.tag("BluetoothSocketManager").e(e, "Listen error")
                disconnectInternal()
            }
        }
    }

    private fun startRawStreaming() {
        scope.launch {
            Timber.tag("BluetoothSocketManager").i("📡 Starting raw data streaming...")
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

                        // Log raw data untuk debugging (hanya di debug build)
                        if (BuildConfig.DEBUG) {
                            val text = String(data).trim()
                            if (text.isNotEmpty()) {
                                Timber.tag("BluetoothSocketManager").d("📥 RAW: $text")
                            }
                        }
                    }

                    delay(10)
                }
            } catch (e: IOException) {
                Timber.tag("BluetoothSocketManager").d("Raw streaming stopped")
            } catch (e: Exception) {
                Timber.tag("BluetoothSocketManager").e(e, "Raw streaming error")
            }
        }
    }

    private fun startRawDataProcessor() {
        scope.launch {
            rawDataStream.collect { rawData ->
                try {
                    val text = String(rawData).trim()
                    if (text.isNotEmpty()) {
                        _receivedData.emit(text)
                    }
                } catch (e: Exception) {
                    Timber.tag("BluetoothSocketManager").e(e, "Error processing raw data")
                }
            }
        }
    }

    private fun startSendProcessor() {
        sendJob?.cancel()
        sendJob = scope.launch {
            Timber.tag("BluetoothSocketManager").i("📤 Send processor started")
            while (isActive) {
                try {
                    val command = sendChannel.receive()

                    if (!isConnected.get()) {
                        Timber.tag("BluetoothSocketManager").w("Not connected, skipping: $command")
                        continue
                    }

                    if (!isChannelActive.get()) {
                        Timber.tag("BluetoothSocketManager").w("Channel not active, skipping: $command")
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

                    Timber.tag("BluetoothSocketManager").d("📤 Sent: $command")

                    delay(50)

                } catch (e: CancellationException) {
                    Timber.tag("BluetoothSocketManager").d("🚫 Send processor cancelled")
                    break
                } catch (e: Exception) {
                    Timber.tag("BluetoothSocketManager").e(e, "Send error")
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

                Timber.tag("BluetoothSocketManager").d(
                    "Send attempt: connected=$connected, channelActive=$channelActive, command=$command"
                )

                if (connected && channelActive) {
                    sendChannel.send(command)
                    Timber.tag("BluetoothSocketManager").d("✅ Command sent to channel: $command")
                } else {
                    Timber.tag("BluetoothSocketManager").w(
                        "Cannot send: connected=$connected, channelActive=$channelActive, command=$command"
                    )
                }
            } catch (e: Exception) {
                Timber.tag("BluetoothSocketManager").e(e, "Failed to queue command")
            }
        }
    }

    private fun disconnectInternal() {
        Timber.tag("BluetoothSocketManager").i("🛑 Starting disconnectInternal")

        isChannelActive.set(false)
        isConnected.set(false)
        isConnecting.set(false)

        listenJob?.cancel()
        listenJob = null

        try {
            inputStream?.close()
        } catch (e: Exception) {
            Timber.tag("BluetoothSocketManager").d("Input stream close error: ${e.message}")
        }

        try {
            outputStream?.close()
        } catch (e: Exception) {
            Timber.tag("BluetoothSocketManager").d("Output stream close error: ${e.message}")
        }

        try {
            socket?.close()
        } catch (e: Exception) {
            Timber.tag("BluetoothSocketManager").d("Socket close error: ${e.message}")
        }

        inputStream = null
        outputStream = null
        socket = null

        Timber.tag("BluetoothSocketManager").i("🛑 Disconnected and cleaned up")
    }

    fun disconnect() {
        Timber.tag("BluetoothSocketManager").i("🛑 Disconnect called")
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

    // Hapus fungsi yang tidak digunakan, atau beri annotation @Suppress jika ingin dipertahankan
    // @Suppress("unused")
    // fun getConnectionStatus(): String {
    //     return when {
    //         isConnected.get() -> "CONNECTED"
    //         isConnecting.get() -> "CONNECTING"
    //         else -> "DISCONNECTED"
    //     }
    // }
}