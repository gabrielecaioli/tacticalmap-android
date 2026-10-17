package com.example.myapplication.utils

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.util.Log
import kotlin.math.abs

/**
 * Advanced TapDetector using Jerk (Delta) detection and Temporal Filtering.
 * Designed to minimize false positives from device vibrations and covers.
 */
class TapDetector(private val onTripleTap: () -> Unit) : SensorEventListener {

    private var lastZ = 0f
    private var lastTapTime: Long = 0
    private var tapCount = 0
    
    // Vibration Lock variables
    private var lockUntil: Long = 0
    
    // Temporal Filter variables
    private var consecutiveHighFrames = 0
    private val maxConsecutiveFrames = 4 // If > 4 frames are high, it's vibration/shake, not a tap

    // Optimized settings
    private val tapWindow = 1000L 
    private val tapCooldown = 250L 
    private val deltaThreshold = 3.0f // Calibrated Delta (Jerk) threshold

    /**
     * Call this when a vibration starts to ignore sensor data for a short period.
     */
    fun lockForVibration(durationMs: Long = 300) {
        lockUntil = System.currentTimeMillis() + durationMs
        Log.d("TapDetector", "Vibration lock active for ${durationMs}ms")
    }

    override fun onSensorChanged(event: SensorEvent?) {
        // Use TYPE_LINEAR_ACCELERATION to ignore gravity and focus on movement impulses
        if (event == null || event.sensor.type != Sensor.TYPE_LINEAR_ACCELERATION) return

        val currentTime = System.currentTimeMillis()
        
        // Vibration Lock: Ignore data if we are currently vibrating
        if (currentTime < lockUntil) {
            lastZ = event.values[2]
            return
        }

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        
        // Jerk Detection: difference between current and previous Z value
        val deltaZ = abs(z - lastZ)
        
        val absX = abs(x)
        val absY = abs(y)

        if (deltaZ > deltaThreshold) {
            // Temporal Peak Filter: count frames
            consecutiveHighFrames++
            
            // Axial Dominance Filter + Temporal Filter check
            // Check if Z-axis impulse is significantly stronger than horizontal impulses
            if (consecutiveHighFrames <= maxConsecutiveFrames && deltaZ > (absX + absY) * 1.5f) {
                
                // Potential tap detected
                if (currentTime - lastTapTime > tapCooldown) {
                    if (currentTime - lastTapTime < tapWindow) {
                        tapCount++
                    } else {
                        tapCount = 1
                    }
                    
                    lastTapTime = currentTime
                    Log.d("TapDetector", "Tap detected! Count: $tapCount (DeltaZ: ${"%.2f".format(deltaZ)})")

                    if (tapCount == 3) {
                        onTripleTap()
                        tapCount = 0
                    }
                }
            } else if (consecutiveHighFrames > maxConsecutiveFrames) {
                // Too many consecutive high frames -> likely vibration or shake
                if (tapCount > 0) {
                    Log.d("TapDetector", "Noise detected (frames: $consecutiveHighFrames), resetting tap count.")
                    tapCount = 0 
                }
            }
        } else {
            // Reset consecutive frame counter when value drops below threshold
            consecutiveHighFrames = 0
        }

        // Update lastZ for next cycle
        lastZ = z
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed for this implementation
    }
}
