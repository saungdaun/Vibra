package zaujaani.vibra

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
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
import zaujaani.vibra.core.bluetooth.BluetoothGateway
import zaujaani.vibra.core.bluetooth.BluetoothStateMachine
import zaujaani.vibra.core.bluetooth.ConnectionState
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
                Log.d("MainActivity", "✅ All permissions granted")
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
        Log.d("MainActivity", "onCreate called")

        zaujaani.vibra.core.exception.AppExceptionHandler.initialize()

        setContentView(R.layout.activity_main)

        BluetoothGateway.init(applicationContext)

        setupToolbar()
        setupNavigation()
        setupDrawerToggle()
        setupBackPressHandler()

        setupBluetoothObserver()

        Log.d("MainActivity", "Activity setup complete")
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
        Log.d("MainActivity", "Activity destroyed")
        activityAlive = false
        serviceStarted = false
        bluetoothObserverJob?.cancel()
    }

    private fun setupToolbar() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        Log.d("MainActivity", "Toolbar set up")
    }

    private fun setupNavigation() {
        drawerLayout = findViewById(R.id.drawerLayout)
        val navView = findViewById<NavigationView>(R.id.nav_view)

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController
        Log.d("MainActivity", "NavController found")

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
                Log.e("MainActivity", "Navigation destination not found: ${menuItem.itemId}")
                drawerLayout.closeDrawer(GravityCompat.START)
                false
            }
        }

        navController.addOnDestinationChangedListener { _, destination, _ ->
            Log.d("MainActivity", "📍 Navigated to: ${destination.label} (ID: ${destination.id})")
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
            Log.d("MainActivity", "🔧 Requesting permissions")
            requestMissingPermissions()
        } else {
            Log.d("MainActivity", "✅ All permissions already granted")
            startBluetoothService()
        }
    }

    private fun startBluetoothService() {
        if (!activityAlive) return

        if (serviceStarted) {
            Log.d("MainActivity", "⚠️ Service already started, skipping")
            return
        }

        if (!hasRequiredPermissions()) {
            Log.e("MainActivity", "❌ Missing Bluetooth permission -> service NOT started")
            Toast.makeText(this, "Bluetooth permissions required", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val serviceIntent = Intent(this, zaujaani.vibra.core.bluetooth.VibraBluetoothService::class.java)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            serviceStarted = true
            Log.d("MainActivity", "🚀 Bluetooth service started successfully")

        } catch (e: SecurityException) {
            Log.e("MainActivity", "🔒 SecurityException starting service", e)
            Toast.makeText(this, "Permission denied: ${e.message}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e("MainActivity", "❌ Error starting Bluetooth service", e)
            Toast.makeText(this, "Failed to start service", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_toolbar, menu)
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
        val missingPermissions = PermissionManager.requiredPermissions()
            .filter { permission ->
                ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
            }
            .toTypedArray()

        if (missingPermissions.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Permissions Required")
                .setMessage("Bluetooth permissions are needed to connect to devices.")
                .setPositiveButton("Grant") { _, _ ->
                    requestPermissionLauncher.launch(missingPermissions)
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
                Log.d("MainActivity", "✅ Bluetooth Connected to $deviceName")
            }
            is ConnectionState.Connecting -> {
                val deviceName = state.deviceName ?: "Unknown Device"
                toolbar.subtitle = "🔄 Connecting to $deviceName..."
                Log.d("MainActivity", "🔄 Bluetooth Connecting to $deviceName")
            }
            is ConnectionState.Disconnected -> {
                toolbar.subtitle = "❌ Bluetooth Disconnected"
                Log.d("MainActivity", "❌ Bluetooth Disconnected")
            }
            is ConnectionState.Error -> {
                toolbar.subtitle = "⚠️ Bluetooth Error"
                Log.e("MainActivity", "⚠️ Bluetooth Error: ${state.message}")
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
        Log.d("MainActivity", "Found ${devices.size} bonded devices")

        if (devices.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("No Paired Devices")
                .setMessage("Please pair with RoadsenseLogger-v3.4 first in Android Bluetooth settings.")
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
                Log.e("MainActivity", "SecurityException accessing device", e)
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
                Log.d("MainActivity", "Selected device: ${deviceList[which].first}")

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
}