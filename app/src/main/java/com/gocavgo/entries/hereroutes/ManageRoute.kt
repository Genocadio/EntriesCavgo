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

    // NEW: Place objects for route planning
    private var originPlace: Place? = null
    private var destinationPlace: Place? = null
    private var waypointPlaces: MutableList<Place> = mutableListOf()

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
                handleLocationSelection(coordinates, place.title, place)

                // Create DbPlace and determine marker type
                val dbPlace = DbPlace.fromPlace(place)
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

                // Show place details using DbPlace
                uiController.showPlaceDetails(place, dbPlace)
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
                    // NEW: Validate and create FinalRoute before saving
                    val finalRoute = uiController.validateAndCreateFinalRoute()
                    if (finalRoute != null) {
                        logFinalRouteDetails(finalRoute)
                        Toast.makeText(this, "Route '${finalRoute.routeName ?: "Unnamed Route"}' saved successfully!", Toast.LENGTH_LONG).show()
                    } else {
                        // Error message is already shown by validateAndCreateFinalRoute
                        Log.w(TAG, "Failed to save route - validation failed")
                    }
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
            val centerCoordinates = mapView?.camera?.state?.targetCoordinates
            centerCoordinates?.let {
                searchManager.performAutoSuggest(query, it)
            }
        }

        // Setup additional button listeners
        setupAdditionalButtons()
    }

    private fun logFinalRouteDetails(finalRoute: FinalRoute) {
        Log.d(TAG, finalRoute.toLogString())

        // Additional summary log
        Log.d(TAG, "=== ROUTE SAVE SUMMARY ===")
        Log.d(TAG, "Route Name: ${finalRoute.routeName ?: "Unnamed Route"}")
        Log.d(TAG, "Total Price: ${finalRoute.getTotalPrice()}")
        Log.d(TAG, "City Route: ${finalRoute.isCityRoute}")
        Log.d(TAG, "Stops: ${finalRoute.origin.getName()} → ${finalRoute.destination.getName()}")
        if (finalRoute.waypoints.isNotEmpty()) {
            Log.d(TAG, "Waypoints: ${finalRoute.waypoints.size}")
        }
        Log.d(TAG, "=========================")
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
            }
        }

        mapController.onMarkerMoved = { marker, newCoordinates ->
            val routeNeedsRecalculation = updateRouteCoordinates(marker, newCoordinates)

            // Find the updated DbPlace to get the proper display name
            val dbPlace = findDbPlaceForMarker(newCoordinates)
            val markerName = dbPlace?.getName() ?: "marker"

            val statusMessage = if (routeNeedsRecalculation) {
                "Moved '$markerName' and recalculated route"
            } else {
                "Moved '$markerName' to new location"
            }

            Toast.makeText(this, statusMessage, Toast.LENGTH_SHORT).show()

            // Log the updated DbPlace details
            dbPlace?.let {
                Log.d(TAG, "Updated DbPlace after move:")
                Log.d(TAG, it.toLogString())
            }
        }
    }

    private fun handleMarkerDeletion(marker: MapMarker) {
        val metadata = marker.metadata
        val markerType = metadata?.getString("marker_type")
        val markerCoords = marker.coordinates

        when (markerType) {
            "search_result" -> {
                // Find which DbPlace this marker represents
                val dbPlaceToDelete = findDbPlaceForMarker(markerCoords)
                val markerName = dbPlaceToDelete?.getName() ?: "marker"

                Log.d(TAG, "Attempting to delete marker: $markerName at $markerCoords")

                var deletionMessage = ""

                // Check if this is the origin marker
                if (selectedOrigin != null &&
                    abs(markerCoords.latitude - selectedOrigin!!.latitude) < 0.0001 &&
                    abs(markerCoords.longitude - selectedOrigin!!.longitude) < 0.0001) {

                    Log.d(TAG, "Deleting origin marker: $markerName")
                    selectedOrigin = null
                    originPlace = null
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
                    destinationPlace = null
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

                        // Remove from all waypoint collections
                        if (waypointIndex < waypointPlaces.size) {
                            waypointPlaces.removeAt(waypointIndex)
                        }
                        if (waypointIndex < waypointDbPlaces.size) {
                            waypointDbPlaces.removeAt(waypointIndex)
                        }

                        // Remove waypoint and recalculate route if origin and destination exist
                        routingExample.removeWaypointAndRecalculate(waypointIndex)
                        deletionMessage = "Waypoint '$markerName' deleted - route recalculated"
                    } else {
                        Log.w(TAG, "Could not find waypoint to delete at coordinates: $markerCoords")
                        deletionMessage = "Marker '$markerName' deleted"
                    }
                }

                Toast.makeText(this, deletionMessage, Toast.LENGTH_LONG).show()
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

        // Fallback: check our local waypoint collections
        waypointDbPlaces.forEachIndexed { index, dbPlace ->
            dbPlace.geoCoordinates?.let { dbCoords ->
                if (abs(coordinates.latitude - dbCoords.latitude) < tolerance &&
                    abs(coordinates.longitude - dbCoords.longitude) < tolerance) {
                    Log.d(TAG, "Found waypoint at index $index using DbPlace collection")
                    return index
                }
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
                val dbPlaceToEdit = findDbPlaceForMarker(currentMarkerCoords)

                dbPlaceToEdit?.let { dbPlace ->
                    Log.d(TAG, "Opening edit dialog for DbPlace:")
                    Log.d(TAG, dbPlace.toLogString())

                    val dialog = PlaceEditDialog.newInstance(dbPlace) { updatedDbPlace ->
                        // Update the DbPlace in collections while preserving coordinate updates
                        val finalDbPlace = if (dbPlace.hasMovedCoordinates) {
                            // If coordinates were moved, preserve them in the updated place
                            updatedDbPlace.copy(updatedCoordinates = dbPlace.updatedCoordinates)
                        } else {
                            updatedDbPlace
                        }

                        updateDbPlaceInCollections(dbPlace, finalDbPlace, currentMarkerCoords)

                        // Update marker title using DbPlace.getName()
                        val newTitle = finalDbPlace.getName()

                        val updatedMetadata = marker.metadata ?: Metadata()
                        updatedMetadata.setString("marker_title", newTitle)
                        marker.metadata = updatedMetadata
                        // After updating the DbPlace in collections and marker metadata
                        uiController.showPlaceDetails(finalDbPlace.originalPlace, finalDbPlace)
                        Toast.makeText(this, "Place updated: $newTitle", Toast.LENGTH_SHORT).show()

                        Log.d(TAG, "Final updated DbPlace:")
                        Log.d(TAG, finalDbPlace.toLogString())
                    }

                    dialog.show(supportFragmentManager, "PlaceEditDialog")
                } ?: run {
                    Toast.makeText(this, "Unable to edit this marker", Toast.LENGTH_SHORT).show()
                    Log.w(TAG, "Could not find DbPlace for marker at coordinates: $currentMarkerCoords")
                }
            }
        }
    }
    private fun updateDbPlaceInCollections(oldDbPlace: DbPlace, newDbPlace: DbPlace, coordinates: GeoCoordinates) {
        // Update origin
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
    private fun findDbPlaceForMarker(coordinates: GeoCoordinates): DbPlace? {
        val tolerance = 0.0001

        // Check origin - compare with current coordinates (updated or original)
        originDbPlace?.let { dbPlace ->
            dbPlace.geoCoordinates?.let { dbPlaceCoords ->
                if (abs(coordinates.latitude - dbPlaceCoords.latitude) < tolerance &&
                    abs(coordinates.longitude - dbPlaceCoords.longitude) < tolerance) {
                    return dbPlace
                }
            }
        }

        // Check destination - compare with current coordinates (updated or original)
        destinationDbPlace?.let { dbPlace ->
            dbPlace.geoCoordinates?.let { dbPlaceCoords ->
                if (abs(coordinates.latitude - dbPlaceCoords.latitude) < tolerance &&
                    abs(coordinates.longitude - dbPlaceCoords.longitude) < tolerance) {
                    return dbPlace
                }
            }
        }

        // Check waypoints - compare with current coordinates (updated or original)
        waypointDbPlaces.forEach { dbPlace ->
            dbPlace.geoCoordinates?.let { dbPlaceCoords ->
                if (abs(coordinates.latitude - dbPlaceCoords.latitude) < tolerance &&
                    abs(coordinates.longitude - dbPlaceCoords.longitude) < tolerance) {
                    return dbPlace
                }
            }
        }

        return null
    }

    private fun handleLocationSelection(coordinates: GeoCoordinates, title: String, place: Place? = null) {
        when {
            isSelectingOrigin -> {
                selectedOrigin = coordinates
                originPlace = place
                originDbPlace = place?.let { DbPlace.fromPlace(it) }
                routingExample.setOrigin(coordinates)
                val displayName = originDbPlace?.getName() ?: title
                Log.d(TAG, "Origin selected: $displayName at ${coordinates.latitude}, ${coordinates.longitude}")
                Log.d(TAG, "Origin DbPlace: ${originDbPlace?.toLogString()}")
            }
            isSelectingDestination -> {
                selectedDestination = coordinates
                destinationPlace = place
                destinationDbPlace = place?.let { DbPlace.fromPlace(it) }
                routingExample.setDestination(coordinates)
                val displayName = destinationDbPlace?.getName() ?: title
                Log.d(TAG, "Destination selected: $displayName at ${coordinates.latitude}, ${coordinates.longitude}")
                Log.d(TAG, "Destination DbPlace: ${destinationDbPlace?.toLogString()}")
            }
            isSelectingWaypoint -> {
                routingExample.addWaypoint(coordinates)
                place?.let {
                    waypointPlaces.add(it)
                    waypointDbPlaces.add(DbPlace.fromPlace(it))
                }
                val displayName = waypointDbPlaces.lastOrNull()?.getName() ?: title
                Log.d(TAG, "Waypoint added: $displayName at ${coordinates.latitude}, ${coordinates.longitude}")
                Log.d(TAG, "Waypoint DbPlaces count: ${waypointDbPlaces.size}")
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
                // Find and update the corresponding DbPlace
                val dbPlaceToUpdate = findDbPlaceForMarker(currentMarkerCoords)

                if (selectedOrigin != null &&
                    abs(currentMarkerCoords.latitude - selectedOrigin!!.latitude) < 0.0001 &&
                    abs(currentMarkerCoords.longitude - selectedOrigin!!.longitude) < 0.0001) {

                    selectedOrigin = newCoordinates

                    // Update origin DbPlace with new coordinates
                    originDbPlace?.let { oldDbPlace ->
                        val updatedDbPlace = oldDbPlace.withUpdatedCoordinates(newCoordinates)
                        updateDbPlaceInCollections(oldDbPlace, updatedDbPlace, newCoordinates)
                    }

                    routingExample.updateOrigin(newCoordinates)
                    routeNeedsRecalculation = true
                    Log.d(TAG, "Updated origin coordinates and DbPlace")
                }
                else if (selectedDestination != null &&
                    abs(currentMarkerCoords.latitude - selectedDestination!!.latitude) < 0.0001 &&
                    abs(currentMarkerCoords.longitude - selectedDestination!!.longitude) < 0.0001) {

                    selectedDestination = newCoordinates

                    // Update destination DbPlace with new coordinates
                    destinationDbPlace?.let { oldDbPlace ->
                        val updatedDbPlace = oldDbPlace.withUpdatedCoordinates(newCoordinates)
                        updateDbPlaceInCollections(oldDbPlace, updatedDbPlace, newCoordinates)
                    }

                    routingExample.updateDestination(newCoordinates)
                    routeNeedsRecalculation = true
                    Log.d(TAG, "Updated destination coordinates and DbPlace")
                }
                else {
                    val waypointIndex = routingExample.findWaypointIndex(currentMarkerCoords)
                    if (waypointIndex >= 0 && waypointIndex < waypointDbPlaces.size) {
                        // Update waypoint DbPlace with new coordinates
                        val oldDbPlace = waypointDbPlaces[waypointIndex]
                        val updatedDbPlace = oldDbPlace.withUpdatedCoordinates(newCoordinates)
                        updateDbPlaceInCollections(oldDbPlace, updatedDbPlace, newCoordinates)

                        routingExample.updateWaypoint(waypointIndex, newCoordinates)
                        routeNeedsRecalculation = true
                        Log.d(TAG, "Updated waypoint $waypointIndex coordinates and DbPlace")
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

        // Clear place objects
        originPlace = null
        destinationPlace = null
        waypointPlaces.clear()

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