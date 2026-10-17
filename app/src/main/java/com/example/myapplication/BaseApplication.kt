package com.example.myapplication

import android.app.Application
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.local.TrackingRepository
import com.example.myapplication.data.local.CloudMeshSyncManager
import com.example.myapplication.logic.policy.PolicyEngine
import org.osmdroid.config.Configuration

class BaseApplication : Application() {
    val database by lazy { AppDatabase.getDatabase(this) }
    val repository by lazy { TrackingRepository(database.trackingDao()) }
    val policyEngine = PolicyEngine()
    val cloudMeshSyncManager by lazy { CloudMeshSyncManager(repository) }

    // Mesh sync components
    var p2pMeshManager: com.example.myapplication.logic.mesh.P2pMeshManager? = null
    var deltaSyncHandler: com.example.myapplication.logic.mesh.DeltaSyncHandler? = null

    override fun onCreate() {
        super.onCreate()
        // Initialize osmdroid configuration
        val config = Configuration.getInstance()
        config.load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        config.userAgentValue = "MyTacticalApp/1.0 (Android; $packageName)"

        // Use internal storage for osmdroid files to ensure reliability and avoid permission issues
        val basePath = java.io.File(filesDir, "osmdroid")
        config.osmdroidBasePath = basePath
        config.osmdroidTileCache = java.io.File(basePath, "tiles")
    }
}
