package com.gocavgo.entries.hereroutes

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
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
import com.here.sdk.search.SearchCallback
import com.here.sdk.search.SearchEngine
import com.here.sdk.search.SearchError
import com.here.sdk.search.SearchOptions
import com.here.sdk.search.SuggestCallback
import com.here.sdk.search.Suggestion
import com.here.sdk.search.TextQuery
import com.here.time.Duration
import java.util.Calendar
import java.util.Date

class ManageRoute : AppCompatActivity() {

    private var mapView: MapView? = null
    private val locationIndicatorList = arrayListOf<LocationIndicator>()
    private lateinit var locationClient: FusedLocationProviderClient
    private val TAG = ManageRoute::class.java.simpleName
    private lateinit var mapStyleButton: Button
    private lateinit var searchAutoComplete: AutoCompleteTextView
    private lateinit var clearSearchButton: ImageButton
    private lateinit var myLocationFab: com.google.android.material.floatingactionbutton.FloatingActionButton
    private lateinit var searchEngine: SearchEngine

    // Search related variables
    private val searchMarkers = arrayListOf<MapMarker>()
    private val suggestions = arrayListOf<Suggestion>()
    private lateinit var suggestionsAdapter: ArrayAdapter<String>
    private var isUserInput = true
    private lateinit var routingExample: RoutingExample
    private lateinit var saveLocationButton: Button
    private lateinit var addDestinationButton: Button
    private lateinit var addWaypointButton: Button
    private lateinit var locationActionsContainer: LinearLayout
    private var isSelectingOrigin = true
    private var isSelectingDestination = false
    private var isSelectingWaypoint = false
    private var selectedOrigin: GeoCoordinates? = null
    private var selectedDestination: GeoCoordinates? = null

    private var searchedLocation: Place? = null

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
        "Hybrid Night",
        "Terrain Day",
        "Terrain Night"
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
        setupSearchFunctionality()
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

    private fun initializeViews() {
        mapView = findViewById(R.id.map_view)
        mapStyleButton = findViewById(R.id.btn_map_style)
        searchAutoComplete = findViewById(R.id.search_autocomplete)
        clearSearchButton = findViewById(R.id.clear_search)
        myLocationFab = findViewById(R.id.fab_my_location)

        // Initialize routing example and location action buttons
        routingExample = RoutingExample(this, mapView!!)
        locationActionsContainer = findViewById(R.id.location_actions_container)
        saveLocationButton = findViewById(R.id.btn_save_location)
        addDestinationButton = findViewById(R.id.btn_add_destination)
        addWaypointButton = findViewById(R.id.btn_add_waypoint)

        setupLocationActionButtons()
    }

    private fun setupLocationActionButtons() {
        saveLocationButton.setOnClickListener {
            selectedOrigin?.let { coordinates ->
                Log.d(TAG, "Location saved: ${coordinates.latitude}, ${coordinates.longitude}")
                Toast.makeText(this, "Location saved to log", Toast.LENGTH_SHORT).show()
            }
        }

        addDestinationButton.setOnClickListener {
            // Switch to destination selection mode
            isSelectingOrigin = false
            isSelectingDestination = true
            isSelectingWaypoint = false

            // Show search box again for destination
            searchAutoComplete.hint = "Search for destination..."
            searchAutoComplete.text.clear()
            locationActionsContainer.visibility = View.GONE

            Log.d(TAG, "Switched to destination selection mode")
        }

        addWaypointButton.setOnClickListener {
            // Switch to waypoint selection mode
            isSelectingOrigin = false
            isSelectingDestination = false
            isSelectingWaypoint = true

            // Show search box again for waypoint
            searchAutoComplete.hint = "Search for waypoint..."
            searchAutoComplete.text.clear()
            locationActionsContainer.visibility = View.GONE

            Log.d(TAG, "Switched to waypoint selection mode")
        }
    }

