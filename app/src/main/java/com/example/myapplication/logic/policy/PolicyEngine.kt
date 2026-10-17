package com.example.myapplication.logic.policy

import android.util.Log
import com.example.myapplication.data.local.entities.ActivityType
import com.example.myapplication.data.local.entities.TrackingStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted

/**
 * The core brain of the application.
 * Evaluates context (Activity, Battery, Status) and emits the optimal LocationConfiguration.
 */
class PolicyEngine {

    private val engineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var immobilityJob: Job? = null
    
    // Constant for immobility alert (README says 15 min, set to 60s for testing)
    private val immobilityTimeoutMs = 60_000L

    private val _activity = MutableStateFlow(ActivityType.STILL)
    private val _batteryLow = MutableStateFlow(false)
    private val _status = MutableStateFlow(TrackingStatus.OK)

    // The current policy state as a combination of all inputs
    val policyState: StateFlow<PolicyState> = combine(_activity, _batteryLow, _status) { activity, batteryLow, status ->
        PolicyState(
            activityType = activity,
            isBatteryLow = batteryLow,
            trackingStatus = status
        )
    }.stateIn(
        scope = engineScope,
        started = SharingStarted.Eagerly,
        initialValue = PolicyState(
            activityType = ActivityType.STILL,
            isBatteryLow = false,
            trackingStatus = TrackingStatus.OK
        )
    )

    private val _currentConfig = MutableStateFlow(getInitialConfig())
    val currentConfig: StateFlow<LocationConfiguration> = _currentConfig.asStateFlow()

    fun updateActivity(type: ActivityType) {
        Log.d("PolicyEngine", "Activity update: $type")
        val previousActivity = _activity.value
        _activity.value = type
        
        // Handle Immobility Timer (Phase 7)
        if (type == ActivityType.STILL) {
            // If we were moving and are now still, OR if the job has never started
            if (previousActivity != ActivityType.STILL || immobilityJob == null || !immobilityJob!!.isActive) {
                startImmobilityTimer()
            }
        } else {
            stopImmobilityTimer()
        }
        
        evaluate()
    }

    private fun startImmobilityTimer() {
        immobilityJob?.cancel()
        immobilityJob = engineScope.launch {
            Log.d("PolicyEngine", "Immobility timer started: $immobilityTimeoutMs ms")
            delay(immobilityTimeoutMs)
            Log.w("PolicyEngine", "!!! IMMOBILITY ALERT: Soldier still for too long! Triggering DANGER.")
            updateTrackingStatus(TrackingStatus.DANGER)
        }
    }

    private fun stopImmobilityTimer() {
        if (immobilityJob != null) {
            Log.d("PolicyEngine", "Immobility timer stopped (Movement detected).")
            immobilityJob?.cancel()
            immobilityJob = null
        }
    }

    fun updateBatteryStatus(isLow: Boolean) {
        _batteryLow.value = isLow
        evaluate()
    }

    fun updateTrackingStatus(status: TrackingStatus) {
        _status.value = status
        evaluate()
    }

    fun isBatteryLow(): Boolean = _batteryLow.value

    /**
     * Decision-making logic based on the project requirements (Point 6).
     */
    private fun evaluate() {
        val activity = _activity.value
        val batteryLow = _batteryLow.value
        val status = _status.value

        Log.d("PolicyEngine", "Evaluating: Activity=$activity, BatteryLow=$batteryLow, Status=$status")

        val config = when {
            // Priority 0: Inactive
            status == TrackingStatus.INACTIVE -> {
                LocationConfiguration(
                    interval = 3600_000L, // 1 hour (effectively stopped)
                    fastestInterval = 1800_000L,
                    priority = 104, // Priority.PRIORITY_PASSIVE
                    smallestDisplacement = 1000f
                ).also { Log.i("PolicyEngine", ">>> POLICY CHANGE: INACTIVE (Passive)") }
            }

            // Priority 1: Emergency (any activity in DANGER -> every 10 sec)
            status == TrackingStatus.DANGER -> {
                LocationConfiguration(
                    interval = 10_000L,
                    fastestInterval = 10_000L,
                    priority = 100, // Priority.PRIORITY_HIGH_ACCURACY
                    smallestDisplacement = 0f
                ).also { Log.i("PolicyEngine", ">>> POLICY CHANGE: EMERGENCY MODE (10s)") }
            }

            // Priority 2: Battery Saving Mode (< 30%) (doubled compared to the base rules)
            batteryLow -> {
                val baseInterval = when (activity) {
                    ActivityType.RUNNING -> 10_000L
                    ActivityType.WALKING -> 30_000L
                    else -> 120_000L // Still (2 min)
                }
                val doubledInterval = baseInterval * 2
                LocationConfiguration(
                    interval = doubledInterval,
                    fastestInterval = doubledInterval,
                    priority = 102, // Priority.PRIORITY_BALANCED_POWER_ACCURACY
                    smallestDisplacement = 30f
                ).also { Log.i("PolicyEngine", ">>> POLICY CHANGE: BATTERY SAVER (${doubledInterval/1000}s)") }
            }

            // Priority 3: Activity Based (> 30%)
            activity == ActivityType.RUNNING -> {
                LocationConfiguration(
                    interval = 10_000L,
                    fastestInterval = 10_000L,
                    priority = 100,
                    smallestDisplacement = 5f
                ).also { Log.i("PolicyEngine", ">>> POLICY CHANGE: RUNNING (10s)") }
            }

            activity == ActivityType.WALKING -> {
                LocationConfiguration(
                    interval = 30_000L,
                    fastestInterval = 30_000L,
                    priority = 100,
                    smallestDisplacement = 10f
                ).also { Log.i("PolicyEngine", ">>> POLICY CHANGE: WALKING (30s)") }
            }

            // Default: Stationary / STILL (> 30%)
            else -> {
                LocationConfiguration(
                    interval = 120_000L, // 2 minutes (lower bound of 2-5 min)
                    fastestInterval = 120_000L,
                    priority = 102,
                    smallestDisplacement = 20f
                ).also { Log.i("PolicyEngine", ">>> POLICY CHANGE: STATIONARY (120s)") }
            }
        }

        _currentConfig.value = config
    }

    private fun getInitialConfig() = LocationConfiguration(
        interval = 120_000L,
        fastestInterval = 60_000L,
        priority = 102,
        smallestDisplacement = 30f
    )
}
