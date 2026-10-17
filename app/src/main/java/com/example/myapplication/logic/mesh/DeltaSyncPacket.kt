package com.example.myapplication.logic.mesh

import com.example.myapplication.data.local.entities.TrackingRecord
import com.google.gson.Gson

data class DeltaSyncPacket(
    val deviceId: String = "",
    val lastSyncTimestamp: Long = 0,
    val records: List<TrackingRecord> = emptyList()
) {
    companion object {
        private val gson = Gson()

        fun toJson(packet: DeltaSyncPacket): String = gson.toJson(packet)

        fun fromJson(json: String): DeltaSyncPacket = gson.fromJson(json, DeltaSyncPacket::class.java)
    }

    fun toJsonString(): String = Companion.toJson(this)

    override fun toString(): String = "DeltaSyncPacket(deviceId=$deviceId, lastSyncTimestamp=$lastSyncTimestamp, records=${records.size})"
}
