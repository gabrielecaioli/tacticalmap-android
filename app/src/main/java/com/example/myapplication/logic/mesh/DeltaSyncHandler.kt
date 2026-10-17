package com.example.myapplication.logic.mesh

import android.util.Log
import com.example.myapplication.data.local.TrackingRepository
import com.example.myapplication.data.local.entities.TrackingRecord
import com.example.myapplication.data.local.entities.TrackingStatus
import com.example.myapplication.logic.policy.PolicyEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DeltaSyncHandler(
    private val repository: TrackingRepository,
    private val policyEngine: PolicyEngine,
    private val p2pMeshManager: P2pMeshManager,
    private val deduplicationCache: MeshDeduplicationCache,
) {
    private val tag = "DeltaSyncHandler"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _lastSyncTime = MutableStateFlow(System.currentTimeMillis())
    val lastSyncTime: StateFlow<Long> = _lastSyncTime

    private val peerLastSyncTime = mutableMapOf<String, Long>()
    private val peerSyncMutex = Mutex()

    private val relayQueue = mutableListOf<Pair<String, DeltaSyncPacket>>()
    private val relayQueueMutex = Mutex()
    private var relayJob: Job? = null

    init {
        startRelayProcessor()
    }

    suspend fun onPeerConnected(peerId: String) {
        sendDeltaToPeer(peerId)
    }

    suspend fun onPeerDisconnected(peerId: String) {
        peerSyncMutex.withLock {
            peerLastSyncTime.remove(peerId)
        }
    }

    suspend fun onRecordCreated(record: TrackingRecord) {
        if (shouldRelayRecord(record)) {
            queueRecordForRelay(record)
        }
    }

    suspend fun sendDeltaToPeer(peerId: String) = withContext(Dispatchers.IO) {
        try {
            val lastSyncTime = peerSyncMutex.withLock {
                peerLastSyncTime[peerId] ?: 0L
            }

            val delta = repository.getRecordsSince(com.example.myapplication.logic.policy.ConfigManager.missionId, lastSyncTime)

            if (delta.isEmpty()) {
                Log.d(tag, "No delta to send to $peerId")
                return@withContext
            }

            val packet = DeltaSyncPacket(
                deviceId = "You",
                lastSyncTimestamp = lastSyncTime,
                records = delta
            )

            val success = p2pMeshManager.sendDataToPeer(peerId, packet.toJsonString())
            if (success) {
                peerSyncMutex.withLock {
                    peerLastSyncTime[peerId] = System.currentTimeMillis()
                }
                _lastSyncTime.value = System.currentTimeMillis()
                Log.d(tag, "Sent ${delta.size} records to $peerId")
            }
        } catch (e: Exception) {
            Log.e(tag, "Error sending delta to $peerId", e)
        }
    }

    suspend fun receiveDeltaFromPeer(packet: DeltaSyncPacket) = withContext(Dispatchers.IO) {
        try {
            Log.d(tag, "Received ${packet.records.size} records from ${packet.deviceId}")

            for (record in packet.records) {
                val dedupeKey = "${record.userId}_${record.timestamp}"
                if (deduplicationCache.isSeen(dedupeKey)) {
                    Log.d(tag, "Skipping duplicate record from ${record.userId} at ${record.timestamp}")
                    continue
                }

                deduplicationCache.markSeen(dedupeKey)

                val existingRecord = repository.getLatestRecordForUser(record.userId, record.missionId)
                if (existingRecord == null || record.timestamp > existingRecord.timestamp) {
                    // Set id to 0 for local Room generation and isCloudSynced to false for relay
                    // Note: set isCloudSynced to false so it's picked up by CloudMeshSyncManager if back online
                    val recordToInsert = record.copy(id = 0, isCloudSynced = false)
                    repository.insert(recordToInsert)
                    Log.d(tag, "Merged record from ${record.userId} (Timestamp: ${record.timestamp})")

                    // Queue for relay to OTHER peers
                    queueRecordForRelay(recordToInsert, skipPeerId = packet.deviceId)
                }
            }

            _lastSyncTime.value = System.currentTimeMillis()
        } catch (e: Exception) {
            Log.e(tag, "Error receiving delta from ${packet.deviceId}", e)
        }
    }

    private fun shouldRelayRecord(record: TrackingRecord): Boolean {
        val isBatteryLow = policyEngine.isBatteryLow()

        return when {
            record.status == TrackingStatus.DANGER -> true
            isBatteryLow -> false
            else -> true
        }
    }

    private suspend fun queueRecordForRelay(record: TrackingRecord, skipPeerId: String? = null) {
        val packet = DeltaSyncPacket(
            deviceId = "You",
            lastSyncTimestamp = 0L,
            records = listOf(record)
        )

        relayQueueMutex.withLock {
            relayQueue.add(skipPeerId.orEmpty() to packet)
        }
    }

    private fun startRelayProcessor() {
        relayJob = scope.launch {
            while (isActive) {
                // Check if we have any DANGER records in queue
                val hasDanger = relayQueueMutex.withLock {
                    relayQueue.any { it.second.records.any { r -> r.status == TrackingStatus.DANGER } }
                }

                // If no danger, wait a bit. If danger, process immediately.
                if (!hasDanger) {
                    delay(2000)
                }

                val toRelay = relayQueueMutex.withLock {
                    if (relayQueue.isEmpty()) return@withLock emptyList()
                    val copy = relayQueue.toList()
                    relayQueue.clear()
                    copy
                }

                if (toRelay.isEmpty()) continue

                val connectedPeers = p2pMeshManager.connectedPeers.value
                if (connectedPeers.isEmpty()) {
                    // Put them back if no one is connected
                    relayQueueMutex.withLock {
                        relayQueue.addAll(toRelay)
                    }
                    delay(5000) // Wait longer if no peers
                    continue
                }

                for (peerEntry in connectedPeers) {
                    val peerId = peerEntry.key
                    for ((skipId, packet) in toRelay) {
                        if (peerId == skipId) continue
                        try {
                            p2pMeshManager.sendDataToPeer(peerId, packet.toJsonString())
                        } catch (e: Exception) {
                            Log.e(tag, "Error relaying to $peerId", e)
                        }
                    }
                }
            }
        }
    }

    fun cleanup() {
        relayJob?.cancel()
        scope.cancel()
    }
}
