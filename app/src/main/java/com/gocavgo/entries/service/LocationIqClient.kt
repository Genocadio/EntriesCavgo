package com.gocavgo.entries.service

import android.util.Log
import com.gocavgo.entries.model.LocationIqResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP client for LocationIQ API.
 * Supports autocomplete, forward search, and reverse geocoding.
 */
class LocationIqClient(private val apiKey: String) {

    companion object {
        private const val TAG = "LocationIqClient"
        private const val BASE_URL = "https://api.locationiq.com/v1"
    }

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Autocomplete search — for typeahead in search bars.
     * @param query The search string
     * @param limit Max results (1-20, default 10)
     * @param countryCodes Optional comma-separated ISO country codes to filter (e.g. "rw")
     */
    suspend fun autocomplete(
        query: String,
        limit: Int = 10,
        countryCodes: String? = null
    ): List<LocationIqResult> = withContext(Dispatchers.IO) {
        try {
            val urlBuilder = "$BASE_URL/autocomplete".toHttpUrl().newBuilder()
                .addQueryParameter("key", apiKey)
                .addQueryParameter("q", query)
                .addQueryParameter("limit", limit.toString())
                .addQueryParameter("format", "json")

            countryCodes?.let { urlBuilder.addQueryParameter("countrycodes", it) }

            val request = Request.Builder()
                .url(urlBuilder.build())
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            response.use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: "[]"
                    val results = json.decodeFromString<List<LocationIqResult>>(body)
                    Log.d(TAG, "Autocomplete: ${results.size} results for '$query'")
                    results
                } else {
                    Log.e(TAG, "Autocomplete failed: HTTP ${resp.code}")
                    emptyList()
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network error during autocomplete: ${e.message}")
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error during autocomplete: ${e.message}")
            emptyList()
        }
    }

    /**
     * Forward search / geocoding — for full-text address search.
     * @param query The address or place name to search
     * @param limit Max results (default 10)
     * @param countryCodes Optional comma-separated ISO country codes
     */
    suspend fun search(
        query: String,
        limit: Int = 10,
        countryCodes: String? = null
    ): List<LocationIqResult> = withContext(Dispatchers.IO) {
        try {
            val urlBuilder = "$BASE_URL/search".toHttpUrl().newBuilder()
                .addQueryParameter("key", apiKey)
                .addQueryParameter("q", query)
                .addQueryParameter("format", "json")
                .addQueryParameter("limit", limit.toString())
                .addQueryParameter("addressdetails", "1")

            countryCodes?.let { urlBuilder.addQueryParameter("countrycodes", it) }

            val request = Request.Builder()
                .url(urlBuilder.build())
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            response.use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: "[]"
                    val results = json.decodeFromString<List<LocationIqResult>>(body)
                    Log.d(TAG, "Search: ${results.size} results for '$query'")
                    results
                } else {
                    Log.e(TAG, "Search failed: HTTP ${resp.code}")
                    emptyList()
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network error during search: ${e.message}")
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error during search: ${e.message}")
            emptyList()
        }
    }

    /**
     * Reverse geocoding — convert coordinates to address.
     * @param lat Latitude
     * @param lon Longitude
     * @param normalizeAddress If true, returns predictable address components
     */
    suspend fun reverseGeocode(
        lat: Double,
        lon: Double,
        normalizeAddress: Boolean = true
    ): LocationIqResult? = withContext(Dispatchers.IO) {
        try {
            val urlBuilder = "$BASE_URL/reverse".toHttpUrl().newBuilder()
                .addQueryParameter("key", apiKey)
                .addQueryParameter("lat", lat.toString())
                .addQueryParameter("lon", lon.toString())
                .addQueryParameter("format", "json")
                .addQueryParameter("addressdetails", "1")

            if (normalizeAddress) {
                urlBuilder.addQueryParameter("normalizeaddress", "1")
            }

            val request = Request.Builder()
                .url(urlBuilder.build())
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            response.use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@withContext null
                    val result = json.decodeFromString<LocationIqResult>(body)
                    Log.d(TAG, "Reverse geocode: ${result.displayName}")
                    result
                } else {
                    Log.e(TAG, "Reverse geocode failed: HTTP ${resp.code}")
                    null
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network error during reverse geocode: ${e.message}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error during reverse geocode: ${e.message}")
            null
        }
    }
}
