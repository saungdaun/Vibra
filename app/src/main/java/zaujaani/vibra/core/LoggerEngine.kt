package zaujaani.vibra.core.logger

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import timber.log.Timber
import zaujaani.vibra.core.bluetooth.BluetoothSocketManager
import zaujaani.vibra.core.bluetooth.BluetoothStateMachine
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@Suppress("unused", "MemberVisibilityCanBePrivate")
class LoggerViewModel : ViewModel() {

    private val _sensorData = MutableStateFlow(SensorData())
    val sensorData: StateFlow<SensorData> = _sensorData.asStateFlow()

    private val _logMessages = MutableStateFlow<List<String>>(emptyList())
    val logMessages: StateFlow<List<String>> = _logMessages.asStateFlow()

    private val _rawStreamData = MutableStateFlow<String>("")
    val rawStreamData: StateFlow<String> = _rawStreamData.asStateFlow()

    private val _streamStats = MutableStateFlow(StreamStats())
    val streamStats: StateFlow<StreamStats> = _streamStats.asStateFlow()

    private val _calibrationData = MutableStateFlow(CalibrationData())
    val calibrationData: StateFlow<CalibrationData> = _calibrationData.asStateFlow()

    private val maxLogSize = 200
    private val packetCounter = AtomicInteger(0)
    private val errorCounter = AtomicInteger(0)
    private var lastPacketTime = System.currentTimeMillis()
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    init {
        startListening()
        startRawStreamListening()
        startStreamMonitoring()
    }

    private fun startListening() {
        viewModelScope.launch(Dispatchers.IO) {
            BluetoothSocketManager.receivedData
                .buffer(5000)
                .collect { data ->
                    processIncomingData(data)
                }
        }
    }

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

