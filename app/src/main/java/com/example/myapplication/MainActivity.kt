package com.example.myapplication

import android.os.Bundle
import com.google.android.material.snackbar.Snackbar
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import android.view.Menu
import android.view.MenuItem
import android.graphics.Color
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.content.Intent
import android.os.Build
import android.content.BroadcastReceiver
import androidx.core.graphics.toColorInt
import com.example.myapplication.services.MissionForegroundService
import android.content.IntentFilter
import com.example.myapplication.databinding.ActivityMainBinding
import com.example.myapplication.utils.VibrationHelper
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.ui.platform.ComposeView
import com.example.myapplication.ui.map.MapPickerBottomSheet
import com.example.myapplication.data.local.entities.TrackingStatus
import com.example.myapplication.logic.policy.ConfigManager
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import org.osmdroid.util.GeoPoint
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.myapplication.logic.map.MapDownloadWorker
import androidx.work.ExistingWorkPolicy

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

import com.example.myapplication.logic.policy.PolicyEngine

import android.app.PendingIntent
import com.google.android.gms.location.ActivityRecognition
import com.example.myapplication.logic.policy.ActivityTransitionReceiver

class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private lateinit var vibrationHelper: VibrationHelper

    private val policyEngine: PolicyEngine by lazy { (application as BaseApplication).policyEngine }
    private var currentActivityInterval = 30000L // Track current interval to avoid redundant updates
    private var hasMapCenter = false

    private val emergencyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == MissionForegroundService.ACTION_EMERGENCY_DETECTED) {
                val isEmergency = intent.getBooleanExtra(MissionForegroundService.EXTRA_IS_EMERGENCY, false)
                updateUI(isEmergency)
            }
        }
    }

    private val hideStatusHandler = Handler(Looper.getMainLooper())
    private val hideStatusRunnable = Runnable {
        binding.contentMain.statusIndicator.visibility = android.view.View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        setSupportActionBar(binding.toolbar)

        requestPermissionsIfNecessary(
            mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    add(Manifest.permission.BLUETOOTH_SCAN)
                    add(Manifest.permission.BLUETOOTH_CONNECT)
                }
            }.toTypedArray()
        )

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment
        val navController = navHostFragment.navController

        appBarConfiguration = AppBarConfiguration(navController.graph)
        setupActionBarWithNavController(navController, appBarConfiguration)

        // Initialize Helpers
        vibrationHelper = VibrationHelper(this)

        // Initialize Activity Recognition (Automated Point 1)
        ActivityTransitionReceiver.engine = policyEngine
        
        // Explicitly register the receiver in code to be 100% sure (Dynamic Registration)
        val filter = IntentFilter().apply {
            addAction("com.google.android.gms.location.ACTIVITY_RECOGNITION_DATA")
            addAction("com.example.myapplication.TEST_ACTIVITY")
        }

        // Starting from Android 14 (API 34), you MUST specify RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED
        // Since we receive data from Google Play Services (external), we use EXPORTED.
        ContextCompat.registerReceiver(
            this,
            ActivityTransitionReceiver(),
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
        Log.i("MainActivity", "ActivityTransitionReceiver registered via code.")

        // Dynamic Activity Recognition based on Battery Status (Point 1 Optimization)
        lifecycleScope.launch {
            policyEngine.policyState.collect { state ->
                val targetInterval = if (state.isBatteryLow) 120000L else 30000L
                if (targetInterval != currentActivityInterval) {
                    Log.w("MainActivity", ">>> BATTERY STATUS CHANGE: isLow=${state.isBatteryLow}. Updating Activity Recognition to ${targetInterval}ms")
                    startActivityRecognition(targetInterval)
                    currentActivityInterval = targetInterval
                }
            }
        }

        observeMapConfig()
        checkInitialMapCenter()
    }

    private fun checkInitialMapCenter() {
        val dbUrl = "https://msss-b34d1-default-rtdb.europe-west1.firebasedatabase.app"
        val database = FirebaseDatabase.getInstance(dbUrl)
        val path = "tracking/${ConfigManager.missionId}/map_config"

        database.getReference(path).get().addOnSuccessListener { snapshot ->
            val lat = snapshot.child("mapCenterLat").getValue(Double::class.java)
            val lon = snapshot.child("mapCenterLon").getValue(Double::class.java)
            hasMapCenter = lat != null && lon != null
            invalidateOptionsMenu()
        }.addOnFailureListener {
            hasMapCenter = false
            invalidateOptionsMenu()
        }
    }

    private fun observeMapConfig() {
        val dbUrl = "https://msss-b34d1-default-rtdb.europe-west1.firebasedatabase.app"
        val database = FirebaseDatabase.getInstance(dbUrl)
        val path = "tracking/${ConfigManager.missionId}/map_config"

        database.getReference(path).addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val lat = snapshot.child("mapCenterLat").getValue(Double::class.java)
                val lon = snapshot.child("mapCenterLon").getValue(Double::class.java)
                hasMapCenter = lat != null && lon != null
                invalidateOptionsMenu()
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("MainActivity", "Error observing map config", error.toException())
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun startActivityRecognition(interval: Long = 30000L) {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Manifest.permission.ACTIVITY_RECOGNITION
        } else {
            "com.google.android.gms.permission.ACTIVITY_RECOGNITION"
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            val intent = Intent(this, ActivityTransitionReceiver::class.java)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getBroadcast(
                this, 0, intent, flags
            )

            ActivityRecognition.getClient(this)
                .requestActivityUpdates(interval, pendingIntent)
                .addOnSuccessListener { Log.i("MainActivity", "Activity Recognition Started: Interval=${interval}ms") }
                .addOnFailureListener { e -> Log.e("MainActivity", "Activity Recognition Failed", e) }
        } else {
            // Request permission specifically for Activity Recognition
            ActivityCompat.requestPermissions(this, arrayOf(permission), 2)
        }
    }


    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val isOnline = isNetworkAvailable()
        val defineMapCenterItem = menu.findItem(R.id.action_define_map_center)
        defineMapCenterItem?.isEnabled = isOnline
        
        val downloadMapItem = menu.findItem(R.id.action_download_map)
        downloadMapItem?.isEnabled = isOnline
        
        return super.onPrepareOptionsMenu(menu)
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        if (network == null) {
            Log.w("MainActivity", "Network Check: No active network")
            return false
        }
        val activeNetwork = connectivityManager.getNetworkCapabilities(network)
        if (activeNetwork == null) {
            Log.w("MainActivity", "Network Check: No network capabilities")
            return false
        }
        val hasInternet = when {
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
            else -> false
        }
        Log.d("MainActivity", "Network Check: hasInternet=$hasInternet")
        return hasInternet
    }

    private fun showMapPicker() {
        val composeView = ComposeView(this)
        binding.root.addView(composeView)
        composeView.setContent {
            MapPickerBottomSheet(
                onDismiss = {
                    binding.root.removeView(composeView)
                },
                onConfirm = { geoPoint ->
                    updateMapCenterInFirebase(geoPoint)
                    hasMapCenter = true
                    invalidateOptionsMenu()
                    binding.root.removeView(composeView)
                    Snackbar.make(binding.root, "Map center updated!", Snackbar.LENGTH_SHORT).show()
                }
            )
        }
    }

    private fun updateMapCenterInFirebase(geoPoint: GeoPoint) {
        val dbUrl = "https://msss-b34d1-default-rtdb.europe-west1.firebasedatabase.app"
        val database = FirebaseDatabase.getInstance(dbUrl)
        val path = "tracking/${ConfigManager.missionId}/map_config"
        val data = mapOf(
            "mapCenterLat" to geoPoint.latitude,
            "mapCenterLon" to geoPoint.longitude,
            "timestamp" to System.currentTimeMillis()
        )
        database.getReference(path).setValue(data)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_mesh_status -> {
                showMeshStatusDialog()
                true
            }
            R.id.action_define_map_center -> {
                lifecycleScope.launch {
                    val navController = findNavController(R.id.nav_host_fragment_content_main)
                    // Check if we are already in TacticalMapScreen, if not navigate
                    // This assumes a simple navigation structure
                    // For now, we assume we are on the map or can trigger the state
                    // We need access to the ViewModel. A common way is a broadcast or shared ViewModel
                    // Since I don't want to refactor everything, I'll use a local broadcast
                    // that the TacticalMapScreen listens to.
                    val intent = Intent("com.example.myapplication.START_SELECTING_CENTER").apply {
                        setPackage(packageName)
                    }
                    sendBroadcast(intent)
                }
                true
            }
            R.id.action_download_map -> {
                triggerManualDownload()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun triggerManualDownload() {
        val dbUrl = "https://msss-b34d1-default-rtdb.europe-west1.firebasedatabase.app"
        val database = FirebaseDatabase.getInstance(dbUrl)
        val path = "tracking/${ConfigManager.missionId}/map_config"

        database.getReference(path).get().addOnSuccessListener { snapshot ->
            val lat = snapshot.child("mapCenterLat").getValue(Double::class.java)
            val lon = snapshot.child("mapCenterLon").getValue(Double::class.java)

            if (lat != null && lon != null) {
                val downloadRequest = OneTimeWorkRequestBuilder<MapDownloadWorker>()
                    .setInputData(workDataOf(
                        MapDownloadWorker.KEY_LAT to lat,
                        MapDownloadWorker.KEY_LON to lon,
                        MapDownloadWorker.KEY_MISSION_ID to ConfigManager.missionId
                    ))
                    .addTag("map_download")
                    .build()

                WorkManager.getInstance(this).enqueueUniqueWork(
                    "tactical_map_download",
                    ExistingWorkPolicy.REPLACE,
                    downloadRequest
                )
                Snackbar.make(binding.root, "Map download started...", Snackbar.LENGTH_SHORT).show()
            } else {
                Snackbar.make(
                    binding.root, 
                    "Map center not set. Select a center on the map before downloading.", 
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration)
                || super.onSupportNavigateUp()
    }

    private fun updateUI(isEmergency: Boolean) {
        // Remove any pending hide callbacks
        hideStatusHandler.removeCallbacks(hideStatusRunnable)

        binding.contentMain.statusIndicator.visibility = android.view.View.VISIBLE

        if (isEmergency) {
            binding.contentMain.statusIndicator.setText(R.string.emergency_active)
            binding.contentMain.statusIndicator.setTextColor(Color.RED)
            binding.contentMain.statusIndicator.setBackgroundColor(Color.argb(200, 255, 255, 255))
        } else {
            binding.contentMain.statusIndicator.setText(R.string.status_ok)
            binding.contentMain.statusIndicator.setTextColor("#388E3C".toColorInt())
            binding.contentMain.statusIndicator.setBackgroundColor(Color.argb(128, 255, 255, 255))
        }

        // Hide after 3 seconds
        hideStatusHandler.postDelayed(hideStatusRunnable, 3000)
    }

    private fun showMeshStatusDialog() {
        val app = application as BaseApplication
        val meshManager = app.p2pMeshManager
        val syncHandler = app.deltaSyncHandler

        if (meshManager == null || syncHandler == null) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Mesh Network")
                .setMessage("Mesh service is not running. Start the mission first.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        lifecycleScope.launch {
            val peers = meshManager.connectedPeers.value
            val lastSync = syncHandler.lastSyncTime.value
            val timeStr = if (lastSync == 0L) "Never" else 
                android.text.format.DateFormat.format("HH:mm:ss", lastSync).toString()

            val message = StringBuilder()
            message.append("Connected Peers: ${peers.size}\n")
            if (peers.isNotEmpty()) {
                message.append("\nActive Soldiers:\n")
                peers.values.forEach { userId -> 
                    message.append("- $userId\n") 
                }
            } else {
                message.append("\nNo soldiers connected. Ensure Bluetooth is ON and devices are PAIRED.\n")
            }
            message.append("\nLast Sync: $timeStr")

            androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                .setTitle("P2P Mesh Status")
                .setMessage(message.toString())
                .setPositiveButton("OK", null)
                .setNeutralButton("Refresh") { _, _ -> showMeshStatusDialog() }
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(MissionForegroundService.ACTION_EMERGENCY_DETECTED)
        ContextCompat.registerReceiver(
            this,
            emergencyReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(emergencyReceiver)
    }

    private fun requestPermissionsIfNecessary(permissions: Array<String>) {
        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), 1)
        }
    }
}
