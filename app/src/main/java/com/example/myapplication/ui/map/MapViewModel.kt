package com.example.myapplication.ui.map

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.myapplication.BaseApplication
import com.example.myapplication.data.local.TrackingRepository
import com.example.myapplication.data.local.entities.TrackingRecord
import com.example.myapplication.data.local.entities.TrackingStatus
import com.example.myapplication.logic.policy.ConfigManager
import com.example.myapplication.services.MissionForegroundService
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.example.myapplication.logic.map.MapDownloadWorker
import androidx.lifecycle.asFlow
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import android.location.Location
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Locale

class MapViewModel(application: Application, private val repository: TrackingRepository) : AndroidViewModel(application) {

    private val _markers = mutableStateOf<List<TrackingRecord>>(emptyList())
    val markers: State<List<TrackingRecord>> = _markers

    private val _isMonitoring = mutableStateOf(false)
    val isMonitoring: State<Boolean> = _isMonitoring

    private val _missionDuration = mutableStateOf("00:00")
    val missionDuration: State<String> = _missionDuration

    private var timerJob: Job? = null
    private var startTime: Long = 0

    // Dynamic historical paths from database
    private val _historicalPaths = mutableStateOf<Map<String, List<TrackingRecord>>>(emptyMap())
    val historicalPaths: Map<String, List<TrackingRecord>> get() = _historicalPaths.value

    private var firebaseListener: ValueEventListener? = null
    private val firebaseDbUrl = "https://msss-b34d1-default-rtdb.europe-west1.firebasedatabase.app"
    private var _remoteMarkers = listOf<TrackingRecord>()
    private var _localMarkers = listOf<TrackingRecord>()

    private val _downloadStatus = mutableStateOf<String?>(null)
    val downloadStatus: State<String?> = _downloadStatus

    private val _unsyncedCount = mutableStateOf(0)
    val unsyncedCount: State<Int> = _unsyncedCount

    private val _isSelectingCenter = mutableStateOf(false)
    val isSelectingCenter: State<Boolean> = _isSelectingCenter

    private val _pendingCenter = mutableStateOf<GeoPoint?>(null)
    val pendingCenter: State<GeoPoint?> = _pendingCenter

    // Observation of the download worker
    private val workManager = WorkManager.getInstance(application)
    val downloadWorkInfo: Flow<WorkInfo?> = workManager
        .getWorkInfosByTagLiveData("map_download")
        .asFlow()
        .map { it.firstOrNull() }

    private var lastDownloadedCenter: GeoPoint? = null

    init {
        // Initialize with a fresh GPS fix to avoid stale coordinates from previous sessions
        fetchInitialLocationAndInsertRecord()
        
        observeTrackingRecords()
        observeAllHistory()
        observeMapConfig()
        observeFirebaseTeammates()
        observeDownloadWorker()
        observeUnsyncedCount()
    }

    private fun fetchInitialLocationAndInsertRecord() {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(getApplication<Application>())
        try {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { location: Location? ->
                    viewModelScope.launch(Dispatchers.IO) {
                        val dao = (getApplication() as BaseApplication).database.trackingDao()
                        
                        // Use current location if available, otherwise fallback to last known but at least we tried
                        val lat = location?.latitude ?: 0.0
                        val lon = location?.longitude ?: 0.0
                        
                        if (lat != 0.0 || lon != 0.0) {
                            val newRecord = TrackingRecord(
                                missionId = ConfigManager.missionId,
                                userId = ConfigManager.userId,
                                timestamp = System.currentTimeMillis(),
                                latitude = lat,
                                longitude = lon,
                                status = TrackingStatus.INACTIVE,
                                activityType = com.example.myapplication.data.local.entities.ActivityType.STILL,
                                isCloudSynced = false
                            )
                            dao.insert(newRecord)
                            Log.d("MapViewModel", "Inserted initial fresh location record: $lat, $lon")
                        } else {
                            // If no location, check latest record but only to maintain continuity if really needed
                            // However, user specifically wants fresh GPS. If 0.0, we might prefer to wait for the first fix from service.
                            Log.w("MapViewModel", "Could not get initial fresh location fix.")
                        }
                    }
                }
        } catch (e: SecurityException) {
            Log.e("MapViewModel", "Location permission missing for initial fix", e)
        }
    }

    private fun observeUnsyncedCount() {
        viewModelScope.launch {
            repository.getUnsyncedCountFlow(ConfigManager.missionId).collect { count ->
                _unsyncedCount.value = count
            }
        }
    }

