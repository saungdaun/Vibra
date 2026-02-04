package zaujaani.vibra.core.bluetooth

sealed class ConnectionState {
    data class Connected(val deviceName: String? = null) : ConnectionState()
    data class Connecting(val deviceName: String? = null) : ConnectionState()
    object Disconnected : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}