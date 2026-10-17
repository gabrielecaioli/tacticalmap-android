package com.example.myapplication.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.firebase.database.IgnoreExtraProperties
import com.google.firebase.database.PropertyName

/**
 * Data structure for tracking updates as defined in section 3 of the README.
 * This represents a single point in time capturing the user's state and position.
 * Unified for both Local Room and Firebase Realtime Database.
 */
@Entity(
    tableName = "tracking_records",
    indices = [Index(value = ["missionId", "userId", "timestamp"], unique = true)]
)
@IgnoreExtraProperties
data class TrackingRecord(
    @PrimaryKey(autoGenerate = true)
    @get:PropertyName("id") @set:PropertyName("id")
    var id: Long = 0,

    @get:PropertyName("missionId") @set:PropertyName("missionId")
    var missionId: String = "",

    @get:PropertyName("userId") @set:PropertyName("userId")
    var userId: String = "",

    @get:PropertyName("timestamp") @set:PropertyName("timestamp")
    var timestamp: Long = 0,

    @get:PropertyName("latitude") @set:PropertyName("latitude")
    var latitude: Double = 0.0,

    @get:PropertyName("longitude") @set:PropertyName("longitude")
    var longitude: Double = 0.0,

    @get:PropertyName("status") @set:PropertyName("status")
    var status: TrackingStatus = TrackingStatus.OK,

    @get:PropertyName("activityType") @set:PropertyName("activityType")
    var activityType: ActivityType = ActivityType.STILL,

    @get:PropertyName("isCloudSynced") @set:PropertyName("isCloudSynced")
    var isCloudSynced: Boolean = false,

    @get:PropertyName("lastMeshSyncTime") @set:PropertyName("lastMeshSyncTime")
    var lastMeshSyncTime: Long = 0
)
