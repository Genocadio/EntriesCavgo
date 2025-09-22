package com.gocavgo.entries.hereroutes

import android.Manifest
import android.os.Bundle
import android.util.Log
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.gocavgo.entries.BuildConfig
import com.gocavgo.entries.R
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.here.sdk.core.GeoCoordinates
import com.here.sdk.core.engine.AuthenticationMode
import com.here.sdk.core.engine.SDKNativeEngine
import com.here.sdk.core.engine.SDKOptions
import com.here.sdk.core.errors.InstantiationErrorException
import com.here.sdk.mapview.MapScheme
import com.here.sdk.mapview.MapView
import com.here.sdk.search.Place
import com.here.sdk.search.SearchEngine
import androidx.core.view.isVisible
import com.here.sdk.mapview.MapMarker
import kotlin.math.abs
import com.here.sdk.core.Metadata
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.gocavgo.entries.service.RemoteDataManager
import com.gocavgo.entries.service.RemoteResult
import android.content.Intent

class ManageRoute : AppCompatActivity() {

    private var mapView: MapView? = null
    private lateinit var locationClient: FusedLocationProviderClient
    private val TAG = ManageRoute::class.java.simpleName
    private lateinit var mapStyleFab: com.google.android.material.floatingactionbutton.FloatingActionButton
    private lateinit var searchAutoComplete: AutoCompleteTextView
    private lateinit var clearSearchButton: ImageButton
    private lateinit var myLocationFab: com.google.android.material.floatingactionbutton.FloatingActionButton
    private lateinit var searchEngine: SearchEngine
    private lateinit var reverseGeocodeEngine: SearchEngine

    // UI containers
    private lateinit var bottomContainer: LinearLayout
    private lateinit var searchContainer: LinearLayout
    private lateinit var searchOverlay: LinearLayout
    private lateinit var searchHintText: TextView
    private lateinit var backSearchButton: ImageButton
    private lateinit var placeDetailsContainer: LinearLayout
    private lateinit var routeSummaryContainer: LinearLayout
    private lateinit var routeActionsContainer: LinearLayout

    private var currentRouteHandle: String? = null

    // Place details views
    private lateinit var tvPlaceTitle: TextView
    private lateinit var tvPlaceAddress: TextView
    private lateinit var tvPlaceType: TextView

    // Route summary views
    private lateinit var tvRouteDistance: TextView
    private lateinit var tvRouteDuration: TextView
    // Components
    private lateinit var routingExample: RoutingExample
    private lateinit var searchManager: SearchManager
    private lateinit var uiController: UIController
    private lateinit var mapController: MapController
    private lateinit var shareLocationProcessor: ShareLocationProcessor

    // Route action buttons
    private lateinit var saveLocationButton: Button
    private lateinit var addDestinationButton: Button
    private lateinit var addWaypointButton: Button
    private lateinit var saveRouteButton: Button
    private lateinit var clearRouteButton: Button
    private lateinit var locationActionsContainer: LinearLayout

    // Selection state
    private var isSelectingOrigin = true
    private var isSelectingDestination = false
    private var isSelectingWaypoint = false
    private var selectedOrigin: GeoCoordinates? = null
    private var selectedDestination: GeoCoordinates? = null
    private var currentSelectedPlace: Place? = null

    // DbPlace objects for route planning (independent of HERE SDK)
    private var originDbPlace: DbPlace? = null
    private var destinationDbPlace: DbPlace? = null
    private var waypointDbPlaces: MutableList<DbPlace> = mutableListOf()

    private lateinit var btnExpandRoute: ImageButton
    private lateinit var routeDetailsContainer: ScrollView
    private lateinit var routeStopsContainer: LinearLayout
    private lateinit var btnClearLocation: Button
    private lateinit var btnAddWaypointRoute: Button


    // Available map schemes
    private val mapSchemes = listOf(
        MapScheme.NORMAL_DAY,
        MapScheme.NORMAL_NIGHT,
        MapScheme.SATELLITE,
        MapScheme.HYBRID_DAY,
        MapScheme.HYBRID_NIGHT,
    )

