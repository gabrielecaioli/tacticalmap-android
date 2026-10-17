package com.example.myapplication.logic.policy

import com.example.myapplication.data.local.entities.ActivityType
import com.example.myapplication.data.local.entities.TrackingStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PolicyEngineTest {

    private lateinit var engine: PolicyEngine

    @Before
    fun setup() {
        engine = PolicyEngine()
    }

    @Test
    fun `test activity change updates configuration`() = runTest {
        engine.updateActivity(ActivityType.RUNNING)
        // RUNNING interval is 10s (10_000ms)
        // assertEquals(ActivityType.RUNNING, engine.policyState.value.activityType)
        assertEquals(10_000L, engine.currentConfig.value.interval)
        
        engine.updateActivity(ActivityType.WALKING)
        // WALKING interval is 30s (30_000ms)
        assertEquals(30_000L, engine.currentConfig.value.interval)
    }

    @Test
    fun `test battery low doubles interval`() = runTest {
        // Start from STILL (default) -> 120s
        engine.updateActivity(ActivityType.STILL)
        val normalInterval = engine.currentConfig.value.interval 
        println("Normal interval for STILL: $normalInterval")
        
        engine.updateBatteryStatus(true)
        val lowBatteryInterval = engine.currentConfig.value.interval
        println("Low battery interval for STILL: $lowBatteryInterval")
        
        // Battery low doubles it
        assertEquals(normalInterval * 2, lowBatteryInterval)
    }

    @Test
    fun `test emergency mode priority`() = runTest {
        engine.updateActivity(ActivityType.STILL)
        engine.updateBatteryStatus(true)
        
        engine.updateTrackingStatus(TrackingStatus.DANGER)
        assertEquals(10_000L, engine.currentConfig.value.interval)
        // Emergency (DANGER) should override battery saving
    }
}
