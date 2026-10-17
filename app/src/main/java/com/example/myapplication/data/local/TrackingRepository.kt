package com.example.myapplication.data.local

import com.example.myapplication.data.local.dao.TrackingDao
import com.example.myapplication.data.local.entities.TrackingRecord
import kotlinx.coroutines.flow.Flow

class TrackingRepository(private val trackingDao: TrackingDao) {

    fun getAllRecords(missionId: String): Flow<List<TrackingRecord>> = trackingDao.getAllRecords(missionId)
    fun getLatestRecordsPerUser(missionId: String): Flow<List<TrackingRecord>> = trackingDao.getLatestRecordsPerUserFlow(missionId)

    suspend fun insert(record: TrackingRecord): Long {
        return trackingDao.insert(record)
    }

    suspend fun insertAll(records: List<TrackingRecord>) {
        trackingDao.insertAll(records)
    }

    suspend fun getLatestRecordForUser(userId: String, missionId: String): TrackingRecord? {
        return trackingDao.getLatestRecordForUser(userId, missionId)
    }

    suspend fun getUnsyncedRecords(missionId: String): List<TrackingRecord> {
        return trackingDao.getUnsyncedRecords(missionId)
    }

    fun getUnsyncedCountFlow(missionId: String): Flow<Int> = trackingDao.getUnsyncedCountFlow(missionId)

    suspend fun markAsSynced(id: Long) {
        trackingDao.markAsSynced(id)
    }

    suspend fun getRecordsSince(missionId: String, since: Long): List<TrackingRecord> {
        return trackingDao.getRecordsSince(missionId, since)
    }

    suspend fun deleteSyncedRecords() {
        trackingDao.deleteSyncedRecords()
    }

    suspend fun deleteAll() {
        trackingDao.deleteAll()
    }
}
