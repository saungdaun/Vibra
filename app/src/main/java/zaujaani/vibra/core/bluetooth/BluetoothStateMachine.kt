package zaujaani.vibra.core.bluetooth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object BluetoothStateMachine {

    private val _state =
        MutableStateFlow<ConnectionState>(
            ConnectionState.Disconnected
        )

    val state: StateFlow<ConnectionState> = _state


    // 🔥 SATU PINTU UPDATE STATE
    fun update(newState: ConnectionState) {

        _state.value = newState
    }
}
