package zaujaani.vibra.core.logger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import zaujaani.vibra.core.bluetooth.BluetoothSocketManager
import zaujaani.vibra.core.bluetooth.BluetoothStateMachine
import java.util.Locale
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

@Suppress("unused", "MemberVisibilityCanBePrivate")
class LoggerViewModel : ViewModel() {

    private val _sensorData = MutableStateFlow(SensorData())
    val sensorData: StateFlow<SensorData> = _sensorData.asStateFlow()

    private val _logMessages = MutableStateFlow<List<String>>(emptyList())
    val logMessages: StateFlow<List<String>> = _logMessages.asStateFlow()

    private val _rawStreamData = MutableStateFlow<String>("")
    val rawStreamData: StateFlow<String> = _rawStreamData.asStateFlow()

    private val maxLogSize = 200 // 🔥 PERBESAR LOG BUFFER
    private val packetCounter = AtomicInteger(0)

    init {
        startListening()
        startRawStreamListening() // 🔥 LISTEN RAW STREAM
    }

    private fun startListening() {
        viewModelScope.launch(Dispatchers.IO) {
            BluetoothSocketManager.receivedData
                .buffer(5000) // 🔥 BUFFER BESAR UNTUK STREAMING
                .collect { data ->
                    processIncomingData(data)
                }
        }
    }

    // 🔥 FUNGSI BARU UNTUK RAW STREAM
    private fun startRawStreamListening() {
        viewModelScope.launch(Dispatchers.IO) {
            BluetoothSocketManager.rawDataStream
                .buffer(10000)
                .collect { rawData ->
                    try {
                        val text = String(rawData).trim()
                        if (text.isNotEmpty()) {
                            _rawStreamData.value = text
                            processStreamingData(text)
                        }
                    } catch (e: Exception) {
                        Log.e("LoggerViewModel", "Error processing raw stream", e)
                    }
                }
        }
    }