                            val currentStats = _streamStats.value
                            _streamStats.value = currentStats.copy(
                                totalPackets = currentStats.totalPackets + 1,
                                lastPacketSize = text.length,
                                lastPacketTime = System.currentTimeMillis()
                            )
                        }
                    } catch (e: Exception) {
                        Timber.tag("LoggerViewModel").e(e, "Error processing raw stream")
                    }
                }
        }
    }

    private fun startStreamMonitoring() {
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                delay(5000)
                val currentTime = System.currentTimeMillis()
                val stats = _streamStats.value
                val timeDiff = currentTime - stats.lastUpdateTime
                if (timeDiff > 0) {
                    val pps = ((stats.totalPackets - stats.lastTotalPackets) * 1000 / timeDiff).toInt()
                    _streamStats.value = stats.copy(
                        packetsPerSecond = pps,
                        lastTotalPackets = stats.totalPackets,
                        lastUpdateTime = currentTime
                    )
                    if (pps > 10) {
                        Timber.tag("LoggerViewModel").d("📊 Stream throughput: $pps packets/sec")
                    }
                }
            }
        }
    }

    private fun processStreamingData(data: String) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                if (data.contains("RS2,")) {
                    processRS2Data(data)
                } else if (data.contains("trip=") || data.contains("spd=") || data.contains("z=")) {
                    processDataResponse(data)
                } else {
                    val current = _sensorData.value
                    _sensorData.value = current.copy(
                        rawData = data,
                        timestamp = System.currentTimeMillis()
                    )
                }
            } catch (e: Exception) {
                Timber.tag("LoggerViewModel").e(e, "Stream processing error")
            }
        }
    }

    private fun processIncomingData(data: String) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                Timber.tag("LoggerViewModel").d("📥 Processing: $data")

                when {
                    data.startsWith("RS2,") -> {
                        processRS2Data(data)
                        addLog("📊 RS2: ${data.take(50)}...")
                    }
                    data.startsWith("ACK:") -> {
                        val ackMsg = data.substringAfter("ACK:")
                        addLog("✅ $ackMsg")
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
                    data.startsWith("SESSION:") -> {
                        processSessionResponse(data)
                        addLog("📋 SESSION updated")
                    }
                    data.startsWith("CAL:") -> {
                        processCalibrationResponse(data)
                        addLog("⚙️ CAL updated")
                    }
                    data.startsWith("DEBUG:") -> {
                        processDebugResponse(data)
                        addLog("🐛 DEBUG info")
                    }
                    data.startsWith("BAT=") -> {
                        processBatteryResponse(data)
                        addLog("🔋 Battery updated")
                    }
                    data.startsWith("WHEEL=") -> {
                        processWheelResponse(data)
                        addLog("⚙️ Wheel updated")
                    }
                    data.startsWith("SMOOTH:") -> {
                        processSmoothResponse(data)
                        addLog("⚙️ Smoothing updated")
                    }
                    data.startsWith("ZOFFSET=") -> {
                        processZOffsetResponse(data)
                        addLog("⚙️ Z Offset updated")
                    }
                    data.contains("trip=") || data.contains("spd=") || data.contains("z=") -> {
                        processDataResponse(data)
                    }
                    else -> {
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

    private fun processRS2Data(data: String) {
        try {
            val cleanData = if (data.contains("*")) data.substring(0, data.indexOf("*")) else data
            val parts = cleanData.split(",")

            var trip: Float? = null
            var speed: Float? = null
            var elevation: Float? = null
            var odo: Float? = null

            for (part in parts) {
                if (part.contains("=")) {
                    val keyValue = part.split("=")
                    when (keyValue[0]) {
                        "TRIP" -> trip = keyValue[1].toFloatOrNull()
                        "ODO" -> odo = keyValue[1].toFloatOrNull()
                        "SPD" -> speed = keyValue[1].toFloatOrNull()
                        "Z" -> elevation = keyValue[1].toFloatOrNull()
                    }
                }
            }

            val current = _sensorData.value
            val updated = current.copy(
                tripDistance = trip ?: current.tripDistance,
                speed = speed ?: current.speed,
                elevation = elevation ?: current.elevation,
                totalOdo = odo ?: current.totalOdo,
                rawData = data,
                timestamp = System.currentTimeMillis(),
                packetCount = packetCounter.incrementAndGet()
            )

            if (updated != current) {
                _sensorData.value = updated
                val now = System.currentTimeMillis()
                if (now - lastPacketTime > 1000) {
                    Timber.tag("LoggerViewModel").d(
                        "RS2 Data: trip=${updated.tripDistance}m, " +
                                "speed=${updated.speed}m/s, elev=${updated.elevation}m/s², " +
                                "odo=${updated.totalOdo}m"
                    )
                    lastPacketTime = now
                }
            }

        } catch (e: Exception) {
            Timber.tag("LoggerViewModel").e(e, "Error parsing RS2 data")
        }
    }

    private fun processDataResponse(data: String) {
        try {
            val cleanData = if (data.startsWith("DATA:")) data.removePrefix("DATA:") else data
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
                                val major = staParts[0].toIntOrNull() ?: current.staMajor
                                val minor = staParts[1].toIntOrNull() ?: current.staMinor
                                val staMeters = (major * 1000) + minor
                                val remainder = updated.tripDistance - staMeters
                                updated.copy(
                                    staMajor = major,
                                    staMinor = minor,
                                    meterRemainder = remainder.toInt()
                                )
                            } else updated
                        }
                        "odo" -> updated.copy(totalOdo = value.toFloatOrNull() ?: current.totalOdo)
                        "spd" -> updated.copy(speed = value.toFloatOrNull() ?: current.speed)
                        "z" -> updated.copy(elevation = value.toFloatOrNull() ?: current.elevation)
                        "pkt" -> updated.copy(packetCount = value.toIntOrNull() ?: current.packetCount)
                        "err" -> updated.copy(errorCount = value.toIntOrNull() ?: current.errorCount)
                        "state" -> {
                            val stateStr = when (value) {
                                "0" -> "READY"
                                "1" -> "RUNNING"
                                "2" -> "STOPPED"
                                "3" -> "PAUSED"
                                else -> value
                            }
                            updated.copy(state = stateStr)
                        }
                        "bat" -> updated.copy(battery = value.toFloatOrNull() ?: current.battery)
                        "sid" -> updated.copy(sessionId = value.toIntOrNull() ?: current.sessionId)
                        "offset" -> updated // Offset dalam meter
                        "wheel" -> updated.copy(wheelCircumference = value.toFloatOrNull() ?: current.wheelCircumference)
                        "dur" -> updated // Duration
                        else -> updated
                    }
                }
            }

            if (updated != current) {
                _sensorData.value = updated.copy(timestamp = System.currentTimeMillis())
            }

        } catch (e: Exception) {
            addLog("⚠️ Error parsing DATA: ${e.message}")
        }
    }

    private fun processSessionResponse(data: String) {
        try {
            val cleanData = data.removePrefix("SESSION:")
            val parts = cleanData.split(",")
            val current = _sensorData.value
            var updated = current

            parts.forEach { part ->
                val keyValue = part.split("=")
                if (keyValue.size == 2) {
                    when (keyValue[0].trim()) {
                        "id" -> updated = updated.copy(sessionId = keyValue[1].toIntOrNull() ?: current.sessionId)
                        "pkt" -> updated = updated.copy(packetCount = keyValue[1].toIntOrNull() ?: current.packetCount)
                        "err" -> updated = updated.copy(errorCount = keyValue[1].toIntOrNull() ?: current.errorCount)
                    }
                }
            }

            if (updated != current) _sensorData.value = updated
        } catch (e: Exception) {
            Timber.tag("LoggerViewModel").e(e, "Error parsing SESSION")
        }
    }

    private fun processCalibrationResponse(data: String) {
        try {
            val cleanData = data.removePrefix("CAL:")
            val parts = cleanData.split(",")
            val current = _sensorData.value
            var updated = current

            parts.forEach { part ->
                val keyValue = part.split("=")
                if (keyValue.size == 2) {
                    when (keyValue[0].trim()) {
                        "WHEEL" -> updated = updated.copy(wheelCircumference = keyValue[1].toFloatOrNull() ?: current.wheelCircumference)
                        "ZOFF" -> updated = updated.copy(zOffset = keyValue[1].toFloatOrNull() ?: current.zOffset)
                    }
                }
            }

            if (updated != current) _sensorData.value = updated
            addLog("⚙️ Calibration updated")
        } catch (e: Exception) {
            Timber.tag("LoggerViewModel").e(e, "Error parsing CAL")
        }
    }

    private fun processDebugResponse(data: String) {
        try {
            val cleanData = data.removePrefix("DEBUG:")
            addLog("🐛 $cleanData")
            Timber.tag("LoggerViewModel").d("Debug: $cleanData")
        } catch (e: Exception) {
            Timber.tag("LoggerViewModel").e(e, "Error parsing DEBUG")
        }
    }

    private fun processBatteryResponse(data: String) {
        try {
            val voltage = data.removePrefix("BAT=").removeSuffix("V").toFloatOrNull()
            voltage?.let {
                val current = _sensorData.value
                _sensorData.value = current.copy(battery = it)
            }
        } catch (e: Exception) {
            Timber.tag("LoggerViewModel").e(e, "Error parsing BAT")
        }
    }

    private fun processWheelResponse(data: String) {
        try {
            val wheel = data.removePrefix("WHEEL=").toFloatOrNull()
            wheel?.let {
                val current = _sensorData.value
                _sensorData.value = current.copy(wheelCircumference = it)
            }
        } catch (e: Exception) {
            Timber.tag("LoggerViewModel").e(e, "Error parsing WHEEL")
        }
    }

    private fun processSmoothResponse(data: String) {
        try {
            val cleanData = data.removePrefix("SMOOTH:")
            val parts = cleanData.split(",")
            val current = _sensorData.value
            var updated = current

            parts.forEach { part ->
                val keyValue = part.split("=")
                if (keyValue.size == 2) {
                    when (keyValue[0].trim()) {
                        "WIN" -> updated = updated.copy(smoothingWindow = keyValue[1].toIntOrNull() ?: current.smoothingWindow)
                        "ALPHA" -> updated = updated.copy(speedAlpha = keyValue[1].toFloatOrNull() ?: current.speedAlpha)
                    }
                }
            }

            if (updated != current) _sensorData.value = updated
        } catch (e: Exception) {
            Timber.tag("LoggerViewModel").e(e, "Error parsing SMOOTH")
        }
    }

    private fun processZOffsetResponse(data: String) {
        try {
            val offset = data.removePrefix("ZOFFSET=").toFloatOrNull()
            offset?.let {
                val current = _sensorData.value
                _sensorData.value = current.copy(zOffset = it)
            }
        } catch (e: Exception) {
            Timber.tag("LoggerViewModel").e(e, "Error parsing ZOFFSET")
        }
    }

    private fun updateStateFromAck(ackMessage: String) {
        viewModelScope.launch(Dispatchers.Main) {
            val current = _sensorData.value
            when {
                ackMessage.startsWith("RUNNING") -> {
                    val sid = ackMessage.substringAfter("SID=").toIntOrNull() ?: current.sessionId
                    _sensorData.value = current.copy(state = "RUNNING", sessionId = sid)
                    addLog("▶️ Logging started - SID: $sid")
                }
                ackMessage.contains("STOPPED") -> {
                    _sensorData.value = current.copy(state = "STOPPED")
                    addLog("⏹️ Logging stopped")
                }
                ackMessage.contains("PAUSED") -> {
                    _sensorData.value = current.copy(state = "PAUSED")
                    addLog("⏸️ Logging paused")
                }
                ackMessage.contains("TRIP_RESET_COMPLETE") -> {
                    _sensorData.value = current.copy(
                        tripDistance = 0.0f,
                        staMajor = 0,
                        staMinor = 0,
                        meterRemainder = 0,
                        state = "READY"
                    )
                    addLog("🔄 Trip reset (offset mechanism)")
                }
                ackMessage.contains("HARD_RESET_COMPLETE") -> {
                    _sensorData.value = current.copy(
                        tripDistance = 0.0f,
                        staMajor = 0,
                        staMinor = 0,
                        meterRemainder = 0,
                        totalOdo = 0.0f,
                        state = "READY"
                    )
                    addLog("🔄 Hard reset (odometer & trip)")
                }
                ackMessage.startsWith("NEW_SESSION") -> {
                    val newSessionId = ackMessage.substringAfter(",").toIntOrNull() ?: (current.sessionId + 1)
                    _sensorData.value = current.copy(
                        sessionId = newSessionId,
                        packetCount = 0,
                        errorCount = 0,
                        state = "NEW SESSION"
                    )
                    addLog("🆕 New session: ID $newSessionId")
                }
                ackMessage.contains("AUTO_PAUSED_NO_DATA") -> {
                    _sensorData.value = current.copy(state = "PAUSED")
                    addLog("⏸️ Auto-paused (no data for 5s)")
                }
                ackMessage.contains("BAT_CRITICAL") -> {
                    _sensorData.value = current.copy(state = "PAUSED")
                    addLog("🔋 Critical battery - Auto-paused")
                }
                ackMessage.startsWith("WHEEL_SET") -> {
                    val wheel = ackMessage.substringAfter(",").toFloatOrNull()
                    wheel?.let {
                        _sensorData.value = current.copy(wheelCircumference = it)
                        addLog("⚙️ Wheel circumference: ${it}m")
                    }
                }
                ackMessage.startsWith("SMOOTH_SET") -> {
                    addLog("⚙️ Smoothing parameters updated")
                }
                ackMessage.startsWith("ALPHA_SET") -> {
                    val alpha = ackMessage.substringAfter(",").toFloatOrNull()
                    alpha?.let {
                        _sensorData.value = current.copy(speedAlpha = it)
                        addLog("⚙️ Alpha: $it")
                    }
                }
                ackMessage.startsWith("ZOFFSET_SET") -> {
                    val offset = ackMessage.substringAfter(",").toFloatOrNull()
                    offset?.let {
                        _sensorData.value = current.copy(zOffset = it)
                        addLog("⚙️ Z Offset: ${it}m/s²")
                    }
                }
                ackMessage.startsWith("TIME_SYNCED") -> {
                    addLog("⏱️ Time synced with device")
                }
                ackMessage.startsWith("VIEW=") -> {
                    val view = ackMessage.substringAfter("=")
                    addLog("👁️ View changed to: $view")
                }
                ackMessage.startsWith("VIEW_SET") -> {
                    addLog("👁️ View mode set")
                }
                ackMessage.contains("BUFFER_CLEARED") -> {
                    addLog("🧹 Buffer cleared")
                }
            }
        }
    }

    private fun addLog(message: String) {
        viewModelScope.launch(Dispatchers.Main) {
            try {
                val timestamp = System.currentTimeMillis()
                val timeString = dateFormat.format(Date(timestamp))
                val formattedMessage = "[$timeString] $message"

                val current = _logMessages.value.toMutableList()
                current.add(0, formattedMessage)

                if (current.size > maxLogSize) {
                    current.subList(maxLogSize, current.size).clear()
                }

                _logMessages.value = current
            } catch (e: Exception) {
                Timber.tag("LoggerViewModel").e(e, "Error adding log")
            }
        }
    }

    fun clearLogs() {
        viewModelScope.launch(Dispatchers.Main) {
            _logMessages.value = emptyList()
            addLog("🧹 All logs cleared")
        }
    }

    // ================= COMMAND FUNCTIONS =================
    fun sendStart() = sendCommandWithCheck("START")
    fun sendStop() = sendCommandWithCheck("STOP")
    fun sendPause() = sendCommandWithCheck("PAUSE")
    fun sendResetTrip() = sendCommandWithCheck("RESETTRIP")
    fun sendHardReset() = sendCommandWithCheck("HARDRESET")
    fun sendGetData() = sendCommandWithCheck("GETDATA")
    fun sendGetBattery() = sendCommandWithCheck("GETBATTERY")
    fun sendGetSession() = sendCommandWithCheck("GETSESSION")
    fun sendNewSession() = sendCommandWithCheck("NEWSESSION")
    fun sendNextView() = sendCommandWithCheck("NEXTVIEW")
    fun sendSetWheel(value: Float) = sendCommandWithCheck("SETWHEEL,$value")
    fun sendSetSmooth(value: Float) = sendCommandWithCheck("SETSMOOTH,$value")
    fun sendSetZOffset(value: Float) = sendCommandWithCheck("SETZOFFSET,$value")
    fun sendSyncTime(timeStr: String) = sendCommandWithCheck("SYNCTIME,$timeStr")
    fun sendGetWheel() = sendCommandWithCheck("GETWHEEL")
    fun sendGetSmooth() = sendCommandWithCheck("GETSMOOTH")
    fun sendGetZOffset() = sendCommandWithCheck("GETZOFFSET")
    fun sendGetCal() = sendCommandWithCheck("GETCAL")
    fun sendGetErrors() = sendCommandWithCheck("GETERRORS")
    fun sendDebugTrip() = sendCommandWithCheck("DEBUGTRIP")
    fun sendClearBuffer() = sendCommandWithCheck("CLEARBUFFER")
    fun sendGetRawData() = sendCommandWithCheck("GETRAWDATA")
    fun sendHelp() = sendCommandWithCheck("HELP")
    fun sendSetView(view: Int) = sendCommandWithCheck("SETVIEW,$view")
    fun sendRawCommand(command: String) = sendCommandWithCheck(command)

    private fun sendCommandWithCheck(command: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                delay(50)

                val isReallyConnected = BluetoothSocketManager.isConnected()

                if (isReallyConnected) {
                    addLog("📤 Sending: $command")
                    Timber.tag("LoggerViewModel").d("Sending command: $command")
                    BluetoothSocketManager.sendCommand(command)
                } else {
                    val state = BluetoothStateMachine.state.value
                    addLog("⚠️ Not connected (state: $state), cannot send: $command")
                    Timber.tag("LoggerViewModel").w("Not connected, cannot send: $command")
                }
            } catch (e: Exception) {
                addLog("❌ Failed to send $command: ${e.message}")
                Timber.tag("LoggerViewModel").e(e, "Failed to send command: $command")
            }
        }
    }
}

