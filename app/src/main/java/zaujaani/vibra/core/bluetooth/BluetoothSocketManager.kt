package zaujaani.vibra.core.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*

object BluetoothSocketManager {

    private val uuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var connectJob: Job? = null
    private var listenJob: Job? = null

    // 🔥 Flow untuk menerima data dari ESP32
    private val _receivedData = MutableSharedFlow<String>()
    val receivedData: SharedFlow<String> = _receivedData.asSharedFlow()

    // 🔥 Channel untuk mengirim data ke ESP32
    private val sendChannel = Channel<String>(Channel.UNLIMITED)

    init {
        startSendProcessor()
    }

    // =========================
    // CONNECT
    // =========================

    fun connect(device: BluetoothDevice) {
        if (connectJob?.isActive == true) return

        connectJob = scope.launch {
            try {
                BluetoothStateMachine.update(ConnectionState.Connecting(device.name))

                val context = BluetoothGateway.getContext()

                // 🔥 EXPLICIT PERMISSION CHECK dengan checkPermission
                val hasConnectPermission = try {
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
                } catch (_: Exception) {
                    false
                }

                if (!hasConnectPermission) {
                    BluetoothStateMachine.update(
                        ConnectionState.Error("Bluetooth Connect permission denied")
                    )
                    return@launch
                }

                // 🔥 Check scan permission for discovery
                val hasScanPermission = try {
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_SCAN
                    ) == PackageManager.PERMISSION_GRANTED
                } catch (_: Exception) {
                    false
                }

                if (!hasScanPermission) {
                    BluetoothStateMachine.update(
                        ConnectionState.Error("Bluetooth Scan permission denied")
                    )
                    return@launch
                }

                // ✅ Cancel discovery (REQUIRED — this causes slow connects)
                try {
                    if (hasScanPermission) {
                        BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
                    }
                } catch (e: SecurityException) {
                    BluetoothStateMachine.update(
                        ConnectionState.Error("Security exception: ${e.message}")
                    )
                    return@launch
                }

                // Close existing connection
                disconnectInternal()

                // Create new socket with permission check
                socket = try {
                    device.createRfcommSocketToServiceRecord(uuid)
                } catch (e: SecurityException) {
                    BluetoothStateMachine.update(
                        ConnectionState.Error("Cannot create socket: ${e.message}")
                    )
                    return@launch
                }

                // 🔥 TIMEOUT — never without this
                try {
                    withTimeout(12_000) {
                        socket!!.connect()
                    }
                } catch (e: SecurityException) {
                    BluetoothStateMachine.update(
                        ConnectionState.Error("Security exception during connect: ${e.message}")
                    )
                    return@launch
                }

                // Setup streams
                inputStream = socket!!.inputStream
                outputStream = socket!!.outputStream

                // Start listening
                startListening()

                // Remember device for auto-reconnect
                BluetoothReconnectEngine.remember(device)
                BluetoothReconnectEngine.start()

                BluetoothStateMachine.update(ConnectionState.Connected(device.name))

            } catch (e: TimeoutCancellationException) {
                BluetoothStateMachine.update(
                    ConnectionState.Error("Connection timeout")
                )
            } catch (e: SecurityException) {
                BluetoothStateMachine.update(
                    ConnectionState.Error("Permission error: ${e.message}")
                )
            } catch (e: IOException) {
                BluetoothStateMachine.update(
                    ConnectionState.Disconnected
                )
            } catch (e: Exception) {
                BluetoothStateMachine.update(
                    ConnectionState.Error("Unexpected error: ${e.message}")
                )
            }
        }
    }

    // =========================
    // LISTENING
    // =========================

    private fun startListening() {
        listenJob?.cancel()
        listenJob = scope.launch {
            val buffer = ByteArray(1024)
            try {
                while (isActive) {
                    val bytes = inputStream?.read(buffer) ?: -1
                    if (bytes == -1) throw IOException("Stream closed")

                    if (bytes > 0) {
                        val data = String(buffer, 0, bytes)

                        // Split by newlines and emit each line
                        data.split("\n", "\r").forEach { line ->
                            if (line.isNotBlank()) {
                                _receivedData.emit(line)
                                // Also forward to LoggerEngine if needed
                                println("ESP32 -> $line")
                            }
                        }
                    }

                    // Small delay to prevent CPU overuse
                    delay(10)
                }
            } catch (e: IOException) {
                // Connection lost
                BluetoothStateMachine.update(ConnectionState.Disconnected)
                disconnectInternal()
            } catch (e: Exception) {
                // Other errors
                e.printStackTrace()
            }
        }
    }

    // =========================
    // SEND DATA TO ESP32
    // =========================

    private fun startSendProcessor() {
        scope.launch {
            for (command in sendChannel) {
                try {
                    // 🔥 Check permission before sending
                    val context = BluetoothGateway.getContext()

                    val hasPermission = try {
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) == PackageManager.PERMISSION_GRANTED
                    } catch (_: Exception) {
                        false
                    }

                    if (!hasPermission) {
                        BluetoothStateMachine.update(
                            ConnectionState.Error("Lost Bluetooth permission while sending")
                        )
                        break
                    }

                    outputStream?.write("$command\n".toByteArray())
                    outputStream?.flush()
                    delay(50) // Small delay between commands
                } catch (e: SecurityException) {
                    BluetoothStateMachine.update(
                        ConnectionState.Error("Security exception while sending: ${e.message}")
                    )
                    break
                } catch (e: IOException) {
                    // Connection lost
                    BluetoothStateMachine.update(ConnectionState.Disconnected)
                    disconnectInternal()
                    break
                }
            }
        }
    }

    fun sendCommand(command: String) {
        scope.launch {
            if (socket?.isConnected == true) {
                sendChannel.send(command)
            }
        }
    }

    // =========================
    // DISCONNECT
    // =========================

    private fun disconnectInternal() {
        listenJob?.cancel()

        try {
            inputStream?.close()
        } catch (_: Exception) {}

        try {
            outputStream?.close()
        } catch (_: Exception) {}

        try {
            socket?.close()
        } catch (_: Exception) {}

        inputStream = null
        outputStream = null
        socket = null
    }

    fun disconnect() {
        connectJob?.cancel()

        scope.launch {
            BluetoothReconnectEngine.stop()

            // 🔥 Check permission before disconnecting
            val context = BluetoothGateway.getContext()
            val hasPermission = try {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            } catch (_: Exception) {
                false
            }

            if (hasPermission) {
                disconnectInternal()
            }

            BluetoothStateMachine.update(ConnectionState.Disconnected)
        }
    }

    // =========================
    // UTILITY
    // =========================

    fun isConnected(): Boolean {
        return socket?.isConnected == true
    }
}