    private fun processStreamingData(data: String) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                // 🔥 PROSES DATA STREAMING REAL-TIME
                if (data.contains("RS2,")) {
                    processRS2Data(data)
                } else if (data.contains("trip=") || data.contains("spd=") || data.contains("z=")) {
                    processDataResponse(data)
                } else {
                    // Simpan sebagai raw data untuk display
                    val current = _sensorData.value
                    _sensorData.value = current.copy(
                        rawData = data,
                        timestamp = System.currentTimeMillis()
                    )
                }
            } catch (e: Exception) {
                Log.e("LoggerViewModel", "Stream processing error", e)
            }
        }
    }

    private fun processIncomingData(data: String) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                Log.d("LoggerViewModel", "📥 Processing: $data")

                when {
                    data.startsWith("RS2,") -> {
                        processRS2Data(data)
                        addLog("📊 RS2: ${data.take(50)}...")
                    }
                    data.startsWith("ACK:") -> {
                        val ackMsg = data.substringAfter("ACK:")
                        addLog("✅ $ackMsg")
                        // Update state jika perlu
                        updateStateFromAck(ackMsg)
                    }
                    data.startsWith("ERR:") -> {
                        addLog("❌ ${data.substringAfter("ERR:")}")
                    }
                    data.startsWith("WARN:") -> {
                        addLog("⚠️ ${data.substringAfter("WARN:")}")
                    }
                    data.startsWith("DATA:") -> {
                        processDataResponse(data)
                        addLog("📈 DATA updated")
                    }
                    data.contains("trip=") || data.contains("spd=") || data.contains("z=") -> {
                        processDataResponse(data)
                    }
                    else -> {
                        // Simpan semua data ke log
                        if (data.length < 100) {
                            addLog("📡 $data")
                        } else {
                            addLog("📡 ${data.take(80)}...")
                        }
                    }
                }
            } catch (e: Exception) {
                addLog("⚠️ Error processing: ${e.message}")
            }
        }
    }

    private fun updateStateFromAck(ackMessage: String) {
        viewModelScope.launch(Dispatchers.Main) {
            val current = _sensorData.value
            when {
                ackMessage.contains("START", ignoreCase = true) -> {
                    _sensorData.value = current.copy(state = "RUNNING")
                    addLog("▶️ Logging started")
                }
                ackMessage.contains("STOP", ignoreCase = true) -> {
                    _sensorData.value = current.copy(state = "STOPPED")
                    addLog("⏹️ Logging stopped")
                }
                ackMessage.contains("PAUSE", ignoreCase = true) -> {
                    _sensorData.value = current.copy(state = "PAUSED")
                    addLog("⏸️ Logging paused")
                }
                ackMessage.contains("RESET", ignoreCase = true) -> {
                    _sensorData.value = current.copy(
                        tripDistance = 0.0f,
                        staMajor = 0,
                        staMinor = 0,
                        state = "READY"
                    )
                    addLog("🔄 Trip reset")
                }
                ackMessage.contains("NEW SESSION", ignoreCase = true) -> {
                    _sensorData.value = current.copy(
                        sessionId = current.sessionId + 1,
                        packetCount = 0,
                        state = "NEW SESSION"
                    )
                    addLog("🆕 New session started")
                }
                ackMessage.contains("NEXT VIEW", ignoreCase = true) -> {
                    addLog("↪️ View changed")
                }
                ackMessage.contains("CLEAR", ignoreCase = true) -> {
                    addLog("🧹 Logs cleared")
                }
            }
        }
    }

    private fun processRS2Data(data: String) {
        try {
            val parts = data.split(",")
            if (parts.size >= 5) {
                val current = _sensorData.value
                val updated = current.copy(
                    tripDistance = parts.getOrNull(1)?.toFloatOrNull() ?: current.tripDistance,
                    speed = parts.getOrNull(2)?.toFloatOrNull() ?: current.speed,
                    elevation = parts.getOrNull(3)?.toFloatOrNull() ?: current.elevation,
                    battery = parts.getOrNull(4)?.toFloatOrNull() ?: current.battery,
                    rawData = data,
                    timestamp = System.currentTimeMillis(),
                    packetCount = packetCounter.incrementAndGet()
                )

                if (updated != current) {
                    _sensorData.value = updated
                }
            }
        } catch (e: Exception) {
            Log.e("LoggerViewModel", "Error parsing RS2 data", e)
        }
    }

    private fun processDataResponse(data: String) {
        try {
            val cleanData = if (data.startsWith("DATA:")) {
                data.removePrefix("DATA:")
            } else {
                data
            }

            val parts = cleanData.split(",")
            val current = _sensorData.value
            var updated = current

            parts.forEach { part ->
                val keyValue = part.split("=")
                if (keyValue.size == 2) {
                    val key = keyValue[0].trim()
                    val value = keyValue[1].trim()

                    updated = when (key) {
                        "trip" -> updated.copy(tripDistance = value.toFloatOrNull() ?: current.tripDistance)
                        "sta" -> {
                            val staParts = value.split("+")
                            if (staParts.size == 2) {
                                updated.copy(
                                    staMajor = staParts[0].toIntOrNull() ?: current.staMajor,
                                    staMinor = staParts[1].toIntOrNull() ?: current.staMinor
                                )
                            } else updated
                        }
                        "spd" -> updated.copy(speed = value.toFloatOrNull() ?: current.speed)
                        "z" -> updated.copy(elevation = value.toFloatOrNull() ?: current.elevation)
                        "pkt" -> updated.copy(packetCount = value.toIntOrNull() ?: current.packetCount)
                        "state" -> updated.copy(state = value)
                        "bat" -> updated.copy(battery = value.toFloatOrNull() ?: current.battery)
                        "sid" -> updated.copy(sessionId = value.toIntOrNull() ?: current.sessionId)
                        else -> updated
                    }
                }
            }

            if (updated != current) {
                updated = updated.copy(
                    timestamp = System.currentTimeMillis(),
                    packetCount = packetCounter.incrementAndGet()
                )
                _sensorData.value = updated
            }

        } catch (e: Exception) {
            addLog("⚠️ Error parsing DATA: ${e.message}")
        }
    }

    private fun addLog(message: String) {
        viewModelScope.launch(Dispatchers.Main) {
            try {
                val timestamp = System.currentTimeMillis()
                val formattedMessage = String.format(
                    Locale.getDefault(),
                    "[%tT.%tL] %s",
                    timestamp,
                    timestamp,
                    message
                )

                val current = _logMessages.value.toMutableList()
                current.add(0, formattedMessage)

                if (current.size > maxLogSize) {
                    current.subList(maxLogSize, current.size).clear()
                }

                _logMessages.value = current
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    // 🔥 FUNGSI BARU UNTUK CLEAR LOG YANG BENAR-BENAR BEKERJA
    fun clearLogs() {
        viewModelScope.launch(Dispatchers.Main) {
            _logMessages.value = emptyList()
            addLog("🧹 All logs cleared")
        }
    }

    // 🔥 FUNGSI YANG LEBIH RESPONSIF
    fun sendStart() = sendCommandWithCheck("START")
    fun sendStop() = sendCommandWithCheck("STOP")
    fun sendPause() = sendCommandWithCheck("PAUSE")
    fun sendResetTrip() = sendCommandWithCheck("RESETTRIP")
    fun sendGetData() = sendCommandWithCheck("GETDATA")
    fun sendGetBattery() = sendCommandWithCheck("GETBATTERY")
    fun sendGetSession() = sendCommandWithCheck("GETSESSION")
    fun sendNewSession() = sendCommandWithCheck("NEWSESSION")
    fun sendNextView() = sendCommandWithCheck("NEXTVIEW")
    fun sendSetWheel(value: Float) = sendCommandWithCheck("SETWHEEL,$value")
    fun sendSetSmooth(value: Float) = sendCommandWithCheck("SETSMOOTH,$value")
    fun sendSetZOffset(value: Float) = sendCommandWithCheck("SETZOFFSET,$value")
    fun sendSyncTime(timeStr: String) = sendCommandWithCheck("SYNCTIME,$timeStr")

    // 🔥 FUNGSI BARU UNTUK RAW COMMAND
    fun sendRawCommand(command: String) = sendCommandWithCheck(command)

    private fun sendCommandWithCheck(command: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                delay(50) // Delay kecil untuk stabilitas

                val isReallyConnected = BluetoothSocketManager.isConnected()

                if (isReallyConnected) {
                    addLog("📤 Sending: $command")
                    BluetoothSocketManager.sendCommand(command)
                } else {
                    val state = BluetoothStateMachine.state.value
                    addLog("⚠️ Not connected (state: $state), cannot send: $command")
                }
            } catch (e: Exception) {
                addLog("❌ Failed to send $command: ${e.message}")
            }
        }
    }
}

