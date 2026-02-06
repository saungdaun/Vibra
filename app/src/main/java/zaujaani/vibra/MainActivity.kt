package zaujaani.vibra

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.NavigationUI
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import zaujaani.vibra.core.bluetooth.BluetoothGateway
import zaujaani.vibra.core.bluetooth.BluetoothStateMachine
import zaujaani.vibra.core.bluetooth.ConnectionState
import zaujaani.vibra.core.bluetooth.VibraBluetoothService
import zaujaani.vibra.core.logger.LoggerEngine
import zaujaani.vibra.core.permission.PermissionManager
import android.annotation.SuppressLint

class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navController: androidx.navigation.NavController
    private lateinit var toggle: ActionBarDrawerToggle

    private var serviceStarted = false
    private var activityAlive = true
    private var bluetoothObserverJob: kotlinx.coroutines.Job? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (activityAlive) {
            val allGranted = permissions.values.all { it }
            if (allGranted) {
                Timber.tag("MainActivity").d("✅ All permissions granted")
                startBluetoothService()
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Permissions Required")
                    .setMessage("Bluetooth permissions are required to connect to ESP32.")
                    .setPositiveButton("Grant Again") { _, _ ->
                        requestMissingPermissions()
                    }
                    .setNegativeButton("Exit") { _, _ ->
                        finish()
                    }
                    .show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.tag("MainActivity").d("onCreate called")

        setContentView(R.layout.activity_main)

        // Inisialisasi BluetoothGateway
        BluetoothGateway.init(applicationContext)

        setupToolbar()
        setupNavigation()
        setupDrawerToggle()
        setupBackPressHandler()

        setupBluetoothObserver()

        Timber.tag("MainActivity").d("Activity setup complete")
    }

    override fun onStart() {
        super.onStart()
        activityAlive = true
        checkAndRequestPermissions()
    }

    override fun onStop() {
        super.onStop()
        activityAlive = false
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.tag("MainActivity").d("Activity destroyed")
        activityAlive = false
        serviceStarted = false
        bluetoothObserverJob?.cancel()
    }

    private fun setupToolbar() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        Timber.tag("MainActivity").d("Toolbar set up")
    }

    private fun setupNavigation() {
        drawerLayout = findViewById(R.id.drawerLayout)
        val navView = findViewById<NavigationView>(R.id.nav_view)

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController
        Timber.tag("MainActivity").d("NavController found")

        appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.bluetoothFragment,
                R.id.calibrationFragment,
                R.id.surveyFragment,
                R.id.dataFragment,
                R.id.aboutFragment,
                R.id.helpFragment
            ),
            drawerLayout
        )

        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)

        navView.setNavigationItemSelectedListener { menuItem ->
            try {
                NavigationUI.onNavDestinationSelected(menuItem, navController)
                drawerLayout.closeDrawer(GravityCompat.START)
                true
            } catch (e: IllegalArgumentException) {
                Timber.tag("MainActivity").e(e, "Navigation destination not found: ${menuItem.itemId}")
                drawerLayout.closeDrawer(GravityCompat.START)
                false
            }
        }

        navController.addOnDestinationChangedListener { _, destination, _ ->
            Timber.tag("MainActivity").d("📍 Navigated to: ${destination.label} (ID: ${destination.id})")
        }
    }

    private fun setupDrawerToggle() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)

        toggle = ActionBarDrawerToggle(
            this,
            drawerLayout,
            toolbar,
            R.string.navigation_drawer_open,
            R.string.navigation_drawer_close
        )

        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
    }

    private fun setupBackPressHandler() {
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                } else if (!navController.navigateUp()) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, callback)
    }

    private fun checkAndRequestPermissions() {
        if (!PermissionManager.hasAll(this)) {
            Timber.tag("MainActivity").d("🔧 Requesting permissions")
            requestMissingPermissions()
        } else {
            Timber.tag("MainActivity").d("✅ All permissions already granted")
            startBluetoothService()
        }
    }

    private fun startBluetoothService() {
        if (!activityAlive) return

        if (serviceStarted) {
            Timber.tag("MainActivity").d("⚠️ Service already started, skipping")
            return
        }

        if (!hasRequiredPermissions()) {
            Timber.tag("MainActivity").e("❌ Missing Bluetooth permission -> service NOT started")
            Toast.makeText(this, "Bluetooth permissions required", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val serviceIntent = Intent(this, VibraBluetoothService::class.java)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            serviceStarted = true
            Timber.tag("MainActivity").d("🚀 Bluetooth service started successfully")

        } catch (e: SecurityException) {
            Timber.tag("MainActivity").e(e, "🔒 SecurityException starting service")
            Toast.makeText(this, "Permission denied: ${e.message}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Timber.tag("MainActivity").e(e, "❌ Error starting Bluetooth service")
            Toast.makeText(this, "Failed to start service", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_toolbar, menu)

        // Add v3.7 specific items
        menu.add(0, 1001, 0, "v3.7 Commands").apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }

        menu.add(0, 1002, 0, "Debug Trip").apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }

        menu.add(0, 1003, 0, "Get Calibration").apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (toggle.onOptionsItemSelected(item)) {
            return true
        }

        return when (item.itemId) {
            R.id.action_bluetooth_connect -> {
                if (hasRequiredPermissions()) {
                    showBluetoothDeviceDialog()
                } else {
                    requestMissingPermissions()
                }
                true
            }
            R.id.action_bluetooth_disconnect -> {
                BluetoothGateway.disconnect()
                true
            }
            1001 -> { // v3.7 Commands
                showV37CommandsDialog()
                true
            }
            1002 -> { // Debug Trip
                LoggerEngine.sendDebugTrip()
                true
            }
            1003 -> { // Get Calibration
                LoggerEngine.sendGetCal()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp() || super.onSupportNavigateUp()
    }

    private fun hasRequiredPermissions(): Boolean {
        return PermissionManager.hasAll(this)
    }

    private fun requestMissingPermissions() {
        val missingPermissions = PermissionManager.getMissingPermissions(this)

        if (missingPermissions.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Permissions Required")
                .setMessage("Bluetooth permissions are needed to connect to devices.")
                .setPositiveButton("Grant") { _, _ ->
                    requestPermissionLauncher.launch(missingPermissions.toTypedArray())
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun setupBluetoothObserver() {
        bluetoothObserverJob = CoroutineScope(Dispatchers.Main).launch {
            BluetoothStateMachine.state.collect { state ->
                if (activityAlive) {
                    updateBluetoothUI(state)
                }
            }
        }
    }

    private fun updateBluetoothUI(state: ConnectionState) {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)

        when (state) {
            is ConnectionState.Connected -> {
                val deviceName = state.deviceName ?: "Unknown Device"
                toolbar.subtitle = "✅ Connected to $deviceName"
                Timber.tag("MainActivity").i("✅ Bluetooth Connected to $deviceName")
            }
            is ConnectionState.Connecting -> {
                val deviceName = state.deviceName ?: "Unknown Device"
                toolbar.subtitle = "🔄 Connecting to $deviceName..."
                Timber.tag("MainActivity").i("🔄 Bluetooth Connecting to $deviceName")
            }
            is ConnectionState.Disconnected -> {
                toolbar.subtitle = "❌ Bluetooth Disconnected"
                Timber.tag("MainActivity").i("❌ Bluetooth Disconnected")
            }
            is ConnectionState.Error -> {
                toolbar.subtitle = "⚠️ Bluetooth Error"
                Timber.tag("MainActivity").e("⚠️ Bluetooth Error: ${state.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun showBluetoothDeviceDialog() {
        if (!hasRequiredPermissions()) {
            requestMissingPermissions()
            return
        }

        val devices = BluetoothGateway.bondedDevices()
        Timber.tag("MainActivity").d("Found ${devices.size} bonded devices")

        if (devices.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("No Paired Devices")
                .setMessage("Please pair with RoadsenseLogger-v3.7 first in Android Bluetooth settings.")
                .setPositiveButton("Open Bluetooth Settings") { _, _ ->
                    val intent = Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
                    startActivity(intent)
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        val deviceList = mutableListOf<Pair<String, BluetoothDevice>>()
        for (device in devices) {
            try {
                // Check permission before accessing device name
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        continue
                    }
                }

                val name = try {
                    device.name ?: device.address
                } catch (e: SecurityException) {
                    device.address
                }

                deviceList.add(Pair(name, device))
            } catch (e: SecurityException) {
                Timber.tag("MainActivity").e(e, "SecurityException accessing device")
            }
        }

        if (deviceList.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("No Accessible Devices")
                .setMessage("Cannot access device names due to permission restrictions.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val deviceNames = deviceList.map { it.first }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select Bluetooth Device")
            .setItems(deviceNames) { _, which ->
                val selectedDevice = deviceList[which].second
                Timber.tag("MainActivity").d("Selected device: ${deviceList[which].first}")

                if (hasRequiredPermissions()) {
                    BluetoothGateway.connect(selectedDevice)
                } else {
                    Toast.makeText(this@MainActivity, "Bluetooth permission required", Toast.LENGTH_SHORT).show()
                    requestMissingPermissions()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Fungsi baru untuk menampilkan dialog command v3.7
    private fun showV37CommandsDialog() {
        val commands = arrayOf(
            "START",
            "STOP",
            "PAUSE",
            "RESETTRIP",
            "HARDRESET",
            "GETDATA",
            "GETBATTERY",
            "GETSESSION",
            "NEWSESSION",
            "NEXTVIEW",
            "GETWHEEL",
            "SETWHEEL,2.0",
            "GETSMOOTH",
            "SETSMOOTH,0.25",
            "GETZOFFSET",
            "SETZOFFSET,0.0",
            "SYNCTIME,2024-01-01T12:00:00",
            "GETCAL",
            "GETERRORS",
            "DEBUGTRIP",
            "CLEARBUFFER",
            "HELP"
        )

        AlertDialog.Builder(this)
            .setTitle("v3.7 Commands")
            .setItems(commands) { _, which ->
                if (hasRequiredPermissions()) {
                    zaujaani.vibra.core.bluetooth.BluetoothSocketManager.sendCommand(commands[which])
                    Toast.makeText(this, "Sent: ${commands[which]}", Toast.LENGTH_SHORT).show()
                } else {
                    requestMissingPermissions()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}