    private fun observeDownloadWorker() {
        var lastState: WorkInfo.State? = null
        viewModelScope.launch {
            downloadWorkInfo.collect { workInfo ->
                val currentState = workInfo?.state
                
                // Only show messages if the state has CHANGED to SUCCEEDED or FAILED
                // This prevents showing a success message for an old task when the ViewModel starts
                if (lastState != null && currentState != lastState) {
                    when (currentState) {
                        WorkInfo.State.SUCCEEDED -> {
                            _downloadStatus.value = "Map download completed successfully!"
                            delay(5000)
                            _downloadStatus.value = null
                        }
                        WorkInfo.State.FAILED -> {
                            val errors = workInfo.outputData.getInt(MapDownloadWorker.KEY_ERRORS, 0)
                            _downloadStatus.value = if (errors > 0) {
                                "Error: $errors tiles failed. Try again later."
                            } else {
                                "Error while downloading the map."
                            }
                            delay(5000)
                            _downloadStatus.value = null
                        }
                        else -> {}
                    }
                }
                lastState = currentState
            }
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

                if (lat != null && lon != null) {
                    val newCenter = GeoPoint(lat, lon)
                    if (newCenter != lastDownloadedCenter) {
                        // REMOVED: triggerMapDownload(newCenter) 
                        // We only update the reference so the menu knows it's available
                        lastDownloadedCenter = newCenter
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                android.util.Log.e("MapViewModel", "Error observing map config", error.toException())
            }
        })
    }

    fun cancelMapDownload() {
        workManager.cancelUniqueWork("tactical_map_download")
        _downloadStatus.value = "Download cancelled."
        viewModelScope.launch {
            delay(3000)
            _downloadStatus.value = null
        }
    }

    private fun triggerMapDownload(center: GeoPoint) {
        // Log to identify any remaining internal calls
        Log.w("MapViewModel", "INTERNAL triggerMapDownload called for ${center.latitude}, ${center.longitude}. BLOCKING.")
        /*
        val downloadRequest = OneTimeWorkRequestBuilder<MapDownloadWorker>()
            .setInputData(workDataOf(
                MapDownloadWorker.KEY_LAT to center.latitude,
                MapDownloadWorker.KEY_LON to center.longitude,
                MapDownloadWorker.KEY_MISSION_ID to ConfigManager.missionId
            ))
            .addTag("map_download")
            .build()

        workManager.enqueueUniqueWork(
            "tactical_map_download",
            androidx.work.ExistingWorkPolicy.REPLACE,
            downloadRequest
        )

        _downloadStatus.value = "Tactical map download started in background..."

        viewModelScope.launch {
            delay(5000)
            _downloadStatus.value = null
        }
        */
    }

