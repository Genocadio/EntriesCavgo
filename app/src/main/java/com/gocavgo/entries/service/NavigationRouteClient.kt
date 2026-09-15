package com.gocavgo.entries.service

import android.util.Log
import com.gocavgo.entries.model.CalcRouteRequest
import com.gocavgo.entries.model.CalcRouteResult
import com.gocavgo.entries.model.CalcWaypoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP client for the Cavgo Navigation service route calculation API.
 * Calls POST /api/routes/calculate with waypoints and returns route metrics.
 */
class NavigationRouteClient(private val baseUrl: String) {

    companion object {
        private const val TAG = "NavRouteClient"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Calculate a route between waypoints.
     * @param waypoints List of lat/lng pairs (minimum 2)
     * @param includeInstructions Whether to include turn-by-turn instructions
     * @return CalcRouteResult with polyline, distance, duration, or null on failure
     */
    suspend fun calculateRoute(
        waypoints: List<Pair<Double, Double>>,
        includeInstructions: Boolean = false
    ): CalcRouteResult? = withContext(Dispatchers.IO) {
        if (waypoints.size < 2) {
            Log.w(TAG, "Need at least 2 waypoints, got ${waypoints.size}")
            return@withContext null
        }

        try {
            val request = CalcRouteRequest(
                waypoints = waypoints.map { (lat, lon) -> CalcWaypoint(latitude = lat, longitude = lon) },
                includeInstructions = includeInstructions
            )

            val jsonBody = json.encodeToString(request)
            Log.d(TAG, "Calculating route with ${waypoints.size} waypoints")

            val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
            val httpRequest = Request.Builder()
                .url("${baseUrl}/routes/calculate")
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .build()

            val response = httpClient.newCall(httpRequest).execute()
            response.use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@withContext null
                    val result = json.decodeFromString<CalcRouteResult>(body)
                    val route = result.route
                    if (route != null) {
                        Log.d(TAG, "Route calculated: ${route.totalDistance}m, ${route.totalDuration}s, ${route.polyline.size} points")
                    } else {
                        Log.w(TAG, "Route response has no route data")
                    }
                    result
                } else {
                    val errorBody = resp.body?.string() ?: "Unknown error"
                    Log.e(TAG, "Route calculation failed: HTTP ${resp.code} - $errorBody")
                    null
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network error during route calculation: ${e.message}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error during route calculation: ${e.message}")
            null
        }
    }
}
