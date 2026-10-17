package com.example.myapplication.logic.mesh

import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.*
import java.io.*

class BluetoothP2pConnection(
    private val socket: BluetoothSocket,
    private val onDataReceived: suspend (String) -> Unit,
    private val onDisconnected: suspend () -> Unit
) {
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var readJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    enum class ConnectionState { CONNECTING, CONNECTED, DISCONNECTED, ERROR }

    private var _state: ConnectionState = ConnectionState.CONNECTING

    var remoteUserId: String? = null

    val state: ConnectionState
        get() = _state

    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            if (!socket.isConnected) {
                socket.connect()
            }
            inputStream = socket.inputStream
            outputStream = socket.outputStream
            _state = ConnectionState.CONNECTED
            startReadLoop()
            true
        } catch (e: Exception) {
            _state = ConnectionState.ERROR
            false
        }
    }

    private fun startReadLoop() {
        readJob = scope.launch {
            try {
                val reader = inputStream?.bufferedReader() ?: return@launch
                while (isActive && _state == ConnectionState.CONNECTED) {
                    val message = reader.readLine() ?: break
                    onDataReceived(message)
                }
            } catch (e: IOException) {
                if (_state == ConnectionState.CONNECTED) {
                    _state = ConnectionState.ERROR
                    disconnect()
                }
            }
        }
    }

    suspend fun sendData(data: String): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val writer = outputStream?.bufferedWriter() ?: return@withContext false
            writer.write(data)
            writer.newLine()
            writer.flush()
            true
        } catch (e: Exception) {
            _state = ConnectionState.ERROR
            false
        }
    }

    suspend fun disconnect() {
        _state = ConnectionState.DISCONNECTED
        readJob?.cancel()
        try {
            inputStream?.close()
            outputStream?.close()
            socket.close()
        } catch (e: Exception) {
            // Ignore close errors
        }
        onDisconnected()
    }

    fun cleanup() {
        scope.cancel()
    }
}
