package zaujaani.vibra.core.bluetooth

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import timber.log.Timber

object BluetoothReconnectEngine {

    private var lastDevice: BluetoothDevice? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reconnectJob: Job? = null

    fun remember(device: BluetoothDevice) {
        lastDevice = device
        Timber.d("Remembering device: ${device.address}")
    }

    fun start() {
        if (reconnectJob?.isActive == true) {
            Timber.w("Reconnect engine already running")
            return
        }

        reconnectJob = scope.launch {
            Timber.d("Auto-reconnect engine started")

            while (isActive) {
                delay(4000)

                val state = BluetoothStateMachine.state.value

                if (state is ConnectionState.Disconnected) {

                    val device = lastDevice ?: continue
                    val context = BluetoothGateway.getContext()

                    val hasConnectPermission =
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) == PackageManager.PERMISSION_GRANTED

                    if (hasConnectPermission) {
                        try {

                            val deviceName = try {
                                device.name ?: "Unknown Device"
                            } catch (_: SecurityException) {
                                "Unknown Device"
                            }

                            Timber.d("Attempting reconnect to $deviceName")

                            BluetoothStateMachine.updateSafe(
                                ConnectionState.Connecting(deviceName)
                            )

                            BluetoothSocketManager.connect(device)

                        } catch (e: SecurityException) {

                            Timber.e(e, "SecurityException during reconnect")
                            stop()

                        } catch (e: Exception) {

                            Timber.d(e, "Reconnect attempt failed")
                        }

                    } else {

                        Timber.w("No Bluetooth Connect permission, stopping")
                        stop()
                    }
                }
            }
        }
    }

    fun stop() {
        reconnectJob?.cancel()
        reconnectJob = null
        Timber.d("Auto-reconnect engine stopped")
    }
}
