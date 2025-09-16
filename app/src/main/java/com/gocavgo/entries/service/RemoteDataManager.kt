package com.gocavgo.entries.service

import android.util.Log
import com.gocavgo.entries.dataclass.PaginationInfo
import com.gocavgo.entries.hereroutes.DbPlace
import com.gocavgo.entries.hereroutes.FinalRoute
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import com.gocavgo.entries.dataclass.SavePlaceRequest
import com.gocavgo.entries.dataclass.SavePlaceResponse
import com.gocavgo.entries.dataclass.SaveRouteRequest
import com.gocavgo.entries.dataclass.SaveRouteResponse
import com.gocavgo.entries.dataclass.SaveRouteWaypoint
import com.gocavgo.entries.hereroutes.WaypointWithPrice
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrl
import com.gocavgo.entries.dataclass.PaginatedLocationsResponse
import com.gocavgo.entries.dataclass.PaginatedRoutesResponse

/**
 * Manages remote operations for places and routes.
 * Currently logs operations but designed to integrate with REST API later.
 */
class RemoteDataManager {

    companion object {
        private const val TAG = "RemoteDataManager"
        private const val BASE_URL = "https://api.gocavgo.com/api/navig/"

        // Singleton instance
        @Volatile
        private var INSTANCE: RemoteDataManager? = null

        fun getInstance(): RemoteDataManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: RemoteDataManager().also { INSTANCE = it }
            }
        }
    }

    // Coroutine scope for async operations
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val httpClient: OkHttpClient by lazy {
        val loggingInterceptor = HttpLoggingInterceptor { message ->
            Log.d("OkHttp", message)
        }.apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /**
     * Save a place to remote storage
     */
    suspend fun savePlace(dbPlace: DbPlace): RemoteResult<SavePlaceResponse> {
        Log.d(TAG, "=== SAVING PLACE TO REMOTE ===")
        Log.d(
            TAG,
            "Place details: ${dbPlace.placeName}, ${dbPlace.latitude}, ${dbPlace.longitude}, ID: ${dbPlace.id}, isEdited: ${dbPlace.edited}, placeId: ${dbPlace.placeId}"
        )
        if (!dbPlace.id.isNullOrBlank()) {
            if(dbPlace.edited) {
                try{
                    Log.d(TAG, "Place already has an ID and is edited, updating place...")
                    return  updatePlace(dbPlace.id!!, dbPlace)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update place: ${e.message}", e)
                    return RemoteResult.Error("Failed to update place: ${e.message}")
                }

            }
        } else {
            return withContext(Dispatchers.IO) {
                try {
                    // Check if place already has an ID
                    if (!dbPlace.id.isNullOrBlank()) {
                        Log.d(TAG, "Place already has an ID, skipping save")
                        throw IllegalArgumentException("Place already has an ID: ${dbPlace.id}")
                    }
                    // Create request payload
                    val saveRequest = SavePlaceRequest(
                        latitude = dbPlace.latitude,
                        longitude = dbPlace.longitude,
                        google_place_name = dbPlace.placeName,
                        custom_name = dbPlace.customName,
                        province = dbPlace.province,
                        district = dbPlace.customDistrict,
                        place_id = dbPlace.placeId
                    )

                    val jsonBody = json.encodeToString(saveRequest)
                    Log.d(TAG, "=== SAVING PLACE TO REMOTE ===")
                    Log.d(TAG, "Request payload: $jsonBody")

                    // Create OkHttp request
                    val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
                    val request = Request.Builder()
                        .url("${BASE_URL}locations")
                        .post(requestBody)
                        .addHeader("Content-Type", "application/json")
                        .addHeader("Accept", "application/json")
                        .build()

                    // Execute request
                    val response = httpClient.newCall(request).execute()

                    response.use { resp ->
                        if (resp.isSuccessful) {
                            val responseBody = resp.body?.string() ?: ""
                            Log.d(TAG, "Response: $responseBody")

                            val saveResponse = json.decodeFromString<SavePlaceResponse>(responseBody)
                            Log.d(TAG, "Place saved successfully with ID: ${saveResponse.id}")
                            Log.d(TAG, "===============================")

                            RemoteResult.Success(saveResponse)
                        } else {
                            val errorBody = resp.body?.string() ?: "Unknown error"
                            Log.e(
                                TAG,
                                "Failed to save place. Response code: ${resp.code}, Error: $errorBody"
                            )
                            RemoteResult.Error("Failed to save place: HTTP ${resp.code} - $errorBody")
                        }
                    }

                } catch (e: IOException) {
                    Log.e(TAG, "Network error while saving place: ${e.message}", e)
                    RemoteResult.Error("Network error: ${e.message}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save place: ${e.message}", e)
                    RemoteResult.Error("Failed to save place: ${e.message}")
                }
            }
        }
        return RemoteResult.Error("Failed to save place")

    }

    /**
     * Update an existing place in remote storage
     */
    suspend fun updatePlace(placeId: String, dbPlace: DbPlace): RemoteResult<SavePlaceResponse> {
        Log.d(TAG, "=== UPDATING PLACE TO REMOTE ===")
        Log.d(TAG, "Place details: ${dbPlace.placeName}, ${dbPlace.latitude}, ${dbPlace.longitude}, ID: $placeId")
        return withContext(Dispatchers.IO) {
            try {
                // Create request payload with ID
                val updateRequest = SavePlaceRequest(
                    latitude = dbPlace.latitude,
                    longitude = dbPlace.longitude,
                    google_place_name = dbPlace.placeName,
                    custom_name = dbPlace.customName,
                    province = dbPlace.province,
                    district = dbPlace.customDistrict,
                    place_id = dbPlace.placeId
                )

                val jsonBody = json.encodeToString(updateRequest)
                Log.d(TAG, "Update request payload: $jsonBody")

                // Create OkHttp request
                val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("${BASE_URL}locations/$placeId")
                    .put(requestBody)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .build()

                // Execute request
                val response = httpClient.newCall(request).execute()

                response.use { resp ->
                    if (resp.isSuccessful) {
                        val responseBody = resp.body?.string() ?: ""
                        Log.d(TAG, "Update response: $responseBody")

                        val saveResponse = json.decodeFromString<SavePlaceResponse>(responseBody)
                        Log.d(TAG, "Place updated successfully with ID: ${saveResponse.id}")
                        Log.d(TAG, "===============================")

                        RemoteResult.Success(saveResponse)
                    } else {
                        val errorBody = resp.body?.string() ?: "Unknown error"
                        Log.e(TAG, "Failed to update place. Response code: ${resp.code}, Error: $errorBody")
                        RemoteResult.Error("Failed to update place: HTTP ${resp.code} - $errorBody")
                    }
                }

            } catch (e: IOException) {
                Log.e(TAG, "Network error while updating place: ${e.message}", e)
                RemoteResult.Error("Network error: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update place: ${e.message}", e)
                RemoteResult.Error("Failed to update place: ${e.message}")
            }
        }
    }

    /**
     * Delete a place from remote storage
     */
    suspend fun deletePlace(placeId: Int): RemoteResult<Unit> {
        Log.d(TAG, "=== DELETING PLACE FROM REMOTE ===")
        Log.d(TAG, "Place ID: $placeId")
        return withContext(Dispatchers.IO) {
            try {
                // Create OkHttp request
                val request = Request.Builder()
                    .url("${BASE_URL}locations/$placeId")
                    .delete()
                    .addHeader("Accept", "application/json")
                    .build()

                // Execute request
                val response = httpClient.newCall(request).execute()

                response.use { resp ->
                    if (resp.isSuccessful) {
                        Log.d(TAG, "Place deleted successfully with ID: $placeId")
                        Log.d(TAG, "===============================")
                        RemoteResult.Success(Unit)
                    } else {
                        val errorBody = resp.body?.string() ?: "Unknown error"
                        Log.e(TAG, "Failed to delete place. Response code: ${resp.code}, Error: $errorBody")
                        RemoteResult.Error("Failed to delete place: HTTP ${resp.code} - $errorBody")
                    }
                }

            } catch (e: IOException) {
                Log.e(TAG, "Network error while deleting place: ${e.message}", e)
                RemoteResult.Error("Network error: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete place: ${e.message}", e)
                RemoteResult.Error("Failed to delete place: ${e.message}")
            }
        }
    }

    /**
     * Delete a route from remote storage
     */
    suspend fun deleteRoute(routeId: Int): RemoteResult<Unit> {
        Log.d(TAG, "=== DELETING ROUTE FROM REMOTE ===")
        Log.d(TAG, "Route ID: $routeId")
        return withContext(Dispatchers.IO) {
            try {
                // Create OkHttp request
                val request = Request.Builder()
                    .url("${BASE_URL}routes/$routeId")
                    .delete()
                    .addHeader("Accept", "application/json")
                    .build()

                // Execute request
                val response = httpClient.newCall(request).execute()

                response.use { resp ->
                    if (resp.isSuccessful) {
                        Log.d(TAG, "Route deleted successfully with ID: $routeId")
                        Log.d(TAG, "===============================")
                        RemoteResult.Success(Unit)
                    } else {
                        val errorBody = resp.body?.string() ?: "Unknown error"
                        Log.e(TAG, "Failed to delete route. Response code: ${resp.code}, Error: $errorBody")
                        RemoteResult.Error("Failed to delete route: HTTP ${resp.code} - $errorBody")
                    }
                }

            } catch (e: IOException) {
                Log.e(TAG, "Network error while deleting route: ${e.message}", e)
                RemoteResult.Error("Network error: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete route: ${e.message}", e)
                RemoteResult.Error("Failed to delete route: ${e.message}")
            }
        }
    }


    /**
     * Get locations with optional search functionality and pagination
     * @param search Optional search term for filtering locations (can be custom name, Google place name, or location code)
     * @param page Page number (default: 1)
     * @param limit Number of items per page (default: 20)
     * @return RemoteResult containing paginated list of DbPlace objects with pagination info
     */
    suspend fun getLocations(
        search: String? = null,
        page: Int = 1,
        limit: Int = 20
    ): RemoteResult<PaginatedResult<List<DbPlace>>> {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "=== FETCHING LOCATIONS FROM REMOTE ===")

                // Build URL with optional search parameter and pagination
                val urlBuilder = "${BASE_URL}locations".toHttpUrl().newBuilder()
                search?.let { searchTerm ->
                    urlBuilder.addQueryParameter("search", searchTerm)
                    Log.d(TAG, "Search term: $searchTerm")
                }
                urlBuilder.addQueryParameter("page", page.toString())
                urlBuilder.addQueryParameter("limit", limit.toString())

                val url = urlBuilder.build()
                Log.d(TAG, "Request URL: $url")

                // Create OkHttp request
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .addHeader("Accept", "application/json")
                    .build()

                // Execute request
                val response = httpClient.newCall(request).execute()

                response.use { resp ->
                    if (resp.isSuccessful) {
                        val responseBody = resp.body?.string() ?: ""
                        Log.d(TAG, "Response: $responseBody")

                        val paginatedResponse =
                            json.decodeFromString<PaginatedLocationsResponse>(responseBody)

                        // Convert SavePlaceResponse objects to DbPlace objects
                        val dbPlaces = paginatedResponse.data.map { saveResponse ->
                            DbPlace.create(
                                placeName = saveResponse.google_place_name,
                                latitude = saveResponse.latitude,
                                longitude = saveResponse.longitude,
                                placeId = saveResponse.place_id,
                                customName = saveResponse.custom_name,
                                customDistrict = saveResponse.district
                            ).copy(
                                id = saveResponse.id.toString(),
                                code = saveResponse.code
                            )
                        }

                        Log.d(
                            TAG,
                            "Retrieved ${dbPlaces.size} locations (page $page of ${paginatedResponse.pagination.total_pages})"
                        )
                        Log.d(TAG, "Total locations: ${paginatedResponse.pagination.total}")
                        Log.d(TAG, "===============================")

                        val paginatedResult = PaginatedResult(
                            data = dbPlaces,
                            pagination = paginatedResponse.pagination
                        )

                        RemoteResult.Success(paginatedResult)
                    } else {
                        val errorBody = resp.body?.string() ?: "Unknown error"
                        Log.e(
                            TAG,
                            "Failed to fetch locations. Response code: ${resp.code}, Error: $errorBody"
                        )
                        RemoteResult.Error("Failed to fetch locations: HTTP ${resp.code} - $errorBody")
                    }
                }

            } catch (e: IOException) {
                Log.e(TAG, "Network error while fetching locations: ${e.message}", e)
                RemoteResult.Error("Network error: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch locations: ${e.message}", e)
                RemoteResult.Error("Failed to fetch locations: ${e.message}")
            }
        }
    }

    data class PaginatedResult<T>(
        val data: T,
        val pagination: PaginationInfo
    )


    /**
     * Save a route to remote storage - handles saving places first if needed
     */
    /**
     * Save a route to remote storage - handles saving places first if needed
     */
    /**
     * Save a route to remote storage - handles saving places first if needed
     */
    suspend fun saveRoute(finalRoute: FinalRoute): RemoteResult<SaveRouteResponse> {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "=== SAVING ROUTE TO REMOTE ===")
                Log.d(TAG, "Starting route save process...")

                val errors = mutableListOf<String>()

                // Step 1: Handle origin - only save if no ID exists
                val originWithId = if (finalRoute.origin.id.isNullOrBlank()) {
                    Log.d(TAG, "Origin has no ID, saving place first...")
                    when (val result = savePlace(finalRoute.origin)) {
                        is RemoteResult.Success -> {
                            finalRoute.origin.copy(id = result.data.id.toString())
                        }
                        is RemoteResult.Error -> {
                            Log.e(TAG, "Failed to save origin place: ${result.message}")
                            errors.add("Failed to save origin: ${result.message}")
                            null
                        }
                    }
                } else {
                    if (finalRoute.origin.edited){
                        when (val result = updatePlace(finalRoute.origin.id!!, finalRoute.origin)) {
                            is RemoteResult.Success -> {
                                Log.d(TAG, "Origin updated successfully with ID: ${result.data.id}")
                            }
                            is RemoteResult.Error -> {
                                Log.e(TAG, "Failed to update origin place: ${result.message}")
                                errors.add("Failed to update origin: ${result.message}")
                                return@withContext RemoteResult.Error("Failed to update origin: ${result.message}")
                            }
                        }
                    }
                    Log.d(TAG, "Origin already has ID: ${finalRoute.origin.id}, skipping save")
                    finalRoute.origin
                }

                // Step 2: Handle destination - only save if no ID exists
                val destinationWithId = if (finalRoute.destination.id.isNullOrBlank()) {
                    Log.d(TAG, "Destination has no ID, saving place first...")
                    when (val result = savePlace(finalRoute.destination)) {
                        is RemoteResult.Success -> {
                            finalRoute.destination.copy(id = result.data.id.toString())
                        }
                        is RemoteResult.Error -> {
                            Log.e(TAG, "Failed to save destination place: ${result.message}")
                            errors.add("Failed to save destination: ${result.message}")
                            null
                        }
                    }
                } else {
                    if (finalRoute.destination.edited){
                        when (val result = updatePlace(finalRoute.destination.id!!, finalRoute.destination)) {
                            is RemoteResult.Success -> {
                                Log.d(TAG, "Destination updated successfully with ID: ${result.data.id}")
                            }
                            is RemoteResult.Error -> {
                                Log.e(TAG, "Failed to update destination place: ${result.message}")
                                errors.add("Failed to update destination: ${result.message}")
                                return@withContext RemoteResult.Error("Failed to update destination: ${result.message}")
                            }
                        }
                    }
                    Log.d(TAG, "Destination already has ID: ${finalRoute.destination.id}, skipping save")
                    finalRoute.destination
                }

               // Step 3: Handle waypoints - first update those with IDs and edited, then save those without IDs
                Log.d(TAG, "=== STEP 3: HANDLE WAYPOINTS ===")
                val waypointsWithIds = mutableListOf<WaypointWithPrice>()

                // First pass: Handle waypoints WITH IDs that are edited (UPDATE operations)
                for ((index, waypoint) in finalRoute.waypoints.withIndex()) {
                    if (!waypoint.dbPlace.id.isNullOrBlank() && waypoint.dbPlace.edited) {
                        Log.d(TAG, "Waypoint $index has ID and is edited, updating place...")
                        when (val result = updatePlace(waypoint.dbPlace.id!!, waypoint.dbPlace)) {
                            is RemoteResult.Success -> {
                                waypointsWithIds.add(waypoint)
                                Log.d(TAG, "Waypoint $index updated successfully")
                            }
                            is RemoteResult.Error -> {
                                Log.e(TAG, "Failed to update waypoint $index: ${result.message}")
                                errors.add("Failed to update waypoint ${index + 1}: ${result.message}")
                            }
                        }
                    } else if (!waypoint.dbPlace.id.isNullOrBlank()) {
                        // Has ID but not edited - use as-is
                        Log.d(TAG, "Waypoint $index has ID but not edited, using as-is")
                        waypointsWithIds.add(waypoint)
                    }
                }

                // Second pass: Handle waypoints WITHOUT IDs (CREATE operations)
                for ((index, waypoint) in finalRoute.waypoints.withIndex()) {
                    if (waypoint.dbPlace.id.isNullOrBlank()) {
                        Log.d(TAG, "Waypoint $index has no ID, saving place first...")
                        when (val result = savePlace(waypoint.dbPlace)) {
                            is RemoteResult.Success -> {
                                val waypointWithId = WaypointWithPrice(
                                    dbPlace = waypoint.dbPlace.copy(id = result.data.id.toString()),
                                    price = waypoint.price
                                )
                                waypointsWithIds.add(waypointWithId)
                                Log.d(TAG, "Waypoint $index saved successfully with ID: ${result.data.id}")
                            }
                            is RemoteResult.Error -> {
                                Log.e(TAG, "Failed to save waypoint $index: ${result.message}")
                                errors.add("Failed to save waypoint ${index + 1}: ${result.message}")
                            }
                        }
                    }
                }

                // If there were any errors saving places, return early with combined error message
                if (errors.isNotEmpty()) {
                    val combinedError = "Route save failed due to location errors: ${errors.joinToString("; ")}"
                    Log.e(TAG, combinedError)
                    return@withContext RemoteResult.Error(combinedError)
                }

                // Verify all required locations have IDs
                if (originWithId?.id.isNullOrBlank()) {
                    return@withContext RemoteResult.Error("Origin location must have an ID")
                }
                if (destinationWithId?.id.isNullOrBlank()) {
                    return@withContext RemoteResult.Error("Destination location must have an ID")
                }

                // Step 4: Parse distance and duration from strings
                val distanceMeters = try {
                    val distanceValue = finalRoute.distance.replace(Regex("[^0-9.]"), "").toDoubleOrNull() ?: 0.0
                    if (finalRoute.distance.contains("km", ignoreCase = true)) {
                        (distanceValue * 1000).toInt()
                    } else {
                        distanceValue.toInt()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse distance: ${finalRoute.distance}, using 0")
                    0
                }

                val durationSeconds = try {
                    val durationStr = finalRoute.duration
                    var totalSeconds = 0

                    val hourMatch = Regex("(\\d+)\\s*h").find(durationStr)
                    hourMatch?.let { totalSeconds += it.groupValues[1].toInt() * 3600 }

                    val minMatch = Regex("(\\d+)\\s*min").find(durationStr)
                    minMatch?.let { totalSeconds += it.groupValues[1].toInt() * 60 }

                    val secMatch = Regex("(\\d+)\\s*s").find(durationStr)
                    secMatch?.let { totalSeconds += it.groupValues[1].toInt() }

                    if (totalSeconds == 0) {
                        val plainNumber = durationStr.replace(Regex("[^0-9.]"), "").toDoubleOrNull()
                        plainNumber?.let { totalSeconds = (it * 60).toInt() }
                    }

                    totalSeconds
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse duration: ${finalRoute.duration}, using 0")
                    0
                }

                // Step 5: Create the route save request
                val saveRequest = SaveRouteRequest(
                    name = finalRoute.routeName,
                    distance_meters = distanceMeters,
                    estimated_duration_seconds = durationSeconds,
                    origin_id = originWithId.id!!.toInt(),
                    destination_id = destinationWithId.id!!.toInt(),
                    route_price = finalRoute.routePrice,
                    city_route = finalRoute.isCityRoute,
                    waypoints = waypointsWithIds.mapIndexed { index, waypoint ->
                        SaveRouteWaypoint(
                            location_id = waypoint.dbPlace.id!!.toInt(),
                            order = index + 1,
                            price = if (waypoint.dbPlace.isPassThrough) null else waypoint.price,
                            is_pass_through = waypoint.dbPlace.isPassThrough
                        )
                    }
                )

                val jsonBody = json.encodeToString(saveRequest)
                Log.d(TAG, "Route request payload: $jsonBody")

                // Step 6: Send the route save request
                val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("${BASE_URL}routes")
                    .post(requestBody)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .build()

                val response = httpClient.newCall(request).execute()

                response.use { resp ->
                    if (resp.isSuccessful) {
                        val responseBody = resp.body?.string() ?: ""
                        Log.d(TAG, "Route save response: $responseBody")

                        val saveResponse = json.decodeFromString<SaveRouteResponse>(responseBody)
                        Log.d(TAG, "Route saved successfully with ID: ${saveResponse.id}")
                        Log.d(TAG, "===============================")

                        RemoteResult.Success(saveResponse)
                    } else {
                        val errorBody = resp.body?.string() ?: "Unknown error"
                        Log.e(TAG, "Failed to save route. Response code: ${resp.code}, Error: $errorBody")
                        RemoteResult.Error("Failed to save route: HTTP ${resp.code} - $errorBody")
                    }
                }

            } catch (e: IOException) {
                Log.e(TAG, "Network error while saving route: ${e.message}", e)
                RemoteResult.Error("Network error: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save route: ${e.message}", e)
                RemoteResult.Error("Failed to save route: ${e.message}")
            }
        }
    }
    /**
     * Get routes with optional search, filtering, and pagination
     * @param origin Optional search term for origin location
     * @param destination Optional search term for destination location
     * @param cityRoute Optional filter for city routes (true/false)
     * @param originProvince Optional filter for origin province
     * @param destinationProvince Optional filter for destination province
     * @param page Page number (default: 1)
     * @param limit Number of items per page (default: 20)
     * @return RemoteResult containing paginated list of SaveRouteResponse objects
     */
    suspend fun getRoutes(
        origin: String? = null,
        destination: String? = null,
        cityRoute: Boolean? = null,
        originProvince: String? = null,
        destinationProvince: String? = null,
        page: Int = 1,
        limit: Int = 20
    ): RemoteResult<PaginatedResult<List<SaveRouteResponse>>> {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "=== FETCHING ROUTES FROM REMOTE ===")

                // Build URL with query parameters
                val urlBuilder = "${BASE_URL}routes".toHttpUrl().newBuilder()

                origin?.let {
                    urlBuilder.addQueryParameter("origin", it)
                    Log.d(TAG, "Origin filter: $it")
                }

                destination?.let {
                    urlBuilder.addQueryParameter("destination", it)
                    Log.d(TAG, "Destination filter: $it")
                }

                cityRoute?.let {
                    urlBuilder.addQueryParameter("city_route", it.toString())
                    Log.d(TAG, "City route filter: $it")
                }

                originProvince?.let {
                    urlBuilder.addQueryParameter("origin_province", it)
                    Log.d(TAG, "Origin province filter: $it")
                }

                destinationProvince?.let {
                    urlBuilder.addQueryParameter("destination_province", it)
                    Log.d(TAG, "Destination province filter: $it")
                }

                urlBuilder.addQueryParameter("page", page.toString())
                urlBuilder.addQueryParameter("limit", limit.toString())

                val url = urlBuilder.build()
                Log.d(TAG, "Request URL: $url")

                // Create OkHttp request
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .addHeader("Accept", "application/json")
                    .build()

                // Execute request
                val response = httpClient.newCall(request).execute()

                response.use { resp ->
                    if (resp.isSuccessful) {
                        val responseBody = resp.body?.string() ?: ""
                        Log.d(TAG, "Response: $responseBody")

                        val paginatedResponse = json.decodeFromString<PaginatedRoutesResponse>(responseBody)

                        Log.d(TAG, "Retrieved ${paginatedResponse.data.size} routes (page $page of ${paginatedResponse.pagination.total_pages})")
                        Log.d(TAG, "Total routes: ${paginatedResponse.pagination.total}")
                        Log.d(TAG, "===============================")

                        val paginatedResult = PaginatedResult(
                            data = paginatedResponse.data,
                            pagination = paginatedResponse.pagination
                        )

                        RemoteResult.Success(paginatedResult)
                    } else {
                        val errorBody = resp.body?.string() ?: "Unknown error"
                        Log.e(TAG, "Failed to fetch routes. Response code: ${resp.code}, Error: $errorBody")
                        RemoteResult.Error("Failed to fetch routes: HTTP ${resp.code} - $errorBody")
                    }
                }

            } catch (e: IOException) {
                Log.e(TAG, "Network error while fetching routes: ${e.message}", e)
                RemoteResult.Error("Network error: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch routes: ${e.message}", e)
                RemoteResult.Error("Failed to fetch routes: ${e.message}")
            }
        }
    }

    /**
     * Update an existing route in remote storage
     */
    suspend fun updateRoute(routeId: Int, finalRoute: FinalRoute): RemoteResult<SaveRouteResponse> {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "=== UPDATING ROUTE TO REMOTE ===")
                Log.d(TAG, "Starting route update process for route ID: $routeId")

                // Step 1: Handle origin - only save if no ID exists
                val originWithId = if (finalRoute.origin.id.isNullOrBlank()) {
                    Log.d(TAG, "Origin has no ID, saving place first...")
                    when (val result = savePlace(finalRoute.origin)) {
                        is RemoteResult.Success -> {
                            finalRoute.origin.copy(id = result.data.id.toString())
                        }
                        is RemoteResult.Error -> {
                            Log.e(TAG, "Failed to save origin place: ${result.message}")
                            return@withContext RemoteResult.Error("Failed to save origin: ${result.message}")
                        }
                    }
                } else {
                    Log.d(TAG, "Origin already has ID: ${finalRoute.origin.id}, skipping save")
                    finalRoute.origin
                }

                // Step 2: Handle destination - only save if no ID exists
                val destinationWithId = if (finalRoute.destination.id.isNullOrBlank()) {
                    Log.d(TAG, "Destination has no ID, saving place first...")
                    when (val result = savePlace(finalRoute.destination)) {
                        is RemoteResult.Success -> {
                            finalRoute.destination.copy(id = result.data.id.toString())
                        }
                        is RemoteResult.Error -> {
                            Log.e(TAG, "Failed to save destination place: ${result.message}")
                            return@withContext RemoteResult.Error("Failed to save destination: ${result.message}")
                        }
                    }
                } else {
                    Log.d(TAG, "Destination already has ID: ${finalRoute.destination.id}, skipping save")
                    finalRoute.destination
                }

                // Step 3: Handle waypoints - only save those without IDs
                val waypointsWithIds = mutableListOf<WaypointWithPrice>()
                for ((index, waypoint) in finalRoute.waypoints.withIndex()) {
                    val waypointWithId = if (waypoint.dbPlace.id.isNullOrBlank()) {
                        Log.d(TAG, "Waypoint $index has no ID, saving place first...")
                        when (val result = savePlace(waypoint.dbPlace)) {
                            is RemoteResult.Success -> {
                                WaypointWithPrice(
                                    dbPlace = waypoint.dbPlace.copy(id = result.data.id.toString()),
                                    price = waypoint.price
                                )
                            }
                            is RemoteResult.Error -> {
                                Log.e(TAG, "Failed to save waypoint $index: ${result.message}")
                                return@withContext RemoteResult.Error("Failed to save waypoint $index: ${result.message}")
                            }
                        }
                    } else {
                        Log.d(TAG, "Waypoint $index already has ID: ${waypoint.dbPlace.id}, skipping save")
                        waypoint
                    }
                    waypointsWithIds.add(waypointWithId)
                }

                // Step 4: Parse distance and duration from strings
                val distanceMeters = try {
                    val distanceValue = finalRoute.distance.replace(Regex("[^0-9.]"), "").toDoubleOrNull() ?: 0.0
                    if (finalRoute.distance.contains("km", ignoreCase = true)) {
                        (distanceValue * 1000).toInt()
                    } else {
                        distanceValue.toInt()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse distance: ${finalRoute.distance}, using 0")
                    0
                }

                val durationSeconds = try {
                    val durationStr = finalRoute.duration
                    var totalSeconds = 0

                    val hourMatch = Regex("(\\d+)\\s*h").find(durationStr)
                    hourMatch?.let { totalSeconds += it.groupValues[1].toInt() * 3600 }

                    val minMatch = Regex("(\\d+)\\s*min").find(durationStr)
                    minMatch?.let { totalSeconds += it.groupValues[1].toInt() * 60 }

                    val secMatch = Regex("(\\d+)\\s*s").find(durationStr)
                    secMatch?.let { totalSeconds += it.groupValues[1].toInt() }

                    if (totalSeconds == 0) {
                        val plainNumber = durationStr.replace(Regex("[^0-9.]"), "").toDoubleOrNull()
                        plainNumber?.let { totalSeconds = (it * 60).toInt() }
                    }

                    totalSeconds
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse duration: ${finalRoute.duration}, using 0")
                    0
                }

                // Step 5: Create the route update request
                val updateRequest = SaveRouteRequest(
                    id = routeId,
                    name = finalRoute.routeName,
                    distance_meters = distanceMeters,
                    estimated_duration_seconds = durationSeconds,
                    origin_id = originWithId.id!!.toInt(),
                    destination_id = destinationWithId.id!!.toInt(),
                    route_price = finalRoute.routePrice,
                    city_route = finalRoute.isCityRoute,
                    waypoints = waypointsWithIds.mapIndexed { index, waypoint ->
                        SaveRouteWaypoint(
                            location_id = waypoint.dbPlace.id!!.toInt(),
                            order = index + 1,
                            price = if (waypoint.dbPlace.isPassThrough) null else waypoint.price,
                            is_pass_through = waypoint.dbPlace.isPassThrough
                        )
                    }
                )

                val jsonBody = json.encodeToString(updateRequest)
                Log.d(TAG, "Route update request payload: $jsonBody")

                // Step 6: Send the route update request (PUT)
                val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("${BASE_URL}routes/$routeId")
                    .put(requestBody)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .build()

                val response = httpClient.newCall(request).execute()

                response.use { resp ->
                    if (resp.isSuccessful) {
                        val responseBody = resp.body?.string() ?: ""
                        Log.d(TAG, "Route update response: $responseBody")

                        val saveResponse = json.decodeFromString<SaveRouteResponse>(responseBody)
                        Log.d(TAG, "Route updated successfully with ID: ${saveResponse.id}")
                        Log.d(TAG, "===============================")

                        RemoteResult.Success(saveResponse)
                    } else {
                        val errorBody = resp.body?.string() ?: "Unknown error"
                        Log.e(TAG, "Failed to update route. Response code: ${resp.code}, Error: $errorBody")
                        RemoteResult.Error("Failed to update route: HTTP ${resp.code} - $errorBody")
                    }
                }

            } catch (e: IOException) {
                Log.e(TAG, "Network error while updating route: ${e.message}", e)
                RemoteResult.Error("Network error: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update route: ${e.message}", e)
                RemoteResult.Error("Failed to update route: ${e.message}")
            }
        }
    }


    /**
     * Clean up resources
     */
    fun cleanup() {
        scope.cancel()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }
}

/**
 * Sealed class representing remote operation results
 */
sealed class RemoteResult<out T> {
    data class Success<T>(val data: T) : RemoteResult<T>()
    data class Error(val message: String) : RemoteResult<Nothing>()

    fun isSuccess(): Boolean = this is Success
    fun isError(): Boolean = this is Error

    fun getDataOrNull(): T? = when (this) {
        is Success -> data
        is Error -> null
    }

    fun getErrorOrNull(): String? = when (this) {
        is Success -> null
        is Error -> message
    }
}