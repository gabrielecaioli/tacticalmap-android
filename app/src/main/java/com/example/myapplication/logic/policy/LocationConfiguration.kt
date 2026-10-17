package com.example.myapplication.logic.policy

/**
 * Encapsulates GPS configuration parameters for FusedLocationProviderClient.
 *
 * @param interval Long: Desired interval for active location updates, in milliseconds.
 * @param fastestInterval Long: Fastest rate at which the app can handle location updates.
 * @param priority Int: Priority of the request (e.g., Priority.PRIORITY_HIGH_ACCURACY).
 * @param smallestDisplacement Float: Minimum displacement between location updates in meters.
 */
data class LocationConfiguration(
    val interval: Long,
    val fastestInterval: Long,
    val priority: Int,
    val smallestDisplacement: Float = 0f
)
