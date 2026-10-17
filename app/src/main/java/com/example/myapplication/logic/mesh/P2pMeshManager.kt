package com.example.myapplication.logic.mesh

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.util.Log
import com.example.myapplication.logic.policy.PolicyEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.*

class P2pMeshManager(
    context: Context,
    private val policyEngine: PolicyEngine,
    var deltaSyncHandler: DeltaSyncHandler? = null,
) {
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _connectedPeers = MutableStateFlow<Map<String, String>>(emptyMap()) // MAC -> UserID
    val connectedPeers: StateFlow<Map<String, String>> = _connectedPeers

    private val connections = mutableMapOf<String, BluetoothP2pConnection>()
    private var scanJob: Job? = null
    private var serverJob: Job? = null
    private var policyJob: Job? = null

    private val bleMeshManager = BleMeshManager(context, policyEngine) { device ->
        scope.launch {
            connectToDevice(device)
        }
    }

    private val meshUuid = UUID.fromString("a73e69f8-5c1d-4b3f-b7c9-7e8a5f6d4c3b")
    private val meshServiceName = "MSSSTacticalMesh"
    private val tag = "P2pMeshManager"

    fun startMeshNetwork() {
        if (bluetoothAdapter == null) {
            Log.e(tag, "Bluetooth not available")
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            Log.w(tag, "Bluetooth is disabled")
            return
        }

        bleMeshManager.start()
        startMeshDiscovery()
        startServerSocket()
        observePolicy()
    }

    suspend fun stopMeshNetwork() {
        policyJob?.cancel()
        scanJob?.cancel()
        serverJob?.cancel()
        bleMeshManager.stopBleDiscovery()
        connections.values.forEach { it.disconnect() }
        connections.clear()
        _connectedPeers.value = emptyMap()
    }

    private fun observePolicy() {
        policyJob = scope.launch {
            policyEngine.policyState.collect { state ->
                Log.d(tag, "Policy change detected: BatteryLow=${state.isBatteryLow}, Status=${state.trackingStatus}")
                // The bleMeshManager already observes this, but we might want to 
                // adjust our Classic BT behavior here too if needed.
                if (state.trackingStatus == com.example.myapplication.data.local.entities.TrackingStatus.DANGER) {
                    // Force a rapid scan of paired devices as well
                    discoverPeers()
                }
            }
        }
    }

    private fun startMeshDiscovery() {
        scanJob = scope.launch {
            while (isActive) {
                val state = policyEngine.policyState.value
                val isBatteryLow = state.isBatteryLow
                val status = state.trackingStatus

                val scanInterval = when {
                    status == com.example.myapplication.data.local.entities.TrackingStatus.DANGER -> 10_000L // 10s
                    !isBatteryLow -> 30_000L     // 30s
                    else -> 120_000L              // 2 min
                }

                try {
                    discoverPeers()
                    
                    // Periodic identity broadcast to ensure peers know who we are
                    // especially if they missed the initial ID packet
                    connections.values.forEach { connection ->
                        if (connection.state == BluetoothP2pConnection.ConnectionState.CONNECTED) {
                            connection.sendData("ID:${com.example.myapplication.logic.policy.ConfigManager.userId}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Mesh discovery error", e)
                }

                delay(scanInterval)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun discoverPeers() {
        val adapter = bluetoothAdapter ?: return

        try {
            val pairedDevices = adapter.bondedDevices
            Log.d(tag, "Checking ${pairedDevices.size} paired devices for mesh connectivity")

            for (device in pairedDevices) {
                if ((device.type == BluetoothDevice.DEVICE_TYPE_CLASSIC) ||
                    (device.type == BluetoothDevice.DEVICE_TYPE_DUAL)
                ) {
                    connectToDevice(device)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error discovering devices", e)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectToDevice(device: BluetoothDevice) {
        withContext(Dispatchers.IO) {
            try {
                if (connections.containsKey(device.address)) {
                    return@withContext
                }

                Log.d(tag, "Attempting connection to ${device.address}")

                val socket = device.createRfcommSocketToServiceRecord(meshUuid)
                    ?: return@withContext

                val connection = BluetoothP2pConnection(
                    socket = socket,
                    onDataReceived = { data ->
                        handleReceivedData(device.address, data)
                    },
                    onDisconnected = {
                        handlePeerDisconnected(device.address)
                    }
                )

                if (connection.connect()) {
                    connections[device.address] = connection
                    // Send our ID immediately
                    connection.sendData("ID:${com.example.myapplication.logic.policy.ConfigManager.userId}")
                    updateConnectedPeers()
                    scope.launch {
                        deltaSyncHandler?.onPeerConnected(device.address)
                    }
                    Log.d(tag, "Connected to ${device.address}")
                } else {
                    connection.cleanup()
                }
            } catch (e: Exception) {
                Log.e(tag, "Connection error to ${device.address}", e)
            }
        }
    }

    private fun startServerSocket() {
        serverJob = scope.launch {
            @SuppressLint("MissingPermission")
            try {
                val serverSocket = bluetoothAdapter?.listenUsingInsecureRfcommWithServiceRecord(
                    meshServiceName,
                    meshUuid
                ) ?: return@launch

                while (isActive) {
                    try {
                        val socket = serverSocket.accept()
                        handleIncomingConnection(socket)
                    } catch (e: Exception) {
                        if (isActive) {
                            Log.e(tag, "Server socket error", e)
                        }
                        delay(1000)
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Server socket creation error", e)
            }
        }
    }

    private suspend fun handleIncomingConnection(socket: BluetoothSocket) {
        withContext(Dispatchers.IO) {
            try {
                val peerId = socket.remoteDevice.address
                Log.d(tag, "Incoming connection from $peerId")

                if (connections.containsKey(peerId)) {
                    socket.close()
                    return@withContext
                }

                val connection = BluetoothP2pConnection(
                    socket = socket,
                    onDataReceived = { data ->
                        handleReceivedData(peerId, data)
                    },
                    onDisconnected = {
                        handlePeerDisconnected(peerId)
                    }
                )

                if (connection.connect()) {
                    connections[peerId] = connection
                    // Send our ID immediately
                    connection.sendData("ID:${com.example.myapplication.logic.policy.ConfigManager.userId}")
                    updateConnectedPeers()
                    scope.launch {
                        deltaSyncHandler?.onPeerConnected(peerId)
                    }
                    Log.d(tag, "Accepted connection from $peerId")
                } else {
                    connection.cleanup()
                }
            } catch (e: Exception) {
                Log.e(tag, "Error handling incoming connection", e)
            }
        }
    }

    private suspend fun handleReceivedData(peerId: String, data: String) {
        try {
            Log.d(tag, "Received data from $peerId: ${data.take(50)}")

            // Check if it's an identity packet first
            if (data.startsWith("ID:")) {
                val userId = data.removePrefix("ID:")
                connections[peerId]?.remoteUserId = userId
                updateConnectedPeers()
                Log.i(tag, "Peer $peerId identified as $userId")
                return
            }

            try {
                val packet = DeltaSyncPacket.fromJson(data)
                deltaSyncHandler?.receiveDeltaFromPeer(packet)
            } catch (e: Exception) {
                Log.w(tag, "Received data is not a valid DeltaSyncPacket, skipping", e)
            }
        } catch (e: Exception) {
            Log.e(tag, "Error processing received data from $peerId", e)
        }
    }

    private suspend fun handlePeerDisconnected(peerId: String) {
        withContext(Dispatchers.Main) {
            connections.remove(peerId)?.cleanup()
            deltaSyncHandler?.onPeerDisconnected(peerId)
            updateConnectedPeers()
            Log.d(tag, "Peer disconnected: $peerId")
        }
    }

    private fun updateConnectedPeers() {
        _connectedPeers.value = connections.mapValues { it.value.remoteUserId ?: "Unknown (${it.key.takeLast(5)})" }
    }

    @SuppressLint("MissingPermission")
    suspend fun sendDataToPeer(peerId: String, data: String): Boolean {
        return withContext(Dispatchers.IO) {
            val connection = connections[peerId] ?: return@withContext false
            connection.sendData(data)
        }
    }

    fun cleanup() {
        scope.cancel()
        bleMeshManager.cleanup()
        connections.values.forEach { it.cleanup() }
    }
}
