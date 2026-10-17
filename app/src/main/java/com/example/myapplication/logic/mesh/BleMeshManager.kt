package com.example.myapplication.logic.mesh

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.example.myapplication.data.local.entities.TrackingStatus
import com.example.myapplication.logic.policy.PolicyEngine
import kotlinx.coroutines.*
import java.util.*

/**
 * Handles BLE Advertising and Scanning for low-power peer discovery.
 * Integrated with PolicyEngine to adjust intervals based on battery and status.
 */
class BleMeshManager(
    context: Context,
    private val policyEngine: PolicyEngine,
    private val onPeerDiscovered: (BluetoothDevice) -> Unit,
) {
    private val tag = "BleMeshManager"
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        context.getSystemService(BluetoothManager::class.java)?.adapter
    }
    private val bleScanner: BluetoothLeScanner? by lazy { bluetoothAdapter?.bluetoothLeScanner }
    private val bleAdvertiser: BluetoothLeAdvertiser? by lazy { bluetoothAdapter?.bluetoothLeAdvertiser }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var job: Job? = null

    private val meshServiceUuid = UUID.fromString("b83e69f8-5c1d-4b3f-b7c9-7e8a5f6d4c3b")
    
    // Tracking current state to avoid redundant restarts
    private var isAdvertising = false
    private var isScanning = false

    fun start() {
        if (bluetoothAdapter == null || (bluetoothAdapter != null && !bluetoothAdapter!!.isEnabled)) {
            Log.e(tag, "Bluetooth not available or disabled")
            return
        }

        job = scope.launch {
            policyEngine.policyState.collect { state ->
                updateBleBehavior(state.isBatteryLow, state.trackingStatus)
            }
        }
    }

    private fun updateBleBehavior(isBatteryLow: Boolean, status: TrackingStatus) {
        stopBleDiscovery()
        
        // Determine intervals based on policy
        val (scanMode, advertiseMode) = when {
            status == TrackingStatus.DANGER -> {
                // Aggressive mode for emergencies
                ScanSettings.SCAN_MODE_LOW_LATENCY to AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
            }
            isBatteryLow -> {
                // Power save mode
                ScanSettings.SCAN_MODE_LOW_POWER to AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
            }
            else -> {
                // Standard mode
                ScanSettings.SCAN_MODE_BALANCED to AdvertiseSettings.ADVERTISE_MODE_BALANCED
            }
        }

        startAdvertising(advertiseMode)
        startScanning(scanMode)
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising(mode: Int) {
        val advertiser = bleAdvertiser ?: return
        
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(mode)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()

        // Pack small amount of data: Service UUID + Manufacturer data (Status)
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(meshServiceUuid))
            .build()

        advertiser.startAdvertising(settings, data, advertiseCallback)
        isAdvertising = true
        Log.d(tag, "BLE Advertising started in mode $mode")
    }

    @SuppressLint("MissingPermission")
    private fun startScanning(scanMode: Int) {
        val scanner = bleScanner ?: return

        val settings = ScanSettings.Builder()
            .setScanMode(scanMode)
            .build()

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(meshServiceUuid))
            .build()

        scanner.startScan(listOf(filter), settings, scanCallback)
        isScanning = true
        Log.d(tag, "BLE Scanning started")
    }

    @SuppressLint("MissingPermission")
    fun stopBleDiscovery() {
        if (isAdvertising) {
            bleAdvertiser?.stopAdvertising(advertiseCallback)
            isAdvertising = false
        }
        if (isScanning) {
            bleScanner?.stopScan(scanCallback)
            isScanning = false
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            super.onStartSuccess(settingsInEffect)
            Log.i(tag, "BLE Advertise success")
        }

        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            Log.e(tag, "BLE Advertise failed: $errorCode")
            isAdvertising = false
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            Log.d(tag, "BLE Device discovered: ${result.device.address}")
            onPeerDiscovered(result.device)
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            super.onBatchScanResults(results)
            results.forEach { onPeerDiscovered(it.device) }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e(tag, "BLE Scan failed: $errorCode")
            isScanning = false
        }
    }

    fun cleanup() {
        stopBleDiscovery()
        job?.cancel()
        scope.cancel()
    }
}
