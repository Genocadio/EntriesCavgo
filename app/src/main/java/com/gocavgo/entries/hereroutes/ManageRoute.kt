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
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.gocavgo.entries.BuildConfig
import com.gocavgo.entries.R
import com.gocavgo.entries.model.LocationIqResult
import com.gocavgo.entries.service.LocationIqClient
import com.gocavgo.entries.service.OfflineMapManager
import com.gocavgo.entries.service.RemoteDataManager
import com.gocavgo.entries.service.RemoteResult
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import kotlin.math.abs

class ManageRoute : AppCompatActivity() {

    private var mapView: org.maplibre.android.maps.MapView? = null
    private lateinit var locationClient: FusedLocationProviderClient
    private val TAG = ManageRoute::class.java.simpleName
    private lateinit var searchAutoComplete: AutoCompleteTextView
    private lateinit var clearSearchButton: ImageButton
    private lateinit var myLocationFab: com.google.android.material.floatingactionbutton.FloatingActionButton
    private lateinit var searchFab: com.google.android.material.floatingactionbutton.FloatingActionButton

    // Offline map manager
    private lateinit var offlineMapManager: OfflineMapManager
    private lateinit var offlineStatusView: android.widget.TextView

    // LocationIQ client for reverse geocoding
    private val locationIqClient: LocationIqClient by lazy {
        val token = try {
            val field = BuildConfig::class.java.getField("LOCATIONIQ_API_KEY")
            field.get(null) as String
        } catch (e: Exception) { "" }
        LocationIqClient(token)
    }

    // UI containers
    private lateinit var bottomContainer: LinearLayout
    private lateinit var searchContainer: LinearLayout
    private lateinit var searchOverlay: android.widget.FrameLayout
    private lateinit var searchHintText: TextView
    private lateinit var placeDetailsContainer: LinearLayout
    private lateinit var routeSummaryContainer: LinearLayout
    private lateinit var routeActionsContainer: LinearLayout

    private var currentRouteHandle: String? = null

    private lateinit var tvPlaceTitle: TextView
    private lateinit var tvPlaceAddress: TextView
    private lateinit var tvPlaceType: TextView
    private lateinit var tvRouteDistance: TextView
    private lateinit var tvRouteDuration: TextView

    private lateinit var routingExample: RoutingExample
    private lateinit var searchManager: SearchManager
    private lateinit var uiController: UIController
    private lateinit var mapController: MapController
    private lateinit var shareLocationProcessor: ShareLocationProcessor

    private lateinit var saveLocationButton: Button
    private lateinit var addDestinationButton: Button
    private lateinit var addWaypointButton: Button
    private lateinit var saveRouteButton: Button
    private lateinit var clearRouteButton: Button
    private lateinit var locationActionsContainer: LinearLayout

    private var isSelectingOrigin = true
    private var isSelectingDestination = false
    private var isSelectingWaypoint = false
    private var selectedOrigin: LatLng? = null
    private var selectedDestination: LatLng? = null

    private var originDbPlace: DbPlace? = null
    private var destinationDbPlace: DbPlace? = null
    private var waypointDbPlaces: MutableList<DbPlace> = mutableListOf()

    // Marker currently being edited through the inline bottom form
    private var editingMarker: Marker? = null

    // Shared link result held until the map is ready to render it
    private var pendingShareResult: ShareLocationProcessor.ShareLocationResult? = null

    private lateinit var routeDetailsContainer: ScrollView
    private lateinit var routeStopsContainer: LinearLayout
    private lateinit var btnClearLocation: Button
    private lateinit var btnAddWaypointRoute: Button

