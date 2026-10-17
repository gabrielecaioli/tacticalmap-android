package com.example.myapplication.logic.map

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import androidx.work.ListenableWorker.Result as WorkResult
import com.example.myapplication.R
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy
import kotlin.math.cos
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class MapDownloadWorker(
    context: Context,
    parameters: WorkerParameters
) : CoroutineWorker(context, parameters) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val TAG = "MapDownloadWorker"
        const val CHANNEL_ID = "mission_downloads"
        const val NOTIFICATION_ID = 101
        const val FINAL_NOTIFICATION_ID = 102
        const val KEY_LAT = "LAT"
        const val KEY_LON = "LON"
        const val KEY_MISSION_ID = "MISSION_ID"
        const val KEY_PROGRESS = "PROGRESS"
        const val KEY_ERRORS = "ERRORS"
    }

    override suspend fun doWork(): WorkResult {
        val lat = inputData.getDouble(KEY_LAT, 0.0)
        val lon = inputData.getDouble(KEY_LON, 0.0)

        if (lat == 0.0 && lon == 0.0) return WorkResult.failure()

        createNotificationChannel()

        // Task 1.1: Start foreground notification (without progress bar)
        setForeground(getForegroundInfo())

        val center = GeoPoint(lat, lon)
        
        // Fix: MapView must be created on Main thread
        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            suspendCancellableCoroutine<WorkResult> { continuation ->
                val boundingBox = calculateBoundingBox(center, 1000.0) // Kept at 1km as requested
                val customPolicy = TileSourcePolicy(
                    2,
                    TileSourcePolicy.FLAG_USER_AGENT_MEANINGFUL or 
                    TileSourcePolicy.FLAG_USER_AGENT_NORMALIZED
                )
                val customSource = XYTileSource(
                    "Mapnik", 0, 19, 256, ".png",
                    arrayOf("https://tile.openstreetmap.org/"),
                    "© OpenStreetMap contributors",
                    customPolicy
                )
                val mapView = MapView(applicationContext)
                mapView.setTileSource(customSource)

                // Ensure we use a specific archive file for the mission
                val basePath = org.osmdroid.config.Configuration.getInstance().osmdroidBasePath
                val archiveFile = java.io.File(basePath, "archives/tactical_area.sqlite")
                archiveFile.parentFile?.mkdirs()

                val writer = try {
                    org.osmdroid.tileprovider.modules.SqliteArchiveTileWriter(archiveFile.absolutePath)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create SqliteArchiveTileWriter", e)
                    null
                }

                val cacheManager = writer?.let { CacheManager(mapView, it) } ?: CacheManager(mapView)

                cacheManager.downloadAreaAsyncNoUI(applicationContext, boundingBox, 4, 19, object : CacheManager.CacheManagerCallback {
                    override fun onTaskComplete() {
                        Log.i(TAG, "Download complete!")
                        writer?.onDetach()
                        mapView.onDetach() // Release resources
                        if (continuation.isActive) {
                            continuation.resume(WorkResult.success())
                        }
                    }

                    override fun onTaskFailed(errors: Int) {
                        Log.e(TAG, "Download failed with $errors errors")
                        writer?.onDetach()
                        mapView.onDetach() // Release resources
                        if (continuation.isActive) {
                            if (errors > 0) {
                                continuation.resume(WorkResult.failure(workDataOf(KEY_ERRORS to errors)))
                            } else {
                                continuation.resume(WorkResult.success())
                            }
                        }
                    }

                    override fun updateProgress(progress: Int, total: Int, currentZoomLevel: Int, zoomLevelMax: Int) {
                        val percentage = if (total > 0) (progress * 100 / total) else 0
                        setProgressAsync(workDataOf(KEY_PROGRESS to percentage))
                    }

                    override fun downloadStarted() {
                        Log.i(TAG, "Download started")
                    }

                    override fun setPossibleTilesInArea(total: Int) {
                        Log.i(TAG, "Total tiles: $total")
                    }
                })

                continuation.invokeOnCancellation {
                    Log.i(TAG, "Worker cancelled")
                }
            }
        }

        // Task 2.6: Call final notification before returning
        showFinalNotification(result == WorkResult.success())

        return result
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, createForegroundNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, createForegroundNotification())
        }
    }

    private fun createForegroundNotification(): Notification {
        val title = "Download in progress... please wait"
        val cancelIntent = WorkManager.getInstance(applicationContext)
            .createCancelPendingIntent(id)

        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setTicker(title)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    // Task 2: Final Notification
    private fun showFinalNotification(isSuccess: Boolean) {
        val title = if (isSuccess) "Tactical Map Ready" else "Map Download Error"
        val text = if (isSuccess) "The AO download completed successfully" else "Unable to download the entire area"
        
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
            
        // Task 2.3: Use FINAL_NOTIFICATION_ID (distinct from NOTIFICATION_ID)
        notificationManager.notify(FINAL_NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Mission Downloads",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for the tactical map download"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun calculateBoundingBox(center: GeoPoint, radiusInMeters: Double): org.osmdroid.util.BoundingBox {
        val latDegreeDistance = 111320.0
        val lonDegreeDistance = 111320.0 * cos(Math.toRadians(center.latitude))
        
        val latDiff = radiusInMeters / latDegreeDistance
        val lonDiff = radiusInMeters / lonDegreeDistance
        
        return org.osmdroid.util.BoundingBox(
            center.latitude + latDiff,
            center.longitude + lonDiff,
            center.latitude - latDiff,
            center.longitude - lonDiff
        )
    }
}