data class StreamStats(
    val totalPackets: Long = 0,
    val packetsPerSecond: Int = 0,
    val lastPacketSize: Int = 0,
    val lastPacketTime: Long = 0,
    val lastTotalPackets: Long = 0,
    val lastUpdateTime: Long = System.currentTimeMillis()
)

data class CalibrationData(
    val wheelCircumference: Float = 2.0f,
    val smoothingWindow: Int = 5,
    val speedAlpha: Float = 0.25f,
    val zOffset: Float = 0.0f
)

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

    val streamStats: StateFlow<StreamStats>
        get() = getViewModel().streamStats

    val calibrationData: StateFlow<CalibrationData>
        get() = getViewModel().calibrationData

    // Basic commands
    fun sendStart() = getViewModel().sendStart()
    fun sendStop() = getViewModel().sendStop()
    fun sendPause() = getViewModel().sendPause()
    fun sendResetTrip() = getViewModel().sendResetTrip()
    fun sendHardReset() = getViewModel().sendHardReset()
    fun sendGetData() = getViewModel().sendGetData()
    fun sendGetBattery() = getViewModel().sendGetBattery()
    fun sendGetSession() = getViewModel().sendGetSession()
    fun sendNewSession() = getViewModel().sendNewSession()
    fun sendNextView() = getViewModel().sendNextView()

    // Calibration commands
    fun sendSetWheel(value: Float) = getViewModel().sendSetWheel(value)
    fun sendSetSmooth(value: Float) = getViewModel().sendSetSmooth(value)
    fun sendSetZOffset(value: Float) = getViewModel().sendSetZOffset(value)
    fun sendSyncTime(timeStr: String) = getViewModel().sendSyncTime(timeStr)

    // Query commands
    fun sendGetWheel() = getViewModel().sendGetWheel()
    fun sendGetSmooth() = getViewModel().sendGetSmooth()
    fun sendGetZOffset() = getViewModel().sendGetZOffset()
    fun sendGetCal() = getViewModel().sendGetCal()
    fun sendGetErrors() = getViewModel().sendGetErrors()
    fun sendDebugTrip() = getViewModel().sendDebugTrip()
    fun sendClearBuffer() = getViewModel().sendClearBuffer()
    fun sendGetRawData() = getViewModel().sendGetRawData()
    fun sendHelp() = getViewModel().sendHelp()
    fun sendSetView(view: Int) = getViewModel().sendSetView(view)

    // Utility functions
    fun clearLogs() = getViewModel().clearLogs()
    fun sendRawCommand(command: String) = getViewModel().sendRawCommand(command)
}