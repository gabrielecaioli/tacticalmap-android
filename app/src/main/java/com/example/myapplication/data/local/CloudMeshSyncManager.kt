package com.example.myapplication.data.local

import android.util.Log
import com.example.myapplication.logic.policy.ConfigManager
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CloudMeshSyncManager(private val repository: TrackingRepository) {

    private val TAG = "CloudMeshSyncManager"
    private val dbUrl = "https://msss-b34d1-default-rtdb.europe-west1.firebasedatabase.app"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    suspend fun syncToCloud(): Int = withContext(Dispatchers.IO) {
        return@withContext try {
            val unsynced = repository.getUnsyncedRecords(ConfigManager.missionId)
            if (unsynced.isEmpty()) {
                Log.d(TAG, "No unsynced records to upload")
                return@withContext 0
            }

            Log.d(TAG, "Syncing ${unsynced.size} records to Firebase")
            val firebaseDb = FirebaseDatabase.getInstance(dbUrl)
            var syncedCount = 0

            unsynced.forEach { record ->
                try {
                    val path = "tracking/${record.missionId}/${record.userId}/${record.timestamp}"
                    val recordToUpload = record.copy(isCloudSynced = true)
                    
                    firebaseDb.getReference(path)
                        .setValue(recordToUpload)
                        .addOnCompleteListener { task ->
                            if (task.isSuccessful) {
                                scope.launch(Dispatchers.IO) {
                                    repository.markAsSynced(record.id)
                                    Log.d(TAG, "Uploaded record ${record.id} (${record.userId}) to Firebase path: $path")
                                }
                            }
                        }

                    syncedCount++
                } catch (e: Exception) {
                    Log.e(TAG, "Error syncing record ${record.id}", e)
                }
            }

            Log.i(TAG, "Cloud sync initiated for $syncedCount/${unsynced.size} records")
            syncedCount
        } catch (e: Exception) {
            Log.e(TAG, "Cloud sync failed", e)
            0
        }
    }
}
