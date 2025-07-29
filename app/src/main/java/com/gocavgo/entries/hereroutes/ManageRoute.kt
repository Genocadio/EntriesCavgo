package com.gocavgo.entries.hereroutes

import android.Manifest
import android.os.Bundle
import android.util.Log
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
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

class ManageRoute : AppCompatActivity() {

    private var mapView: MapView? = null
    private lateinit var locationClient: FusedLocationProviderClient
    private val TAG = ManageRoute::class.java.simpleName
    private lateinit var mapStyleFab: com.google.android.material.floatingactionbutton.FloatingActionButton
    private lateinit var searchAutoComplete: AutoCompleteTextView
    private lateinit var clearSearchButton: ImageButton
    private lateinit var myLocationFab: com.google.android.material.floatingactionbutton.FloatingActionButton
    private lateinit var searchEngine: SearchEngine

    // UI containers
    private lateinit var bottomContainer: LinearLayout
    private lateinit var searchContainer: LinearLayout
    private lateinit var searchOverlay: LinearLayout
    private lateinit var searchHintText: TextView
    private lateinit var backSearchButton: ImageButton
    private lateinit var placeDetailsContainer: LinearLayout
    private lateinit var routeSummaryContainer: LinearLayout
    private lateinit var routeActionsContainer: LinearLayout

    // Place details views
    private lateinit var tvPlaceTitle: TextView
    private lateinit var tvPlaceAddress: TextView
    private lateinit var tvPlaceType: TextView

    // Route summary views
    private lateinit var tvRouteDistance: TextView
    private lateinit var tvRouteDuration: TextView
    private lateinit var btnStartNavigation: Button

    // Components
    private lateinit var routingExample: RoutingExample
    private lateinit var searchManager: SearchManager
    private lateinit var uiController: UIController
    private lateinit var mapController: MapController

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

    private var currentSchemeIndex = 0

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
        btnStartNavigation = findViewById(R.id.btn_start_navigation)

