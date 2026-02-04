package zaujaani.vibra

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.core.app.ActivityCompat
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

class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navController: androidx.navigation.NavController
    private lateinit var toggle: ActionBarDrawerToggle

    // 🔥 Flag untuk mencegah multiple service starts
    private var serviceStarted = false

    // 🔥 Activity Result Launcher untuk permissions
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Log.d("MainActivity", "✅ All permissions granted -> starting Bluetooth service")
            startBluetoothService()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Permissions Required")
                .setMessage("Bluetooth permissions are required to connect to ESP32.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("MainActivity", "onCreate called")

        setContentView(R.layout.activity_main)

        // ✅ Initialize BluetoothGateway FIRST dengan application context
        BluetoothGateway.init(applicationContext)

        // Setup UI components
        setupToolbar()
        setupNavigation()
        setupDrawerToggle()
        setupBackPressHandler() // 🔥 NEW: Setup modern back press handler

        // Observe Bluetooth state
        observeBluetoothState()

        Log.d("MainActivity", "Activity setup complete")
    }

    private fun setupToolbar() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        Log.d("MainActivity", "Toolbar set up: ${toolbar != null}")
    }

    private fun setupNavigation() {
        drawerLayout = findViewById(R.id.drawerLayout)
        val navView = findViewById<NavigationView>(R.id.nav_view)

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController
        Log.d("MainActivity", "NavController found: ${navController != null}")

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

        // ✅ Fix: Gunakan proper navigation dengan NavController
        navView.setNavigationItemSelectedListener { menuItem ->
            try {
                // Coba navigate ke destination
                NavigationUI.onNavDestinationSelected(menuItem, navController)

                // Tutup drawer setelah selection
                drawerLayout.closeDrawer(GravityCompat.START)
                true
            } catch (e: IllegalArgumentException) {
                // Jika destination tidak ditemukan
                Log.e("MainActivity", "Navigation destination not found: ${menuItem.itemId}")
                drawerLayout.closeDrawer(GravityCompat.START)
                false
            }
        }

        // Debug listener untuk melihat navigasi
        navController.addOnDestinationChangedListener { _, destination, _ ->
            Log.d("MainActivity", "📍 Navigated to: ${destination.label} (ID: ${destination.id})")
        }

        Log.d("MainActivity", "Navigation setup complete")
    }

    private fun setupDrawerToggle() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)

        // ✅ CRITICAL FIX: Setup ActionBarDrawerToggle dengan benar
        toggle = ActionBarDrawerToggle(
            this,
            drawerLayout,
            toolbar,
            R.string.navigation_drawer_open,
            R.string.navigation_drawer_close
        )

        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        // Tambahkan listener untuk debug
        drawerLayout.addDrawerListener(object : DrawerLayout.DrawerListener {
            override fun onDrawerSlide(drawerView: android.view.View, slideOffset: Float) {
                Log.d("MainActivity", "Drawer sliding: offset=$slideOffset")
            }

            override fun onDrawerOpened(drawerView: android.view.View) {
                Log.d("MainActivity", "✅ Drawer OPENED")
            }

            override fun onDrawerClosed(drawerView: android.view.View) {
                Log.d("MainActivity", "✅ Drawer CLOSED")
            }

            override fun onDrawerStateChanged(newState: Int) {
                Log.d("MainActivity", "Drawer state changed: $newState")
            }
        })

        Log.d("MainActivity", "Drawer toggle setup complete")
    }

    private fun setupBackPressHandler() {
        // 🔥 MODERN: Gunakan OnBackPressedDispatcher untuk handle back gesture
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Jika drawer terbuka, tutup drawer
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                    Log.d("MainActivity", "Back pressed: Closing drawer")
                }
                // Jika tidak di start destination, navigate up (back fragment)
                else if (!navController.navigateUp()) {
                    // Jika tidak bisa navigate up (sudah di start destination),
                    // disable callback dan biarkan system handle (close app)
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                    Log.d("MainActivity", "Back pressed: At start destination")
                } else {
                    Log.d("MainActivity", "Back pressed: Navigating up in fragment")
                }
            }
        }

        // Register callback
        onBackPressedDispatcher.addCallback(this, callback)
        Log.d("MainActivity", "Back press handler setup complete")
    }

    override fun onStart() {
        super.onStart()
        // 🔥 Check permissions saat activity dimulai
        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val requiredPermissions = PermissionManager.requiredPermissions()

        val missingPermissions = requiredPermissions
            .filter { permission ->
                ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
            }
            .toTypedArray()

        if (missingPermissions.isNotEmpty()) {
            Log.d("MainActivity", "🔧 Requesting permissions: ${missingPermissions.joinToString()}")
            requestPermissionLauncher.launch(missingPermissions)
        } else {
            Log.d("MainActivity", "✅ All permissions already granted")
            startBluetoothService()
        }
    }

    // 🔥 FIX INDUSTRY STANDARD: Start service HANYA setelah permission granted
    private fun startBluetoothService() {
        if (serviceStarted) {
            Log.d("MainActivity", "⚠️ Service already started, skipping")
            return
        }

        // 🔥 CHECK PERMISSION FIRST
        if (!hasRequiredPermissions()) {
            Log.e("MainActivity", "❌ Missing Bluetooth permission -> service NOT started")
            Toast.makeText(this, "Bluetooth permissions required", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val serviceIntent = Intent(this, zaujaani.vibra.core.bluetooth.VibraBluetoothService::class.java)

            // 🔥 GUNAKAN ContextCompat.startForegroundService (LEBIH AMAN)
            ContextCompat.startForegroundService(this, serviceIntent)

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
        // ✅ Handle hamburger icon click
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
        // Handle up navigation (back button in action bar)
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

    private fun observeBluetoothState() {
        CoroutineScope(Dispatchers.Main).launch {
            BluetoothStateMachine.state.collect { state ->
                updateBluetoothUI(state)
            }
        }
    }

    private fun updateBluetoothUI(state: ConnectionState) {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)

        when (state) {
            is ConnectionState.Connected -> {
                val deviceName = state.deviceName ?: "Unknown Device"
                toolbar.subtitle = "Bluetooth: Connected to $deviceName"
                Log.d("MainActivity", "✅ Bluetooth Connected to $deviceName")
            }
            is ConnectionState.Connecting -> {
                val deviceName = state.deviceName ?: "Unknown Device"
                toolbar.subtitle = "Bluetooth: Connecting to $deviceName..."
                Log.d("MainActivity", "🔄 Bluetooth Connecting to $deviceName")
            }
            is ConnectionState.Disconnected -> {
                toolbar.subtitle = "Bluetooth: Disconnected"
                Log.d("MainActivity", "❌ Bluetooth Disconnected")
            }
            is ConnectionState.Error -> {
                toolbar.subtitle = "Bluetooth: Error - ${state.message}"
                Log.e("MainActivity", "⚠️ Bluetooth Error: ${state.message}")
            }
        }
    }

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

        val namedDevices = devices.filter { if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return
        }
            it.name != null && it.name.isNotEmpty() }

        if (namedDevices.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("No Named Devices")
                .setMessage("Found ${devices.size} devices but none have names. Please check your Bluetooth pairing.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val deviceNames = namedDevices.map { it.name ?: it.address }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select Bluetooth Device")
            .setItems(deviceNames) { _, which ->
                val selectedDevice = namedDevices.elementAt(which)
                Log.d("MainActivity", "Selected device: ${selectedDevice.name} (${selectedDevice.address})")

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

    override fun onDestroy() {
        super.onDestroy()
        Log.d("MainActivity", "Activity destroyed")
        serviceStarted = false
    }
}