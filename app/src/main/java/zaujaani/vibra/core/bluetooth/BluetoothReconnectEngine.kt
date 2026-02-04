package zaujaani.vibra.core.bluetooth

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

object BluetoothReconnectEngine {
    private var lastDevice: BluetoothDevice? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reconnectJob: Job? = null

    fun remember(device: BluetoothDevice) {
        lastDevice = device
        Log.d("BluetoothReconnectEngine", "Remembering device: ${device.address}")
    }

    fun start() {
        if (reconnectJob?.isActive == true) {
            Log.d("BluetoothReconnectEngine", "⚠️ Reconnect engine already running")
            return
        }

        reconnectJob = scope.launch {
            Log.d("BluetoothReconnectEngine", "🔄 Auto-reconnect engine started")
            while (isActive) {
                delay(4000)
                val state = BluetoothStateMachine.state.value

                if (state is ConnectionState.Disconnected) {
                    val device = lastDevice ?: continue
                    val context = BluetoothGateway.getContext()

                    val hasConnectPermission = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED

                    if (hasConnectPermission) {
                        try {
                            val deviceName = try {
                                device.name ?: "Unknown Device"
                            } catch (e: SecurityException) {
                                "Unknown Device"
                            }

                            Log.d("BluetoothReconnectEngine", "🔄 Attempting reconnect to $deviceName")
                            BluetoothStateMachine.updateSafe(ConnectionState.Connecting(deviceName))
                            BluetoothSocketManager.connect(device)
                        } catch (e: SecurityException) {
                            Log.e("BluetoothReconnectEngine", "SecurityException during reconnect", e)
                            stop()
                        } catch (_: Exception) {
                            Log.d("BluetoothReconnectEngine", "Reconnect attempt failed")
                        }
                    } else {
                        Log.w("BluetoothReconnectEngine", "No Bluetooth Connect permission, stopping")
                        stop()
                    }
                }
            }
        }

        Log.d("BluetoothReconnectEngine", "🔄 Auto-reconnect engine started")
    }

    fun stop() {
        reconnectJob?.cancel()
        reconnectJob = null
        Log.d("BluetoothReconnectEngine", "🛑 Auto-reconnect engine stopped")
    }
}