package zaujaani.vibra.core.bluetooth

import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import kotlinx.coroutines.flow.StateFlow

object BluetoothGateway {

    private var service: Service? = null

    private val adapter: BluetoothAdapter? by lazy {
        BluetoothAdapter.getDefaultAdapter()
    }

    // 🔥 SINGLE SOURCE OF TRUTH
    val state: StateFlow<ConnectionState>
        get() = BluetoothStateMachine.state


    // ===============================
    // INIT (WAJIB dari Service)
    // ===============================

    fun init(service: Service) {
        this.service = service
    }

    fun getService(): Service {
        return service
            ?: throw IllegalStateException(
                "BluetoothGateway not initialized. Start VibraBluetoothService first."
            )
    }


    // ===============================
    // CONNECT
    // ===============================

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {

        val currentState = state.value

        // 🛑 anti spam tombol
        if (
            currentState == ConnectionState.Connected ||
            currentState == ConnectionState.Connecting
        ) {
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

        // ✅ Pengecekan service sudah diinisialisasi
        if (service == null) {
            BluetoothStateMachine.update(
                ConnectionState.Error("Bluetooth service not ready")
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
        return adapter?.isEnabled == true
    }


    // ===============================
    // BONDED DEVICES
    // ===============================

    @SuppressLint("MissingPermission")
    fun bondedDevices(): Set<BluetoothDevice> {
        return try {
            adapter?.bondedDevices ?: emptySet()
        } catch (e: SecurityException) {
            BluetoothStateMachine.update(
                ConnectionState.Error("Bluetooth permission denied")
            )
            emptySet()
        }
    }
}