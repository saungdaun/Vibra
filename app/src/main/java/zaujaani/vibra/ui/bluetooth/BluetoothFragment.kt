package zaujaani.vibra.ui.bluetooth

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import timber.log.Timber
import zaujaani.vibra.R
import zaujaani.vibra.core.bluetooth.BluetoothSocketManager
import zaujaani.vibra.core.bluetooth.BluetoothStateMachine
import zaujaani.vibra.core.bluetooth.ConnectionState
import zaujaani.vibra.core.logger.LoggerEngine
import zaujaani.vibra.core.permission.PermissionManager
import zaujaani.vibra.databinding.FragmentBluetoothBinding
import android.widget.AdapterView

@SuppressLint("SetTextI18n", "NotifyDataSetChanged")
class BluetoothFragment : Fragment() {

    private var _binding: FragmentBluetoothBinding? = null
    private val binding get() = _binding!!

    private lateinit var permissionHelper: PermissionManager.FragmentHelper
    private lateinit var logAdapter: ArrayAdapter<String>
    private val logList = mutableListOf<String>()
    private var isAutoScroll = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBluetoothBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        Timber.tag("BluetoothFragment").d("Fragment created")
        permissionHelper = PermissionManager.FragmentHelper(this)
        setupUI()
        observeData()
        checkPermissions()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionHelper.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun setupUI() {
        Timber.tag("BluetoothFragment").d("Setting up UI")

        logAdapter = ArrayAdapter(requireContext(), R.layout.item_log, R.id.logText, logList)
        binding.logListView.adapter = logAdapter

        binding.switchAutoScroll.isChecked = isAutoScroll
        binding.switchAutoScroll.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            isAutoScroll = isChecked
            Timber.tag("BluetoothFragment").d("Auto-scroll ${if (isChecked) "enabled" else "disabled"}")
            if (isAutoScroll && logList.isNotEmpty()) {
                binding.logListView.smoothScrollToPosition(0)
            }
        }

