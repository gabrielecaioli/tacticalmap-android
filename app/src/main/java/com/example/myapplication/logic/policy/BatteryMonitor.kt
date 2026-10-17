package com.example.myapplication.logic.policy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Monitors battery level and state using a BroadcastReceiver.
 */
class BatteryMonitor(private val context: Context) {

    private val _batteryLevel = MutableStateFlow(100)
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()

    private val _isLow = MutableStateFlow(false)
    val isLow: StateFlow<Boolean> = _isLow.asStateFlow()

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val batteryPct = ((level * 100) / scale.toFloat()).toInt()

            _batteryLevel.value = batteryPct
            _isLow.value = batteryPct < 30 // Threshold defined in README
        }
    }

    fun startMonitoring() {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        context.registerReceiver(batteryReceiver, filter)
    }

    fun stopMonitoring() {
        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {
            // Receiver might not be registered
        }
    }
}
