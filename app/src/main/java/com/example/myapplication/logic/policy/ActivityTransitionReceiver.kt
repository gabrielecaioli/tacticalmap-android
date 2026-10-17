package com.example.myapplication.logic.policy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.myapplication.data.local.entities.ActivityType
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity

import android.util.Log

class ActivityTransitionReceiver : BroadcastReceiver() {

    // In a real app with Hilt/DI, we would inject the engine. 
    // For this implementation, we'll use a static reference or a callback setter.
    companion object {
        var engine: PolicyEngine? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d("ActivityReceiver", "Received Intent: ${intent.action}")

        // Manual test override for ADB
        if (intent.action == "com.example.myapplication.TEST_ACTIVITY") {
            val type = intent.getIntExtra("type", 3) // Default to STILL
            Log.w("ActivityReceiver", ">>> MANUAL TEST TRIGGERED: Type $type")
            val activityType = when (type) {
                8 -> ActivityType.RUNNING
                7 -> ActivityType.WALKING
                else -> ActivityType.STILL
            }
            engine?.updateActivity(activityType)
            return
        }

        if (ActivityRecognitionResult.hasResult(intent)) {
            val result = ActivityRecognitionResult.extractResult(intent)
            result?.let {
                val mostProbableActivity = it.mostProbableActivity
                Log.d("ActivityReceiver", "Detected: ${mostProbableActivity.type} (Confidence: ${mostProbableActivity.confidence})")
                
                val activityType = when (mostProbableActivity.type) {
                    DetectedActivity.RUNNING -> ActivityType.RUNNING
                    DetectedActivity.WALKING -> ActivityType.WALKING
                    DetectedActivity.ON_FOOT -> ActivityType.WALKING
                    DetectedActivity.STILL -> ActivityType.STILL
                    else -> ActivityType.STILL
                }
                
                engine?.updateActivity(activityType)
            }
        }
    }
}
