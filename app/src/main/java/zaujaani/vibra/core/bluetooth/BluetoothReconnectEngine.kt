package zaujaani.vibra.core.bluetooth

import android.bluetooth.BluetoothDevice
import kotlinx.coroutines.*

object BluetoothReconnectEngine {

    private var lastDevice: BluetoothDevice? = null

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO
    )

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

                delay(4000) // 🔥 sweet spot (jangan terlalu cepat)

                val state = BluetoothStateMachine.state.value

                if (state == ConnectionState.Disconnected) {

                    lastDevice?.let {

                        BluetoothStateMachine.update(
                            ConnectionState.Connecting
                        )

                        BluetoothSocketManager.connect(it)
                    }
                }
            }
        }
    }


    // ===============================
    // STOP ENGINE
    // ===============================

    fun stop() {
        reconnectJob?.cancel()
    }
}
