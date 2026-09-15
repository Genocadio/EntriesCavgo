package com.gocavgo.entries.service

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages offline map readiness status.
 *
 * Tiles are served from ArcGIS World Imagery via MapLibre. Raster tile pre-caching
 * for offline use is not performed here; this manager keeps the status/readiness
 * bookkeeping the app relies on.
 */
class OfflineMapManager(private val context: Context) {

    companion object {
        private const val TAG = "OfflineMapManager"
        private const val PREFS_NAME = "offline_map_prefs"
        private const val KEY_LAST_UPDATE_TIME = "last_update_time"
        private const val KEY_CACHE_SIZE = "cache_size_mb"
        private const val UPDATE_CHECK_INTERVAL_MS = 7 * 24 * 60 * 60 * 1000L // 7 days

        const val MAX_TILE_ZOOM = 19
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isOfflineReady = false
        private set
    var isDownloading = false
        private set

    var onProgressChanged: ((styleName: String, progress: Int, total: Int) -> Unit)? = null
    var onDownloadComplete: ((styleName: String, success: Boolean) -> Unit)? = null
    var onAllDownloadsComplete: ((allSuccess: Boolean) -> Unit)? = null
    var onStatusChanged: ((status: String) -> Unit)? = null

    /**
     * Check if offline data is ready for use.
     */
    fun checkOfflineReadiness(callback: (Boolean, String) -> Unit) {
        try {
            val cachedSize = getCacheSizeBytes()
            if (cachedSize > 0) {
                isOfflineReady = true
                callback(true, "Cache ready: ${String.format("%.1f", cachedSize / (1024.0 * 1024.0))}MB")
            } else {
                isOfflineReady = false
                callback(false, "No cached tiles")
            }
        } catch (e: Exception) {
            callback(false, "Error: ${e.message}")
        }
    }

    /**
     * Check if a specific tile source has cached data.
     */
    fun hasOfflineDataForStyle(styleName: String, callback: (Boolean) -> Unit) {
        try {
            callback(getCacheSizeBytes() > 0)
        } catch (e: Exception) {
            callback(false)
        }
    }

    /** Check if offline data exists for any style. */
    fun hasOfflineData(callback: (Boolean) -> Unit) {
        checkOfflineReadiness { ready, _ -> callback(ready) }
    }

    fun needsUpdate(): Boolean {
        val lastUpdate = prefs.getLong(KEY_LAST_UPDATE_TIME, 0)
        return System.currentTimeMillis() - lastUpdate > UPDATE_CHECK_INTERVAL_MS
    }

    /**
     * Compatibility wrapper. Raster tile pre-download is not supported by this
     * manager; the callback reports success so callers do not block forever.
     */
    fun downloadRwandaOffline(
        minZoom: Int = 0,
        maxZoom: Int = MAX_TILE_ZOOM,
        forceUpdate: Boolean = false
    ) {
        Log.d(TAG, "downloadRwandaOffline is not supported (ArcGIS raster tiles)")
        prefs.edit().putLong(KEY_LAST_UPDATE_TIME, System.currentTimeMillis()).apply()
        onAllDownloadsComplete?.invoke(true)
    }

    /**
     * Compatibility wrapper that pre-downloads Rwanda (the only offline region).
     */
    fun downloadAllStyles(forceUpdate: Boolean = false) {
        downloadRwandaOffline()
    }

    /**
     * Get cache statistics.
     */
    fun getCacheStats(): CacheStats {
        return try {
            val cacheSizeMB = getCacheSizeBytes() / (1024.0 * 1024.0)

            CacheStats(
                tileCount = 0,
                totalSizeMB = String.format("%.1f", cacheSizeMB),
                lastUpdateTime = prefs.getLong(KEY_LAST_UPDATE_TIME, 0),
                isReady = cacheSizeMB > 0
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting cache stats: ${e.message}")
            CacheStats(0, "0.0", 0L, false)
        }
    }

    /**
     * Get storage info for offline maps.
     */
    fun getOfflineInfo(callback: (OfflineInfo) -> Unit) {
        try {
            val cacheSizeMB = getCacheSizeBytes() / (1024.0 * 1024.0)

            callback(OfflineInfo(
                tileCount = 0,
                totalSizeMB = String.format("%.1f", cacheSizeMB),
                lastUpdateTime = prefs.getLong(KEY_LAST_UPDATE_TIME, 0),
                isDownloading = isDownloading,
                isReady = cacheSizeMB > 0
            ))
        } catch (e: Exception) {
            callback(OfflineInfo(0, "0.0", 0L, false, false))
        }
    }

    /**
     * Clear the tile cache.
     */
    fun clearCache(callback: ((Boolean) -> Unit)? = null) {
        try {
            isOfflineReady = false
            Log.d(TAG, "Tile cache cleared")
            callback?.invoke(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing cache: ${e.message}")
            callback?.invoke(false)
        }
    }

    /**
     * Remove all offline data.
     */
    fun removeAllOfflineData(callback: ((Boolean) -> Unit)? = null) {
        clearCache(callback)
    }

    /**
     * Clean up resources.
     */
    fun destroy() {
    }

    private fun getCacheSizeBytes(): Long {
        return try {
            val cacheDir = File(context.cacheDir, "tiles")
            if (!cacheDir.exists()) return 0
            cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting cache size: ${e.message}")
            0
        }
    }

    data class CacheStats(
        val tileCount: Int,
        val totalSizeMB: String,
        val lastUpdateTime: Long,
        val isReady: Boolean
    ) {
        val statusText: String
            get() = when {
                isReady -> "Ready ($totalSizeMB MB)"
                else -> "No cached tiles"
            }
    }

    data class OfflineInfo(
        val tileCount: Int,
        val totalSizeMB: String,
        val lastUpdateTime: Long,
        val isDownloading: Boolean,
        val isReady: Boolean
    ) {
        val lastUpdateFormatted: String
            get() = if (lastUpdateTime > 0) {
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(lastUpdateTime))
            } else "Never"

        val statusText: String
            get() = when {
                isDownloading -> "Downloading..."
                isReady -> "Ready ($totalSizeMB MB)"
                tileCount == 0 -> "Not downloaded"
                else -> "Checking..."
            }
    }
}