    private val mapSchemeNames = listOf(
        "Normal Day",
        "Normal Night",
        "Satellite",
        "Hybrid Day",
        "Hybrid Night"
    )

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeHERESDK()
        initializeSearchEngine()
        locationClient = LocationServices.getFusedLocationProviderClient(this)
        enableEdgeToEdge()
        setContentView(R.layout.activity_manage_route)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.manageroutes)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        mapView = findViewById(R.id.map_view)
        mapView?.onCreate(savedInstanceState)

        initializeViews()
        initializeComponents()
        setupCallbacks()

        mapView?.setOnReadyListener {
            Log.d(TAG, "MapView is ready to use.")
        }

        getLocation { geoCoordinates ->
            if (geoCoordinates != null) {
                mapController.loadMapScene(geoCoordinates)
            }
        }

        // Process shared location if this activity was launched with a share intent
        processSharedLocation()
    }

    /**
     * Process shared location from navigation apps
     */
    private fun processSharedLocation() {
        val intent = intent
        Log.d(TAG, "=== PROCESSING SHARED LOCATION ===")
        Log.d(TAG, "Intent action: ${intent.action}")
        Log.d(TAG, "Intent type: ${intent.type}")
        Log.d(TAG, "Intent data: ${intent.data}")
        Log.d(TAG, "Intent extras: ${intent.extras}")
        
        // Log all intent extras
        intent.extras?.let { extras ->
            Log.d(TAG, "Intent extras keys: ${extras.keySet()}")
            for (key in extras.keySet()) {
                val value = extras.get(key)
                Log.d(TAG, "  $key: $value")
            }
        }
        
        // Log specific share-related extras
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
        val sharedSubject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        val sharedHtmlText = intent.getStringExtra(Intent.EXTRA_HTML_TEXT)
        
        Log.d(TAG, "EXTRA_TEXT: $sharedText")
        Log.d(TAG, "EXTRA_SUBJECT: $sharedSubject")
        Log.d(TAG, "EXTRA_HTML_TEXT: $sharedHtmlText")
        
        // Check if we have a shortened URL that needs resolution
        if (sharedText != null && sharedText.contains("maps.app.goo.gl")) {
            Log.d(TAG, "Detected shortened Google Maps URL, resolving asynchronously...")
            resolveAndProcessShortenedUrl(sharedText, sharedSubject)
        } else {
            // Process normally
            val shareResult = shareLocationProcessor.processSharedIntent(intent)
            handleShareResult(shareResult)
        }
        
        Log.d(TAG, "=== END PROCESSING SHARED LOCATION ===")
    }
    
    /**
     * Resolve shortened URL and process the location
     */
    private fun resolveAndProcessShortenedUrl(url: String, title: String?) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Resolving shortened URL: $url")
                val resolvedUrl = shareLocationProcessor.resolveShortenedUrl(url)
                
                Log.d(TAG, "Resolved URL: $resolvedUrl")
                
                if (resolvedUrl != null && resolvedUrl != url) {
                    // Create a new intent with the resolved URL
                    val resolvedIntent = Intent().apply {
                        action = Intent.ACTION_SEND
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, resolvedUrl)
                        putExtra(Intent.EXTRA_TITLE, title)
                    }
                    
                    // Process the resolved URL
                    val shareResult = shareLocationProcessor.processSharedIntent(resolvedIntent)
                    
                    // Switch back to main thread for UI updates
                    withContext(Dispatchers.Main) {
                        handleShareResult(shareResult)
                    }
                } else {
                    Log.w(TAG, "Failed to resolve shortened URL")
                    withContext(Dispatchers.Main) {
                        CustomToast.showError(this@ManageRoute, "Could not resolve shared location URL")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error resolving shortened URL: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    CustomToast.showError(this@ManageRoute, "Error processing shared location: ${e.message}")
                }
            }
        }
    }
    
    /**
     * Handle the result of share processing
     */
    private fun handleShareResult(shareResult: ShareLocationProcessor.ShareLocationResult?) {
        if (shareResult != null) {
            Log.d(TAG, "Successfully processed shared location:")
            Log.d(TAG, "  Coordinates: ${shareResult.coordinates.latitude}, ${shareResult.coordinates.longitude}")
            Log.d(TAG, "  Source: ${shareResult.sourceApp}")
            Log.d(TAG, "  Original text: ${shareResult.originalText}")
            Log.d(TAG, "  Route data: ${shareResult.routeData}")
            
            // Check if this is a route share
            if (shareResult.routeData != null) {
                Log.d(TAG, "Processing route share with origin and destination")
                handleRouteShare(shareResult.routeData, shareResult.sourceApp)
            } else {
                Log.d(TAG, "Processing single location share")
                // Create DbPlace with temporary name (will be updated by reverse geocoding)
                val dbPlace = DbPlace.create(
                    placeName = "Shared Location (${shareResult.sourceApp})",
                    latitude = shareResult.coordinates.latitude,
                    longitude = shareResult.coordinates.longitude,
                    address = "Shared from ${shareResult.sourceApp}"
                )
                
                // Add the shared location to the map
                addSharedLocationToMap(shareResult.coordinates, dbPlace, shareResult.sourceApp)
                
                // Show success message
                CustomToast.showSuccess(this, "Location shared from ${shareResult.sourceApp}")
            }
            
        } else {
            Log.d(TAG, "No shared location found in intent")
            Log.d(TAG, "ShareLocationProcessor returned null")
            CustomToast.showWarning(this, "Could not extract location from shared content")
        }
    }
    
    /**
     * Handle route share (origin, destination, and waypoints)
     */
    private fun handleRouteShare(routeData: ShareLocationProcessor.RouteData, sourceApp: String) {
        Log.d(TAG, "Handling route share:")
        Log.d(TAG, "  Origin: ${routeData.origin.latitude}, ${routeData.origin.longitude}")
        Log.d(TAG, "  Destination: ${routeData.destination.latitude}, ${routeData.destination.longitude}")
        Log.d(TAG, "  Waypoints: ${routeData.waypoints.size}")
        
        // Clear existing route
        resetToInitialState()
        
        // Create DbPlace for origin
        val originDbPlace = DbPlace.create(
            placeName = "Origin (${sourceApp})",
            latitude = routeData.origin.latitude,
            longitude = routeData.origin.longitude,
            address = "Shared from ${sourceApp}"
        )
        
        // Create DbPlace for destination
        val destinationDbPlace = DbPlace.create(
            placeName = "Destination (${sourceApp})",
            latitude = routeData.destination.latitude,
            longitude = routeData.destination.longitude,
            address = "Shared from ${sourceApp}"
        )
        
        // Add origin to map (without triggering route calculation)
        addRouteLocationToMapSilently(routeData.origin, originDbPlace, "origin", sourceApp)
        
        // Add destination to map (without triggering route calculation)
        addRouteLocationToMapSilently(routeData.destination, destinationDbPlace, "destination", sourceApp)
        
        // Add waypoints to map (without triggering route calculation)
        routeData.waypoints.forEachIndexed { index, waypointCoords ->
            val waypointDbPlace = DbPlace.create(
                placeName = "Waypoint ${index + 1} (${sourceApp})",
                latitude = waypointCoords.latitude,
                longitude = waypointCoords.longitude,
                address = "Shared from ${sourceApp}"
            )
            
            addRouteLocationToMapSilently(waypointCoords, waypointDbPlace, "waypoint", sourceApp)
        }
        
        // Show success message
        val waypointText = if (routeData.waypoints.isNotEmpty()) " with ${routeData.waypoints.size} waypoint(s)" else ""
        CustomToast.showSuccess(this, "Route shared from ${sourceApp}${waypointText}")
        
        // Calculate route ONCE after all locations are added
        Log.d(TAG, "All route locations added - calculating route automatically")
        routingExample.calculateRouteWithWaypoints()
    }
    
    /**
     * Add route location to map silently (without triggering route calculation)
     */
    private fun addRouteLocationToMapSilently(coordinates: GeoCoordinates, dbPlace: DbPlace, markerType: String, sourceApp: String) {
        // Move map to the location
        mapController.moveMapToLocation(coordinates)
        
        // Add marker for the location
        val marker = mapController.addSearchMarker(coordinates, dbPlace, markerType)
        
        // Handle location selection
        handleLocationSelection(coordinates, dbPlace.getName(), dbPlace)
        
        // Show place details initially (will be updated by reverse geocoding)
        uiController.showPlaceDetails(null, dbPlace)
        
        // Update selection state
        when (markerType) {
            "origin" -> {
                isSelectingOrigin = true
                isSelectingDestination = false
                isSelectingWaypoint = false
            }
            "destination" -> {
                isSelectingOrigin = false
                isSelectingDestination = true
                isSelectingWaypoint = false
            }
            "waypoint" -> {
                isSelectingOrigin = false
                isSelectingDestination = false
                isSelectingWaypoint = true
            }
        }
        
        Log.d(TAG, "Added route $markerType silently: ${dbPlace.getName()}")
        
        // Reverse geocode to get proper place name and address
        val options = com.here.sdk.search.SearchOptions().apply {
            languageCode = com.here.sdk.core.LanguageCode.EN_GB
            maxItems = 1
        }
        
        reverseGeocodeEngine.searchByCoordinates(coordinates, options, object : com.here.sdk.search.SearchCallback {
            override fun onSearchCompleted(searchError: com.here.sdk.search.SearchError?, list: List<com.here.sdk.search.Place>?) {
                if (searchError == null) {
                    val place = list?.firstOrNull()
                    place?.let {
                        val updatedTitle = it.title.ifBlank { it.address.addressText }
                        dbPlace.setPlaceName(updatedTitle)
                        dbPlace.setAddress(it.address.addressText)
                        
                        // Update marker title
                        val updatedMetadata = marker.metadata ?: com.here.sdk.core.Metadata()
                        updatedMetadata.setString("marker_title", dbPlace.getName())
                        marker.metadata = updatedMetadata
                        
                        // Show updated place details
                        uiController.showPlaceDetails(it, dbPlace)
                        
                        Log.d(TAG, "Updated route $markerType with reverse geocoded name: ${dbPlace.getName()}")
                    }
                } else {
                    Log.w(TAG, "Reverse geocoding failed for route $markerType: ${searchError.name}")
                }
            }
        })
    }
    
    /**
     * Add route location to map (origin or destination)
     */
    private fun addRouteLocationToMap(coordinates: GeoCoordinates, dbPlace: DbPlace, markerType: String, sourceApp: String) {
        // Move map to the location
        mapController.moveMapToLocation(coordinates)
        
        // Add marker for the location
        val marker = mapController.addSearchMarker(coordinates, dbPlace, markerType)
        
        // Handle location selection
        handleLocationSelection(coordinates, dbPlace.getName(), dbPlace)
        
        // Show place details initially (will be updated by reverse geocoding)
        uiController.showPlaceDetails(null, dbPlace)
        
        // Update selection state
        when (markerType) {
            "origin" -> {
                isSelectingOrigin = true
                isSelectingDestination = false
                isSelectingWaypoint = false
            }
            "destination" -> {
                isSelectingOrigin = false
                isSelectingDestination = true
                isSelectingWaypoint = false
            }
            "waypoint" -> {
                isSelectingOrigin = false
                isSelectingDestination = false
                isSelectingWaypoint = true
            }
        }
        
        Log.d(TAG, "Added route $markerType: ${dbPlace.getName()}")
        
        // Reverse geocode to get proper place name and address
        val options = com.here.sdk.search.SearchOptions().apply {
            languageCode = com.here.sdk.core.LanguageCode.EN_GB
            maxItems = 1
        }
        
        reverseGeocodeEngine.searchByCoordinates(coordinates, options, object : com.here.sdk.search.SearchCallback {
            override fun onSearchCompleted(searchError: com.here.sdk.search.SearchError?, list: List<com.here.sdk.search.Place>?) {
                if (searchError == null) {
                    val place = list?.firstOrNull()
                    place?.let {
                        val updatedTitle = it.title.ifBlank { it.address.addressText }
                        dbPlace.setPlaceName(updatedTitle)
                        dbPlace.setAddress(it.address.addressText)
                        
                        // Update marker title
                        val updatedMetadata = marker.metadata ?: com.here.sdk.core.Metadata()
                        updatedMetadata.setString("marker_title", dbPlace.getName())
                        marker.metadata = updatedMetadata
                        
                        // Show updated place details
                        uiController.showPlaceDetails(it, dbPlace)
                        
                        Log.d(TAG, "Updated route $markerType with reverse geocoded name: ${dbPlace.getName()}")
                    }
                } else {
                    Log.w(TAG, "Reverse geocoding failed for route $markerType: ${searchError.name}")
                }
            }
        })
    }
    
    /**
     * Add shared location to map and handle it like a search result
     */
    private fun addSharedLocationToMap(coordinates: GeoCoordinates, dbPlace: DbPlace, sourceApp: String) {
        // Move map to the shared location
        mapController.moveMapToLocation(coordinates)
        
        // Determine the appropriate marker type based on current state
        val markerType = when {
            selectedOrigin == null -> "origin"
            selectedDestination == null -> "destination"
            else -> "waypoint"
        }
        
        // Add marker for the shared location
        val marker = mapController.addSearchMarker(coordinates, dbPlace, markerType)
        
        // Handle location selection (this will set origin/destination/waypoint as appropriate)
        handleLocationSelection(coordinates, dbPlace.getName(), dbPlace)
        
        // Show place details initially (will be updated by reverse geocoding)
        uiController.showPlaceDetails(null, dbPlace)
        
        // Update selection state if needed
        when (markerType) {
            "origin" -> {
                isSelectingOrigin = true
                isSelectingDestination = false
                isSelectingWaypoint = false
            }
            "destination" -> {
                isSelectingOrigin = false
                isSelectingDestination = true
                isSelectingWaypoint = false
            }
            "waypoint" -> {
                isSelectingOrigin = false
                isSelectingDestination = false
                isSelectingWaypoint = true
            }
        }
        
        Log.d(TAG, "Added shared location as $markerType: ${dbPlace.getName()}")
        
        // Reverse geocode to get proper place name and address (same as long press)
        val options = com.here.sdk.search.SearchOptions().apply {
            languageCode = com.here.sdk.core.LanguageCode.EN_GB
            maxItems = 1
        }
        
        reverseGeocodeEngine.searchByCoordinates(coordinates, options, object : com.here.sdk.search.SearchCallback {
            override fun onSearchCompleted(searchError: com.here.sdk.search.SearchError?, list: List<com.here.sdk.search.Place>?) {
                if (searchError == null) {
                    val place = list?.firstOrNull()
                    place?.let {
                        val updatedTitle = it.title.ifBlank { it.address.addressText }
                        dbPlace.setPlaceName(updatedTitle)
                        dbPlace.setAddress(it.address.addressText)
                        
                        // Update marker title
                        val updatedMetadata = marker.metadata ?: com.here.sdk.core.Metadata()
                        updatedMetadata.setString("marker_title", dbPlace.getName())
                        marker.metadata = updatedMetadata
                        
                        // Show updated place details
                        uiController.showPlaceDetails(it, dbPlace)
                        
                        Log.d(TAG, "Updated shared location with reverse geocoded name: ${dbPlace.getName()}")
                        
                        // Update success message with proper name
                        CustomToast.showSuccess(this@ManageRoute, "Location shared from $sourceApp: ${dbPlace.getName()}")
                    }
                } else {
                    Log.w(TAG, "Reverse geocoding failed for shared location: ${searchError.name}")
                }
            }
        })
        
        // If we have both origin and destination now, calculate route automatically
        if (selectedOrigin != null && selectedDestination != null && !routingExample.hasActiveRoute()) {
            Log.d(TAG, "Both origin and destination set - calculating route automatically")
            routingExample.calculateRouteWithWaypoints()
        }
    }

    private fun handleTogglePassThrough(marker: MapMarker) {
        val coords = marker.coordinates
        val dbPlace = findDbPlaceForCoordinates(coords.latitude, coords.longitude)
        if (dbPlace == null) {
            CustomToast.showWarning(this, "No waypoint found to toggle")
            return
        }

        // Toggle flag
        dbPlace.isPassThrough = !dbPlace.isPassThrough

        // Update UI hint in metadata title
        val updatedMetadata = marker.metadata ?: Metadata()
        val baseTitle = dbPlace.getName()
        updatedMetadata.setString("marker_title", baseTitle)
        marker.metadata = updatedMetadata

        // Recalculate route with updated waypoint type if applicable
        val waypointIndex = routingExample.findWaypointIndex(coords)
        if (waypointIndex >= 0) {
            // Force recalculation by triggering calculateRouteWithWaypoints again.
            // updateWaypoint already triggers, but we need to persist type. The RoutingExample reads flags only when building waypoints.
            // Easiest: temporarily nudge waypoint position by 0 and call calculate.
            routingExample.calculateRouteWithWaypoints()
        } else {
            // If it's origin/destination, ignore
        }

        val modeText = if (dbPlace.isPassThrough) "Pass-Through" else "Stopover"
        CustomToast.showInfo(this, "Waypoint set to $modeText")
    }

    private fun initializeViews() {
        mapView = findViewById(R.id.map_view)
        mapStyleFab = findViewById(R.id.fab_map_style)
        searchAutoComplete = findViewById(R.id.search_autocomplete)
        clearSearchButton = findViewById(R.id.clear_search)
        myLocationFab = findViewById(R.id.fab_my_location)

        // Bottom container views
        bottomContainer = findViewById(R.id.bottom_container)
        searchContainer = findViewById(R.id.search_container)
        searchOverlay = findViewById(R.id.search_overlay)
        searchHintText = findViewById(R.id.search_hint_text)
        backSearchButton = findViewById(R.id.back_search_button)
        placeDetailsContainer = findViewById(R.id.place_details_container)
        routeSummaryContainer = findViewById(R.id.route_summary_container)
        routeActionsContainer = findViewById(R.id.route_actions_container)

        // Place details views
        tvPlaceTitle = findViewById(R.id.tv_place_title)
        tvPlaceAddress = findViewById(R.id.tv_place_address)
        tvPlaceType = findViewById(R.id.tv_place_type)

        // Route summary views
        tvRouteDistance = findViewById(R.id.tv_route_distance)
        tvRouteDuration = findViewById(R.id.tv_route_duration)

        // Location action buttons
        locationActionsContainer = findViewById(R.id.location_actions_container)
        saveLocationButton = findViewById(R.id.btn_save_location)
        addDestinationButton = findViewById(R.id.btn_add_destination)
        addWaypointButton = findViewById(R.id.btn_add_waypoint)
        saveRouteButton = findViewById(R.id.btn_save_route)
        clearRouteButton = findViewById(R.id.btn_clear_route)

        btnExpandRoute = findViewById(R.id.btn_expand_route)
        routeDetailsContainer = findViewById(R.id.route_details_container)
        routeStopsContainer = findViewById(R.id.route_stops_container)
        btnClearLocation = findViewById(R.id.btn_clear_location)
        btnAddWaypointRoute = findViewById(R.id.btn_add_waypoint_route)
    }

    private fun initializeComponents() {
        routingExample = RoutingExample(this, mapView!!)
        searchManager = SearchManager(this)
        shareLocationProcessor = ShareLocationProcessor()

        uiController = UIController(
            this,
            searchContainer,
            searchOverlay,
            placeDetailsContainer,
            routeSummaryContainer,
            routeActionsContainer,
            locationActionsContainer,
            searchAutoComplete,
            clearSearchButton,
            searchHintText,
            backSearchButton,
            tvPlaceTitle,
            tvPlaceAddress,
            tvPlaceType,
            tvRouteDistance,
            tvRouteDuration
        )

        mapController = MapController(
            this,
            mapView!!,
            mapStyleFab,
            myLocationFab,
            locationClient,
            mapSchemes,
            mapSchemeNames
        )
    }

    private fun setupCallbacks() {
        // Setup routing callback
        routingExample.onRouteCalculated = { route, routeHandle ->
            currentRouteHandle = routeHandle
            route?.let {
                uiController.updateRouteSummaryWithActualData(it)
                Log.d(TAG, "Route calculated with handle: $routeHandle")
            } ?: run {
                currentRouteHandle = null
                routeSummaryContainer.visibility = android.view.View.GONE
                Log.d(TAG, "Route calculation failed - no handle available")
            }
        }


        // Setup search manager callbacks
        setupSearchManagerCallbacks()

        // Setup UI controller callbacks
        setupUIControllerCallbacks()

        // Setup map controller callbacks
        setupMapControllerCallbacks()

        // Setup search functionality
        uiController.setupSearchFunctionality(searchManager)
    }

    private fun setupSearchManagerCallbacks() {
        searchManager.onPlaceSelected = { place ->
            Log.d(TAG, "Place selected: ${place.title} at ${place.areaType}")
            currentSelectedPlace = place

            place.geoCoordinates?.let { coordinates ->
                // Create DbPlace from HERE SDK Place
                val dbPlace = DbPlace.fromPlace(place)
                Log.d(TAG, "Created DbPlace - ID: ${dbPlace.id}, Edited: ${dbPlace.edited}")
                handleLocationSelection(coordinates, place.title, dbPlace)

                // Determine marker type
                val markerType = when {
                    isSelectingOrigin -> "origin"
                    isSelectingDestination -> "destination"
                    isSelectingWaypoint -> "waypoint"
                    else -> "waypoint" // default
                }

                // Use the enhanced addSearchMarker method with DbPlace and marker type
                mapController.addSearchMarker(coordinates, dbPlace, markerType)
                mapController.moveMapToLocation(coordinates)

                uiController.clearSearchState()
                uiController.hideSearchOverlay()

                // Show place details using DbPlace (compatible with original Place if needed)
                uiController.showPlaceDetails(place, dbPlace)
            }
        }

        searchManager.onDbPlaceSelected = { dbPlace ->
            Log.d(TAG, "Database place selected: ${dbPlace.getName()} at ${dbPlace.latitude}, ${dbPlace.longitude}, ${dbPlace.id}. ${dbPlace.code}")

            val coordinates = GeoCoordinates(dbPlace.latitude, dbPlace.longitude)

            // Handle location selection using existing logic
            handleLocationSelection(coordinates, dbPlace.getName(), dbPlace)

            // Determine marker type for database places
            val markerType = when {
                isSelectingOrigin -> "origin"
                isSelectingDestination -> "destination"
                isSelectingWaypoint -> "waypoint"
                else -> "waypoint"
            }

            // Add marker for database place
            mapController.addSearchMarker(coordinates, dbPlace, markerType)
            mapController.moveMapToLocation(coordinates)

            uiController.clearSearchState()
            uiController.hideSearchOverlay()

            // Show place details using database place
            uiController.showPlaceDetails(null, dbPlace)  // No original Place for DB results
        }

        searchManager.onSearchError = { errorMessage ->
            runOnUiThread {
                // OLD: Toast.makeText(this, errorMessage, Toast.LENGTH_SHORT).show()
                CustomToast.showError(this, errorMessage)
            }
        }

        searchManager.onSuggestionsUpdated = { suggestionTitles ->
            runOnUiThread {
                searchManager.showDropdown(searchAutoComplete)
            }
        }

        searchManager.onTextSearchRequested = { query ->
            val centerCoordinates = mapView?.camera?.state?.targetCoordinates
            centerCoordinates?.let {
                searchManager.executeTextSearch(query, it)
            }
        }
    }

    private fun markEdit(dbPlace: DbPlace) {
        if (!dbPlace.id.isNullOrBlank()) {
            Log.d(TAG, "=== MARKING PLACE AS EDITED ===")
            Log.d(TAG, "Place: ${dbPlace.getName()}")
            Log.d(TAG, "ID: ${dbPlace.id}")
            Log.d(TAG, "Before - edited flag: ${dbPlace.edited}")

            dbPlace.setEdited(true)

            Log.d(TAG, "After - edited flag: ${dbPlace.edited}")
            Log.d(TAG, "Has moved coordinates: ${dbPlace.hasMovedCoordinates}")
            Log.d(TAG, "================================")
        } else {
            Log.w(TAG, "Cannot mark DbPlace as edited - no ID available: ${dbPlace.getName()}")
            Log.w(TAG, "  Current coordinates: ${dbPlace.latitude}, ${dbPlace.longitude}")
            Log.w(TAG, "  Has moved coordinates: ${dbPlace.hasMovedCoordinates}")
        }
    }

    private fun setupUIControllerCallbacks() {
        uiController.onLocationActionRequested = { action ->
            when (action) {
                "save_location" -> {
                    // Find the DbPlace for the currently selected location
                    val dbPlaceToSave = when {
                        isSelectingOrigin -> originDbPlace
                        isSelectingDestination -> destinationDbPlace
                        isSelectingWaypoint -> waypointDbPlaces.lastOrNull()
                        else -> {
                            // Fallback: find DbPlace by current coordinates
                            currentSelectedPlace?.geoCoordinates?.let { coords ->
                                findDbPlaceForCoordinates(coords.latitude, coords.longitude)
                            }
                        }
                    }

                    dbPlaceToSave?.let { dbPlace ->
                        Log.d(TAG, "About to save DbPlace:")
                        Log.d(TAG, dbPlace.debugCoordinateState())

                        // Create a proper copy for saving that handles moved coordinates correctly
                        val finalDbPlace = dbPlace.createSaveCopy()

                        Log.d(TAG, "Created save copy:")
                        Log.d(TAG, finalDbPlace.debugCoordinateState())

                        if (!finalDbPlace.hasValidLocationData()) {
                            val errorMessage = finalDbPlace.getLocationValidationError()
                                ?: "Location is missing district or province information"

                            CustomToast.showWarning(this@ManageRoute, "Cannot save location: $errorMessage")
                            Log.w(TAG, "Location save blocked - ${finalDbPlace.getName()}: $errorMessage")
                            return@let
                        }

                        // Save using RemoteDataManager
                        val remoteDataManager = RemoteDataManager.getInstance()

                        lifecycleScope.launch {
                            try {
                                when (val result = remoteDataManager.savePlace(finalDbPlace)) {
                                    is RemoteResult.Success -> {
                                        runOnUiThread {
                                            CustomToast.showSuccess(this@ManageRoute, "Location '${finalDbPlace.getName()}' saved successfully with ID: ${result.data.id}!")
                                        }
                                        Log.d(TAG, "Place saved to remote with ID: ${result.data.id}")
                                        Log.d(TAG, "Final DbPlace saved coordinates: ${finalDbPlace.latitude}, ${finalDbPlace.longitude}")
                                    }
                                    is RemoteResult.Error -> {
                                        runOnUiThread {
                                            CustomToast.showError(this@ManageRoute, "Failed to save location: ${result.message}")
                                        }
                                        Log.e(TAG, "Failed to save place: ${result.message}")
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Exception during place save: ${e.message}", e)
                                runOnUiThread {
                                    CustomToast.showError(this@ManageRoute, "Error saving location: ${e.message}")
                                }
                            }
                        }
                    } ?: run {
                        Toast.makeText(this, "No location selected to save", Toast.LENGTH_SHORT).show()
                    }
                }
                "add_destination" -> {
                    isSelectingOrigin = false
                    isSelectingDestination = true
                    isSelectingWaypoint = false
                    uiController.switchToDestinationSelection()
                    Log.d(TAG, "Switched to destination selection mode")
                }
                "add_waypoint" -> {
                    isSelectingOrigin = false
                    isSelectingDestination = false
                    isSelectingWaypoint = true
                    uiController.switchToWaypointSelection()
                    Log.d(TAG, "Switched to waypoint selection mode")
                }
                "save_route" -> {
                    val finalRoute = createFinalRouteWithHandle()
                    if (finalRoute != null) {
                        logFinalRouteDetails(finalRoute)
                        // OLD: Toast.makeText(this, "Route '${finalRoute.origin.getName()}'-> ${finalRoute.destination.getName()} saved successfully!", Toast.LENGTH_LONG).show()
                        CustomToast.showSuccess(this, "Route '${finalRoute.origin.getName()}' → ${finalRoute.destination.getName()}' saved successfully!")
                    } else {
                        Log.w(TAG, "Failed to save route - validation failed")
                    }
                }
                "clear_route" -> {
                    resetToInitialState()
                }

            }
        }

        uiController.onSelectionStateRequest = {
            Triple(isSelectingOrigin, isSelectingDestination, isSelectingWaypoint)
        }

        uiController.onRouteDataRequest = {
            val hasRoute = selectedOrigin != null && selectedDestination != null
            val isRouteSummaryVisible = routeSummaryContainer.isVisible
            Pair(hasRoute, isRouteSummaryVisible)
        }

        // Provide route stops data to UIController
        uiController.onRouteStopsDbDataRequest = {
            Triple(originDbPlace, destinationDbPlace, waypointDbPlaces.toList())
        }

        // Handle auto-suggest requests (not direct search)
        uiController.onAutoSuggestRequested = { query ->
            if (uiController.isDatabaseSearchMode()) {
                // Use database search
                Log.d(TAG, "Using database search for query: $query")
                searchManager.performDatabaseSearch(query)
            } else {
                // Use HERE SDK autosuggest
                Log.d(TAG, "Using HERE SDK autosuggest for query: $query")
                val centerCoordinates = mapView?.camera?.state?.targetCoordinates
                centerCoordinates?.let {
                    searchManager.performAutoSuggest(query, it)
                }
            }
        }

        // Setup additional button listeners
        setupAdditionalButtons()
    }

    private fun createFinalRouteWithHandle(): FinalRoute? {
        // Get route data from UIController
        val routeData = uiController.validateAndCreateFinalRoute()

        return routeData?.let { route ->
            // Create new FinalRoute with the route handle
            FinalRoute(
                origin = route.origin,
                id = route.id,
                destination = route.destination,
                routePrice = route.routePrice,
                routeName = route.routeName,
                isCityRoute = route.isCityRoute,
                distance = route.distance,
                duration = route.duration,
                routeMapId = currentRouteHandle, // Add the route handle here
                waypoints = route.waypoints
            )
        }
    }

    private fun logFinalRouteDetails(finalRoute: FinalRoute) {
        val remoteDataManager = RemoteDataManager.getInstance()
        lifecycleScope.launch {
            try {
                when (val result = remoteDataManager.saveRoute(finalRoute)) {
                    is RemoteResult.Success -> {
                        runOnUiThread {
                            // OLD: Toast.makeText(this@ManageRoute, "Route '${finalRoute.routeName ?: "Unnamed Route"}' saved successfully with ID: ${result.data.id}!", Toast.LENGTH_LONG).show()
                            CustomToast.showSuccess(this@ManageRoute, "Route '${finalRoute.routeName ?: "Unnamed Route"}' saved successfully with ID: ${result.data.id}!")
                        }
                        Log.d(TAG, "Route saved to remote with ID: ${result.data.id}")
                    }
                    is RemoteResult.Error -> {
                        runOnUiThread {
                            // OLD: Toast.makeText(this@ManageRoute, "Failed to save route: ${result.message}", Toast.LENGTH_LONG).show()
                            CustomToast.showError(this@ManageRoute, "Failed to save route: ${result.message}")
                        }
                        Log.e(TAG, "Failed to save route: ${result.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during route save: ${e.message}", e)
                runOnUiThread {
                    CustomToast.showError(this@ManageRoute, "Error saving route: ${e.message}")
                }
            }
        }
    }

    private fun setupAdditionalButtons() {
        btnClearLocation.setOnClickListener {
            resetToInitialState()
        }

        btnAddWaypointRoute.setOnClickListener {
            isSelectingOrigin = false
            isSelectingDestination = false
            isSelectingWaypoint = true
            uiController.switchToWaypointSelection()
            Log.d(TAG, "Switched to waypoint selection mode from route")
        }
    }

    private fun setupMapControllerCallbacks() {
        mapController.onLocationReceived = { coordinates ->
            mapController.clearAllMarkers()
            mapController.clearSearchMarkers()
            mapController.moveMapToLocation(coordinates)
            mapController.showLocationIndicatorPedestrian(coordinates)
        }

        mapController.onMarkerActionRequested = { action, marker ->
            when (action) {
                "remove" ->{
                    handleMarkerDeletion(marker)
                    mapController.removeMarker(marker)
                }
                "move" -> mapController.startMarkerMove(marker)
                "edit" -> handleMarkerEdit(marker)
                "save" -> mapController.saveMarker(marker)
                "toggle_passthrough" -> handleTogglePassThrough(marker)
            }
        }

        // FIXED: The onMarkerMoved callback now uses the OLD marker coordinates to find the DbPlace
        mapController.onMarkerMoved = { marker, newCoordinates ->
            // CRITICAL FIX: Use the OLD marker coordinates (before the move) to find the DbPlace
            val oldCoordinates = marker.coordinates

            Log.d(TAG, "onMarkerMoved callback:")
            Log.d(TAG, "  Old marker coordinates: ${oldCoordinates.latitude}, ${oldCoordinates.longitude}")
            Log.d(TAG, "  New coordinates: ${newCoordinates.latitude}, ${newCoordinates.longitude}")

            // Find the DbPlace using the OLD coordinates (before the move)
            val dbPlace = findDbPlaceForCoordinates(oldCoordinates.latitude, oldCoordinates.longitude)

            if (dbPlace != null) {
                Log.d(TAG, "Found DbPlace to update:")
                Log.d(TAG, dbPlace.debugCoordinateState())
                Log.d(TAG, "BEFORE ANY CHANGES - Edited flag: ${dbPlace.edited}")

                // CRITICAL FIX: Mark as edited FIRST (if it has an ID)
                if (!dbPlace.id.isNullOrBlank()) {
                    Log.d(TAG, "Marking place as edited because coordinates are moving")
                    markEdit(dbPlace)
                    Log.d(TAG, "AFTER markEdit - Edited flag: ${dbPlace.edited}")
                }

                // THEN update the coordinates
                dbPlace.updateCoordinates(newCoordinates.latitude, newCoordinates.longitude)
                Log.d(TAG, "AFTER updateCoordinates - Edited flag: ${dbPlace.edited}")

                Log.d(TAG, "DbPlace after coordinate update:")
                Log.d(TAG, dbPlace.debugCoordinateState())

                // Update route coordinates if needed
                val routeNeedsRecalculation = updateRouteCoordinatesForMovedPlace(dbPlace, oldCoordinates, newCoordinates)

                val markerName = dbPlace.getName()
                val statusMessage = if (routeNeedsRecalculation) {
                    "Moved '$markerName' and recalculated route"
                } else {
                    "Moved '$markerName' to new location"
                }

                Toast.makeText(this, statusMessage, Toast.LENGTH_SHORT).show()
                Log.d(TAG, "Successfully updated DbPlace: $statusMessage")
                Log.d(TAG, "FINAL - Edited flag: ${dbPlace.edited}")

                // Reverse geocode to refresh place name/address after move (dedicated engine)
                val options = com.here.sdk.search.SearchOptions().apply {
                    languageCode = com.here.sdk.core.LanguageCode.EN_GB
                    maxItems = 1
                }
                reverseGeocodeEngine.searchByCoordinates(newCoordinates, options, object : com.here.sdk.search.SearchCallback {
                    override fun onSearchCompleted(searchError: com.here.sdk.search.SearchError?, list: List<com.here.sdk.search.Place>?) {
                        if (searchError == null) {
                            val place = list?.firstOrNull()
                            place?.let {
                                val updatedTitle = it.title.ifBlank{ it.address.addressText  }
                                dbPlace.setPlaceName(updatedTitle)
                                dbPlace.setAddress(it.address.addressText)

                                val updatedMetadata = marker.metadata ?: com.here.sdk.core.Metadata()
                                updatedMetadata.setString("marker_title", dbPlace.getName())
                                marker.metadata = updatedMetadata

                                uiController.showPlaceDetails(it, dbPlace)
                            }
                        }
                    }
                })
            } else {
                Log.w(TAG, "Could not find DbPlace for old coordinates: ${oldCoordinates.latitude}, ${oldCoordinates.longitude}")
                Toast.makeText(this, "Warning: Could not update place coordinates", Toast.LENGTH_SHORT).show()
            }
        }

        // Handle long press on map to set origin / destination / waypoints
        mapController.onMapLongPress = { coords ->
            Log.d(TAG, "Handling long press at: ${coords.latitude}, ${coords.longitude}")

            // Decide target based on current state
            val targetType = when {
                selectedOrigin == null -> "origin"
                selectedDestination == null -> "destination"
                isSelectingDestination -> "destination"
                isSelectingWaypoint || routingExample.hasActiveRoute() -> "waypoint"
                else -> "destination"
            }

            // Sync selection flags to target
            when (targetType) {
                "origin" -> {
                    isSelectingOrigin = true
                    isSelectingDestination = false
                    isSelectingWaypoint = false
                }
                "destination" -> {
                    isSelectingOrigin = false
                    isSelectingDestination = true
                    isSelectingWaypoint = false
                    // Don't call switchToDestinationSelection() for long press - it shows search overlay
                }
                else -> {
                    isSelectingOrigin = false
                    isSelectingDestination = false
                    isSelectingWaypoint = true
                    // Don't call switchToWaypointSelection() for long press - it shows search overlay
                }
            }

            val displayName = when (targetType) {
                "origin" -> "Origin (${"%.5f".format(coords.latitude)}, ${"%.5f".format(coords.longitude)})"
                "destination" -> "Destination (${"%.5f".format(coords.latitude)}, ${"%.5f".format(coords.longitude)})"
                else -> "Waypoint (${"%.5f".format(coords.latitude)}, ${"%.5f".format(coords.longitude)})"
            }

            val dbPlace = DbPlace.create(
                placeName = displayName,
                latitude = coords.latitude,
                longitude = coords.longitude,
                isPassThrough = true
            )

            // Apply selection via existing handler (this triggers routing where appropriate)
            handleLocationSelection(coords, displayName, dbPlace)

            // Add marker and move camera
            val marker = mapController.addSearchMarker(coords, dbPlace, targetType)
            mapController.moveMapToLocation(coords)

            // Reverse geocode for proper title/address (dedicated engine)
            val options = com.here.sdk.search.SearchOptions().apply {
                languageCode = com.here.sdk.core.LanguageCode.EN_GB
                maxItems = 1
            }
            reverseGeocodeEngine.searchByCoordinates(coords, options, object : com.here.sdk.search.SearchCallback {
                override fun onSearchCompleted(searchError: com.here.sdk.search.SearchError?, list: List<com.here.sdk.search.Place>?) {
                    if (searchError == null) {
                        val place = list?.firstOrNull()
                        place?.let {
                            val updatedTitle = it.title.ifBlank{ it.address.addressText }
                            dbPlace.setPlaceName(updatedTitle)
                            dbPlace.setAddress(it.address.addressText)

                            val updatedMetadata = marker.metadata ?: com.here.sdk.core.Metadata()
                            updatedMetadata.setString("marker_title", dbPlace.getName())
                            marker.metadata = updatedMetadata

                            uiController.showPlaceDetails(it, dbPlace)
                            // Ensure action buttons are visible after reverse geocoding
                            uiController.updateActionButtonsVisibility()
                        }
                    }
                }
            })

            // Update UI details first (will be refined by reverse geocode callback if it returns)
            uiController.clearSearchState()
            uiController.hideSearchOverlay()
            uiController.showPlaceDetails(null, dbPlace)
            
            // Ensure search overlay is completely hidden for long press
            uiController.hideSearchOverlay()

            // Don't auto-advance selection state for long press - let user manually click buttons
            // This ensures place details with action buttons are always shown for long press

            Log.d(TAG, "Long press set $targetType: ${dbPlace.getName()}")
        }
    }

    private fun handleMarkerDeletion(marker: MapMarker) {
        val metadata = marker.metadata
        val markerType = metadata?.getString("marker_type")
        val markerCoords = marker.coordinates

        when (markerType) {
            "search_result" -> {
                // Find which DbPlace this marker represents
                val dbPlaceToDelete = findDbPlaceForCoordinates(markerCoords.latitude, markerCoords.longitude)
                val markerName = dbPlaceToDelete?.getName() ?: "marker"

                Log.d(TAG, "Attempting to delete marker: $markerName at $markerCoords")

                var deletionMessage = ""

                // Check if this is the origin marker
                if (selectedOrigin != null &&
                    abs(markerCoords.latitude - selectedOrigin!!.latitude) < 0.0001 &&
                    abs(markerCoords.longitude - selectedOrigin!!.longitude) < 0.0001) {

                    Log.d(TAG, "Deleting origin marker: $markerName")
                    selectedOrigin = null
                    originDbPlace = null

                    // Clear the entire route since origin is removed
                    routingExample.clearRouteAfterOriginDeletion()
                    deletionMessage = "Origin '$markerName' deleted - route cleared"
                }
                // Check if this is the destination marker
                else if (selectedDestination != null &&
                    abs(markerCoords.latitude - selectedDestination!!.latitude) < 0.0001 &&
                    abs(markerCoords.longitude - selectedDestination!!.longitude) < 0.0001) {

                    Log.d(TAG, "Deleting destination marker: $markerName")
                    selectedDestination = null
                    destinationDbPlace = null

                    // Clear the entire route since destination is removed
                    routingExample.clearRouteAfterDestinationDeletion()
                    deletionMessage = "Destination '$markerName' deleted - route cleared"
                }
                // Check if this is a waypoint marker
                else {
                    val waypointIndex = findWaypointIndexByCoordinates(markerCoords)
                    if (waypointIndex >= 0) {
                        Log.d(TAG, "Deleting waypoint marker at index $waypointIndex: $markerName")

                        // Remove from waypoint collections
                        if (waypointIndex < waypointDbPlaces.size) {
                            waypointDbPlaces.removeAt(waypointIndex)
                        }

                        // Remove waypoint and recalculate route
                        routingExample.removeWaypointAndRecalculate(waypointIndex)
                        deletionMessage = "Waypoint '$markerName' deleted - route recalculated"
                    } else {
                        Log.w(TAG, "Could not find waypoint to delete at coordinates: $markerCoords")
                        deletionMessage = "Marker '$markerName' deleted"
                    }
                }

                CustomToast.showInfo(this, deletionMessage)
                Log.d(TAG, "Marker deletion completed: $deletionMessage")
            }
            else -> {
                Log.w(TAG, "Attempted to delete marker with unknown type: $markerType")
                Toast.makeText(this, "Marker deleted", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun findWaypointIndexByCoordinates(coordinates: GeoCoordinates): Int {
        val tolerance = 0.0001

        // First check RoutingExample's waypoints (most reliable)
        val routingWaypointIndex = routingExample.findWaypointIndex(coordinates)
        if (routingWaypointIndex >= 0) {
            Log.d(TAG, "Found waypoint at index $routingWaypointIndex using RoutingExample")
            return routingWaypointIndex
        }

        // Fallback: check our local waypoint collections using coordinate comparison
        waypointDbPlaces.forEachIndexed { index, dbPlace ->
            if (abs(coordinates.latitude - dbPlace.latitude) < tolerance &&
                abs(coordinates.longitude - dbPlace.longitude) < tolerance) {
                Log.d(TAG, "Found waypoint at index $index using DbPlace collection")
                return index
            }
        }

        Log.d(TAG, "No waypoint found at coordinates: ${coordinates.latitude}, ${coordinates.longitude}")
        return -1
    }



    private fun handleMarkerEdit(marker: MapMarker) {
        val metadata = marker.metadata
        val markerType = metadata?.getString("marker_type")
        val currentMarkerCoords = marker.coordinates

        when (markerType) {
            "search_result" -> {
                // Find which DbPlace this marker represents using current coordinates
                val dbPlaceToEdit = findDbPlaceForCoordinates(currentMarkerCoords.latitude, currentMarkerCoords.longitude)

                dbPlaceToEdit?.let { dbPlace ->
                    Log.d(TAG, "Opening edit dialog for DbPlace:")
                    Log.d(TAG, dbPlace.toLogString())
                    Log.d(TAG, "Original edited flag: ${dbPlace.edited}")

                    // CRITICAL FIX: Mark as edited BEFORE opening dialog if coordinates moved
                    if (dbPlace.hasMovedCoordinates && !dbPlace.id.isNullOrBlank()) {
                        Log.d(TAG, "Marking place as edited due to moved coordinates")
                        markEdit(dbPlace)
                    }

                    val dialog = PlaceEditDialog.newInstance(dbPlace) { updatedDbPlace ->
                        // Update the DbPlace in collections while preserving coordinate updates
                        val finalDbPlace = if (dbPlace.hasMovedCoordinates) {
                            // If coordinates were moved, preserve them in the updated place
                            updatedDbPlace.withUpdatedCoordinates(dbPlace.latitude, dbPlace.longitude)
                                .also {
                                    if (!it.id.isNullOrBlank()){
                                        it.setEdited(true)
                                        Log.d(TAG, "DbPlace marked as edited after coordinate move: ${it.getName()}")
                                    }
                                }
                        } else {
                            updatedDbPlace
                        }
                        Log.d(TAG, "Final DbPlace edited flag: ${finalDbPlace.edited}")

                        updateDbPlaceInCollections(dbPlace, finalDbPlace, currentMarkerCoords.latitude, currentMarkerCoords.longitude)

                        // Update marker title using DbPlace.getName()
                        val newTitle = finalDbPlace.getName()

                        val updatedMetadata = marker.metadata ?: Metadata()
                        updatedMetadata.setString("marker_title", newTitle)
                        marker.metadata = updatedMetadata

                        // Show updated place details (pass original Place if available for compatibility)
                        uiController.showPlaceDetails(finalDbPlace.originalPlace, finalDbPlace)
                        CustomToast.showSuccess(this, "Place updated: $newTitle")

                        Log.d(TAG, "Final updated DbPlace:")
                        Log.d(TAG, finalDbPlace.toLogString())
                    }

                    dialog.show(supportFragmentManager, "PlaceEditDialog")
                } ?: run {
                    CustomToast.showWarning(this, "No location selected to save")
                    Log.w(TAG, "Could not find DbPlace for marker at coordinates: $currentMarkerCoords")
                }
            }
        }
    }

    private fun updateDbPlaceInCollections(oldDbPlace: DbPlace, newDbPlace: DbPlace, latitude: Double, longitude: Double) {
        // Update origin
        markEdit(newDbPlace)
        if (originDbPlace == oldDbPlace) {
            originDbPlace = newDbPlace
            Log.d(TAG, "Updated origin DbPlace: ${newDbPlace.getName()}")
            return
        }

        // Update destination
        if (destinationDbPlace == oldDbPlace) {
            destinationDbPlace = newDbPlace
            Log.d(TAG, "Updated destination DbPlace: ${newDbPlace.getName()}")
            return
        }

        // Update waypoint
        val waypointIndex = waypointDbPlaces.indexOfFirst { it == oldDbPlace }
        if (waypointIndex >= 0) {
            waypointDbPlaces[waypointIndex] = newDbPlace
            Log.d(TAG, "Updated waypoint DbPlace at index $waypointIndex: ${newDbPlace.getName()}")
        }
    }

    private fun findDbPlaceForCoordinates(latitude: Double, longitude: Double): DbPlace? {
        val tolerance = 0.0001

        Log.d(TAG, "findDbPlaceForCoordinates called with: $latitude, $longitude")

        // Check origin - compare with current coordinates
        originDbPlace?.let { dbPlace ->
            Log.d(TAG, "Checking origin DbPlace: ${dbPlace.latitude}, ${dbPlace.longitude}")
            if (abs(latitude - dbPlace.latitude) < tolerance &&
                abs(longitude - dbPlace.longitude) < tolerance) {
                Log.d(TAG, "Found matching origin DbPlace")
                return dbPlace
            }
        }

        // Check destination - compare with current coordinates
        destinationDbPlace?.let { dbPlace ->
            Log.d(TAG, "Checking destination DbPlace: ${dbPlace.latitude}, ${dbPlace.longitude}")
            if (abs(latitude - dbPlace.latitude) < tolerance &&
                abs(longitude - dbPlace.longitude) < tolerance) {
                Log.d(TAG, "Found matching destination DbPlace")
                return dbPlace
            }
        }

        // Check waypoints - compare with current coordinates
        waypointDbPlaces.forEachIndexed { index, dbPlace ->
            Log.d(TAG, "Checking waypoint $index DbPlace: ${dbPlace.latitude}, ${dbPlace.longitude}")
            if (abs(latitude - dbPlace.latitude) < tolerance &&
                abs(longitude - dbPlace.longitude) < tolerance) {
                Log.d(TAG, "Found matching waypoint $index DbPlace")
                return dbPlace
            }
        }

        Log.d(TAG, "No matching DbPlace found for coordinates: $latitude, $longitude")
        return null
    }

    private fun handleLocationSelection(coordinates: GeoCoordinates, title: String, dbPlace: DbPlace) {
        when {
            isSelectingOrigin -> {
                selectedOrigin = coordinates
                originDbPlace = dbPlace
                routingExample.setOrigin(coordinates)
                val displayName = dbPlace.getName()
                Log.d(TAG, "Origin selected: $displayName at ${coordinates.latitude}, ${coordinates.longitude}")
                Log.d(TAG, "Origin DbPlace: ${dbPlace.toLogString()}")
            }
            isSelectingDestination -> {
                selectedDestination = coordinates
                destinationDbPlace = dbPlace
                routingExample.setDestination(coordinates)
                val displayName = dbPlace.getName()
                Log.d(TAG, "Destination selected: $displayName at ${coordinates.latitude}, ${coordinates.longitude}")
                Log.d(TAG, "Destination DbPlace: ${dbPlace.toLogString()}")
            }
            isSelectingWaypoint -> {
                routingExample.addWaypoint(coordinates)
                waypointDbPlaces.add(dbPlace)
                val displayName = dbPlace.getName()
                Log.d(TAG, "Waypoint added: $displayName at ${coordinates.latitude}, ${coordinates.longitude}")
                Log.d(TAG, "Waypoint DbPlaces count: ${waypointDbPlaces.size}")
            }
        }
    }

    private fun updateRouteCoordinatesForMovedPlace(
        dbPlace: DbPlace,
        oldCoordinates: GeoCoordinates,
        newCoordinates: GeoCoordinates
    ): Boolean {
        if (!routingExample.hasActiveRoute()) {
            return false
        }

        var routeNeedsRecalculation = false
        val tolerance = 0.0001

        Log.d(TAG, "updateRouteCoordinatesForMovedPlace:")
        Log.d(TAG, "  DbPlace: ${dbPlace.getName()}")
        Log.d(TAG, "  Old coordinates: ${oldCoordinates.latitude}, ${oldCoordinates.longitude}")
        Log.d(TAG, "  New coordinates: ${newCoordinates.latitude}, ${newCoordinates.longitude}")
        markEdit(dbPlace)

        // Check if this is the origin
        if (selectedOrigin != null &&
            abs(oldCoordinates.latitude - selectedOrigin!!.latitude) < tolerance &&
            abs(oldCoordinates.longitude - selectedOrigin!!.longitude) < tolerance) {

            Log.d(TAG, "Updating ORIGIN route coordinates")
            selectedOrigin = newCoordinates
            routingExample.updateOrigin(newCoordinates)
            routeNeedsRecalculation = true
        }
        // Check if this is the destination
        else if (selectedDestination != null &&
            abs(oldCoordinates.latitude - selectedDestination!!.latitude) < tolerance &&
            abs(oldCoordinates.longitude - selectedDestination!!.longitude) < tolerance) {

            Log.d(TAG, "Updating DESTINATION route coordinates")
            selectedDestination = newCoordinates
            routingExample.updateDestination(newCoordinates)
            routeNeedsRecalculation = true
        }
        // Check if this is a waypoint
        else {
            val waypointIndex = routingExample.findWaypointIndex(oldCoordinates)
            if (waypointIndex >= 0) {
                Log.d(TAG, "Updating WAYPOINT $waypointIndex route coordinates")
                routingExample.updateWaypoint(waypointIndex, newCoordinates)
                routeNeedsRecalculation = true
            }
        }

        Log.d(TAG, "Route needs recalculation: $routeNeedsRecalculation")
        return routeNeedsRecalculation
    }

    private fun updateRouteCoordinates(marker: com.here.sdk.mapview.MapMarker, newCoordinates: GeoCoordinates): Boolean {
        if (!routingExample.hasActiveRoute()) {
            return false
        }

        var routeNeedsRecalculation = false
        val metadata = marker.metadata
        val markerType = metadata?.getString("marker_type")
        val currentMarkerCoords = marker.coordinates

        Log.d(TAG, "updateRouteCoordinates called:")
        Log.d(TAG, "  Marker type: $markerType")
        Log.d(TAG, "  Current marker coords: ${currentMarkerCoords.latitude}, ${currentMarkerCoords.longitude}")
        Log.d(TAG, "  New coordinates: ${newCoordinates.latitude}, ${newCoordinates.longitude}")

        when (markerType) {
            "search_result" -> {
                // Check if this is the origin marker
                if (selectedOrigin != null &&
                    abs(currentMarkerCoords.latitude - selectedOrigin!!.latitude) < 0.0001 &&
                    abs(currentMarkerCoords.longitude - selectedOrigin!!.longitude) < 0.0001) {

                    Log.d(TAG, "Updating ORIGIN coordinates")

                    // CRITICAL FIX: Update selectedOrigin FIRST
                    selectedOrigin = newCoordinates

                    // CRITICAL FIX: Update origin DbPlace with new coordinates
                    originDbPlace?.let { dbPlace ->
                        Log.d(TAG, "Origin DbPlace BEFORE update:")
                        Log.d(TAG, dbPlace.debugCoordinateState())

                        // Update the DbPlace coordinates directly
                        dbPlace.updateCoordinates(newCoordinates.latitude, newCoordinates.longitude)

                        Log.d(TAG, "Origin DbPlace AFTER update:")
                        Log.d(TAG, dbPlace.debugCoordinateState())
                    } ?: run {
                        Log.w(TAG, "Origin DbPlace is null - cannot update coordinates")
                    }

                    routingExample.updateOrigin(newCoordinates)
                    routeNeedsRecalculation = true
                    Log.d(TAG, "Updated origin coordinates and DbPlace")
                }
                // Check if this is the destination marker
                else if (selectedDestination != null &&
                    abs(currentMarkerCoords.latitude - selectedDestination!!.latitude) < 0.0001 &&
                    abs(currentMarkerCoords.longitude - selectedDestination!!.longitude) < 0.0001) {

                    Log.d(TAG, "Updating DESTINATION coordinates")

                    // CRITICAL FIX: Update selectedDestination FIRST
                    selectedDestination = newCoordinates

                    // CRITICAL FIX: Update destination DbPlace with new coordinates
                    destinationDbPlace?.let { dbPlace ->
                        Log.d(TAG, "Destination DbPlace BEFORE update:")
                        Log.d(TAG, dbPlace.debugCoordinateState())

                        // Update the DbPlace coordinates directly
                        dbPlace.updateCoordinates(newCoordinates.latitude, newCoordinates.longitude)

                        Log.d(TAG, "Destination DbPlace AFTER update:")
                        Log.d(TAG, dbPlace.debugCoordinateState())
                    } ?: run {
                        Log.w(TAG, "Destination DbPlace is null - cannot update coordinates")
                    }

                    routingExample.updateDestination(newCoordinates)
                    routeNeedsRecalculation = true
                    Log.d(TAG, "Updated destination coordinates and DbPlace")
                }
                // Check if this is a waypoint marker
                else {
                    val waypointIndex = routingExample.findWaypointIndex(currentMarkerCoords)
                    if (waypointIndex >= 0 && waypointIndex < waypointDbPlaces.size) {
                        Log.d(TAG, "Updating WAYPOINT $waypointIndex coordinates")

                        // CRITICAL FIX: Update waypoint DbPlace with new coordinates
                        val dbPlace = waypointDbPlaces[waypointIndex]

                        Log.d(TAG, "Waypoint DbPlace BEFORE update:")
                        Log.d(TAG, dbPlace.debugCoordinateState())

                        // Update the DbPlace coordinates directly
                        dbPlace.updateCoordinates(newCoordinates.latitude, newCoordinates.longitude)

                        Log.d(TAG, "Waypoint DbPlace AFTER update:")
                        Log.d(TAG, dbPlace.debugCoordinateState())

                        routingExample.updateWaypoint(waypointIndex, newCoordinates)
                        routeNeedsRecalculation = true
                        Log.d(TAG, "Updated waypoint $waypointIndex coordinates and DbPlace")
                    } else {
                        Log.w(TAG, "Could not find waypoint to update at coordinates: $currentMarkerCoords")
                    }
                }
            }
            else -> {
                Log.w(TAG, "Unknown marker type: $markerType")
            }
        }

        Log.d(TAG, "updateRouteCoordinates completed. Route needs recalculation: $routeNeedsRecalculation")
        return routeNeedsRecalculation
    }



    private fun resetToInitialState() {
        isSelectingOrigin = true
        isSelectingDestination = false
        isSelectingWaypoint = false

        selectedOrigin = null
        selectedDestination = null
        currentSelectedPlace = null
        currentRouteHandle = null

        // Clear DbPlace objects
        originDbPlace = null
        destinationDbPlace = null
        waypointDbPlaces.clear()

        mapController.cancelMarkerMove()
        uiController.clearSearchState()
        uiController.resetToInitialState()

        routingExample.clearRouteData()
        mapController.clearSearchMarkers()

        Log.d(TAG, "Reset to initial state completed - all places cleared")
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun getLocation(onLocationReceived: (GeoCoordinates?) -> Unit) {
        try {
            locationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    val geoCoordinates = GeoCoordinates(location.latitude, location.longitude)
                    Log.d(TAG, "Current location: $geoCoordinates")
                    onLocationReceived(geoCoordinates)
                } else {
                    Log.d(TAG, "Location is null.")
                    onLocationReceived(null)
                }
            }.addOnFailureListener { e ->
                Log.e(TAG, "Failed to get last location: ${e.message}")
                onLocationReceived(null)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission not granted: ${e.message}")
            onLocationReceived(null)
        }
    }

    private fun initializeSearchEngine() {
        try {
            searchEngine = SearchEngine()
            reverseGeocodeEngine = SearchEngine()
        } catch (e: InstantiationErrorException) {
            throw RuntimeException("Initialization of SearchEngine failed: " + e.error.name)
        }
    }

    private fun initializeHERESDK() {
        val accessKeyID = BuildConfig.HERE_ACCESS_KEY_ID
        val accessKeySecret = BuildConfig.HERE_ACCESS_KEY_SECRET
        val authenticationMode = AuthenticationMode.withKeySecret(accessKeyID, accessKeySecret)
        val options = SDKOptions(authenticationMode)
        try {
            SDKNativeEngine.makeSharedInstance(this, options)
        } catch (e: InstantiationErrorException) {
            throw RuntimeException("Initialization of HERE SDK failed: " + e.error.name)
        }
    }

    private fun disposeHERESDK() {
        SDKNativeEngine.getSharedInstance()?.dispose()
        SDKNativeEngine.setSharedInstance(null)
    }

    /**
     * Check if there are any unsaved locations on the map
     * Returns true if there are locations that haven't been saved to backend
     */
    private fun hasUnsavedLocations(): Boolean {
        // Check if origin exists and is unsaved
        val hasUnsavedOrigin = originDbPlace?.let { dbPlace ->
            dbPlace.id.isNullOrBlank() || dbPlace.edited
        } ?: false

        // Check if destination exists and is unsaved
        val hasUnsavedDestination = destinationDbPlace?.let { dbPlace ->
            dbPlace.id.isNullOrBlank() || dbPlace.edited
        } ?: false

        // Check if any waypoints exist and are unsaved
        val hasUnsavedWaypoints = waypointDbPlaces.any { dbPlace ->
            dbPlace.id.isNullOrBlank() || dbPlace.edited
        }

        val hasAnyLocation = selectedOrigin != null || selectedDestination != null || waypointDbPlaces.isNotEmpty()
        val hasUnsavedLocation = hasUnsavedOrigin || hasUnsavedDestination || hasUnsavedWaypoints

        Log.d(TAG, "hasUnsavedLocations check:")
        Log.d(TAG, "  Has any location: $hasAnyLocation")
        Log.d(TAG, "  Has unsaved origin: $hasUnsavedOrigin")
        Log.d(TAG, "  Has unsaved destination: $hasUnsavedDestination")
        Log.d(TAG, "  Has unsaved waypoints: $hasUnsavedWaypoints")
        Log.d(TAG, "  Has unsaved location: $hasUnsavedLocation")

        return hasAnyLocation && hasUnsavedLocation
    }

    override fun onPause() {
        mapView?.onPause()
        super.onPause()
    }

    override fun onResume() {
        mapView?.onResume()
        super.onResume()
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d(TAG, "onNewIntent called with action: ${intent.action}")
        
        // Update the intent and process shared location
        this.intent = intent
        processSharedLocation()
    }

    override fun onDestroy() {
        mapController.clearSearchMarkers()
        mapView?.onDestroy()
        disposeHERESDK()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        mapView?.onSaveInstanceState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onBackPressed() {
        Log.d(TAG, "Back button pressed")
        
        // Check if search overlay is visible
        if (searchOverlay.isVisible) {
            Log.d(TAG, "Search overlay is visible - closing search")
            uiController.hideSearchOverlay()
            return
        }

        // Check if search container is visible (search interface is open)
        if (searchContainer.isVisible) {
            Log.d(TAG, "Search container is visible - closing search")
            uiController.hideSearchOverlay()
            return
        }

        // Check if there are unsaved locations on the map
        if (hasUnsavedLocations()) {
            Log.d(TAG, "Unsaved locations detected - showing confirmation dialog")
            showExitConfirmationDialog()
            return
        }

        // No unsaved locations - exit normally
        Log.d(TAG, "No unsaved locations - exiting activity")
        super.onBackPressed()
    }

    private fun showExitConfirmationDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Exit Route Planning")
            .setMessage("You have unsaved locations on the map. The map will be cleared if you exit.")
            .setPositiveButton("Exit") { _, _ ->
                Log.d(TAG, "User confirmed exit - clearing map and exiting")
                resetToInitialState()
                finish()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                Log.d(TAG, "User cancelled exit")
                dialog.dismiss()
            }
            .setCancelable(true)
            .show()
    }
}