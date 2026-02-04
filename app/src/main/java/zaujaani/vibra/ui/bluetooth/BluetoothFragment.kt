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
import zaujaani.vibra.R
import zaujaani.vibra.core.bluetooth.BluetoothSocketManager
import zaujaani.vibra.core.bluetooth.BluetoothStateMachine
import zaujaani.vibra.core.bluetooth.ConnectionState
import zaujaani.vibra.core.logger.LoggerEngine
import zaujaani.vibra.core.permission.PermissionHelper
import zaujaani.vibra.databinding.FragmentBluetoothBinding
import android.widget.AdapterView

@SuppressLint("SetTextI18n", "NotifyDataSetChanged")
class BluetoothFragment : Fragment() {

    private var _binding: FragmentBluetoothBinding? = null
    private val binding get() = _binding!!

    private lateinit var permissionHelper: PermissionHelper
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

        permissionHelper = PermissionHelper(this)
        setupUI()
        observeData()
        checkPermissions()
    }

    private fun setupUI() {
        // Setup log adapter dengan custom layout
        logAdapter = ArrayAdapter(requireContext(), R.layout.item_log, R.id.logText, logList)
        binding.logListView.adapter = logAdapter

        // Setup auto scroll toggle
        binding.switchAutoScroll.isChecked = isAutoScroll
        binding.switchAutoScroll.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            isAutoScroll = isChecked
            if (isAutoScroll && logList.isNotEmpty()) {
                binding.logListView.smoothScrollToPosition(0)
            }
        }

        // Setup log item click listener
        binding.logListView.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            if (position < logList.size) {
                val log = logList[position]
                Toast.makeText(requireContext(), "Log: $log", Toast.LENGTH_SHORT).show()
            }
        }

        // Setup command buttons
        binding.btnStart.setOnClickListener {
            if (checkBluetoothPermission()) {
                LoggerEngine.sendStart()
            }
        }

        binding.btnStop.setOnClickListener {
            if (checkBluetoothPermission()) {
                LoggerEngine.sendStop()
            }
        }

        binding.btnPause.setOnClickListener {
            if (checkBluetoothPermission()) {
                LoggerEngine.sendPause()
            }
        }

        binding.btnResetTrip.setOnClickListener {
            if (checkBluetoothPermission()) {
                LoggerEngine.sendResetTrip()
            }
        }

        binding.btnGetData.setOnClickListener {
            if (checkBluetoothPermission()) {
                LoggerEngine.sendGetData()
            }
        }

        binding.btnGetBattery.setOnClickListener {
            if (checkBluetoothPermission()) {
                LoggerEngine.sendGetBattery()
            }
        }

        binding.btnNewSession.setOnClickListener {
            if (checkBluetoothPermission()) {
                LoggerEngine.sendNewSession()
            }
        }

        binding.btnNextView.setOnClickListener {
            if (checkBluetoothPermission()) {
                LoggerEngine.sendNextView()
            }
        }

        binding.btnClearLogs.setOnClickListener {
            LoggerEngine.clearLogs()
            Toast.makeText(requireContext(), "Logs cleared", Toast.LENGTH_SHORT).show()
        }

        // Setup calibration buttons
        binding.btnSetWheel.setOnClickListener {
            if (checkBluetoothPermission()) {
                val value = binding.etWheelValue.text.toString().toFloatOrNull()
                value?.let {
                    LoggerEngine.sendSetWheel(it)
                    Toast.makeText(requireContext(), "Wheel set to $value", Toast.LENGTH_SHORT).show()
                } ?: run {
                    Toast.makeText(requireContext(), "Invalid value", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnSetSmooth.setOnClickListener {
            if (checkBluetoothPermission()) {
                val value = binding.etSmoothValue.text.toString().toFloatOrNull()
                value?.let {
                    LoggerEngine.sendSetSmooth(it)
                    Toast.makeText(requireContext(), "Smooth set to $value", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnSetZOffset.setOnClickListener {
            if (checkBluetoothPermission()) {
                val value = binding.etZOffsetValue.text.toString().toFloatOrNull()
                value?.let {
                    LoggerEngine.sendSetZOffset(it)
                    Toast.makeText(requireContext(), "Z Offset set to $value", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Setup manual command
        binding.btnSendCommand.setOnClickListener {
            if (checkBluetoothPermission()) {
                val command = binding.etCommand.text.toString().trim()
                if (command.isNotEmpty()) {
                    BluetoothSocketManager.sendCommand(command)
                    binding.etCommand.text.clear()
                    Toast.makeText(requireContext(), "Command sent: $command", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Setup refresh button
        binding.btnRefresh.setOnClickListener {
            refreshUI()
        }

        enableControls(false)
    }

    private fun checkPermissions() {
        if (!permissionHelper.checkBluetoothPermissions()) {
            permissionHelper.onPermissionResult = { granted ->
                if (granted) {
                    Toast.makeText(requireContext(), "✅ Permissions granted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(),
                        "❌ Bluetooth permissions required", Toast.LENGTH_LONG).show()
                }
            }
            permissionHelper.requestBluetoothPermissions()
        }
    }

    private fun checkBluetoothPermission(): Boolean {
        if (!permissionHelper.hasBluetoothConnectPermission()) {
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
                    }
                }
            }
        }
    }

    private fun updateSensorUI(data: zaujaani.vibra.core.logger.SensorData) {
        binding.tvTripDistance.text = data.formatTripDistance()
        binding.tvSta.text = data.formatSTA()
        binding.tvSpeed.text = data.formatSpeed()
        binding.tvElevation.text = data.formatElevation()
        binding.tvBattery.text = data.formatBattery()
        binding.tvSessionId.text = "SID: ${data.sessionId}"
        binding.tvPacketCount.text = "Packets: ${data.packetCount}"
        binding.tvState.text = "State: ${data.state}"

        // Update timestamp
        val timeDiff = System.currentTimeMillis() - data.timestamp
        binding.tvLastUpdate.text = "Last: ${timeDiff}ms ago"

        // Visual feedback jika data baru
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
    }

    private fun updateLogUI(logs: List<String>) {
        val previousSize = logList.size
        logList.clear()
        logList.addAll(logs)

        if (logs.isNotEmpty()) {
            binding.tvLogCount.text = "Logs: ${logs.size}"

            // Update adapter
            logAdapter.notifyDataSetChanged()

            // Auto scroll jika enabled
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
                viewLifecycleOwner.lifecycleScope.launch {
                    delay(1000)
                    enableControls(true)
                    Toast.makeText(requireContext(), "✅ Connected!", Toast.LENGTH_SHORT).show()
                }
            }
            is ConnectionState.Connecting -> {
                binding.connectionStatus.text = "🔄 Connecting to ${state.deviceName}..."
                binding.connectionStatus.setBackgroundColor(
                    resources.getColor(R.color.connecting, null)
                )
                enableControls(false)
            }
            is ConnectionState.Disconnected -> {
                binding.connectionStatus.text = "❌ Disconnected"
                binding.connectionStatus.setBackgroundColor(
                    resources.getColor(R.color.disconnected, null)
                )
                enableControls(false)
            }
            is ConnectionState.Error -> {
                binding.connectionStatus.text = "⚠️ Error: ${state.message}"
                binding.connectionStatus.setBackgroundColor(
                    resources.getColor(R.color.error, null)
                )
                enableControls(false)
                Toast.makeText(requireContext(), "Error: ${state.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun enableControls(enabled: Boolean) {
        binding.btnStart.isEnabled = enabled
        binding.btnStop.isEnabled = enabled
        binding.btnPause.isEnabled = enabled
        binding.btnResetTrip.isEnabled = enabled
        binding.btnGetData.isEnabled = enabled
        binding.btnGetBattery.isEnabled = enabled
        binding.btnNewSession.isEnabled = enabled
        binding.btnNextView.isEnabled = enabled
        binding.btnSendCommand.isEnabled = enabled
        binding.btnSetWheel.isEnabled = enabled
        binding.btnSetSmooth.isEnabled = enabled
        binding.btnSetZOffset.isEnabled = enabled
        binding.btnClearLogs.isEnabled = true // Always enabled
        binding.btnRefresh.isEnabled = true // Always enabled
    }

    private fun refreshUI() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            // Flash refresh
            binding.scrollView.setBackgroundColor(resources.getColor(R.color.refresh, null))
            binding.scrollView.postDelayed({
                binding.scrollView.setBackgroundColor(resources.getColor(android.R.color.transparent, null))
            }, 300)

            // Refresh data
            LoggerEngine.sendGetData()
            LoggerEngine.sendGetBattery()
            Toast.makeText(requireContext(), "🔄 Refreshing...", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}