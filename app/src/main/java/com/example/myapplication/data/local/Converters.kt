package com.example.myapplication.data.local

import androidx.room.TypeConverter
import com.example.myapplication.data.local.entities.ActivityType
import com.example.myapplication.data.local.entities.TrackingStatus

@Suppress("unused")
class Converters {
    @TypeConverter
    fun fromStatus(status: TrackingStatus): String {
        return status.name
    }

    @TypeConverter
    fun toStatus(status: String): TrackingStatus {
        return TrackingStatus.valueOf(status)
    }

    @TypeConverter
    fun fromActivityType(type: ActivityType): String {
        return type.name
    }

    @TypeConverter
    fun toActivityType(type: String): ActivityType {
        return ActivityType.valueOf(type)
    }
}
