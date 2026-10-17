package com.example.myapplication.data.local

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.local.entities.TrackingRecord
import com.example.myapplication.logic.policy.ConfigManager
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TrackingViewModel(private val repository: TrackingRepository) : ViewModel() {

    val allRecords: StateFlow<List<TrackingRecord>> = repository.getAllRecords(ConfigManager.missionId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun insert(record: TrackingRecord) = viewModelScope.launch {
        repository.insert(record)
    }

    fun syncWithFirebase(database: FirebaseDatabase) = viewModelScope.launch {
        val unsynced = repository.getUnsyncedRecords(ConfigManager.missionId)
        unsynced.forEach { record ->
            val path = "tracking/${record.missionId}/${record.userId}/${record.timestamp}"
            database.getReference(path).setValue(record)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        viewModelScope.launch {
                            repository.markAsSynced(record.id)
                        }
                    }
                }
        }
    }

    fun fetchFromFirebase(database: FirebaseDatabase) = viewModelScope.launch {
        // Check whether we are connected to the server before proceeding
        database.getReference(".info/connected").get().addOnSuccessListener { connectedSnapshot ->
            val isOnline = connectedSnapshot.getValue(Boolean::class.java) ?: false
            val path = "tracking/${ConfigManager.missionId}/${ConfigManager.userId}"
            
            database.getReference(path).get().addOnSuccessListener { snapshot ->
                val remoteRecords = mutableListOf<TrackingRecord>()
                snapshot.children.forEach { child ->
                    try {
                        val record = child.getValue(TrackingRecord::class.java)
                        if (record != null) {
                            // If offline, don't force the 'synced' flag since we might only be reading Firebase's local cache
                            val finalRecord = if (!isOnline) record.copy(isCloudSynced = false) else record
                            remoteRecords.add(finalRecord)
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("TrackingVM", "Error converting record: ${child.key}", e)
                    }
                }
                if (remoteRecords.isNotEmpty()) {
                    viewModelScope.launch {
                        repository.insertAll(remoteRecords)
                        android.util.Log.i("TrackingVM", "Fetched ${remoteRecords.size} records. Online status: $isOnline")
                    }
                }
            }
        }
    }

    fun clearDatabase() = viewModelScope.launch {
        repository.deleteAll()
    }
}

class TrackingViewModelFactory(private val repository: TrackingRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TrackingViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return TrackingViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
