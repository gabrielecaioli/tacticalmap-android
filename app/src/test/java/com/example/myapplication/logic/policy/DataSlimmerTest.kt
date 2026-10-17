package com.example.myapplication.logic.policy

import com.example.myapplication.data.local.entities.ActivityType
import com.example.myapplication.data.local.entities.TrackingRecord
import com.example.myapplication.data.local.entities.TrackingStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DataSlimmerTest {

    private lateinit var slimmer: DataSlimmer

    @Before
    fun setup() {
        slimmer = DataSlimmer()
    }

    @Test
    fun `first record should always transmit`() {
        val record = createRecord(latitude = 45.0, longitude = 9.0)
        assertTrue(slimmer.shouldTransmit(record))
    }

    @Test
    fun `emergency status should always transmit`() {
        val first = createRecord(latitude = 45.0, longitude = 9.0)
        slimmer.shouldTransmit(first)
        
        val second = createRecord(latitude = 45.0, longitude = 9.0, status = TrackingStatus.DANGER)
        assertTrue(slimmer.shouldTransmit(second))
    }

    @Test
    fun `redundant records should be slimmed`() {
        val first = createRecord(latitude = 45.0, longitude = 9.0, activity = ActivityType.STILL)
        slimmer.shouldTransmit(first)
        
        // Small movement (approx 1 meter)
        val second = createRecord(latitude = 45.00001, longitude = 9.0, activity = ActivityType.STILL)
        assertFalse(slimmer.shouldTransmit(second))
    }

    @Test
    fun `activity change should trigger transmission`() {
        val first = createRecord(latitude = 45.0, longitude = 9.0, activity = ActivityType.STILL)
        slimmer.shouldTransmit(first)
        
        val second = createRecord(latitude = 45.0, longitude = 9.0, activity = ActivityType.WALKING)
        assertTrue(slimmer.shouldTransmit(second))
    }

    private fun createRecord(
        latitude: Double,
        longitude: Double,
        status: TrackingStatus = TrackingStatus.OK,
        activity: ActivityType = ActivityType.STILL
    ): TrackingRecord {
        return TrackingRecord(
            id = 0,
            missionId = "TEST",
            userId = "USER",
            timestamp = System.currentTimeMillis(),
            latitude = latitude,
            longitude = longitude,
            status = status,
            activityType = activity
        )
    }
}
