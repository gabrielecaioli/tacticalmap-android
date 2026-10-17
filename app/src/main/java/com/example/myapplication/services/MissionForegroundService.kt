package com.example.myapplication.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.myapplication.R
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.local.entities.TrackingStatus
import com.example.myapplication.logic.mesh.P2pMeshManager
import com.example.myapplication.logic.policy.BatteryMonitor
import com.example.myapplication.logic.policy.LocationTracker
import com.example.myapplication.logic.policy.ConfigManager
import com.example.myapplication.utils.TapDetector
import com.example.myapplication.utils.VibrationHelper
import com.example.myapplication.BaseApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MissionForegroundService : Service() {

    companion object {
        const val ACTION_EMERGENCY_DETECTED = "com.example.myapplication.EMERGENCY_DETECTED"
        const val EXTRA_IS_EMERGENCY = "is_emergency"
        const val ACTION_TRANSMIT_STATUS = "com.example.myapplication.TRANSMIT_STATUS"
    }

    private lateinit var sensorManager: SensorManager
    private var linearAccelerationSensor: Sensor? = null
    private lateinit var vibrationHelper: VibrationHelper
    
    private val serviceScope = CoroutineScope(Dispatchers.IO)

    // Gesture detection
    private lateinit var tapDetector: TapDetector
    
    private var isEmergencyActive = false

    // Location & Policy
    private lateinit var locationTracker: LocationTracker
    private lateinit var batteryMonitor: BatteryMonitor
    private var batteryJob: Job? = null
    private var statusJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // P2P Mesh Networking
    private lateinit var meshManager: P2pMeshManager
    private var meshJob: Job? = null
    private lateinit var deduplicationCache: com.example.myapplication.logic.mesh.MeshDeduplicationCache
    private lateinit var deltaSyncHandler: com.example.myapplication.logic.mesh.DeltaSyncHandler

    // Cloud Sync
    private var cloudSyncJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as BaseApplication
        
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        linearAccelerationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        vibrationHelper = VibrationHelper(this)
        
        tapDetector = TapDetector {
            triggerEmergency()
        }

        // Initialize Location Tracking components
        deduplicationCache = com.example.myapplication.logic.mesh.MeshDeduplicationCache()
        meshManager = P2pMeshManager(this, app.policyEngine)
        deltaSyncHandler = com.example.myapplication.logic.mesh.DeltaSyncHandler(
            app.repository,
            app.policyEngine,
            meshManager,
            deduplicationCache
        )
        meshManager.deltaSyncHandler = deltaSyncHandler
        
        // Expose to App for UI
        app.p2pMeshManager = meshManager
        app.deltaSyncHandler = deltaSyncHandler

        locationTracker = LocationTracker(
            this,
            app.policyEngine,
            app.repository,
            onRecordCreated = { record ->
                serviceScope.launch {
                    deltaSyncHandler.onRecordCreated(record)
                    // If online, trigger immediate sync for the new record
                    if (isOnline()) {
                        app.cloudMeshSyncManager.syncToCloud()
                    }
                }
            }
        )
        batteryMonitor = BatteryMonitor(this)
        
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TRANSMIT_STATUS) {
            locationTracker.transmitImmediateStatusUpdate()
            return START_STICKY
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, createNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(1, createNotification())
        }

        val app = application as BaseApplication

        // Start monitoring battery and location
        batteryMonitor.startMonitoring()
        batteryJob = serviceScope.launch {
            batteryMonitor.isLow.collectLatest { isLow ->
                app.policyEngine.updateBatteryStatus(isLow)
            }
        }

        // Observe Tracking Status from Policy Engine (Manual or Automatic via Immobility)
        statusJob = serviceScope.launch {
            app.policyEngine.policyState.collect { state ->
                val isDanger = state.trackingStatus == TrackingStatus.DANGER
                if (isDanger != isEmergencyActive) {
                    isEmergencyActive = isDanger
                    handleStatusTransition(state.trackingStatus)
                }
            }
        }

        // Start P2P mesh network
        meshJob = serviceScope.launch {
            meshManager.startMeshNetwork()
        }

        // Start cloud sync loop with dynamic interval based on Policy Engine
        
        // Listen for connectivity changes to trigger sync immediately
        setupNetworkCallback()

        cloudSyncJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    if (isOnline()) {
                        app.cloudMeshSyncManager.syncToCloud()
                    }
                } catch (e: Exception) {
                    Log.e("MissionService", "Cloud sync error", e)
                }
                
                // Dynamic delay based on Policy Engine state
                val state = app.policyEngine.policyState.value
                val syncDelay = when {
                    state.trackingStatus == TrackingStatus.DANGER -> 5_000L  // 5 sec in emergency
                    state.isBatteryLow -> 60_000L                           // 1 min if battery is low
                    else -> 20_000L                                         // 20 sec normal catch-up
                }
                
                kotlinx.coroutines.delay(syncDelay)
            }
        }

        // Acquire WakeLock to keep sensors active when screen is off
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MissionMonitor::WakeLock").apply {
            acquire(10 * 60 * 1000L /*10 minutes*/)
        }

        linearAccelerationSensor?.let {
            sensorManager.registerListener(tapDetector, it, SensorManager.SENSOR_DELAY_GAME)
        }

        Log.d("MissionService", "Service started")

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d("MissionService", "Task removed - stopping service")
        // The user wants the service to stop if the app is closed (swiped away)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(tapDetector)

        // Stop connectivity monitoring
        networkCallback?.let {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(it)
        }

        // Stop tracking and battery monitoring
        locationTracker.stopTracking()
        batteryMonitor.stopMonitoring()
        batteryJob?.cancel()
        statusJob?.cancel()

        // Stop P2P mesh network
        serviceScope.launch {
            meshManager.stopMeshNetwork()
            meshManager.cleanup()
            deltaSyncHandler.cleanup()
        }
        meshJob?.cancel()

        // Stop cloud sync
        cloudSyncJob?.cancel()

        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        Log.d("MissionService", "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null


    private fun triggerEmergency() {
        val engine = (application as BaseApplication).policyEngine
        val currentStatus = engine.policyState.value.trackingStatus
        val newStatus = if (currentStatus == TrackingStatus.DANGER) TrackingStatus.OK else TrackingStatus.DANGER
        Log.d("MissionService", "Manual Emergency Toggle: $currentStatus -> $newStatus")
        engine.updateTrackingStatus(newStatus)
    }

    private fun handleStatusTransition(newStatus: TrackingStatus) {
        val isDanger = newStatus == TrackingStatus.DANGER
        Log.w("MissionService", ">>> STATUS TRANSITION: isDanger=$isDanger")

        // 1. Notify UI
        val intent = Intent(ACTION_EMERGENCY_DETECTED).apply {
            putExtra(EXTRA_IS_EMERGENCY, isDanger)
            setPackage(packageName)
        }
        sendBroadcast(intent)

        // 2. Feedback
        if (isDanger) {
            tapDetector.lockForVibration(300)
            vibrationHelper.vibrateOnce()
        } else {
            tapDetector.lockForVibration(500)
            vibrationHelper.vibrateTwice()
        }

        // 3. Database & Network Sync
        serviceScope.launch(Dispatchers.IO) {
            val database = AppDatabase.getDatabase(applicationContext)
            val dao = database.trackingDao()
            val latest = dao.getLatestRecordForUser(ConfigManager.userId, ConfigManager.missionId)

            if (latest != null) {
                val newRecord = latest.copy(
                    id = 0,
                    status = newStatus,
                    timestamp = System.currentTimeMillis(),
                    isCloudSynced = false
                )
                dao.insert(newRecord)
                locationTracker.transmitImmediateStatusUpdate()
            }
        }
    }

    private fun setupNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i("MissionService", "Network available - triggering cloud sync")
                serviceScope.launch {
                    (application as BaseApplication).cloudMeshSyncManager.syncToCloud()
                }
            }
        }
        cm.registerNetworkCallback(request, networkCallback!!)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "mission_channel")
            .setContentTitle("Mission Monitor Active")
            .setContentText("Emergency gesture detection running in background")
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Ensure this exists or use a default
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "mission_channel",
                "Mission Monitoring Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun isOnline(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
