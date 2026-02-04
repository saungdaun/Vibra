package zaujaani.vibra.ui.bluetooth

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import zaujaani.vibra.R
import zaujaani.vibra.core.bluetooth.BluetoothSocketManager
import zaujaani.vibra.core.bluetooth.BluetoothStateMachine
import zaujaani.vibra.core.bluetooth.ConnectionState
import zaujaani.vibra.core.logger.LoggerEngine
import zaujaani.vibra.core.permission.PermissionHelper
import zaujaani.vibra.databinding.FragmentBluetoothBinding

class BluetoothFragment : Fragment() {

    private var _binding: FragmentBluetoothBinding? = null
    private val binding get() = _binding!!

    // ✅ PAKAI PermissionHelper
    private lateinit var permissionHelper: PermissionHelper

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

        // ✅ INIT PermissionHelper
        permissionHelper = PermissionHelper(this)

        setupUI()
        observeData()
        checkPermissions()
    }

    private fun setupUI() {
        // Setup log list
        val logAdapter = ArrayAdapter<String>(requireContext(), android.R.layout.simple_list_item_1)
        binding.logListView.adapter = logAdapter

        // Setup commands buttons
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
        }

        // Manual command send
        binding.btnSendCommand.setOnClickListener {
            if (checkBluetoothPermission()) {
                val command = binding.etCommand.text.toString().trim()
                if (command.isNotEmpty()) {
                    BluetoothSocketManager.sendCommand(command)
                    binding.etCommand.text.clear()
                }
            }
        }

        // Wheel calibration
        binding.btnSetWheel.setOnClickListener {
            if (checkBluetoothPermission()) {
                val value = binding.etWheelValue.text.toString().toFloatOrNull()
                value?.let { LoggerEngine.sendSetWheel(it) }
            }
        }

        // Smoothing calibration
        binding.btnSetSmooth.setOnClickListener {
            if (checkBluetoothPermission()) {
                val value = binding.etSmoothValue.text.toString().toFloatOrNull()
                value?.let { LoggerEngine.sendSetSmooth(it) }
            }
        }

        // Z offset calibration
        binding.btnSetZOffset.setOnClickListener {
            if (checkBluetoothPermission()) {
                val value = binding.etZOffsetValue.text.toString().toFloatOrNull()
                value?.let { LoggerEngine.sendSetZOffset(it) }
            }
        }
    }

    private fun checkPermissions() {
        if (!permissionHelper.checkBluetoothPermissions()) {
            permissionHelper.onPermissionResult = { granted ->
                if (granted) {
                    Toast.makeText(requireContext(), "Permissions granted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Permissions required for Bluetooth", Toast.LENGTH_LONG).show()
                }
            }
            permissionHelper.requestBluetoothPermissions()
        }
    }

    private fun checkBluetoothPermission(): Boolean {
        if (!permissionHelper.hasBluetoothConnectPermission()) {
            Toast.makeText(requireContext(), "Bluetooth permission required", Toast.LENGTH_SHORT).show()
            permissionHelper.requestBluetoothPermissions()
            return false
        }
        return true
    }

    private fun observeData() {
        // Observe sensor data
        lifecycleScope.launch {
            LoggerEngine.sensorData.collect { data ->
                updateSensorUI(data)
            }
        }

        // Observe log messages
        lifecycleScope.launch {
            LoggerEngine.logMessages.collect { logs ->
                updateLogUI(logs)
            }
        }

        // Observe Bluetooth state
        lifecycleScope.launch {
            BluetoothStateMachine.state.collect { state ->
                updateConnectionUI(state)
            }
        }
    }

    private fun updateSensorUI(data: zaujaani.vibra.core.logger.SensorData) {
        binding.tvTripDistance.text = String.format("%.3f km", data.tripDistance / 1000)
        binding.tvSta.text = String.format("STA %02d+%03d", data.staMajor, data.staMinor)
        binding.tvSpeed.text = String.format("%.1f km/h", data.speed)
        binding.tvElevation.text = String.format("%.2f m", data.elevation)
        binding.tvBattery.text = String.format("%.2f V", data.battery)
        binding.tvSessionId.text = "SID: ${data.sessionId}"
        binding.tvPacketCount.text = "Packets: ${data.packetCount}"
        binding.tvState.text = "State: ${data.state}"
    }

    private fun updateLogUI(logs: List<String>) {
        val adapter = binding.logListView.adapter as ArrayAdapter<String>
        adapter.clear()
        adapter.addAll(logs)
    }

    private fun updateConnectionUI(state: ConnectionState) {
        when (state) {
            is ConnectionState.Connected -> {
                binding.connectionStatus.text = "✅ Connected"
                binding.connectionStatus.setBackgroundColor(
                    resources.getColor(android.R.color.holo_green_light, null)
                )
                enableControls(true)
            }
            is ConnectionState.Connecting -> {
                binding.connectionStatus.text = "🔄 Connecting..."
                binding.connectionStatus.setBackgroundColor(
                    resources.getColor(android.R.color.holo_orange_light, null)
                )
                enableControls(false)
            }
            is ConnectionState.Disconnected -> {
                binding.connectionStatus.text = "❌ Disconnected"
                binding.connectionStatus.setBackgroundColor(
                    resources.getColor(android.R.color.holo_red_light, null)
                )
                enableControls(false)
            }
            is ConnectionState.Error -> {
                binding.connectionStatus.text = "⚠️ Error: ${state.message}"
                binding.connectionStatus.setBackgroundColor(
                    resources.getColor(android.R.color.holo_red_light, null)
                )
                enableControls(false)
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
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}