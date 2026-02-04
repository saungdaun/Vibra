package zaujaani.vibra.core.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.StateFlow
import java.lang.ref.WeakReference

object BluetoothGateway {

    private var appContext: WeakReference<Context>? = null

    private val adapter: BluetoothAdapter? by lazy {
        appContext?.get()?.let { context ->
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                    bluetoothManager?.adapter
                } else {
                    @Suppress("DEPRECATION")
                    BluetoothAdapter.getDefaultAdapter()
                }
            } catch (e: Exception) {
                Log.e("BluetoothGateway", "Failed to get adapter", e)
                null
            }
        }
    }

    val state: StateFlow<ConnectionState>
        get() = BluetoothStateMachine.state

    fun init(context: Context) {
        appContext = WeakReference(context.applicationContext)
        Log.d("BluetoothGateway", "✅ Initialized with application context")
    }

    fun getContext(): Context {
        return appContext?.get() ?: throw IllegalStateException(
            "BluetoothGateway not initialized. Call init() first."
        )
    }

    fun hasBluetoothConnectPermission(): Boolean {
        return try {
            val context = getContext()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                @Suppress("DEPRECATION")
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH
                ) == PackageManager.PERMISSION_GRANTED
            }
        } catch (e: Exception) {
            Log.e("BluetoothGateway", "Permission check failed", e)
            false
        }
    }

    fun hasBluetoothScanPermission(): Boolean {
        return try {
            val context = getContext()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_SCAN
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                @Suppress("DEPRECATION")
                val hasBluetoothAdmin = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_ADMIN
                ) == PackageManager.PERMISSION_GRANTED

                val hasLocation = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED

                hasBluetoothAdmin && hasLocation
            }
        } catch (e: Exception) {
            Log.e("BluetoothGateway", "Permission check failed", e)
            false
        }
    }

    private fun hasAllBluetoothPermissions(): Boolean {
        return hasBluetoothConnectPermission() && hasBluetoothScanPermission()
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        val currentState = state.value

        if (currentState is ConnectionState.Connected || currentState is ConnectionState.Connecting) {
            Log.d("BluetoothGateway", "⚠️ Already connecting/connected, ignoring")
            return
        }

        if (!isBluetoothAvailable()) {
            BluetoothStateMachine.updateSafe(
                ConnectionState.Error("Bluetooth not available on this device")
            )
            return
        }

        if (!isBluetoothEnabled()) {
            BluetoothStateMachine.updateSafe(
                ConnectionState.Error("Please enable Bluetooth first")
            )
            return
        }

        if (!hasAllBluetoothPermissions()) {
            BluetoothStateMachine.updateSafe(
                ConnectionState.Error("Bluetooth permissions required")
            )
            return
        }

        val deviceName = try {
            device.name ?: device.address
        } catch (e: SecurityException) {
            device.address
        }

        BluetoothStateMachine.updateSafe(ConnectionState.Connecting(deviceName))
        BluetoothSocketManager.connect(device)
    }

    fun disconnect() {
        Log.d("BluetoothGateway", "🛑 Disconnecting Bluetooth")
        BluetoothSocketManager.disconnect()
        BluetoothReconnectEngine.stop()
    }

    fun isBluetoothAvailable(): Boolean {
        return adapter != null
    }

    @SuppressLint("MissingPermission")
    fun isBluetoothEnabled(): Boolean {
        return try {
            if (!hasBluetoothConnectPermission()) {
                return false
            }
            adapter?.isEnabled == true
        } catch (e: SecurityException) {
            Log.e("BluetoothGateway", "SecurityException checking Bluetooth", e)
            false
        } catch (_: Exception) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun bondedDevices(): Set<BluetoothDevice> {
        return try {
            if (!hasBluetoothConnectPermission()) {
                Log.w("BluetoothGateway", "No permission for bonded devices")
                return emptySet()
            }

            adapter?.bondedDevices ?: emptySet()
        } catch (e: SecurityException) {
            Log.e("BluetoothGateway", "SecurityException getting bonded devices", e)
            emptySet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    fun cleanup() {
        Log.d("BluetoothGateway", "🧹 Cleaning up BluetoothGateway")
        disconnect()
        appContext?.clear()
        appContext = null
    }
}