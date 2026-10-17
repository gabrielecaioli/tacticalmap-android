package com.example.myapplication.logic.map

import android.content.Context
import android.util.Log
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.modules.SqliteArchiveTileWriter
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import java.io.File
import kotlin.math.cos

class TacticalMapDownloader(private val context: Context) {

    private val tag = "TacticalMapDownloader"

    /**
     * Downloads tiles for a 5km radius around the center point.
     * Zoom levels: 12 to 18.
     */
    fun downloadTacticalArea(center: GeoPoint, onProgress: (Int, Int) -> Unit, onComplete: () -> Unit) {
        val boundingBox = calculateBoundingBox(center, 5000.0) // 5km
        
        Log.i(tag, "Starting download for area: $boundingBox")
        
        // OSM policies are strict about bulk downloading. 
        // We define a custom policy to bypass FLAG_NO_BULK, 
        // but it's important to keep User-Agent meaningful and normalized.
        val customPolicy = TileSourcePolicy(
            2, // max concurrent downloads
            TileSourcePolicy.FLAG_USER_AGENT_MEANINGFUL or 
            TileSourcePolicy.FLAG_USER_AGENT_NORMALIZED
        )

        val customSource = XYTileSource(
            "Mapnik", 0, 19, 256, ".png",
            arrayOf("https://tile.openstreetmap.org/"),
            "© OpenStreetMap contributors",
            customPolicy
        )

        val mapView = MapView(context)
        mapView.setTileSource(customSource)
        
        // Define separate archive file in the osmdroid/archives directory
        val basePath = Configuration.getInstance().osmdroidBasePath
        val archiveFile = File(basePath, "archives/tactical_area.sqlite")
        
        // Ensure directory exists
        archiveFile.parentFile?.mkdirs()
        
        val writer = try {
            SqliteArchiveTileWriter(archiveFile.absolutePath)
        } catch (e: Exception) {
            Log.e(tag, "Failed to create SqliteArchiveTileWriter at ${archiveFile.absolutePath}", e)
            null
        }

        val cacheManager = writer?.let { CacheManager(mapView, it) } ?: CacheManager(mapView)
        
        cacheManager.downloadAreaAsyncNoUI(context, boundingBox, 12, 18, object : CacheManager.CacheManagerCallback {
            override fun onTaskComplete() {
                Log.i(tag, "Download complete!")
                writer?.onDetach()
                mapView.onDetach()
                onComplete()
            }

            override fun onTaskFailed(errors: Int) {
                Log.e(tag, "Download failed with $errors errors")
                writer?.onDetach()
                mapView.onDetach()
                onComplete() // Complete anyway to hide status
            }

            override fun updateProgress(progress: Int, total: Int, currentZoomLevel: Int, zoomLevelMax: Int) {
                onProgress(progress, total)
            }

            override fun downloadStarted() {
                Log.i(tag, "Download started")
            }

            override fun setPossibleTilesInArea(total: Int) {
                Log.i(tag, "Total tiles to download: $total")
            }
        })
    }

    /**
     * Calculates a BoundingBox that circunscribes a radius (in meters) around a center point.
     */
    private fun calculateBoundingBox(center: GeoPoint, radiusInMeters: Double): BoundingBox {
        val latDegreeDistance = 111320.0 // meters per degree of latitude
        val lonDegreeDistance = 111320.0 * cos(Math.toRadians(center.latitude))
        
        val latDiff = radiusInMeters / latDegreeDistance
        val lonDiff = radiusInMeters / lonDegreeDistance
        
        return BoundingBox(
            center.latitude + latDiff,
            center.longitude + lonDiff,
            center.latitude - latDiff,
            center.longitude - lonDiff
        )
    }
}
