package zaujaani.vibra.core.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.IOException
import java.util.*

object BluetoothSocketManager {

    private val uuid: UUID =
        UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var socket: BluetoothSocket? = null

    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var connectJob: Job? = null


    // =========================
    // CONNECT
    // =========================

    fun connect(device: BluetoothDevice) {

        // 🛑 anti double connect
        if (connectJob?.isActive == true) return

        connectJob = scope.launch {

            try {

                BluetoothStateMachine.update(
                    ConnectionState.Connecting
                )

                val service = BluetoothGateway.getService()

                // ✅ permission guard (lint bakal diem)
                if (
                    ContextCompat.checkSelfPermission(
                        service,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {

                    BluetoothStateMachine.update(
                        ConnectionState.Error("Bluetooth permission denied")
                    )
                    return@launch
                }

                // ✅ cancel discovery (WAJIB — ini penyebab connect lama)
                BluetoothAdapter
                    .getDefaultAdapter()
                    ?.cancelDiscovery()

                socket?.close()

                socket =
                    device.createRfcommSocketToServiceRecord(uuid)

                // 🔥 TIMEOUT — jangan pernah tanpa ini
                withTimeout(12_000) {
                    socket!!.connect()
                }

                BluetoothReconnectEngine.remember(device)
                BluetoothReconnectEngine.start()

                BluetoothStateMachine.update(
                    ConnectionState.Connected
                )

                listen()

            } catch (e: TimeoutCancellationException) {

                BluetoothStateMachine.update(
                    ConnectionState.Error("Connection timeout")
                )

            } catch (e: SecurityException) {

                BluetoothStateMachine.update(
                    ConnectionState.Error("Permission error")
                )

            } catch (e: IOException) {

                BluetoothStateMachine.update(
                    ConnectionState.Disconnected
                )

            }
        }
    }


    // =========================
    // LISTEN
    // =========================

    private suspend fun listen() {

        val input = socket?.inputStream ?: return
        val buffer = ByteArray(1024)

        try {

            while (currentCoroutineContext().isActive) {

                val bytes = input.read(buffer)

                if (bytes == -1) throw IOException()

                if (bytes > 0) {

                    val data =
                        String(buffer, 0, bytes)

                    // 🔥 nanti masuk LoggerEngine
                    println("ESP32 -> $data")
                }
            }

        } catch (e: IOException) {

            BluetoothStateMachine.update(
                ConnectionState.Disconnected
            )

            socket?.close()
            socket = null
        }
    }


    // =========================
    // DISCONNECT (MANUAL)
    // =========================

    fun disconnect() {

        connectJob?.cancel()

        scope.launch {

            BluetoothReconnectEngine.stop()

            try {
                socket?.close()
            } catch (_: Exception) {}

            socket = null

            BluetoothStateMachine.update(
                ConnectionState.Disconnected
            )
        }
    }
}