    private fun observeFirebaseTeammates() {
        val database = FirebaseDatabase.getInstance(firebaseDbUrl)
        val ref = database.getReference("tracking/${ConfigManager.missionId}")

        // Remove old listener if exists
        firebaseListener?.let { ref.removeEventListener(it) }

        firebaseListener = ref.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val remoteList = mutableListOf<TrackingRecord>()
                try {
                    for (userSnapshot in snapshot.children) {
                        val userId = userSnapshot.key ?: continue
                        if (userId == ConfigManager.userId || userId == "map_config") continue // Skip self and config

                        // Get latest record for this user
                        // We iterate in reverse to find the latest valid TrackingRecord
                        val records = userSnapshot.children.reversed()
                        var latestFound: TrackingRecord? = null
                        for (recordSnapshot in records) {
                            try {
                                if (recordSnapshot.value is Map<*, *>) {
                                    val record = recordSnapshot.getValue(TrackingRecord::class.java)
                                    if (record != null) {
                                        latestFound = record
                                        break
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("MapViewModel", "Error parsing record for user $userId", e)
                            }
                        }
                        latestFound?.let { remoteList.add(it) }
                    }
                } catch (e: Exception) {
                    Log.e("MapViewModel", "Critical error in onDataChange", e)
                }
                _remoteMarkers = remoteList
                combineMarkers()
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("MapViewModel", "Firebase listener cancelled", error.toException())
            }
        })
    }

    private fun combineMarkers() {
        // Merge local user record with remote teammate records
        // IMPORTANT: Local markers now include mesh-synced teammates.
        // We prefer the record with the latest timestamp regardless of source.
        val combined = (_localMarkers + _remoteMarkers)
            .sortedByDescending { it.timestamp }
            .distinctBy { it.userId }
        _markers.value = combined
    }

    private fun observeTrackingRecords() {
        viewModelScope.launch {
            repository.getLatestRecordsPerUser(ConfigManager.missionId).collectLatest { records ->
                _localMarkers = records
                combineMarkers()

                val userRecord = records.find { it.userId == ConfigManager.userId }
                // Update monitoring state based on current user record
                if (userRecord != null && userRecord.status == TrackingStatus.INACTIVE) {
                    _isMonitoring.value = false
                }
            }
        }
    }

    private fun observeAllHistory() {
        viewModelScope.launch {
            repository.getAllRecords(ConfigManager.missionId).collectLatest { allRecords ->
                // Group by userId and sort by timestamp
                _historicalPaths.value = allRecords
                    .groupBy { it.userId }
                    .mapValues { (_, records) ->
                        records.sortedBy { it.timestamp }
                    }
            }
        }
    }

    fun startMission() {
        _isMonitoring.value = true
        startTime = System.currentTimeMillis()
        startTimer()
        updateOrInsertUserStatus(TrackingStatus.OK)
        
        // Notify Policy Engine
        (getApplication() as BaseApplication).policyEngine.updateTrackingStatus(TrackingStatus.OK)

        // Start Foreground Service
        val intent = Intent(getApplication(), MissionForegroundService::class.java)
        getApplication<Application>().startService(intent)
    }

    fun stopMission() {
        _isMonitoring.value = false
        timerJob?.cancel()
        _missionDuration.value = "00:00"
        updateOrInsertUserStatus(TrackingStatus.INACTIVE)
        
        // Notify Policy Engine
        (getApplication() as BaseApplication).policyEngine.updateTrackingStatus(TrackingStatus.INACTIVE)

        // Stop Foreground Service
        val intent = Intent(getApplication(), MissionForegroundService::class.java)
        getApplication<Application>().stopService(intent)
    }

    fun toggleEmergency() {
        if (!_isMonitoring.value) return
        val engine = (getApplication() as BaseApplication).policyEngine
        val currentStatus = engine.policyState.value.trackingStatus
        val newStatus = if (currentStatus == TrackingStatus.DANGER) TrackingStatus.OK else TrackingStatus.DANGER
        
        // 1. Notify Policy Engine
        engine.updateTrackingStatus(newStatus)
        
        // 2. Broadcast intent (so MainActivity and Service are in sync)
        val intent = Intent(MissionForegroundService.ACTION_EMERGENCY_DETECTED).apply {
            putExtra(MissionForegroundService.EXTRA_IS_EMERGENCY, newStatus == TrackingStatus.DANGER)
            setPackage(getApplication<Application>().packageName)
        }
        getApplication<Application>().sendBroadcast(intent)

        // 3. Feedback (Vibration)
        val vibrationHelper = com.example.myapplication.utils.VibrationHelper(getApplication())
        if (newStatus == TrackingStatus.DANGER) {
            vibrationHelper.vibrateOnce()
        } else {
            vibrationHelper.vibrateTwice()
        }

        // 4. Persistence & Immediate Sync (via local tracking logic if possible, 
        // but here we just update DB. The Service/Tracker will handle the rest if running)
        updateOrInsertUserStatus(newStatus)

        // 5. Force immediate transmission if service is running
        val syncIntent = Intent(getApplication(), MissionForegroundService::class.java).apply {
            action = MissionForegroundService.ACTION_TRANSMIT_STATUS
        }
        getApplication<Application>().startService(syncIntent)
    }

    private fun updateOrInsertUserStatus(status: TrackingStatus) {
        viewModelScope.launch(Dispatchers.IO) {
            val dao = (getApplication() as BaseApplication).database.trackingDao()
            val latest = dao.getLatestRecordForUser(ConfigManager.userId, ConfigManager.missionId)
            if (latest != null) {
                val newRecord = latest.copy(
                    id = 0,
                    status = status,
                    timestamp = System.currentTimeMillis(),
                    isCloudSynced = false
                )
                dao.insert(newRecord)
            }
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (true) {
                val elapsed = System.currentTimeMillis() - startTime
                val seconds = (elapsed / 1000) % 60
                val minutes = (elapsed / (1000 * 60)) % 60
                val hours = (elapsed / (1000 * 60 * 60))
                
                _missionDuration.value = if (hours > 0) {
                    String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
                } else {
                    String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
                }
                delay(1000)
            }
        }
    }

    fun startSelectingCenter() {
        _isSelectingCenter.value = true
        _pendingCenter.value = null
    }

    fun stopSelectingCenter() {
        _isSelectingCenter.value = false
        _pendingCenter.value = null
    }

    fun setPendingCenter(point: GeoPoint) {
        _pendingCenter.value = point
    }

    fun confirmMapCenter() {
        _pendingCenter.value?.let { point ->
            updateMapCenterInFirebase(point)
            _isSelectingCenter.value = false
            _pendingCenter.value = null
            _downloadStatus.value = "Map center updated!"
            viewModelScope.launch {
                delay(3000)
                _downloadStatus.value = null
            }
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

    override fun onCleared() {
        super.onCleared()
        firebaseListener?.let {
            FirebaseDatabase.getInstance(firebaseDbUrl)
                .getReference("tracking/${ConfigManager.missionId}")
                .removeEventListener(it)
        }
    }
}

class MapViewModelFactory(private val application: Application, private val repository: TrackingRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MapViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MapViewModel(application, repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
