package com.example.myapplication.logic.policy

import android.util.Log
import com.example.myapplication.data.local.entities.ActivityType
import com.example.myapplication.data.local.entities.TrackingRecord
import com.example.myapplication.data.local.entities.TrackingStatus
import kotlin.math.*

/**
 * Implements the "Data Slimming" logic (Phase 2 of Point 6).
 * Decides whether a record should be synced to the cloud immediately or discarded 
 * to save energy and bandwidth.
 */
class DataSlimmer {

    private var lastSyncedRecord: TrackingRecord? = null

    /**
     * Evaluates a record and returns true if it's "significant" enough to be transmitted.
     * 
     * Rules:
     * 1. DANGER status: ALWAYS transmit immediately.
     * 2. Significant movement: Transmit if distance from last sync > X meters.
     * 3. Activity change: Transmit if activity type changes (e.g., from STILL to RUNNING).
     * 4. Deduplication: If still/walking and small movement, skip it.
     */
    fun shouldTransmit(currentRecord: TrackingRecord): Boolean {
        // Rule 1: Emergency priority
        if (currentRecord.status == TrackingStatus.DANGER) {
            Log.i("DataSlimmer", ">>> TRANSMIT: EMERGENCY STATUS DETECTED")
            lastSyncedRecord = currentRecord
            return true
        }

        val last = lastSyncedRecord ?: run {
            Log.i("DataSlimmer", ">>> TRANSMIT: First record of the session")
            lastSyncedRecord = currentRecord
            return true
        }

        // Rule 3: Activity Change
        if (currentRecord.activityType != last.activityType) {
            Log.i("DataSlimmer", ">>> TRANSMIT: Activity changed to ${currentRecord.activityType}")
            lastSyncedRecord = currentRecord
            return true
        }

        // Rule 2 & 4: Displacement and Deduplication
        val distance = calculateDistance(
            currentRecord.latitude, currentRecord.longitude,
            last.latitude, last.longitude
        )

        // Thresholds based on activity - REDUCED for real-time visual feedback
        val threshold = when (currentRecord.activityType) {
            ActivityType.RUNNING -> 5.0   // 5 meters
            ActivityType.WALKING -> 10.0  // 10 meters
            ActivityType.STILL -> 50.0    // 50 meters
        }

        return if (distance >= threshold) {
            Log.i("DataSlimmer", ">>> TRANSMIT: Significant movement ($distance m)")
            lastSyncedRecord = currentRecord
            true
        } else {
            Log.d("DataSlimmer", "--- SLIMMED: Redundant data discarded (Movement $distance m < threshold $threshold m)")
            false
        }
    }

    /**
     * Simple Haversine-like distance calculation (in meters).
     */
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371e3 // Earth radius in meters
        val phi1 = lat1 * PI / 180.0
        val phi2 = lat2 * PI / 180.0
        val deltaPhi = (lat2 - lat1) * PI / 180.0
        val deltaLambda = (lon2 - lon1) * PI / 180.0

        val a = sin(deltaPhi / 2).pow(2) +
                cos(phi1) * cos(phi2) *
                sin(deltaLambda / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return r * c
    }
}