        binding.logListView.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            if (position < logList.size) {
                val log = logList[position]
                Timber.tag("BluetoothFragment").d("Log clicked: $log")
                Toast.makeText(requireContext(), "Log: $log", Toast.LENGTH_SHORT).show()
            }
        }

        setupButtonListeners()
        enableControls(false)
    }

    private fun setupButtonListeners() {
        // Basic controls
        binding.btnStart.setOnClickListener {
            if (checkBluetoothPermission()) {
                Timber.tag("BluetoothFragment").i("Start button clicked")
                LoggerEngine.sendStart()
            }
        }

        binding.btnStop.setOnClickListener {
            if (checkBluetoothPermission()) {
                Timber.tag("BluetoothFragment").i("Stop button clicked")
                LoggerEngine.sendStop()
            }
        }

        binding.btnPause.setOnClickListener {
            if (checkBluetoothPermission()) {
                Timber.tag("BluetoothFragment").i("Pause button clicked")
                LoggerEngine.sendPause()
            }
        }

        binding.btnResetTrip.setOnClickListener {
            if (checkBluetoothPermission()) {
                Timber.tag("BluetoothFragment").i("Reset Trip button clicked")
                LoggerEngine.sendResetTrip()
            }
        }

        binding.btnHardReset.setOnClickListener {
            if (checkBluetoothPermission()) {
                Timber.tag("BluetoothFragment").i("Hard Reset button clicked")
                LoggerEngine.sendHardReset()
            }
        }

        // Data query
        binding.btnGetData.setOnClickListener {
            if (checkBluetoothPermission()) {
                Timber.tag("BluetoothFragment").i("Get Data button clicked")
                LoggerEngine.sendGetData()
            }
        }

        binding.btnGetBattery.setOnClickListener {
            if (checkBluetoothPermission()) {
                Timber.tag("BluetoothFragment").i("Get Battery button clicked")
                LoggerEngine.sendGetBattery()
            }
        }

        binding.btnGetSession.setOnClickListener {
            if (checkBluetoothPermission()) {
                Timber.tag("BluetoothFragment").i("Get Session button clicked")
                LoggerEngine.sendGetSession()
            }
        }

        // Session control
        binding.btnNewSession.setOnClickListener {
            if (checkBluetoothPermission()) {
                Timber.tag("BluetoothFragment").i("New Session button clicked")
                LoggerEngine.sendNewSession()
            }
        }

        binding.btnNextView.setOnClickListener {
            if (checkBluetoothPermission()) {
                Timber.tag("BluetoothFragment").i("Next View button clicked")
                LoggerEngine.sendNextView()
            }
        }

        // Calibration controls
        binding.btnSetWheel.setOnClickListener {
            if (checkBluetoothPermission()) {
                val value = binding.etWheelValue.text.toString().toFloatOrNull()
                value?.let {
                    Timber.tag("BluetoothFragment").i("Set Wheel to $value")
                    LoggerEngine.sendSetWheel(it)
                    Toast.makeText(requireContext(), "Wheel set to ${it}m", Toast.LENGTH_SHORT).show()
                } ?: run {
                    Toast.makeText(requireContext(), "Invalid value", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnGetWheel.setOnClickListener {
            if (checkBluetoothPermission()) {
                Timber.tag("BluetoothFragment").i("Get Wheel clicked")
                LoggerEngine.sendGetWheel()
            }
        }

        binding.btnSetSmooth.setOnClickListener {
            if (checkBluetoothPermission()) {
                val value = binding.etSmoothValue.text.toString().toFloatOrNull()
                value?.let {
                    Timber.tag("BluetoothFragment").i("Set Smooth to $value")
                    LoggerEngine.sendSetSmooth(it)
                    Toast.makeText(requireContext(), "Smooth set to $value", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnGetSmooth.setOnClickListener {
            if (checkBluetoothPermission()) {
                Timber.tag("BluetoothFragment").i("Get Smooth clicked")
                LoggerEngine.sendGetSmooth()
            }
        }

        binding.btnSetZOffset.setOnClickListener {
            if (checkBluetoothPermission()) {
                val value = binding.etZOffsetValue.text.toString().toFloatOrNull()
                value?.let {
                    Timber.tag("BluetoothFragment").i("Set Z Offset to $value")
                    LoggerEngine.sendSetZOffset(it)
                    Toast.makeText(requireContext(), "Z Offset set to ${it}m/s²", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnGetZOffset.setOnClickListener {
            if (checkBluetoothPermission()) {
                Timber.tag("BluetoothFragment").i("Get Z Offset clicked")
                LoggerEngine.sendGetZOffset()
            }
        }

        // Utility buttons
        binding.btnClearLogs.setOnClickListener {
            Timber.tag("BluetoothFragment").i("Clear Logs button clicked")
            LoggerEngine.clearLogs()
            Toast.makeText(requireContext(), "Logs cleared", Toast.LENGTH_SHORT).show()
        }

        binding.btnRefresh.setOnClickListener {
            Timber.tag("BluetoothFragment").i("Refresh button clicked")
            refreshUI()
        }

        binding.btnGetCal.setOnClickListener {
            if (checkBluetoothPermission()) {
                Timber.tag("BluetoothFragment").i("Get Calibration clicked")
                LoggerEngine.sendGetCal()
            }
        }

        binding.btnGetErrors.setOnClickListener {
            if (checkBluetoothPermission()) {
                Timber.tag("BluetoothFragment").i("Get Errors clicked")
                LoggerEngine.sendGetErrors()
            }
        }

        binding.btnDebugTrip.setOnClickListener {
            if (checkBluetoothPermission()) {
                Timber.tag("BluetoothFragment").i("Debug Trip clicked")
                LoggerEngine.sendDebugTrip()
            }
        }

        binding.btnHelp.setOnClickListener {
            if (checkBluetoothPermission()) {
                Timber.tag("BluetoothFragment").i("Help clicked")
                LoggerEngine.sendHelp()
            }
        }

        // Raw command
        binding.btnSendCommand.setOnClickListener {
            if (checkBluetoothPermission()) {
                val command = binding.etCommand.text.toString().trim()
                if (command.isNotEmpty()) {
                    Timber.tag("BluetoothFragment").i("Sending raw command: $command")
                    BluetoothSocketManager.sendCommand(command)
                    binding.etCommand.text.clear()
                    Toast.makeText(requireContext(), "Command sent: $command", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun checkPermissions() {
        if (!permissionHelper.checkBluetoothPermissions()) {
            Timber.tag("BluetoothFragment").w("Bluetooth permissions missing")
            permissionHelper.onPermissionResult = { granted ->
                if (granted) {
                    Timber.tag("BluetoothFragment").i("Permissions granted")
                    Toast.makeText(requireContext(), "✅ Permissions granted", Toast.LENGTH_SHORT).show()
                } else {
                    Timber.tag("BluetoothFragment").e("Permissions denied")
                    Toast.makeText(requireContext(),
                        "❌ Bluetooth permissions required", Toast.LENGTH_LONG).show()
                }
            }
            permissionHelper.requestBluetoothPermissions()
        } else {
            Timber.tag("BluetoothFragment").d("Bluetooth permissions OK")
        }
    }

    private fun checkBluetoothPermission(): Boolean {
        if (!permissionHelper.hasBluetoothConnectPermission()) {
            Timber.tag("BluetoothFragment").w("Bluetooth connect permission missing")
            Toast.makeText(requireContext(), "🔒 Bluetooth permission required", Toast.LENGTH_SHORT).show()
            permissionHelper.requestBluetoothPermissions()
            return false
        }
        return true
    }

    private fun observeData() {
        // Observe sensor data
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                LoggerEngine.sensorData.collectLatest { data ->
                    updateSensorUI(data)
                }
            }
        }

        // Observe log messages
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                LoggerEngine.logMessages.collectLatest { logs ->
                    updateLogUI(logs)
                }
            }
        }

        // Observe connection state
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                BluetoothStateMachine.state.collectLatest { state ->
                    updateConnectionUI(state)
                }
            }
        }

        // Observe raw stream data
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                LoggerEngine.rawStreamData.collectLatest { rawData ->
                    if (rawData.isNotEmpty()) {
                        binding.tvRawData.text = "RAW: ${rawData.take(50)}..."
                        Timber.tag("BluetoothFragment").v("Raw data: ${rawData.take(50)}...")
                    }
                }
            }
        }

        // Observe stream stats
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                LoggerEngine.streamStats.collectLatest { stats ->
                    binding.tvStreamStats.text =
                        "📊 Stream: ${stats.packetsPerSecond} pps, Total: ${stats.totalPackets}"
                }
            }
        }

        // Observe calibration data
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                LoggerEngine.calibrationData.collectLatest { cal ->
                    binding.tvCalibration.text =
                        "⚙️ Wheel: ${cal.wheelCircumference}m, Z: ${cal.zOffset}m/s²"
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateSensorUI(data: zaujaani.vibra.core.logger.SensorData) {
        // Update basic data
        binding.tvTripDistance.text = data.formatTripDistance()
        binding.tvSta.text = data.formatSTA()
        binding.tvSpeed.text = data.formatSpeed()
        binding.tvElevation.text = data.formatElevation()
        binding.tvBattery.text = data.formatBattery()
        binding.tvSessionId.text = "SID: ${data.sessionId}"
        binding.tvPacketCount.text = "Packets: ${data.packetCount}"
        binding.tvErrorCount.text = "Errors: ${data.errorCount}"
        binding.tvState.text = "State: ${data.state}"

        // Update meter remainder
        if (data.meterRemainder != 0) {
            binding.tvMeterRemainder.text = "+${data.meterRemainder} m"
        } else {
            binding.tvMeterRemainder.text = ""
        }

        // Update odometer
        binding.tvTotalOdo.text = "ODO: ${data.formatTotalOdo()}"

        // Update calibration info
        binding.tvWheelInfo.text = "Wheel: ${data.formatWheel()}"
        binding.tvZOffsetInfo.text = "Z Offset: ${data.formatZOffset()}"

        // Update last update time
        val timeDiff = System.currentTimeMillis() - data.timestamp
        binding.tvLastUpdate.text = "Last: ${timeDiff}ms ago"

        // Visual feedback for recent updates
        if (timeDiff < 1000) {
            binding.sensorContainer.setBackgroundColor(
                resources.getColor(R.color.sensor_update, null)
            )
            binding.sensorContainer.postDelayed({
                binding.sensorContainer.setBackgroundColor(
                    resources.getColor(android.R.color.transparent, null)
                )
            }, 200)
        }

        // Warning for auto-pause state
        if (data.state == "PAUSED" && timeDiff > 5000) {
            binding.tvState.setTextColor(resources.getColor(R.color.warning, null))
        } else {
            binding.tvState.setTextColor(resources.getColor(android.R.color.black, null))
        }
    }

    private fun updateLogUI(logs: List<String>) {
        val previousSize = logList.size
        logList.clear()
        logList.addAll(logs)

        if (logs.isNotEmpty()) {
            binding.tvLogCount.text = "Logs: ${logs.size}"
            logAdapter.notifyDataSetChanged()

            if (isAutoScroll && logs.size > previousSize) {
                binding.logListView.post {
                    binding.logListView.smoothScrollToPosition(0)
                }
            }
        }
    }

    private fun updateConnectionUI(state: ConnectionState) {
        when (state) {
            is ConnectionState.Connected -> {
                binding.connectionStatus.text = "✅ Connected to ${state.deviceName}"
                binding.connectionStatus.setBackgroundColor(
                    resources.getColor(R.color.connected, null)
                )
                Timber.tag("BluetoothFragment").i("Connected to ${state.deviceName}")
                viewLifecycleOwner.lifecycleScope.launch {
                    delay(1000)
                    enableControls(true)
                    Toast.makeText(requireContext(), "✅ Connected to ${state.deviceName}!", Toast.LENGTH_SHORT).show()
                }
            }
            is ConnectionState.Connecting -> {
                binding.connectionStatus.text = "🔄 Connecting to ${state.deviceName}..."
                binding.connectionStatus.setBackgroundColor(
                    resources.getColor(R.color.connecting, null)
                )
                enableControls(false)
                Timber.tag("BluetoothFragment").i("Connecting to ${state.deviceName}")
            }
            is ConnectionState.Disconnected -> {
                binding.connectionStatus.text = "❌ Disconnected"
                binding.connectionStatus.setBackgroundColor(
                    resources.getColor(R.color.disconnected, null)
                )
                enableControls(false)
                Timber.tag("BluetoothFragment").i("Disconnected")
            }
            is ConnectionState.Error -> {
                binding.connectionStatus.text = "⚠️ Error: ${state.message}"
                binding.connectionStatus.setBackgroundColor(
                    resources.getColor(R.color.error, null)
                )
                enableControls(false)
                Timber.tag("BluetoothFragment").e("Error: ${state.message}")
                Toast.makeText(requireContext(), "Error: ${state.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun enableControls(enabled: Boolean) {
        // Basic controls
        binding.btnStart.isEnabled = enabled
        binding.btnStop.isEnabled = enabled
        binding.btnPause.isEnabled = enabled
        binding.btnResetTrip.isEnabled = enabled
        binding.btnHardReset.isEnabled = enabled

        // Data query controls
        binding.btnGetData.isEnabled = enabled
        binding.btnGetBattery.isEnabled = enabled
        binding.btnGetSession.isEnabled = enabled
        binding.btnGetWheel.isEnabled = enabled
        binding.btnGetSmooth.isEnabled = enabled
        binding.btnGetZOffset.isEnabled = enabled
        binding.btnGetCal.isEnabled = enabled
        binding.btnGetErrors.isEnabled = enabled
        binding.btnDebugTrip.isEnabled = enabled

        // Session controls
        binding.btnNewSession.isEnabled = enabled
        binding.btnNextView.isEnabled = enabled

        // Calibration controls
        binding.btnSetWheel.isEnabled = enabled
        binding.btnSetSmooth.isEnabled = enabled
        binding.btnSetZOffset.isEnabled = enabled

        // Utility controls
        binding.btnSendCommand.isEnabled = enabled
        binding.btnHelp.isEnabled = enabled

        // Always enabled
        binding.btnClearLogs.isEnabled = true
        binding.btnRefresh.isEnabled = true

        Timber.tag("BluetoothFragment").d("Controls ${if (enabled) "enabled" else "disabled"}")
    }

    private fun refreshUI() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            Timber.tag("BluetoothFragment").i("Refreshing UI")

            binding.scrollView.setBackgroundColor(resources.getColor(R.color.refresh, null))
            binding.scrollView.postDelayed({
                binding.scrollView.setBackgroundColor(resources.getColor(android.R.color.transparent, null))
            }, 300)

            // Request all data
            LoggerEngine.sendGetData()
            LoggerEngine.sendGetBattery()
            LoggerEngine.sendGetSession()
            LoggerEngine.sendGetCal()

            Toast.makeText(requireContext(), "🔄 Refreshing data...", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Timber.tag("BluetoothFragment").d("Fragment destroyed")
        _binding = null
    }
}