object LoggerEngine {

    private var viewModelInstance: LoggerViewModel? = null

    private fun getViewModel(): LoggerViewModel {
        return viewModelInstance ?: LoggerViewModel().also {
            viewModelInstance = it
        }
    }

    val sensorData: StateFlow<SensorData>
        get() = getViewModel().sensorData

    val logMessages: StateFlow<List<String>>
        get() = getViewModel().logMessages

    val rawStreamData: StateFlow<String>
        get() = getViewModel().rawStreamData

    // 🔥 FUNGSI UTAMA
    fun sendStart() = getViewModel().sendStart()
    fun sendStop() = getViewModel().sendStop()
    fun sendPause() = getViewModel().sendPause()
    fun sendResetTrip() = getViewModel().sendResetTrip()
    fun sendGetData() = getViewModel().sendGetData()
    fun sendGetBattery() = getViewModel().sendGetBattery()
    fun sendGetSession() = getViewModel().sendGetSession()
    fun sendNewSession() = getViewModel().sendNewSession()
    fun sendNextView() = getViewModel().sendNextView()
    fun sendSetWheel(value: Float) = getViewModel().sendSetWheel(value)
    fun sendSetSmooth(value: Float) = getViewModel().sendSetSmooth(value)
    fun sendSetZOffset(value: Float) = getViewModel().sendSetZOffset(value)
    fun sendSyncTime(timeStr: String) = getViewModel().sendSyncTime(timeStr)
    fun clearLogs() = getViewModel().clearLogs()
    fun sendRawCommand(command: String) = getViewModel().sendRawCommand(command)
}