package zaujaani.vibra.core.logger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import zaujaani.vibra.core.bluetooth.BluetoothSocketManager

data class SensorData(
    val tripDistance: Float = 0.0f,
    val staMajor: Int = 0,
    val staMinor: Int = 0,
    val meterRemainder: Int = 0,
    val speed: Float = 0.0f,
    val elevation: Float = 0.0f,
    val battery: Float = 0.0f,
    val sessionId: Int = 0,
    val packetCount: Int = 0,
    val state: String = "READY",
    val rawData: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

object LoggerEngine : ViewModel() {

    private val _sensorData = MutableStateFlow(SensorData())
    val sensorData: StateFlow<SensorData> = _sensorData

    private val _logMessages = MutableStateFlow<List<String>>(emptyList())
    val logMessages: StateFlow<List<String>> = _logMessages

    private val maxLogSize = 100

    init {
        startListening()
    }

    private fun startListening() {
        viewModelScope.launch {
            BluetoothSocketManager.receivedData.collect { data ->
                processIncomingData(data)
            }
        }
    }

    private fun processIncomingData(data: String) {
        when {
            data.startsWith("RS2,") -> processRS2Data(data)
            data.startsWith("ACK:") -> addLog("✅ $data")
            data.startsWith("ERR:") -> addLog("❌ $data")
            data.startsWith("WARN:") -> addLog("⚠️ $data")
            data.startsWith("DATA:") -> processDataResponse(data)
            else -> addLog("📡 $data")
        }
    }

    private fun processRS2Data(data: String) {
        val current = _sensorData.value
        _sensorData.value = current.copy(
            rawData = data,
            timestamp = System.currentTimeMillis()
        )

        addLog("📊 RS2 Data: ${data.take(50)}...")
    }

    private fun processDataResponse(data: String) {
        val clean = data.removePrefix("DATA:")
        val parts = clean.split(",")

        val updates = mutableMapOf<String, Any>()
        parts.forEach { part ->
            val keyValue = part.split("=")
            if (keyValue.size == 2) {
                when (keyValue[0]) {
                    "trip" -> updates["tripDistance"] = keyValue[1].toFloatOrNull() ?: 0f
                    "sta" -> {
                        val staParts = keyValue[1].split("+")
                        if (staParts.size == 2) {
                            updates["staMajor"] = staParts[0].toIntOrNull() ?: 0
                            updates["staMinor"] = staParts[1].toIntOrNull() ?: 0
                        }
                    }
                    "spd" -> updates["speed"] = keyValue[1].toFloatOrNull() ?: 0f
                    "z" -> updates["elevation"] = keyValue[1].toFloatOrNull() ?: 0f
                    "pkt" -> updates["packetCount"] = keyValue[1].toIntOrNull() ?: 0
                    "state" -> updates["state"] = keyValue[1]
                    "bat" -> updates["battery"] = keyValue[1].toFloatOrNull() ?: 0f
                    "sid" -> updates["sessionId"] = keyValue[1].toIntOrNull() ?: 0
                }
            }
        }

        val current = _sensorData.value
        _sensorData.value = current.copy(
            tripDistance = updates["tripDistance"] as? Float ?: current.tripDistance,
            staMajor = updates["staMajor"] as? Int ?: current.staMajor,
            staMinor = updates["staMinor"] as? Int ?: current.staMinor,
            speed = updates["speed"] as? Float ?: current.speed,
            elevation = updates["elevation"] as? Float ?: current.elevation,
            battery = updates["battery"] as? Float ?: current.battery,
            sessionId = updates["sessionId"] as? Int ?: current.sessionId,
            packetCount = updates["packetCount"] as? Int ?: current.packetCount,
            state = updates["state"] as? String ?: current.state,
            timestamp = System.currentTimeMillis()
        )
    }

    private fun addLog(message: String) {
        val current = _logMessages.value.toMutableList()
        current.add(0, "[${System.currentTimeMillis()}] $message")
        if (current.size > maxLogSize) {
            current.removeAt(current.lastIndex)
        }
        _logMessages.value = current
    }

    // Command helpers
    fun sendStart() = sendCommand("START")
    fun sendStop() = sendCommand("STOP")
    fun sendPause() = sendCommand("PAUSE")
    fun sendResetTrip() = sendCommand("RESETTRIP")
    fun sendGetData() = sendCommand("GETDATA")
    fun sendGetBattery() = sendCommand("GETBATTERY")
    fun sendGetSession() = sendCommand("GETSESSION")
    fun sendNewSession() = sendCommand("NEWSESSION")
    fun sendNextView() = sendCommand("NEXTVIEW")
    fun sendSetWheel(value: Float) = sendCommand("SETWHEEL,$value")
    fun sendSetSmooth(value: Float) = sendCommand("SETSMOOTH,$value")
    fun sendSetZOffset(value: Float) = sendCommand("SETZOFFSET,$value")
    fun sendSyncTime(timeStr: String) = sendCommand("SYNCTIME,$timeStr")

    private fun sendCommand(command: String) {
        if (BluetoothSocketManager.isConnected()) {
            BluetoothSocketManager.sendCommand(command)
            addLog("📤 Sent: $command")
        } else {
            addLog("⚠️ Not connected, cannot send: $command")
        }
    }

    fun clearLogs() {
        _logMessages.value = emptyList()
    }
}