    private fun initializeSearchEngine() {
        try {
            searchEngine = SearchEngine()
        } catch (e: InstantiationErrorException) {
            throw RuntimeException("Initialization of SearchEngine failed: " + e.error.name)
        }
    }

    private fun setupSearchFunctionality() {
        // Initialize suggestions adapter
        suggestionsAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, arrayListOf<String>())
        searchAutoComplete.setAdapter(suggestionsAdapter)
        searchAutoComplete.threshold = 1

        // SOLUTION 1: Override focus change behavior to keep suggestions visible
        searchAutoComplete.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus && searchAutoComplete.text.isNotEmpty() && suggestionsAdapter.count > 0) {
                // Show dropdown when focused and there are suggestions
                searchAutoComplete.showDropDown()
            }
            // Don't automatically dismiss when losing focus - let user control this
        }

        // SOLUTION 2: Add click listener to keep dropdown visible when clicking on the field
        searchAutoComplete.setOnClickListener {
            if (searchAutoComplete.text.isNotEmpty() && suggestionsAdapter.count > 0) {
                searchAutoComplete.showDropDown()
            }
        }

        // Setup text change listener for auto-suggestions
        searchAutoComplete.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim() ?: ""

                if (isUserInput) {
                    if (query.isNotEmpty()) {
                        Log.d(TAG, "Performing auto-suggest for: $query")
                        performAutoSuggest(query)
                    } else if (query.isEmpty()) {
                        // SOLUTION 3: Only clear and hide when search box is actually empty
                        suggestions.clear()
                        suggestionsAdapter.clear()
                        suggestionsAdapter.notifyDataSetChanged()
                        searchAutoComplete.dismissDropDown()
                        Log.d(TAG, "Search box empty - suggestions cleared and dropdown dismissed")
                    }
                }

                clearSearchButton.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        // Handle item selection from suggestions
        searchAutoComplete.setOnItemClickListener { parent, view, position, id ->
            Log.d(TAG, "Suggestion item clicked at position: $position")

            isUserInput = false

            if (position < suggestions.size) {
                val selectedSuggestion = suggestions[position]
                val suggestionTitle = selectedSuggestion.title

                Log.d(TAG, "Selected suggestion: $suggestionTitle")

                searchAutoComplete.setText(suggestionTitle)
                clearSearchMarkers()

                // Handle location selection based on current mode
                selectedSuggestion.place?.let { place ->
                    place.geoCoordinates?.let { coordinates ->
                        Log.d(TAG, "Moving to place coordinates: $coordinates")
                        handleLocationSelection(coordinates, place.title)
                        addSearchMarker(coordinates, place.title)
                        flyTo(coordinates)
                    }
                } ?: run {
                    Log.d(TAG, "No place in suggestion, performing text search")
                    performTextSearch(suggestionTitle)
                }

                // SOLUTION 4: Only clear suggestions after successful selection
                suggestions.clear()
                suggestionsAdapter.clear()
                suggestionsAdapter.notifyDataSetChanged()
                searchAutoComplete.dismissDropDown()
                Log.d(TAG, "Item selected - suggestions cleared and dropdown dismissed")
            } else {
                Log.w(TAG, "Invalid suggestion position: $position, suggestions size: ${suggestions.size}")
            }

            isUserInput = true
        }

        // SOLUTION 5: Modified search action to keep suggestions visible after hiding keyboard
        searchAutoComplete.setOnEditorActionListener { textView, actionId, event ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                val query = searchAutoComplete.text.toString().trim()
                Log.d(TAG, "Search action triggered with query: $query")

                // Hide keyboard
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(searchAutoComplete.windowToken, 0)

                // IMPORTANT: Show dropdown again after hiding keyboard if we have suggestions
                searchAutoComplete.post {
                    if (suggestionsAdapter.count > 0) {
                        searchAutoComplete.showDropDown()
                        Log.d(TAG, "Dropdown reshown after hiding keyboard")
                    }
                }

                Log.d(TAG, "Search button pressed - keyboard hidden but suggestions kept visible")
                true
            } else {
                false
            }
        }

        // Clear search functionality - keep existing logic
        clearSearchButton.setOnClickListener {
            Log.d(TAG, "Clear search button clicked")
            searchAutoComplete.text.clear()
            clearSearchMarkers()
            suggestions.clear()
            suggestionsAdapter.clear()
            suggestionsAdapter.notifyDataSetChanged()
            searchAutoComplete.dismissDropDown()

            // Reset to origin selection mode
            isSelectingOrigin = true
            isSelectingDestination = false
            isSelectingWaypoint = false
            searchAutoComplete.hint = "Search for origin location..."
            locationActionsContainer.visibility = View.GONE

            // Reset routing state
            routingExample.clearRouteData()
            selectedOrigin = null
            selectedDestination = null

            // Reset button visibility
            addDestinationButton.visibility = View.VISIBLE
            addWaypointButton.visibility = View.GONE

            Log.d(TAG, "Search cleared and state reset")
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
                        moveMapToLocation(it)
                        // Optionally show location indicator
                        showLocationIndicatorPedestrian(it)
                    }
                }
            } else {
                // Request permissions or show message
                Toast.makeText(this, "Location permission is required", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun performAutoSuggest(query: String) {
        val centerCoordinates = mapView?.camera?.state?.targetCoordinates ?: return

        val searchOptions = SearchOptions().apply {
            languageCode = com.here.sdk.core.LanguageCode.EN_US
            maxItems = 10
        }

        val textQuery = TextQuery(query, TextQuery.Area(centerCoordinates))

        searchEngine.suggestByText(textQuery, searchOptions, object : SuggestCallback {
            override fun onSuggestCompleted(searchError: SearchError?, list: List<Suggestion>?) {
                if (searchError != null) {
                    Log.d(TAG, "Autosuggest Error: ${searchError.name}")
                    runOnUiThread {
                        suggestions.clear()
                        suggestionsAdapter.clear()
                        suggestionsAdapter.notifyDataSetChanged()
                        // Only dismiss if there are actually no suggestions
                        if (suggestionsAdapter.count == 0) {
                            searchAutoComplete.dismissDropDown()
                            Log.d(TAG, "No suggestions due to error - dropdown dismissed")
                        }
                    }
                    return
                }

                list?.let { suggestionList ->
                    Log.d(TAG, "Received ${suggestionList.size} suggestions for query: $query")

                    suggestions.clear()
                    suggestions.addAll(suggestionList)

                    val suggestionTitles = suggestionList.mapNotNull { suggestion ->
                        val title = suggestion.title
                        val place = suggestion.place

                        when {
                            place != null -> {
                                val address = place.address.addressText
                                if (address.isNotEmpty() && address != title) {
                                    "$title - $address"
                                } else {
                                    title
                                }
                            }
                            title.isNotEmpty() -> title
                            else -> null
                        }
                    }.filter { it.isNotEmpty() }

                    runOnUiThread {
                        suggestionsAdapter.clear()
                        if (suggestionTitles.isNotEmpty()) {
                            suggestionsAdapter.addAll(suggestionTitles)
                            Log.d(TAG, "Added ${suggestionTitles.size} suggestion titles to adapter")

                            // SOLUTION 7: Always show dropdown when we have valid suggestions
                            // regardless of focus state
                            searchAutoComplete.showDropDown()
                            Log.d(TAG, "Dropdown shown with suggestions")
                        } else {
                            Log.d(TAG, "No valid suggestion titles found")
                            searchAutoComplete.dismissDropDown()
                            Log.d(TAG, "No valid suggestions - dropdown dismissed")
                        }
                        suggestionsAdapter.notifyDataSetChanged()
                    }
                } ?: run {
                    Log.d(TAG, "Suggestion list is null")
                    runOnUiThread {
                        suggestions.clear()
                        suggestionsAdapter.clear()
                        suggestionsAdapter.notifyDataSetChanged()
                        searchAutoComplete.dismissDropDown()
                        Log.d(TAG, "Null suggestion list - dropdown dismissed")
                    }
                }
            }
        })
    }


    private fun performTextSearch(query: String) {
        Log.d(TAG, "Performing text search for: $query")

        val centerCoordinates = mapView?.camera?.state?.targetCoordinates
        if (centerCoordinates == null) {
            Log.e(TAG, "Cannot perform search: map center coordinates are null")
            return
        }

        val searchOptions = SearchOptions().apply {
            languageCode = com.here.sdk.core.LanguageCode.EN_US
            maxItems = 20
        }

        val textQuery = TextQuery(query, TextQuery.Area(centerCoordinates))

        searchEngine.searchByText(textQuery, searchOptions, object : SearchCallback {
            override fun onSearchCompleted(searchError: SearchError?, list: List<Place>?) {
                if (searchError != null) {
                    Log.e(TAG, "Search Error: ${searchError.name}")
                    runOnUiThread {
                        Toast.makeText(this@ManageRoute, "Search failed: ${searchError.name}", Toast.LENGTH_SHORT).show()
                    }
                    return
                }

                list?.let { places ->
                    Log.d(TAG, "Search completed with ${places.size} results")

                    runOnUiThread {
                        clearSearchMarkers()

                        if (places.isNotEmpty()) {
                            val firstPlace = places[0]
                            Log.d(TAG, "First search result: ${firstPlace.toString()} at ${firstPlace.geoCoordinates}")
                            firstPlace.geoCoordinates?.let { coordinates ->
                                // Handle location selection based on current mode
                                handleLocationSelection(coordinates, firstPlace.title)

                                // Add marker and move camera
                                addSearchMarker(coordinates, firstPlace.title)
                                flyTo(coordinates)
                            }

                            Toast.makeText(this@ManageRoute, "Location selected", Toast.LENGTH_SHORT).show()
                        } else {
                            Log.d(TAG, "No search results found")
                            Toast.makeText(this@ManageRoute, "No results found for '$query'", Toast.LENGTH_SHORT).show()
                        }
                    }
                } ?: run {
                    Log.e(TAG, "Search results list is null")
                    runOnUiThread {
                        Toast.makeText(this@ManageRoute, "Search failed: No results", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }

    private fun handleLocationSelection(coordinates: GeoCoordinates, title: String) {
        when {
            isSelectingOrigin -> {
                selectedOrigin = coordinates
                routingExample.setOrigin(coordinates)

                // Hide search box and show action buttons
                locationActionsContainer.visibility = View.VISIBLE
                addWaypointButton.visibility = View.GONE // Hide waypoint button initially

                Log.d(TAG, "Origin selected: $title at ${coordinates.latitude}, ${coordinates.longitude}")
            }

            isSelectingDestination -> {
                selectedDestination = coordinates
                routingExample.setDestination(coordinates)

                // Show waypoint button and hide destination button
                addDestinationButton.visibility = View.GONE
                addWaypointButton.visibility = View.VISIBLE
                locationActionsContainer.visibility = View.VISIBLE

                Log.d(TAG, "Destination selected: $title at ${coordinates.latitude}, ${coordinates.longitude}")
            }

            isSelectingWaypoint -> {
                routingExample.addWaypoint(coordinates)
                locationActionsContainer.visibility = View.VISIBLE

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
        val animation =  MapCameraAnimationFactory.flyTo(geoCoordinatesUpdate, bowFactor, Duration.ofSeconds(3))
        mapView?.camera?.startAnimation(animation)
    }

    private fun setupMapStyleButton() {
        // Set initial scheme based on time
        currentSchemeIndex = getTimeBasedSchemeIndex()
        updateButtonText()

        mapStyleButton.setOnClickListener {
            currentSchemeIndex = (currentSchemeIndex + 1) % mapSchemes.size
            updateButtonText()
            changeMapScheme(mapSchemes[currentSchemeIndex])
        }
    }

    private fun updateButtonText() {
        mapStyleButton.text = mapSchemeNames[currentSchemeIndex]
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