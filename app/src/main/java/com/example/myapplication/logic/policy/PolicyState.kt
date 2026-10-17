package com.example.myapplication.logic.policy

import com.example.myapplication.data.local.entities.ActivityType
import com.example.myapplication.data.local.entities.TrackingStatus

/**
 * Represents the current global state used by the PolicyEngine to make decisions.
 */
data class PolicyState(
    val activityType: ActivityType = ActivityType.STILL,
    val batteryLevel: Int = 100,
    val isBatteryLow: Boolean = false,
    val trackingStatus: TrackingStatus = TrackingStatus.OK
)