    private val styleUrls = listOf(
        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
    )
    private val tileExtension = ".png"
    private val styleNames = listOf("Esri World Imagery")

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        locationClient = LocationServices.getFusedLocationProviderClient(this)
        enableEdgeToEdge()
        setContentView(R.layout.activity_manage_route)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.manageroutes)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        mapView = findViewById(R.id.map_view)

        initializeViews()
        initializeComponents()
        setupCallbacks()

        // Load map centered on GPS location, or fall back to last-known/default center
        getLocation { geoPoint ->
            mapController.loadMapScene(geoPoint ?: LatLng(-1.9403, 29.8739))  // Default: Kigali
        }

        processSharedLocation()
    }

    /** Only attempt to process shared location when the intent actually contains share data */
    private fun processSharedLocation() {
        val intent = intent
        val isShareIntent = intent.action == android.content.Intent.ACTION_SEND && intent.type == "text/plain"
        val isViewIntent = intent.action == android.content.Intent.ACTION_VIEW && intent.data != null
        if (!isShareIntent && !isViewIntent) return  // Normal app launch — nothing to process

        val sharedText = intent.getStringExtra(android.content.Intent.EXTRA_TEXT)
        if (isShareIntent && sharedText != null && sharedText.contains("maps.app.goo.gl")) {
            lifecycleScope.launch(Dispatchers.IO) {
                // Try to resolve shortened URL, fall back to original if resolution fails
                val resolvedUrl = try {
                    shareLocationProcessor.resolveShortenedUrl(sharedText)
                } catch (e: Exception) { null }

                val urlToProcess = if (resolvedUrl != null && resolvedUrl != sharedText) resolvedUrl else sharedText
                val processIntent = android.content.Intent().apply {
                    action = android.content.Intent.ACTION_SEND
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_TEXT, urlToProcess)
                }
                val shareResult = shareLocationProcessor.processSharedIntent(processIntent)
                withContext(Dispatchers.Main) { handleShareResult(shareResult) }
            }
        } else {
            lifecycleScope.launch(Dispatchers.IO) {
                val shareResult = shareLocationProcessor.processSharedIntent(intent)
                withContext(Dispatchers.Main) { handleShareResult(shareResult) }
            }
        }
    }

    private fun handleShareResult(shareResult: ShareLocationProcessor.ShareLocationResult?) {
        if (shareResult == null) {
            CustomToast.showWarning(this, "Could not extract location from shared content")
        } else if (mapController.isMapReady) {
            applyShareResult(shareResult)
        } else {
            pendingShareResult = shareResult
        }
    }

    private fun flushPendingShareResult() {
        pendingShareResult?.let {
            pendingShareResult = null
            applyShareResult(it)
        }
    }

    private fun applyShareResult(shareResult: ShareLocationProcessor.ShareLocationResult) {
        if (shareResult.routeData != null) {
            handleRouteShare(shareResult.routeData, shareResult.sourceApp)
        } else {
            val dbPlace = DbPlace.create(
                placeName = "Shared Location (${shareResult.sourceApp})",
                latitude = shareResult.coordinates.latitude,
                longitude = shareResult.coordinates.longitude,
                address = "Shared from ${shareResult.sourceApp}"
            )
            addSharedLocationToMap(shareResult.coordinates, dbPlace, shareResult.sourceApp)
            CustomToast.showSuccess(this, "Location shared from ${shareResult.sourceApp}")
        }
    }

    private fun handleRouteShare(routeData: ShareLocationProcessor.RouteData, sourceApp: String) {
        resetToInitialState()
        val originDb = DbPlace.create(placeName = "Origin ($sourceApp)", latitude = routeData.origin.latitude, longitude = routeData.origin.longitude, address = "Shared from $sourceApp")
        val destDb = DbPlace.create(placeName = "Destination ($sourceApp)", latitude = routeData.destination.latitude, longitude = routeData.destination.longitude, address = "Shared from $sourceApp")
        routingExample.setRecalculationEnabled(false)
        try {
            addRouteLocationToMapSilently(routeData.origin, originDb, "origin", sourceApp)
            addRouteLocationToMapSilently(routeData.destination, destDb, "destination", sourceApp)
            routeData.waypoints.forEachIndexed { index, coords ->
                val wpDb = DbPlace.create(placeName = "Waypoint ${index + 1} ($sourceApp)", latitude = coords.latitude, longitude = coords.longitude, address = "Shared from $sourceApp")
                addRouteLocationToMapSilently(coords, wpDb, "waypoint", sourceApp)
            }
        } finally {
            routingExample.setRecalculationEnabled(true)
        }
        CustomToast.showSuccess(this, "Route shared from $sourceApp")
        routingExample.calculateRouteWithWaypoints()
    }

    private fun addRouteLocationToMapSilently(coordinates: LatLng, dbPlace: DbPlace, markerType: String, sourceApp: String) {
        mapController.moveMapToLocation(coordinates)
        val marker = mapController.addSearchMarker(coordinates, dbPlace, markerType)
        updateSelectionState(markerType)
        handleLocationSelection(coordinates, dbPlace.getName(), dbPlace)
        uiController.showPlaceDetails(dbPlace)
        reverseGeocodeAndUpdate(marker, dbPlace, coordinates)
    }

    private fun addSharedLocationToMap(coordinates: LatLng, dbPlace: DbPlace, sourceApp: String) {
        mapController.moveMapToLocation(coordinates)
        val markerType = when {
            selectedOrigin == null -> "origin"
            selectedDestination == null -> "destination"
            else -> "waypoint"
        }
        val marker = mapController.addSearchMarker(coordinates, dbPlace, markerType)
        updateSelectionState(markerType)
        handleLocationSelection(coordinates, dbPlace.getName(), dbPlace)
        uiController.showPlaceDetails(dbPlace)
        reverseGeocodeAndUpdate(marker, dbPlace, coordinates)
        if (selectedOrigin != null && selectedDestination != null && !routingExample.hasActiveRoute()) {
            routingExample.calculateRouteWithWaypoints()
        }
    }

    private fun updateSelectionState(markerType: String) {
        isSelectingOrigin = markerType == "origin"
        isSelectingDestination = markerType == "destination"
        isSelectingWaypoint = markerType == "waypoint"
    }

    private fun updateMyLocationFabVisibility(searchFabHiddenByUi: Boolean = false) {
        val hasRouteLocations = selectedOrigin != null || selectedDestination != null
        myLocationFab.visibility = if (hasRouteLocations) android.view.View.GONE else android.view.View.VISIBLE
        searchFab.visibility = if (hasRouteLocations || searchFabHiddenByUi) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun placeSearchResult(coords: LatLng, dbPlace: DbPlace) {
        val markerType = when {
            isSelectingOrigin -> "origin"
            isSelectingDestination -> "destination"
            else -> "waypoint"
        }
        handleLocationSelection(coords, dbPlace.getName(), dbPlace)
        mapController.addSearchMarker(coords, dbPlace, markerType)
        mapController.moveMapToLocation(coords)
        uiController.clearSearchState()
        uiController.hideSearchOverlay()
        if (markerType == "origin") {
            CustomToast.showSuccess(this, "Pickup set: ${dbPlace.getName()}")
            advanceToDestinationSelection()
        } else {
            uiController.showPlaceDetails(dbPlace)
        }
    }

    private fun setCurrentLocationAsPickup(coords: LatLng) {
        mapController.clearLocationIndicators()
        mapController.hideLocationComponent()
        isSelectingOrigin = true
        isSelectingDestination = false
        isSelectingWaypoint = false
        val dbPlace = DbPlace.create(
            placeName = "My current location",
            latitude = coords.latitude,
            longitude = coords.longitude
        )
        handleLocationSelection(coords, dbPlace.getName(), dbPlace)
        val marker = mapController.addSearchMarker(coords, dbPlace, "origin")
        reverseGeocodeAndUpdate(marker, dbPlace, coords)
        uiController.clearSearchState()
        uiController.hideSearchOverlay()
        CustomToast.showSuccess(this, "Pickup set to your current location")
        // Advance to destination selection WITHOUT opening the search bar,
        // showing the bottom place details popup instead.
        isSelectingOrigin = false
        isSelectingDestination = true
        isSelectingWaypoint = false
        uiController.switchToDestinationSelectionSilently()
        uiController.showPlaceDetails(dbPlace)
    }

    private fun advanceToDestinationSelection() {
        isSelectingOrigin = false
        isSelectingDestination = true
        isSelectingWaypoint = false
        uiController.switchToDestinationSelection()
    }

    private fun reverseGeocodeAndUpdate(marker: Marker, dbPlace: DbPlace, coords: LatLng) {
        lifecycleScope.launch {
            val result = locationIqClient.reverseGeocode(coords.latitude, coords.longitude)
            if (result != null) {
                val title = result.placeName.ifBlank { result.addressText }
                dbPlace.setPlaceName(title)
                dbPlace.setAddress(result.addressText)
                marker.title = dbPlace.getName()
                uiController.showPlaceDetails(dbPlace)
            }
        }
    }

    private fun handleTogglePassThrough(marker: Marker) {
        val coords = marker.position
        val dbPlace = findDbPlaceForCoordinates(coords.latitude, coords.longitude) ?: run {
            CustomToast.showWarning(this, "No waypoint found to toggle")
            return
        }
        dbPlace.isPassThrough = !dbPlace.isPassThrough
        marker.title = dbPlace.getName()
        val waypointIndex = routingExample.findWaypointIndex(coords)
        if (waypointIndex >= 0) routingExample.calculateRouteWithWaypoints()
        CustomToast.showInfo(this, "Waypoint set to ${if (dbPlace.isPassThrough) "Pass-Through" else "Stopover"}")
    }

    private fun initializeViews() {
        mapView = findViewById(R.id.map_view)
        searchAutoComplete = findViewById(R.id.search_autocomplete)
        clearSearchButton = findViewById(R.id.clear_search)
        myLocationFab = findViewById(R.id.fab_my_location)
        bottomContainer = findViewById(R.id.bottom_container)
        searchContainer = findViewById(R.id.search_container)
        searchOverlay = findViewById(R.id.search_overlay)
        searchHintText = findViewById(R.id.search_hint_text)
        placeDetailsContainer = findViewById(R.id.place_details_container)
        routeSummaryContainer = findViewById(R.id.route_summary_container)
        routeActionsContainer = findViewById(R.id.route_actions_container)
        tvPlaceTitle = findViewById(R.id.tv_place_title)
        tvPlaceAddress = findViewById(R.id.tv_place_address)
        tvPlaceType = findViewById(R.id.tv_place_type)
        tvRouteDistance = findViewById(R.id.tv_route_distance)
        tvRouteDuration = findViewById(R.id.tv_route_duration)
        locationActionsContainer = findViewById(R.id.location_actions_container)
        saveLocationButton = findViewById(R.id.btn_save_location)
        addDestinationButton = findViewById(R.id.btn_add_destination)
        addWaypointButton = findViewById(R.id.btn_add_waypoint)
        saveRouteButton = findViewById(R.id.btn_save_route)
        clearRouteButton = findViewById(R.id.btn_clear_route)
        routeDetailsContainer = findViewById(R.id.route_details_container)
        routeStopsContainer = findViewById(R.id.route_stops_container)
        btnClearLocation = findViewById(R.id.btn_clear_location)
        btnAddWaypointRoute = findViewById(R.id.btn_add_waypoint_route)
        searchFab = findViewById(R.id.fab_search)
    }

    private fun initializeComponents() {
        routingExample = RoutingExample(this, mapView!!)
        searchManager = SearchManager(this)
        shareLocationProcessor = ShareLocationProcessor()
        uiController = UIController(this, searchContainer, searchOverlay, placeDetailsContainer, routeSummaryContainer, routeActionsContainer, locationActionsContainer, searchAutoComplete, null, searchHintText, null, tvPlaceTitle, tvPlaceAddress, tvPlaceType, tvRouteDistance, tvRouteDuration)
        mapController = MapController(this, mapView!!, null, myLocationFab, locationClient, styleUrls, styleNames, tileExtension)

        // Initialize offline status view
        offlineStatusView = findViewById(R.id.tv_offline_status)

        // Initialize offline map manager and auto-download if needed
        offlineMapManager = OfflineMapManager(this).apply {
            onProgressChanged = { style, progress, total ->
                val pct = if (total > 0) (progress * 100 / total) else 0
                Log.d(TAG, "Offline download $style: $pct%")
                runOnUiThread {
                    offlineStatusView.text = "Downloading maps: $style ($pct%)"
                    offlineStatusView.visibility = android.view.View.VISIBLE
                }
            }
            onDownloadComplete = { style, success ->
                Log.d(TAG, "Offline download $style: ${if (success) "done" else "failed"}")
            }
            onAllDownloadsComplete = { allSuccess ->
                Log.d(TAG, "All offline downloads complete: success=$allSuccess")
                runOnUiThread {
                    if (allSuccess) {
                        offlineStatusView.text = "Offline maps ready"
                        offlineStatusView.postDelayed({
                            offlineStatusView.visibility = android.view.View.GONE
                        }, 2000)
                    } else {
                        offlineStatusView.text = "Offline download failed"
                    }
                }
            }
            onStatusChanged = { status ->
                runOnUiThread {
                    offlineStatusView.text = status
                    offlineStatusView.visibility = android.view.View.VISIBLE
                }
            }
        }

        // Check offline readiness and show status
        offlineMapManager.checkOfflineReadiness { ready, message ->
            Log.d(TAG, "Offline readiness: $ready - $message")
            if (!ready) {
                // Auto-download offline tiles on first launch or after 7 days
                offlineMapManager.hasOfflineData { hasData ->
                    if (!hasData || offlineMapManager.needsUpdate()) {
                        Log.d(TAG, "Starting offline map download...")
                        lifecycleScope.launch(Dispatchers.IO) {
                            offlineMapManager.downloadAllStyles()
                        }
                    }
                }
            }
        }
    }

    private fun setupCallbacks() {
        routingExample.onRouteCalculated = { routeResult, routeHandle ->
            currentRouteHandle = routeHandle
            routeResult?.let {
                uiController.updateRouteSummaryWithActualData(it)
            } ?: run {
                currentRouteHandle = null
                routeSummaryContainer.visibility = android.view.View.GONE
            }
        }

        // Search FAB: tap to open search overlay
        searchFab.setOnClickListener {
            uiController.showSearchOverlay()
        }

        setupSearchManagerCallbacks()
        setupUIControllerCallbacks()
        setupMapControllerCallbacks()
        uiController.setupSearchFunctionality(searchManager)
    }

    private fun setupSearchManagerCallbacks() {
        searchManager.onPlaceSelected = { result ->
            placeSearchResult(LatLng(result.latitude, result.longitude), DbPlace.fromLocationIqResult(result))
        }

        searchManager.onDbPlaceSelected = { dbPlace ->
            placeSearchResult(LatLng(dbPlace.latitude, dbPlace.longitude), dbPlace)
        }

        searchManager.onSearchError = { msg -> runOnUiThread { CustomToast.showError(this, msg) } }
        searchManager.onSuggestionsUpdated = { runOnUiThread { searchManager.showDropdown(searchAutoComplete) } }

        uiController.onAutoSuggestRequested = { query ->
            if (uiController.isDatabaseSearchMode()) searchManager.performDatabaseSearch(query)
            else searchManager.performAutoSuggest(query)
        }
    }

    private fun markEdit(dbPlace: DbPlace) {
        if (!dbPlace.id.isNullOrBlank()) dbPlace.setEdited(true)
    }

    private fun setupUIControllerCallbacks() {
        uiController.onLocationActionRequested = { action ->
            when (action) {
                "save_location" -> {
                    val dbPlaceToSave = when {
                        isSelectingOrigin -> originDbPlace
                        isSelectingDestination -> destinationDbPlace
                        isSelectingWaypoint -> waypointDbPlaces.lastOrNull()
                        else -> null
                    }
                    dbPlaceToSave?.let { dbPlace ->
                        val finalDbPlace = dbPlace.createSaveCopy()
                        if (!finalDbPlace.hasValidLocationData()) {
                            CustomToast.showWarning(this, "Cannot save location: ${finalDbPlace.getLocationValidationError() ?: "missing data"}")
                            return@let
                        }
                        val remoteDataManager = RemoteDataManager.getInstance()
                        lifecycleScope.launch {
                            when (val result = remoteDataManager.savePlace(finalDbPlace)) {
                                is RemoteResult.Success -> withContext(Dispatchers.Main) { CustomToast.showSuccess(this@ManageRoute, "Location '${finalDbPlace.getName()}' saved with ID: ${result.data.id}!") }
                                is RemoteResult.Error -> withContext(Dispatchers.Main) { CustomToast.showError(this@ManageRoute, "Failed to save: ${result.message}") }
                            }
                        }
                    } ?: Toast.makeText(this, "No location selected to save", Toast.LENGTH_SHORT).show()
                }
                "add_destination" -> { isSelectingOrigin = false; isSelectingDestination = true; isSelectingWaypoint = false; uiController.switchToDestinationSelection() }
                "add_waypoint" -> { isSelectingOrigin = false; isSelectingDestination = false; isSelectingWaypoint = true; uiController.switchToWaypointSelection() }
                "save_route" -> {
                    val finalRoute = uiController.validateAndCreateFinalRoute()?.let {
                        FinalRoute(origin = it.origin, id = it.id, destination = it.destination, routePrice = it.routePrice, routeName = it.routeName, isCityRoute = it.isCityRoute, distance = it.distance, duration = it.duration, routeMapId = currentRouteHandle, waypoints = it.waypoints)
                    }
                    if (finalRoute != null) {
                        val remoteDataManager = RemoteDataManager.getInstance()
                        lifecycleScope.launch {
                            when (val result = remoteDataManager.saveRoute(finalRoute)) {
                                is RemoteResult.Success -> withContext(Dispatchers.Main) { CustomToast.showSuccess(this@ManageRoute, "Route saved with ID: ${result.data.id}!") }
                                is RemoteResult.Error -> withContext(Dispatchers.Main) { CustomToast.showError(this@ManageRoute, "Failed to save route: ${result.message}") }
                            }
                        }
                    }
                }
                "clear_route" -> requestClearRouteConfirmation()
            }
        }

        uiController.onSelectionStateRequest = { Triple(isSelectingOrigin, isSelectingDestination, isSelectingWaypoint) }
        uiController.onDestinationSelectedRequest = { selectedDestination != null }
        uiController.onRouteDataRequest = { Pair(selectedOrigin != null && selectedDestination != null, routeSummaryContainer.isVisible) }
        uiController.onRouteStopsDbDataRequest = { Triple(originDbPlace, destinationDbPlace, waypointDbPlaces.toList()) }

        uiController.onSearchFabVisibilityChanged = { showFab ->
            updateMyLocationFabVisibility(searchFabHiddenByUi = !showFab)
        }

        uiController.onPlaceEditSaveRequested = { customName, placeName, district ->
            savePlaceEdit(customName, placeName, district)
        }

        uiController.onPlaceEditMenuActionRequested = { action ->
            editingMarker?.let { marker ->
                when (action) {
                    "move" -> {
                        uiController.hidePlaceEditForm()
                        mapController.startMarkerMove(marker)
                    }
                    "remove" -> {
                        handleMarkerDeletion(marker)
                        mapController.removeMarker(marker)
                        editingMarker = null
                    }
                    "cancel_edit" -> {
                        uiController.hidePlaceEditForm()
                        editingMarker = null
                        val dbPlace = findDbPlaceForCoordinates(marker.position.latitude, marker.position.longitude)
                        if (dbPlace != null) uiController.showPlaceDetails(dbPlace)
                        else restoreBottomMenuAfterPlaceRemoval()
                    }
                }
            }
        }

        btnClearLocation.setOnClickListener { resetToInitialState() }
        btnAddWaypointRoute.setOnClickListener { isSelectingOrigin = false; isSelectingDestination = false; isSelectingWaypoint = true; uiController.switchToWaypointSelection() }
    }

    private fun setupMapControllerCallbacks() {
        mapController.onMapReady = {
            flushPendingShareResult()
        }

        mapController.onLocationReceived = { coords ->
            // Always move the camera to the user's location
            mapController.moveMapToLocation(coords)

            // When no pickup is selected yet, the my-location button sets the
            // current position as the pickup and advances to destination search.
            if (selectedOrigin == null) setCurrentLocationAsPickup(coords)
            else mapController.showLocationIndicator(coords)
        }

        mapController.onMarkerActionRequested = { action, marker ->
            when (action) {
                "remove" -> { handleMarkerDeletion(marker); mapController.removeMarker(marker) }
                "move" -> mapController.startMarkerMove(marker)
                "edit" -> handleMarkerEdit(marker)
                "save" -> mapController.saveMarker(marker)
                "toggle_passthrough" -> handleTogglePassThrough(marker)
                "show_options" -> showMarkerMoveRemoveDialog(marker)
                "show_edit" -> openInlinePlaceEdit(marker)
            }
        }

        mapController.onMarkerMoved = { marker, newCoords ->
            val oldCoords = marker.position
            val dbPlace = findDbPlaceForCoordinates(oldCoords.latitude, oldCoords.longitude)
            if (dbPlace != null) {
                if (!dbPlace.id.isNullOrBlank()) markEdit(dbPlace)
                dbPlace.updateCoordinates(newCoords.latitude, newCoords.longitude)
                updateRouteCoordinatesForMovedPlace(dbPlace, oldCoords, newCoords)

                // For an already-saved place, persist the move right away as an
                // update (PUT) - this editing an existing place must not create a new one.
                if (!dbPlace.id.isNullOrBlank()) {
                    val savedId = dbPlace.id!!
                    val saveCopy = dbPlace.createSaveCopy()
                    lifecycleScope.launch {
                        when (val result = RemoteDataManager.getInstance().updatePlace(savedId, saveCopy)) {
                            is RemoteResult.Success -> {
                                dbPlace.setEdited(false)
                                withContext(Dispatchers.Main) {
                                    CustomToast.showSuccess(this@ManageRoute, "Location updated: ${dbPlace.getName()}")
                                    uiController.refreshSaveRouteButtonState()
                                }
                            }
                            is RemoteResult.Error -> withContext(Dispatchers.Main) {
                                CustomToast.showError(this@ManageRoute, "Failed to update location: ${result.message}")
                            }
                        }
                    }
                }

                Toast.makeText(this, "Moved '${dbPlace.getName()}'", Toast.LENGTH_SHORT).show()
                reverseGeocodeAndUpdate(marker, dbPlace, newCoords)
            }
        }

        mapController.onMapLongPress = { coords ->
            val targetType = when {
                selectedOrigin == null -> "origin"
                selectedDestination == null -> "destination"
                else -> "waypoint"
            }
            updateSelectionState(targetType)
            val displayName = "$targetType (${String.format("%.5f", coords.latitude)}, ${String.format("%.5f", coords.longitude)})"
            val dbPlace = DbPlace.create(placeName = displayName, latitude = coords.latitude, longitude = coords.longitude)
            handleLocationSelection(coords, displayName, dbPlace)
            val marker = mapController.addSearchMarker(coords, dbPlace, targetType)
            mapController.moveMapToLocation(coords)
            reverseGeocodeAndUpdate(marker, dbPlace, coords)
            uiController.clearSearchState()
            uiController.hideSearchOverlay()
            if (targetType == "origin") advanceToDestinationSelection()
            else uiController.showPlaceDetails(dbPlace)
        }
    }

    private fun handleMarkerDeletion(marker: Marker) {
        val coords = marker.position
        val dbPlace = findDbPlaceForCoordinates(coords.latitude, coords.longitude)
        val markerName = dbPlace?.getName() ?: "marker"

        if (selectedOrigin != null && abs(coords.latitude - selectedOrigin!!.latitude) < 0.0001 && abs(coords.longitude - selectedOrigin!!.longitude) < 0.0001) {
            selectedOrigin = null; originDbPlace = null; routingExample.clearRouteAfterOriginDeletion()
            CustomToast.showInfo(this, "Origin '$markerName' deleted - route cleared")
        } else if (selectedDestination != null && abs(coords.latitude - selectedDestination!!.latitude) < 0.0001 && abs(coords.longitude - selectedDestination!!.longitude) < 0.0001) {
            selectedDestination = null; destinationDbPlace = null; routingExample.clearRouteAfterDestinationDeletion()
            CustomToast.showInfo(this, "Destination '$markerName' deleted - route cleared")
        } else {
            val wpIndex = routingExample.findWaypointIndex(coords)
            if (wpIndex >= 0) {
                if (wpIndex < waypointDbPlaces.size) waypointDbPlaces.removeAt(wpIndex)
                routingExample.removeWaypointAndRecalculate(wpIndex)
                CustomToast.showInfo(this, "Waypoint '$markerName' deleted - route recalculated")
            }
        }
        updateMyLocationFabVisibility()
        restoreBottomMenuAfterPlaceRemoval()
    }

    /**
     * After a place is removed, close any open inline edit form and return the
     * bottom menu to the most appropriate state: the route summary if a route
     * still exists, the default bottom menu if other places remain, or the
     * initial current-location/search state if nothing is left.
     */
    private fun restoreBottomMenuAfterPlaceRemoval() {
        uiController.hidePlaceEditForm()
        editingMarker = null
        when {
            routingExample.hasActiveRoute() -> {
                uiController.updateActionButtonsVisibility()
                uiController.refreshSaveRouteButtonState()
                updateMyLocationFabVisibility()
            }
            selectedOrigin != null || selectedDestination != null || waypointDbPlaces.isNotEmpty() -> {
                val remaining = originDbPlace ?: destinationDbPlace ?: waypointDbPlaces.lastOrNull()
                if (remaining != null) uiController.showPlaceDetails(remaining)
                else uiController.updateActionButtonsVisibility()
                updateMyLocationFabVisibility()
            }
            else -> resetToInitialState()
        }
    }

    private fun handleMarkerEdit(marker: Marker) {
        val coords = marker.position
        val dbPlace = findDbPlaceForCoordinates(coords.latitude, coords.longitude) ?: return
        if (dbPlace.hasMovedCoordinates && !dbPlace.id.isNullOrBlank()) markEdit(dbPlace)
        val dialog = PlaceEditDialog.newInstance(dbPlace) { updatedDbPlace ->
            val finalDbPlace = if (dbPlace.hasMovedCoordinates) updatedDbPlace.withUpdatedCoordinates(dbPlace.latitude, dbPlace.longitude).also { if (!it.id.isNullOrBlank()) it.setEdited(true) } else updatedDbPlace
            updateDbPlaceInCollections(dbPlace, finalDbPlace)
            marker.title = finalDbPlace.getName()
            uiController.showPlaceDetails(finalDbPlace)
            CustomToast.showSuccess(this, "Place updated: ${finalDbPlace.getName()}")
        }
        dialog.show(supportFragmentManager, "PlaceEditDialog")
    }

    private fun showMarkerMoveRemoveDialog(marker: Marker) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(marker.title ?: "Marker Options")
            .setItems(arrayOf("Move", "Remove")) { _, which ->
                when (which) {
                    0 -> mapController.startMarkerMove(marker)
                    1 -> { handleMarkerDeletion(marker); mapController.removeMarker(marker) }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Asks for confirmation before clearing the whole route/locations from the map. */
    private fun requestClearRouteConfirmation() {
        val hasPlaces = selectedOrigin != null || selectedDestination != null || waypointDbPlaces.isNotEmpty()
        if (!hasPlaces) {
            resetToInitialState()
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Clear Route")
            .setMessage("This will remove all locations (origin, destination, waypoints) and clear the route from the map. This cannot be undone.")
            .setPositiveButton("Clear") { _, _ -> resetToInitialState() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Opens the inline bottom edit form for the tapped marker and pre-fills it. */
    private fun openInlinePlaceEdit(marker: Marker) {
        val dbPlace = findDbPlaceForCoordinates(marker.position.latitude, marker.position.longitude) ?: return
        editingMarker = marker
        uiController.showPlaceEditForm(dbPlace)
    }

    /** Persists the inline-edit values straight to the backend (create or update). */
    private fun savePlaceEdit(customName: String?, placeName: String, district: String?) {
        val marker = editingMarker ?: run {
            CustomToast.showWarning(this, "No place selected to edit")
            return
        }
        val original = findDbPlaceForCoordinates(marker.position.latitude, marker.position.longitude) ?: return

        original.customName = customName
        original.customDistrict = district
        original.setPlaceName(placeName)
        if (!original.id.isNullOrBlank()) original.setEdited(true)
        updateDbPlaceInCollections(original, original)
        marker.title = original.getName()

        val remoteDataManager = RemoteDataManager.getInstance()
        val saveCopy = original.createSaveCopy()
        lifecycleScope.launch {
            val result = if (!saveCopy.id.isNullOrBlank()) {
                remoteDataManager.updatePlace(saveCopy.id!!, saveCopy)
            } else {
                remoteDataManager.savePlace(saveCopy)
            }
            withContext(Dispatchers.Main) {
                when (result) {
                    is RemoteResult.Success -> {
                        if (saveCopy.id.isNullOrBlank()) original.id = result.data.id.toString()
                        original.setEdited(false)
                        marker.title = original.getName()
                        editingMarker = null
                        uiController.hidePlaceEditForm()
                        uiController.showPlaceDetails(original)
                        uiController.refreshSaveRouteButtonState()
                        CustomToast.showSuccess(this@ManageRoute, "Place saved: ${original.getName()}")
                    }
                    is RemoteResult.Error -> {
                        original.setEdited(true)
                        CustomToast.showError(this@ManageRoute, "Failed to save place: ${result.message}")
                    }
                }
            }
        }
    }

    private fun updateDbPlaceInCollections(oldDbPlace: DbPlace, newDbPlace: DbPlace) {
        markEdit(newDbPlace)
        if (originDbPlace == oldDbPlace) { originDbPlace = newDbPlace; return }
        if (destinationDbPlace == oldDbPlace) { destinationDbPlace = newDbPlace; return }
        val idx = waypointDbPlaces.indexOfFirst { it == oldDbPlace }
        if (idx >= 0) waypointDbPlaces[idx] = newDbPlace
    }

    private fun findDbPlaceForCoordinates(latitude: Double, longitude: Double): DbPlace? {
        val t = 0.0001
        originDbPlace?.let { if (abs(latitude - it.latitude) < t && abs(longitude - it.longitude) < t) return it }
        destinationDbPlace?.let { if (abs(latitude - it.latitude) < t && abs(longitude - it.longitude) < t) return it }
        waypointDbPlaces.forEach { if (abs(latitude - it.latitude) < t && abs(longitude - it.longitude) < t) return it }
        return null
    }

    private fun handleLocationSelection(coordinates: LatLng, title: String, dbPlace: DbPlace) {
        when {
            isSelectingOrigin -> { selectedOrigin = coordinates; originDbPlace = dbPlace; routingExample.setOrigin(coordinates) }
            isSelectingDestination -> { selectedDestination = coordinates; destinationDbPlace = dbPlace; routingExample.setDestination(coordinates) }
            isSelectingWaypoint -> { routingExample.addWaypoint(coordinates); waypointDbPlaces.add(dbPlace) }
        }
        updateMyLocationFabVisibility()
    }

    private fun updateRouteCoordinatesForMovedPlace(dbPlace: DbPlace, oldCoords: LatLng, newCoords: LatLng): Boolean {
        if (!routingExample.hasActiveRoute()) return false
        val t = 0.0001
        markEdit(dbPlace)
        if (selectedOrigin != null && abs(oldCoords.latitude - selectedOrigin!!.latitude) < t && abs(oldCoords.longitude - selectedOrigin!!.longitude) < t) {
            selectedOrigin = newCoords; routingExample.updateOrigin(newCoords); return true
        }
        if (selectedDestination != null && abs(oldCoords.latitude - selectedDestination!!.latitude) < t && abs(oldCoords.longitude - selectedDestination!!.longitude) < t) {
            selectedDestination = newCoords; routingExample.updateDestination(newCoords); return true
        }
        val wpIdx = routingExample.findWaypointIndex(oldCoords)
        if (wpIdx >= 0) { routingExample.updateWaypoint(wpIdx, newCoords); return true }
        return false
    }

    private fun resetToInitialState() {
        isSelectingOrigin = true; isSelectingDestination = false; isSelectingWaypoint = false
        selectedOrigin = null; selectedDestination = null; currentRouteHandle = null
        originDbPlace = null; destinationDbPlace = null; waypointDbPlaces.clear()
        mapController.cancelMarkerMove()
        uiController.clearSearchState()
        uiController.resetToInitialState()
        routingExample.clearRouteData()
        mapController.clearSearchMarkers()
        updateMyLocationFabVisibility()
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun getLocation(onLocationReceived: (LatLng?) -> Unit) {
        try {
            locationClient.lastLocation.addOnSuccessListener { location ->
                onLocationReceived(location?.let { LatLng(it.latitude, it.longitude) })
            }.addOnFailureListener { onLocationReceived(null) }
        } catch (e: SecurityException) { onLocationReceived(null) }
    }

    private fun hasUnsavedLocations(): Boolean {
        val hasAny = selectedOrigin != null || selectedDestination != null || waypointDbPlaces.isNotEmpty()
        val hasUnsaved = listOfNotNull(originDbPlace, destinationDbPlace).any { it.id.isNullOrBlank() || it.edited } || waypointDbPlaces.any { it.id.isNullOrBlank() || it.edited }
        return hasAny && hasUnsaved
    }

    override fun onPause() { mapView?.onPause(); super.onPause() }
    override fun onResume() { mapView?.onResume(); super.onResume() }
    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); this.intent = intent; processSharedLocation() }

    private fun getPrefs() = getSharedPreferences("map_prefs", MODE_PRIVATE)
    override fun onDestroy() { mapController.clearSearchMarkers(); offlineMapManager.destroy(); super.onDestroy() }

    override fun onBackPressed() {
        if (searchOverlay.isVisible || searchContainer.isVisible) { uiController.closeSearchOverlay(); return }
        if (hasUnsavedLocations()) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Exit Route Planning")
                .setMessage("You have unsaved locations. The map will be cleared if you exit.")
                .setPositiveButton("Exit") { _, _ -> resetToInitialState(); finish() }
                .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
                .show()
            return
        }
        super.onBackPressed()
    }
}
