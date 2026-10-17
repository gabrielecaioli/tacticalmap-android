package com.example.myapplication.logic.policy

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.util.Log
import com.google.android.gms.location.*
import com.example.myapplication.data.local.TrackingRepository
import com.example.myapplication.data.local.entities.TrackingRecord
import com.example.myapplication.data.local.entities.TrackingStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The \"Arm\" of the system.
 * Connects the PolicyEngine's decisions to the actual FusedLocationProviderClient.
 * Now integrated with TrackingRepository to persist data for Map and Cloud sync.
 */
class LocationTracker(
    private val context: Context,
    private val policyEngine: PolicyEngine,
    private val repository: TrackingRepository,
    private val onRecordCreated: (suspend (com.example.myapplication.data.local.entities.TrackingRecord) -> Unit)? = null
) {
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    private val dataSlimmer = DataSlimmer() // Added DataSlimmer instance
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var roomAutoSaveJob: Job? = null
    private var firebaseSyncJob: Job? = null
    private var firebaseRemoteListener: com.google.firebase.database.ValueEventListener? = null
    
    private val firebaseDbUrl = "https://msss-b34d1-default-rtdb.europe-west1.firebasedatabase.app"

    // Initialize as null to wait for the first real GPS fix
    private val _lastLocation = MutableStateFlow<Location?>(null)
    val lastLocation: StateFlow<Location?> = _lastLocation.asStateFlow()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            for (location in locationResult.locations) {
                // Defensive check to avoid processing invalid coordinates during initialization
                if (location.latitude == 0.0 && location.longitude == 0.0) continue
                
                // If we get a location update, we treat it as "activity" for the PolicyEngine 
                // to make sure the timer logic is evaluated even if ActivityRecognition is slow.
                policyEngine.updateActivity(policyEngine.policyState.value.activityType)

                val currentRecord = TrackingRecord(
                    missionId = ConfigManager.missionId,
                    userId = ConfigManager.userId,
                    timestamp = location.time, // Use GPS timestamp for deduplication
                    latitude = location.latitude,
                    longitude = location.longitude,
                    status = policyEngine.policyState.value.trackingStatus,
                    activityType = policyEngine.policyState.value.activityType,
                    isCloudSynced = false
                )

                if (dataSlimmer.shouldTransmit(currentRecord)) {
                    Log.i("LocationTracker", ">>> PERSISTING LOCATION: Lat=${location.latitude}, Lng=${location.longitude}")
                    _lastLocation.value = location

                    // SAVE TO DATABASE AND AUTO-SYNC
                    scope.launch(Dispatchers.IO) {
                        repository.insert(currentRecord)
                        Log.d("LocationTracker", "Inserted record to DB: Lat=${location.latitude}, Lng=${location.longitude}")

                        // Notify mesh sync handler if available
                        onRecordCreated?.invoke(currentRecord)

                        // Auto-Sync to Firebase (Immediate)
                        try {
                            val dbUrl = "https://msss-b34d1-default-rtdb.europe-west1.firebasedatabase.app"
                            val firebaseDb = com.google.firebase.database.FirebaseDatabase.getInstance(dbUrl)
                            val path = "tracking/${currentRecord.missionId}/${currentRecord.userId}/${currentRecord.timestamp}"
                            val syncedRecord = currentRecord.copy(isCloudSynced = true)
                            
                            firebaseDb.getReference(path).setValue(syncedRecord)
                                .addOnCompleteListener { task ->
                                    if (task.isSuccessful) {
                                        scope.launch(Dispatchers.IO) {
                                            repository.markAsSynced(currentRecord.id)
                                            Log.d("LocationTracker", "Auto-synced record ${currentRecord.id} to Firebase")
                                        }
                                    }
                                }
                        } catch (e: Exception) {
                            Log.e("LocationTracker", "Immediate auto-sync failed", e)
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun requestInitialLocation() {
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    Log.i("LocationTracker", "Initial Location Found: Lat=${location.latitude}, Lng=${location.longitude}")
                    _lastLocation.value = location
                    val currentRecord = createRecordFromLocation(location)
                    scope.launch(Dispatchers.IO) {
                        val id = repository.insert(currentRecord)
                        transmitSingleRecord(currentRecord.copy(id = id))
                    }
                } else {
                    Log.w("LocationTracker", "No Last Known Location available. Fetching current location...")
                    fetchCurrentLocationForcefully()
                }
            }.addOnFailureListener { e ->
                Log.e("LocationTracker", "Error getting initial location (GPS might be off)", e)
                fetchCurrentLocationForcefully()
            }
        } catch (e: Exception) {
            Log.e("LocationTracker", "Critical error in requestInitialLocation", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchCurrentLocationForcefully() {
        try {
            val priority = Priority.PRIORITY_HIGH_ACCURACY
            fusedLocationClient.getCurrentLocation(priority, null)
                .addOnSuccessListener { location ->
                    if (location != null) {
                        Log.i("LocationTracker", "Current Location Forcefully Fetched: Lat=${location.latitude}, Lng=${location.longitude}")
                        _lastLocation.value = location
                        val currentRecord = createRecordFromLocation(location)
                        scope.launch(Dispatchers.IO) {
                            val id = repository.insert(currentRecord)
                            transmitSingleRecord(currentRecord.copy(id = id))
                        }
                    } else {
                        Log.e("LocationTracker", "Failed to fetch any location even forcefully (Check if Location is ON).")
                        // REMOVED 0,0 placeholder logic
                    }
                }.addOnFailureListener { e ->
                    Log.e("LocationTracker", "Error forcefully fetching current location (GPS might be off)", e)
                }
        } catch (e: Exception) {
            Log.e("LocationTracker", "Critical error in fetchCurrentLocationForcefully", e)
        }
    }

    fun transmitImmediateStatusUpdate() {
        val location = _lastLocation.value
        if (location == null) {
            Log.w("LocationTracker", "Cannot transmit status update: No location available yet.")
            return
        }

        val record = createRecordFromLocation(location)
        scope.launch(Dispatchers.IO) {
            val id = repository.insert(record)
            transmitSingleRecord(record.copy(id = id))
        }
    }

    private fun createRecordFromLocation(location: Location): TrackingRecord {
        return TrackingRecord(
            missionId = ConfigManager.missionId,
            userId = ConfigManager.userId,
            timestamp = if (location.time > 0) location.time else System.currentTimeMillis(),
            latitude = location.latitude,
            longitude = location.longitude,
            status = policyEngine.policyState.value.trackingStatus,
            activityType = policyEngine.policyState.value.activityType,
            isCloudSynced = false
        )
    }

    private fun transmitSingleRecord(record: TrackingRecord) {
        // Also trigger mesh sync handler if available
        scope.launch(Dispatchers.IO) {
            onRecordCreated?.invoke(record)
        }

        val firebaseDb = com.google.firebase.database.FirebaseDatabase.getInstance(firebaseDbUrl)
        val path = "tracking/${record.missionId}/${record.userId}/${record.timestamp}"
        
        val syncedRecord = record.copy(isCloudSynced = true)
        
        firebaseDb.getReference(path).setValue(syncedRecord)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    scope.launch(Dispatchers.IO) {
                        repository.markAsSynced(record.id)
                        Log.i("LocationTracker", ">>> INSTANT SYNC SUCCESS: Record ${record.id} sent to Cloud (Path=$path)")
                    }
                } else {
                    Log.d("LocationTracker", "Instant record ${record.id} queued in Firebase local cache (Offline)")
                }
            }
    }

    init {
        // Observe PolicyEngine changes and re-request updates
        scope.launch {
            policyEngine.currentConfig.collect { config ->
                Log.w("LocationTracker", "Applying New GPS Config: Interval=${config.interval}ms, Priority=${config.priority}")
                restartLocationUpdates(config)
            }
        }
        startFirebaseRemoteListener()
    }

    private fun startFirebaseRemoteListener() {
        val firebaseDb = com.google.firebase.database.FirebaseDatabase.getInstance(firebaseDbUrl)
        val missionPath = "tracking/${ConfigManager.missionId}"
        Log.i("LocationTracker", "STARTING FIREBASE LISTENER on path: $missionPath")
        
        firebaseRemoteListener = firebaseDb.getReference(missionPath).addValueEventListener(object : com.google.firebase.database.ValueEventListener {
            override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                Log.d("LocationTracker", "Firebase data changed! Mission path: $missionPath")
                if (!snapshot.exists()) {
                    Log.w("LocationTracker", "Snapshot is empty or does not exist at $missionPath")
                    return
                }

                scope.launch(Dispatchers.IO) {
                    val remoteRecords = mutableListOf<TrackingRecord>()
                    
                    for (userSnapshot in snapshot.children) {
                        val userId = userSnapshot.key
                        // Skip local user and special nodes
                        if (userId == null || userId == ConfigManager.userId || userId == "map_config") continue

                        for (recordSnapshot in userSnapshot.children) {
                            try {
                                if (recordSnapshot.value is Map<*, *>) {
                                    val record = recordSnapshot.getValue(TrackingRecord::class.java)
                                    if (record != null) {
                                        // Ensure we don't accidentally insert local user data received via mesh/relay
                                        if (record.userId != ConfigManager.userId) {
                                            remoteRecords.add(record.copy(id = 0, isCloudSynced = true))
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("LocationTracker", "Error parsing remote record for user $userId", e)
                            }
                        }
                    }
                    if (remoteRecords.isNotEmpty()) {
                        repository.insertAll(remoteRecords)
                        Log.i("LocationTracker", "Remote Sync: Successfully integrated ${remoteRecords.size} records into local Room")
                    } else {
                        Log.d("LocationTracker", "No valid teammate records found in snapshot")
                    }
                }
            }

            override fun onCancelled(error: com.google.firebase.database.DatabaseError) {
                Log.e("LocationTracker", "Firebase listener cancelled", error.toException())
            }
        })
    }

    private fun startRoomAutoSave() {
        roomAutoSaveJob?.cancel()
        roomAutoSaveJob = scope.launch {
            while (isActive) {
                delay(20000L) // 20 seconds
                saveLocationToRoom()
            }
        }
    }


    private fun saveLocationToRoom() {
        val location = _lastLocation.value
        if (location != null) {
            val record = TrackingRecord(
                id = 0, // Ensure ID is 0 so Room auto-generates a new unique ID
                missionId = ConfigManager.missionId,
                userId = ConfigManager.userId,
                timestamp = System.currentTimeMillis(),
                latitude = location.latitude,
                longitude = location.longitude,
                status = policyEngine.policyState.value.trackingStatus,
                activityType = policyEngine.policyState.value.activityType,
                isCloudSynced = false
            )

            scope.launch(Dispatchers.IO) {
                repository.insert(record)
                Log.d("LocationTracker", ">>> AUTO-SAVE (20s) - NEW RECORD ADDED: Lat=${location.latitude}, Lng=${location.longitude}")
            }
        } else {
            Log.w("LocationTracker", "Auto-save skipped: No location data yet.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun restartLocationUpdates(config: LocationConfiguration) {
        try {
            // 1. Remove existing updates
            fusedLocationClient.removeLocationUpdates(locationCallback)

            // 2. Build new request
            val locationRequest = LocationRequest.Builder(config.priority, config.interval).apply {
                setMinUpdateIntervalMillis(config.fastestInterval)
                setMinUpdateDistanceMeters(config.smallestDisplacement)
            }.build()

            // 3. Request new updates
            val task = fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                context.mainLooper
            )

            task.addOnFailureListener { e ->
                Log.e("LocationTracker", "Failed to start location updates. GPS might be disabled.", e)
            }
        } catch (e: SecurityException) {
            Log.e("LocationTracker", "Permission denied for location updates", e)
        } catch (e: Exception) {
            Log.e("LocationTracker", "Error requesting location updates (Check if GPS is ON)", e)
        }
    }

    fun stopTracking() {
        firebaseSyncJob?.cancel()

        firebaseRemoteListener?.let { listener ->
            val firebaseDb = com.google.firebase.database.FirebaseDatabase.getInstance(firebaseDbUrl)
            firebaseDb.getReference("tracking/${ConfigManager.missionId}").removeEventListener(listener)
            firebaseRemoteListener = null
        }

        fusedLocationClient.removeLocationUpdates(locationCallback)
    }
}
