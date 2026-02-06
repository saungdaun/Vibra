package zaujaani.vibra.core.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import timber.log.Timber
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.StateFlow
import java.lang.ref.WeakReference
import zaujaani.vibra.core.permission.PermissionManager

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
                Timber.e(e, "Failed to get Bluetooth adapter")
                null
            }
        }
    }

    val state: StateFlow<ConnectionState>
        get() = BluetoothStateMachine.state

    fun init(context: Context) {
        appContext = WeakReference(context.applicationContext)
        Timber.tag("BluetoothGateway").d("✅ Initialized with application context")
    }

    fun getContext(): Context {
        return appContext?.get() ?: throw IllegalStateException(
            "BluetoothGateway not initialized. Call init() first."
        )
    }

    // Tambahkan method untuk mendapatkan BluetoothAdapter dengan cara yang benar
    fun getBluetoothAdapter(): BluetoothAdapter? {
        return adapter
    }

    fun hasBluetoothConnectPermission(): Boolean {
        return try {
            val context = getContext()
            PermissionManager.hasBluetoothConnectPermission(context)
        } catch (e: Exception) {
            Timber.e(e, "Bluetooth connect permission check failed")
            false
        }
    }

    fun hasBluetoothScanPermission(): Boolean {
        return try {
            val context = getContext()
            PermissionManager.hasBluetoothScanPermission(context)
        } catch (e: Exception) {
            Timber.e(e, "Bluetooth scan permission check failed")
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
            Timber.tag("BluetoothGateway").d("⚠️ Already connecting/connected, ignoring")
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

        Timber.tag("BluetoothGateway").i("🔗 Connecting to $deviceName")
        BluetoothStateMachine.updateSafe(ConnectionState.Connecting(deviceName))
        BluetoothSocketManager.connect(device)
    }

    fun disconnect() {
        Timber.tag("BluetoothGateway").i("🛑 Disconnecting Bluetooth")
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
            Timber.e(e, "SecurityException checking Bluetooth")
            false
        } catch (_: Exception) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun bondedDevices(): Set<BluetoothDevice> {
        return try {
            if (!hasBluetoothConnectPermission()) {
                Timber.tag("BluetoothGateway").w("No permission for bonded devices")
                return emptySet()
            }

            adapter?.bondedDevices ?: emptySet()
        } catch (e: SecurityException) {
            Timber.e(e, "SecurityException getting bonded devices")
            emptySet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    fun cleanup() {
        Timber.tag("BluetoothGateway").i("🧹 Cleaning up BluetoothGateway")
        disconnect()
        appContext?.clear()
        appContext = null
    }
}