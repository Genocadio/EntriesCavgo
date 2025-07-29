package com.gocavgo.entries.hereroutes

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.gocavgo.entries.BuildConfig
import com.gocavgo.entries.R
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.here.sdk.core.Anchor2D
import com.here.sdk.core.GeoCoordinates
import com.here.sdk.core.GeoCoordinatesUpdate
import com.here.sdk.core.GeoOrientationUpdate
import com.here.sdk.core.Location
import com.here.sdk.core.engine.AuthenticationMode
import com.here.sdk.core.engine.SDKNativeEngine
import com.here.sdk.core.engine.SDKOptions
import com.here.sdk.core.errors.InstantiationErrorException
import com.here.sdk.gestures.TapListener
import com.here.sdk.mapview.LocationIndicator
import com.here.sdk.mapview.MapCameraAnimationFactory
import com.here.sdk.mapview.MapImageFactory
import com.here.sdk.mapview.MapMarker
import com.here.sdk.mapview.MapMeasure
import com.here.sdk.mapview.MapScheme
import com.here.sdk.mapview.MapView
import com.here.sdk.search.Place
import com.here.sdk.search.SearchEngine
import com.here.time.Duration
import java.util.Calendar
import java.util.Date

class ManageRoute : AppCompatActivity() {

    private var mapView: MapView? = null
    private val locationIndicatorList = arrayListOf<LocationIndicator>()
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

    // Search related variables
    private val searchMarkers = arrayListOf<MapMarker>()
    private lateinit var routingExample: RoutingExample
    private lateinit var saveLocationButton: Button
    private lateinit var addDestinationButton: Button
    private lateinit var addWaypointButton: Button
    private lateinit var saveRouteButton: Button
    private lateinit var clearRouteButton: Button
    private lateinit var locationActionsContainer: LinearLayout

    private var isSelectingOrigin = true
    private var isSelectingDestination = false
    private var isSelectingWaypoint = false
    private var selectedOrigin: GeoCoordinates? = null
    private var selectedDestination: GeoCoordinates? = null
    private var currentSelectedPlace: Place? = null

    private lateinit var searchManager: SearchManager



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
        setupMapStyleButton()
        searchManager = SearchManager(this)
        setupSearchFunctionality()
        setupSearchManagerCallbacks()
        setupMyLocationButton()

