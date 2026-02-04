package zaujaani.vibra.core.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.StateFlow
import android.util.Log
import android.Manifest
object BluetoothGateway {

    // 🔥 Gunakan application context untuk menghindari memory leak
    private var appContext: Context? = null

    private val adapter: BluetoothAdapter? by lazy {
        if (appContext == null) return@lazy null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val bluetoothManager = appContext?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            bluetoothManager?.adapter
        } else {
            @Suppress("DEPRECATION")
            BluetoothAdapter.getDefaultAdapter()
        }
    }

    // 🔥 SINGLE SOURCE OF TRUTH
    val state: StateFlow<ConnectionState>
        get() = BluetoothStateMachine.state

    // ===============================
    // INIT (WAJIB dari Service/Application)
    // ===============================

    fun init(context: Context) {
        appContext = context.applicationContext
        Log.d("BluetoothGateway", "Initialized with context")
    }

    // 🔥 Public untuk diakses oleh BluetoothSocketManager
    fun getContext(): Context {
        return appContext ?: throw IllegalStateException(
            "BluetoothGateway not initialized. Start VibraBluetoothService first."
        )
    }
    // Tambahkan di dalam object BluetoothGateway:

// ===============================
// PERMISSION CHECKERS (Public)
// ===============================

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
                val hasBluetooth = ContextCompat.checkSelfPermission(
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

                hasBluetooth && hasLocation
            }
        } catch (e: Exception) {
            false
        }
    }

    fun hasAllBluetoothPermissions(): Boolean {
        return hasBluetoothConnectPermission() && hasBluetoothScanPermission()
    }

    // ===============================
    // PERMISSION HELPERS
    // ===============================

    private fun hasBluetoothConnectPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                "android.permission.BLUETOOTH_CONNECT"
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // Untuk API < 31, gunakan permission lama
            @Suppress("DEPRECATION")
            ContextCompat.checkSelfPermission(
                context,
                "android.permission.BLUETOOTH"
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasBluetoothScanPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                "android.permission.BLUETOOTH_SCAN"
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // Untuk API < 31, gunakan permission lama + location
            @Suppress("DEPRECATION")
            val hasBluetooth = ContextCompat.checkSelfPermission(
                context,
                "android.permission.BLUETOOTH_ADMIN"
            ) == PackageManager.PERMISSION_GRANTED

            val hasLocation = ContextCompat.checkSelfPermission(
                context,
                "android.permission.ACCESS_FINE_LOCATION"
            ) == PackageManager.PERMISSION_GRANTED

            hasBluetooth && hasLocation
        }
    }

    // ===============================
    // CONNECT
    // ===============================

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        val currentState = state.value

        // 🛑 anti spam tombol
        if (currentState is ConnectionState.Connected || currentState is ConnectionState.Connecting) {
            return
        }

        if (!isBluetoothAvailable()) {
            BluetoothStateMachine.update(
                ConnectionState.Error("Bluetooth not available")
            )
            return
        }

        if (!isBluetoothEnabled()) {
            BluetoothStateMachine.update(
                ConnectionState.Error("Bluetooth disabled")
            )
            return
        }

        // 🔥 EXPLICIT PERMISSION CHECK
        val context = getContext()

        if (!hasBluetoothConnectPermission(context)) {
            BluetoothStateMachine.update(
                ConnectionState.Error("Bluetooth Connect permission required")
            )
            return
        }

        if (!hasBluetoothScanPermission(context)) {
            BluetoothStateMachine.update(
                ConnectionState.Error("Bluetooth Scan/Location permission required")
            )
            return
        }

        BluetoothSocketManager.connect(device)
    }

    // ===============================
    // DISCONNECT
    // ===============================

    fun disconnect() {
        BluetoothSocketManager.disconnect()
    }

    // ===============================
    // CHECK DEVICE
    // ===============================

    fun isBluetoothAvailable(): Boolean {
        return adapter != null
    }

    @SuppressLint("MissingPermission")
    fun isBluetoothEnabled(): Boolean {
        return try {
            // 🔥 Check permission first
            val context = getContext()

            if (!hasBluetoothConnectPermission(context)) return false

            adapter?.isEnabled == true
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    // ===============================
    // BONDED DEVICES
    // ===============================

    @SuppressLint("MissingPermission")
    fun bondedDevices(): Set<BluetoothDevice> {
        return try {
            // 🔥 Check permission first
            val context = getContext()

            if (!hasBluetoothConnectPermission(context)) {
                BluetoothStateMachine.update(
                    ConnectionState.Error("Bluetooth permission denied for bonded devices")
                )
                return emptySet()
            }

            adapter?.bondedDevices ?: emptySet()
        } catch (e: SecurityException) {
            BluetoothStateMachine.update(
                ConnectionState.Error("Security exception: ${e.message}")
            )
            emptySet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    // Clean up method
    fun cleanup() {
        appContext = null
    }
}