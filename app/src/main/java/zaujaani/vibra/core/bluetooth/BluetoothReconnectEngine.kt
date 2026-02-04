package zaujaani.vibra.core.bluetooth

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

object BluetoothReconnectEngine {
    private var lastDevice: BluetoothDevice? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reconnectJob: Job? = null

    // ===============================
    // SAVE DEVICE
    // ===============================
    fun remember(device: BluetoothDevice) {
        lastDevice = device
    }

    // ===============================
    // START ENGINE
    // ===============================
    fun start() {
        if (reconnectJob?.isActive == true) return

        reconnectJob = scope.launch {
            while (isActive) {
                delay(4000)
                val state = BluetoothStateMachine.state.value

                if (state is ConnectionState.Disconnected) {
                    val device = lastDevice ?: continue
                    val context = BluetoothGateway.getContext()

                    // ✅ Explicitly check permissions where the sensitive call happens
                    val hasConnectPermission = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED

                    if (hasConnectPermission) {
                        try {
                            // The lint warning usually triggers on device.name or connect()
                            BluetoothStateMachine.update(ConnectionState.Connecting(device.name ?: "Unknown Device"))
                            BluetoothSocketManager.connect(device)
                        } catch (e: SecurityException) {
                            // 🛡️ Handle the case where permission is revoked at runtime
                            stop()
                        }
                    } else {
                        stop()
                    }
                }
            }
        }
    }
    // ===============================
    // PERMISSION CHECK (SAFE WAY)
    // ===============================
    private fun hasBluetoothPermission(): Boolean {
        return try {
            val context = BluetoothGateway.getContext()

            // 🔥 CHECK SECURITY PERMISSION DENGAN AMAN
            val hasConnectPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED

            val hasScanPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED

            hasConnectPermission && hasScanPermission
        } catch (e: Exception) {
            false
        }
    }

    // ===============================
    // STOP ENGINE
    // ===============================
    fun stop() {
        reconnectJob?.cancel()
        reconnectJob = null
    }
}