        // Location action buttons
        locationActionsContainer = findViewById(R.id.location_actions_container)
        saveLocationButton = findViewById(R.id.btn_save_location)
        addDestinationButton = findViewById(R.id.btn_add_destination)
        addWaypointButton = findViewById(R.id.btn_add_waypoint)
        saveRouteButton = findViewById(R.id.btn_save_route)
        clearRouteButton = findViewById(R.id.btn_clear_route)
    }

    private fun initializeComponents() {
        routingExample = RoutingExample(this, mapView!!)
        searchManager = SearchManager(this)

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
            tvRouteDuration,
            btnStartNavigation
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
        routingExample.onRouteCalculated = { route ->
            route?.let {
                uiController.updateRouteSummaryWithActualData(it)
            } ?: run {
                routeSummaryContainer.visibility = android.view.View.GONE
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
                handleLocationSelection(coordinates, place.title)
                mapController.addSearchMarker(coordinates, place.title)
                mapController.moveMapToLocation(coordinates)

                uiController.clearSearchState()
                uiController.hideSearchOverlay()
                uiController.showPlaceDetails(place)
            }
        }

        searchManager.onSearchError = { errorMessage ->
            runOnUiThread {
                Toast.makeText(this, errorMessage, Toast.LENGTH_SHORT).show()
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

    private fun setupUIControllerCallbacks() {
        uiController.onLocationActionRequested = { action ->
            when (action) {
                "save_location" -> {
                    currentSelectedPlace?.let { place ->
                        Log.d(TAG, "Location saved: ${place.title} - ${place.address}")
                        Toast.makeText(this, "Location '${place.title}' saved to log", Toast.LENGTH_SHORT).show()
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
                    Log.d(TAG, "Route saved with origin: $selectedOrigin, destination: $selectedDestination")
                    Toast.makeText(this, "Route saved to log", Toast.LENGTH_SHORT).show()
                }
                "clear_route" -> {
                    resetToInitialState()
                }
                "start_navigation" -> {
                    Toast.makeText(this, "Starting navigation...", Toast.LENGTH_SHORT).show()
                }
            }
        }

        uiController.onSelectionStateRequest = {
            Triple(isSelectingOrigin, isSelectingDestination, isSelectingWaypoint)
        }

        uiController.onRouteDataRequest = {
            Pair(selectedOrigin != null && selectedDestination != null,
                routeSummaryContainer.isVisible)
        }

        // NEW: Handle auto-suggest requests (not direct search)
        uiController.onAutoSuggestRequested = { query ->
            val centerCoordinates = mapView?.camera?.state?.targetCoordinates
            centerCoordinates?.let {
                searchManager.performAutoSuggest(query, it)  // Only perform auto-suggest, not search
            }
        }
    }

    private fun setupMapControllerCallbacks() {
        mapController.onLocationReceived = { coordinates ->
            mapController.clearSearchMarkers()
            mapController.moveMapToLocation(coordinates)
            mapController.showLocationIndicatorPedestrian(coordinates)
        }

        mapController.onMarkerActionRequested = { action, marker ->
            when (action) {
                "remove" -> mapController.removeMarker(marker)
                "move" -> mapController.startMarkerMove(marker)
                "edit" -> mapController.editMarker(marker)
                "save" -> mapController.saveMarker(marker)
            }
        }

        mapController.onMarkerMoved = { marker, newCoordinates ->
            val routeNeedsRecalculation = updateRouteCoordinates(marker, newCoordinates)
            val metadata = marker.metadata
            val markerTitle = metadata?.getString("marker_title") ?: "marker"

            val statusMessage = if (routeNeedsRecalculation) {
                "Moved '$markerTitle' and recalculated route"
            } else {
                "Moved '$markerTitle' to new location"
            }

            Toast.makeText(this, statusMessage, Toast.LENGTH_SHORT).show()
        }
    }

    fun getSelectionState() = Triple(isSelectingOrigin, isSelectingDestination, isSelectingWaypoint)

    fun getCurrentSelectedPlace() = currentSelectedPlace

    fun getRouteData() = Triple(selectedOrigin, selectedDestination, routingExample.hasActiveRoute())

    private fun handleLocationSelection(coordinates: GeoCoordinates, title: String) {
        when {
            isSelectingOrigin -> {
                selectedOrigin = coordinates
                routingExample.setOrigin(coordinates)
                Log.d(TAG, "Origin selected: $title at ${coordinates.latitude}, ${coordinates.longitude}")
            }
            isSelectingDestination -> {
                selectedDestination = coordinates
                routingExample.setDestination(coordinates)
                Log.d(TAG, "Destination selected: $title at ${coordinates.latitude}, ${coordinates.longitude}")
            }
            isSelectingWaypoint -> {
                routingExample.addWaypoint(coordinates)
                Log.d(TAG, "Waypoint added: $title at ${coordinates.latitude}, ${coordinates.longitude}")
            }
        }
    }

    private fun updateRouteCoordinates(marker: com.here.sdk.mapview.MapMarker, newCoordinates: GeoCoordinates): Boolean {
        if (!routingExample.hasActiveRoute()) {
            return false
        }

        var routeNeedsRecalculation = false
        val metadata = marker.metadata
        val markerType = metadata?.getString("marker_type")
        val currentMarkerCoords = marker.coordinates

        when (markerType) {
            "search_result" -> {
                if (selectedOrigin != null &&
                    Math.abs(currentMarkerCoords.latitude - selectedOrigin!!.latitude) < 0.0001 &&
                    Math.abs(currentMarkerCoords.longitude - selectedOrigin!!.longitude) < 0.0001) {

                    selectedOrigin = newCoordinates
                    routingExample.updateOrigin(newCoordinates)
                    routeNeedsRecalculation = true
                    Log.d(TAG, "Updated origin coordinates and triggered route recalculation")
                }
                else if (selectedDestination != null &&
                    Math.abs(currentMarkerCoords.latitude - selectedDestination!!.latitude) < 0.0001 &&
                    Math.abs(currentMarkerCoords.longitude - selectedDestination!!.longitude) < 0.0001) {

                    selectedDestination = newCoordinates
                    routingExample.updateDestination(newCoordinates)
                    routeNeedsRecalculation = true
                    Log.d(TAG, "Updated destination coordinates and triggered route recalculation")
                }
                else {
                    val waypointIndex = routingExample.findWaypointIndex(currentMarkerCoords)
                    if (waypointIndex >= 0) {
                        routingExample.updateWaypoint(waypointIndex, newCoordinates)
                        routeNeedsRecalculation = true
                        Log.d(TAG, "Updated waypoint $waypointIndex coordinates and triggered route recalculation")
                    }
                }
            }
        }

        return routeNeedsRecalculation
    }

    private fun resetToInitialState() {
        isSelectingOrigin = true
        isSelectingDestination = false
        isSelectingWaypoint = false

        selectedOrigin = null
        selectedDestination = null
        currentSelectedPlace = null

        mapController.cancelMarkerMove()
        uiController.clearSearchState()
        uiController.resetToInitialState()

        routingExample.clearRouteData()
        mapController.clearSearchMarkers()

        Log.d(TAG, "Reset to initial state completed")
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

    override fun onPause() {
        mapView?.onPause()
        super.onPause()
    }

    override fun onResume() {
        mapView?.onResume()
        super.onResume()
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
}