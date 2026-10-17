package com.example.myapplication.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.myapplication.data.local.entities.TrackingRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: TrackingRecord): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<TrackingRecord>)

    @Query("SELECT * FROM tracking_records WHERE missionId = :missionId ORDER BY timestamp DESC")
    fun getAllRecords(missionId: String): Flow<List<TrackingRecord>>

    @Query("SELECT * FROM tracking_records WHERE userId = :userId AND missionId = :missionId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestRecordForUser(userId: String, missionId: String): TrackingRecord?

    @Query("""
        SELECT * FROM tracking_records 
        WHERE missionId = :missionId AND id IN (
            SELECT id FROM tracking_records AS t1
            WHERE missionId = :missionId AND timestamp = (SELECT MAX(timestamp) FROM tracking_records WHERE userId = t1.userId AND missionId = :missionId)
            GROUP BY userId
        )
    """)
    fun getLatestRecordsPerUserFlow(missionId: String): Flow<List<TrackingRecord>>

    @Query("SELECT * FROM tracking_records WHERE isCloudSynced = 0 AND missionId = :missionId")
    suspend fun getUnsyncedRecords(missionId: String): List<TrackingRecord>

    @Query("SELECT COUNT(*) FROM tracking_records WHERE isCloudSynced = 0 AND missionId = :missionId")
    fun getUnsyncedCountFlow(missionId: String): kotlinx.coroutines.flow.Flow<Int>

    @Query("UPDATE tracking_records SET isCloudSynced = 1 WHERE id = :id")
    suspend fun markAsSynced(id: Long)

    @Query("SELECT * FROM tracking_records WHERE missionId = :missionId AND timestamp > :since ORDER BY timestamp ASC")
    suspend fun getRecordsSince(missionId: String, since: Long): List<TrackingRecord>
    
    @Query("DELETE FROM tracking_records WHERE isCloudSynced = 1")
    suspend fun deleteSyncedRecords()

    @Query("DELETE FROM tracking_records")
    suspend fun deleteAll()
}
