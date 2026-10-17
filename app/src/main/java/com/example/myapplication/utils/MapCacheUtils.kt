package com.example.myapplication.utils

import android.content.Context
import android.util.Log
import org.osmdroid.config.Configuration
import java.io.File

object MapCacheUtils {
    private const val TAG = "MapCacheUtils"

    /**
     * Clears all map data including the SQL cache (tiles folder) and downloaded archives.
     */
    fun clearAllMapData(context: Context) {
        try {
            val configuration = Configuration.getInstance()
            val basePath = configuration.osmdroidBasePath
            
            // 1. Clear tiles directory (SQL cache)
            val tilesDir = configuration.osmdroidTileCache
            if (tilesDir.exists()) {
                deleteRecursive(tilesDir)
                Log.i(TAG, "Tiles cache cleared at ${tilesDir.absolutePath}")
            }

            // 2. Clear archives directory (Downloaded .sqlite files)
            val archivesDir = File(basePath, "archives")
            if (archivesDir.exists()) {
                deleteRecursive(archivesDir)
                Log.i(TAG, "Archives cleared at ${archivesDir.absolutePath}")
            }
            
            // Re-ensure directories exist for future use
            tilesDir.mkdirs()
            archivesDir.mkdirs()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing map data", e)
        }
    }

    private fun deleteRecursive(fileOrDirectory: File) {
        if (fileOrDirectory.isDirectory) {
            fileOrDirectory.listFiles()?.forEach { child ->
                deleteRecursive(child)
            }
        }
        fileOrDirectory.delete()
    }
}
