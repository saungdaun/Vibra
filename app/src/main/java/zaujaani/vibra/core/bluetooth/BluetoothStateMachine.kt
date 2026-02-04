package zaujaani.vibra.core.bluetooth

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

object BluetoothStateMachine {
    private val TAG = "BluetoothStateMachine"

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val isUpdating = AtomicBoolean(false)
    private val uiScope = CoroutineScope(Dispatchers.Main.immediate)

    fun updateSafe(newState: ConnectionState) {
        if (isUpdating.getAndSet(true)) {
            Log.w(TAG, "⚠️ Update already in progress, skipping: $newState")
            return
        }

        try {
            val current = _state.value
            if (current != newState) {
                uiScope.launch {
                    _state.value = newState
                    Log.d(TAG, "🔄 State changed: $current → $newState")
                }
            }
        } finally {
            isUpdating.set(false)
        }
    }

    fun update(newState: ConnectionState) {
        updateSafe(newState)
    }

    fun reset() {
        updateSafe(ConnectionState.Disconnected)
    }
}