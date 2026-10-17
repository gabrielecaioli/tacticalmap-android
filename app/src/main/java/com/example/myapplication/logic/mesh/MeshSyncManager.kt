package com.example.myapplication.logic.mesh

import com.example.myapplication.data.local.entities.TrackingRecord

interface MeshSyncManager {
    /**
     * Called when a delta sync packet is received from a peer.
     * Implement: validate, merge records into local Room, update sync timestamp.
     */
    suspend fun onRemoteSyncReceived(packet: DeltaSyncPacket)

    /**
     * Called to send our local delta to a specific peer.
     * Implement: compute delta, send DeltaSyncPacket, handle transport.
     */
    suspend fun sendLocalSync(peerId: String)

    /**
     * Called when a peer device connects.
     * Implement: trigger initial sync, store peer info.
     */
    suspend fun onPeerConnected(peerId: String)

    /**
     * Called when a peer device disconnects.
     * Implement: cleanup peer state.
     */
    suspend fun onPeerDisconnected(peerId: String)

    /**
     * Called when a new TrackingRecord is created locally.
     * Implement: queue record for relay to connected peers.
     */
    suspend fun onRecordCreated(record: TrackingRecord)

    /**
     * Get the timestamp of the last successful mesh sync.
     */
    fun getLastSyncTimestamp(): Long
}