        mapView?.onCreate(savedInstanceState)
        mapView?.setOnReadyListener {
            Log.d(TAG, "MapView is ready to use.")
        }
        mapView?.let { setTapGestureHandler(it) }
        getLocation { geoCoordinates ->
            if (geoCoordinates != null) {
                loadMapScene(geoCoordinates)
            }
        }
    }

    private fun setupSearchManagerCallbacks() {
        searchManager.onPlaceSelected = { place ->
            Log.d(TAG, "Place selected: ${place.title} at ${place.areaType}")
            currentSelectedPlace = place

            place.geoCoordinates?.let { coordinates ->
                handleLocationSelection(coordinates, place.title)
                addSearchMarker(coordinates, place.title)
                moveMapToLocation(coordinates)

                // Clear search state and hide overlay immediately after selection
                clearSearchState()
                hideSearchOverlay()

                // Show place details after clearing search
                showPlaceDetails(place)
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
    private fun clearSearchState() {
        // Clear search text and dismiss dropdown
        searchManager.isUserInput = false // Prevent triggering text change listeners
        searchAutoComplete.setText("")
        searchManager.isUserInput = true

        searchAutoComplete.dismissDropDown()
        searchManager.clearSuggestions()

        // Hide clear button
        clearSearchButton.visibility = View.GONE

        Log.d(TAG, "Search state cleared")
    }

    private fun setupSearchFunctionality() {
        // Setup SearchManager with AutoCompleteTextView
        searchManager.setupAutoCompleteTextView(searchAutoComplete)

        // Setup text change listener for auto-suggestions
        searchAutoComplete.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim() ?: ""

                if (searchManager.isUserInput) {
                    if (query.isNotEmpty()) {
                        Log.d(TAG, "Performing auto-suggest for: $query")
                        val centerCoordinates = mapView?.camera?.state?.targetCoordinates
                        centerCoordinates?.let {
                            searchManager.performAutoSuggest(query, it)
                        }
                    } else if (query.isEmpty()) {
                        searchManager.clearSuggestions()
                        searchAutoComplete.dismissDropDown()
                        Log.d(TAG, "Search box empty - suggestions cleared and dropdown dismissed")
                    }
                }

                clearSearchButton.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        // Focus listener for search box
        searchAutoComplete.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                showSearchOverlay()
            }
        }

        // Updated clear search functionality - only clears search, doesn't reset entire state
        clearSearchButton.setOnClickListener {
            Log.d(TAG, "Clear search button clicked")
            clearSearchState()

            // Only clear search markers if we're in search mode (not selected place mode)
            if (placeDetailsContainer.visibility != View.VISIBLE) {
                clearSearchMarkers()
            }

            Log.d(TAG, "Search cleared")
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

        // Initialize routing example and location action buttons
        routingExample = RoutingExample(this, mapView!!)
        locationActionsContainer = findViewById(R.id.location_actions_container)
        saveLocationButton = findViewById(R.id.btn_save_location)
        addDestinationButton = findViewById(R.id.btn_add_destination)
        addWaypointButton = findViewById(R.id.btn_add_waypoint)
        saveRouteButton = findViewById(R.id.btn_save_route)
        clearRouteButton = findViewById(R.id.btn_clear_route)

        setupLocationActionButtons()
        setupSearchOverlay()
    }

    private fun setupSearchOverlay() {
        // Click on compact search bar to open full screen search
        searchContainer.setOnClickListener {
            showSearchOverlay()
        }

        // Back button in search overlay
        backSearchButton.setOnClickListener {
            hideSearchOverlay()
        }
    }

    private fun showSearchOverlay() {
        searchOverlay.visibility = View.VISIBLE
        searchAutoComplete.requestFocus()

        // Show keyboard
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(searchAutoComplete, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideSearchOverlay() {
        searchOverlay.visibility = View.GONE
        searchAutoComplete.clearFocus()

        // Ensure dropdown is dismissed
        searchAutoComplete.dismissDropDown()
        searchManager.clearSuggestions()

        // Hide keyboard
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(searchAutoComplete.windowToken, 0)
    }
    @SuppressLint("SetTextI18n")
    private fun setupLocationActionButtons() {
        saveLocationButton.setOnClickListener {
            currentSelectedPlace?.let { place ->
                Log.d(TAG, "Location saved: ${place.title} - ${place.address}")
                Toast.makeText(this, "Location '${place.title}' saved to log", Toast.LENGTH_SHORT).show()
            }
        }

        addDestinationButton.setOnClickListener {
            // Switch to destination selection mode
            isSelectingOrigin = false
            isSelectingDestination = true
            isSelectingWaypoint = false

            // Clear current search state and reset for new search
            clearSearchState()

            // Update search hint and show search overlay with fresh state
            searchAutoComplete.hint = "Search for destination..."
            searchHintText.text = "Search for destination..."

            // Switch back to search mode
            showSearchHideDetails()

            Log.d(TAG, "Switched to destination selection mode")
        }

        addWaypointButton.setOnClickListener {
            // Switch to waypoint selection mode
            isSelectingOrigin = false
            isSelectingDestination = false
            isSelectingWaypoint = true

            // Clear current search state and reset for new search
            clearSearchState()

            // Update search hint and show search overlay with fresh state
            searchAutoComplete.hint = "Search for waypoint..."
            searchHintText.text = "Search for waypoint..."

            // Switch back to search mode
            showSearchHideDetails()

            Log.d(TAG, "Switched to waypoint selection mode")
        }

        saveRouteButton.setOnClickListener {
            // Log the complete route
            Log.d(TAG, "Route saved with origin: $selectedOrigin, destination: $selectedDestination")
            Toast.makeText(this, "Route saved to log", Toast.LENGTH_SHORT).show()
        }

        clearRouteButton.setOnClickListener {
            resetToInitialState()
        }

        btnStartNavigation.setOnClickListener {
            Toast.makeText(this, "Starting navigation...", Toast.LENGTH_SHORT).show()
            // Here you would typically start navigation
        }
    }

    private fun showPlaceDetails(place: Place) {
        // Update place information
        tvPlaceTitle.text = place.title
        tvPlaceAddress.text = place.address.addressText

        // Show place type if available
        place.areaType?.let { areaType ->
            tvPlaceType.text = areaType.toString()
            tvPlaceType.visibility = View.VISIBLE
        } ?: run {
            tvPlaceType.visibility = View.GONE
        }

        // Show place details container and hide search container
        placeDetailsContainer.visibility = View.VISIBLE
        searchContainer.visibility = View.GONE

        // Show appropriate action buttons based on selection mode
        updateActionButtonsVisibility()
    }

    private fun updateActionButtonsVisibility() {
        when {
            isSelectingOrigin -> {
                locationActionsContainer.visibility = View.VISIBLE
                routeActionsContainer.visibility = View.GONE
                routeSummaryContainer.visibility = View.GONE

                addDestinationButton.visibility = View.VISIBLE
                addWaypointButton.visibility = View.GONE
            }

            isSelectingDestination -> {
                locationActionsContainer.visibility = View.VISIBLE
                routeActionsContainer.visibility = View.VISIBLE
                routeSummaryContainer.visibility = View.GONE

                addDestinationButton.visibility = View.GONE
                addWaypointButton.visibility = View.VISIBLE

                // Show route summary if we have both origin and destination
                if (selectedOrigin != null && selectedDestination != null) {
                    showRouteSummary()
                }
            }

            isSelectingWaypoint -> {
                locationActionsContainer.visibility = View.VISIBLE
                routeActionsContainer.visibility = View.VISIBLE
                routeSummaryContainer.visibility = View.VISIBLE
            }
        }
    }

    private fun showRouteSummary() {
        // This would typically get actual route data from your routing engine
        tvRouteDistance.text = "2.5 km"  // Replace with actual distance
        tvRouteDuration.text = "8 mins"  // Replace with actual duration
        routeSummaryContainer.visibility = View.VISIBLE
    }

    private fun showSearchHideDetails() {
        searchContainer.visibility = View.VISIBLE
        placeDetailsContainer.visibility = View.GONE
        routeSummaryContainer.visibility = View.GONE
        showSearchOverlay()

        // Focus on search box for immediate typing
        searchAutoComplete.requestFocus()
    }


    private fun resetToInitialState() {
        // Reset selection state
        isSelectingOrigin = true
        isSelectingDestination = false
        isSelectingWaypoint = false

        // Clear selections
        selectedOrigin = null
        selectedDestination = null
        currentSelectedPlace = null

        // Clear search state
        clearSearchState()

        // Reset UI hints
        searchAutoComplete.hint = "Search for origin location..."
        searchHintText.text = "Search for origin location..."

        // Reset visibility
        searchContainer.visibility = View.VISIBLE
        placeDetailsContainer.visibility = View.GONE
        routeSummaryContainer.visibility = View.GONE
        routeActionsContainer.visibility = View.GONE
        hideSearchOverlay()

        // Clear routing data and markers
        routingExample.clearRouteData()
        clearSearchMarkers()

        Log.d(TAG, "Reset to initial state completed")
    }

    private fun initializeSearchEngine() {
        try {
            searchEngine = SearchEngine()
        } catch (e: InstantiationErrorException) {
            throw RuntimeException("Initialization of SearchEngine failed: " + e.error.name)
        }
    }

    private fun setupMyLocationButton() {
        myLocationFab.setOnClickListener {
            // Check permissions directly
            val fineLocationPermission = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
            val coarseLocationPermission = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )

            if (fineLocationPermission == PackageManager.PERMISSION_GRANTED ||
                coarseLocationPermission == PackageManager.PERMISSION_GRANTED) {
                getLocation { coordinates ->
                    coordinates?.let {
                        clearSearchMarkers()
                        moveMapToLocation(it)
                        showLocationIndicatorPedestrian(it)
                    }
                }
            } else {
                Toast.makeText(this, "Location permission is required", Toast.LENGTH_SHORT).show()
            }
        }
    }

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

    private fun addSearchMarker(coordinates: GeoCoordinates, title: String) {
        try {
            val mapImage = MapImageFactory.fromResource(resources, android.R.drawable.ic_menu_mylocation)
            val mapMarker = MapMarker(coordinates, mapImage, Anchor2D(0.5, 1.0))

            mapView?.mapScene?.addMapMarker(mapMarker)
            searchMarkers.add(mapMarker)

            Log.d(TAG, "Successfully added search marker: '$title' at $coordinates")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add search marker: ${e.message}")
        }
    }

    private fun clearSearchMarkers() {
        searchMarkers.forEach { marker ->
            mapView?.mapScene?.removeMapMarker(marker)
        }
        searchMarkers.clear()
    }

    private fun moveMapToLocation(coordinates: GeoCoordinates) {
        val distanceInMeters = (1000 * 2).toDouble() // 2km zoom
        val mapMeasureZoom = MapMeasure(MapMeasure.Kind.DISTANCE_IN_METERS, distanceInMeters)
        mapView?.camera?.lookAt(coordinates, mapMeasureZoom)
    }

    private fun flyTo(geoCoordinates: GeoCoordinates) {
        val geoCoordinatesUpdate = GeoCoordinatesUpdate(geoCoordinates)
        val bowFactor = 1.0
        val animation = MapCameraAnimationFactory.flyTo(geoCoordinatesUpdate, bowFactor, Duration.ofSeconds(3))
        mapView?.camera?.startAnimation(animation)
    }

    private fun setupMapStyleButton() {
        // Set initial scheme based on time
        currentSchemeIndex = getTimeBasedSchemeIndex()

        mapStyleFab.setOnClickListener {
            currentSchemeIndex = (currentSchemeIndex + 1) % mapSchemes.size
            changeMapScheme(mapSchemes[currentSchemeIndex])

            // Show toast with current map style
            Toast.makeText(this, mapSchemeNames[currentSchemeIndex], Toast.LENGTH_SHORT).show()
        }
    }

    private fun getTimeBasedSchemeIndex(): Int {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)

        // Consider night time between 6 PM (18:00) and 6 AM (06:00)
        return if (hour >= 18 || hour < 6) {
            1 // NORMAL_NIGHT
        } else {
            0 // NORMAL_DAY
        }
    }

    private fun getTimeBasedMapScheme(): MapScheme {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)

        // Consider night time between 6 PM (18:00) and 6 AM (06:00)
        return if (hour >= 18 || hour < 6) {
            MapScheme.NORMAL_NIGHT
        } else {
            MapScheme.NORMAL_DAY
        }
    }

    private fun loadMapScene(geoCoordinates: GeoCoordinates) {
        val mapScheme = getTimeBasedMapScheme()
        mapView?.mapScene?.loadScene(mapScheme) { mapError ->
            if (mapError == null) {
                val distanceInMeters = (1000 * 10).toDouble()
                val mapMeasureZoom = MapMeasure(MapMeasure.Kind.DISTANCE_IN_METERS, distanceInMeters)
                mapView?.camera?.lookAt(geoCoordinates, mapMeasureZoom)
                showLocationIndicatorPedestrian(geoCoordinates)
            } else {
                Log.d(TAG, "Loading map failed: mapError: " + mapError.name)
            }
        }
    }

    private fun changeMapScheme(mapScheme: MapScheme) {
        mapView?.mapScene?.loadScene(mapScheme) { mapError ->
            if (mapError == null) {
                Log.d(TAG, "Map scheme changed successfully to: ${mapSchemeNames[currentSchemeIndex]}")
            } else {
                Log.d(TAG, "Failed to change map scheme: " + mapError.name)
            }
        }
    }

    private fun getRandom(min: Double, max: Double): Double {
        return min + Math.random() * (max - min)
    }

    private fun addLocationIndicator(
        geoCoordinates: GeoCoordinates,
        indicatorStyle: LocationIndicator.IndicatorStyle
    ) {
        val locationIndicator = LocationIndicator()
        locationIndicator.locationIndicatorStyle = indicatorStyle

        // A LocationIndicator is intended to mark the user's current location,
        // including a bearing direction.
        val location = Location(geoCoordinates)
        location.time = Date()
        location.bearingInDegrees = getRandom(0.0, 360.0)

        locationIndicator.updateLocation(location)

        // Show the indicator on the map view.
        mapView?.let {
            locationIndicator.enable(it)
            locationIndicatorList.add(locationIndicator)
        }
    }

    fun showLocationIndicatorPedestrian(geoCoordinates: GeoCoordinates) {
        unTiltMap()
        // Centered on location.
        addLocationIndicator(geoCoordinates, LocationIndicator.IndicatorStyle.PEDESTRIAN)
    }

    private fun unTiltMap() {
        val bearing = mapView?.camera?.state?.orientationAtTarget?.bearing
        val tilt = 0.0
        mapView?.camera?.setOrientationAtTarget(GeoOrientationUpdate(bearing, tilt))
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

    private fun setTapGestureHandler(mapView: MapView) {
        mapView.gestures.tapListener = TapListener { touchPoint ->
            val geoCoordinates = mapView.viewToGeoCoordinates(touchPoint)
            Log.d(TAG, "Tap at: ${geoCoordinates?.latitude}, ${geoCoordinates?.longitude}")
        }
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
        // Clear search markers before destroying
        clearSearchMarkers()
        mapView?.onDestroy()
        disposeHERESDK()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        mapView?.onSaveInstanceState(outState)
        super.onSaveInstanceState(outState)
    }

    private fun disposeHERESDK() {
        SDKNativeEngine.getSharedInstance()?.dispose()
        SDKNativeEngine.setSharedInstance(null)
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
}