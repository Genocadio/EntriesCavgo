package com.gocavgo.entries.mapview


import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.gocavgo.entries.BuildConfig
import com.gocavgo.entries.R
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.*
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.AutocompleteSessionToken
import com.google.android.libraries.places.api.net.*
import com.google.android.gms.maps.MapView
import com.google.android.libraries.places.api.model.Place
import org.json.JSONObject
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import android.content.Context
import android.graphics.Color
import android.location.Geocoder
import android.widget.ArrayAdapter
import android.widget.Switch
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import com.gocavgo.entries.service.LocationService
import com.gocavgo.entries.dataclass.LocationDataDto
import com.gocavgo.entries.dataclass.WaypointDataDto
import com.gocavgo.entries.service.RouteService
import javax.inject.Inject
import dagger.hilt.android.AndroidEntryPoint


@AndroidEntryPoint
class CreateRouteActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var mapView: MapView
    private lateinit var googleMap: GoogleMap
    private lateinit var originInput: AutoCompleteTextView
    private lateinit var routePriceInput: EditText
    private lateinit var destinationInput: AutoCompleteTextView
    private lateinit var saveButton: Button
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var placesClient: PlacesClient
    private var originCustomName: String? = null
    private var destinationCustomName: String? = null
    private var originGooglePlaceName: String? = null
    private var destinationGooglePlaceName: String? = null
    private var routeDistance: Double = 0.0

    private var routeDuration: Int = 0

    @Inject lateinit var locationService: LocationService
    @Inject lateinit var routeService: RouteService
    private var dbLocations = mutableListOf<LocationDataDto>()


    private var originLatLng: LatLng? = null
    private var destinationLatLng: LatLng? = null
    private var originMarker: Marker? = null
    private var destinationMarker: Marker? = null
    private var polyline: Polyline? = null // Reference for the previously drawn polyline
    private val apiKey = BuildConfig.MAPS_API_KEY
    private val token = AutocompleteSessionToken.newInstance()

    private lateinit var addWaypointButton: Button
    private val waypoints = mutableListOf<LatLng>()
    private val waypointMarkers = mutableListOf<Marker?>()
    private val waypointDetails = mutableListOf<WaypointDetail>()
    private lateinit var mapTypeButton: ImageButton
    private var routePrice: Double = 0.0

    private var originPlaceId: String? = null
    private var destinationPlaceId: String? = null

    private var useDbLocations = false

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private lateinit var useDbLocationsSwitch: Switch


    // New data class to hold waypoint information
    data class WaypointDetail(
        var marker: Marker?,
        var latLng: LatLng,
        var googlePlaceName: String? = null,  // Original Google place name
        var customName: String? = null,       // User-defined custom name
        var price: Double? = null,
        var index: Int = -1,
        var placeId: String? = null           // Optional: store Google Place ID for future reference
    )

    private fun loadDatabaseLocations(callback: () -> Unit) {
        lifecycleScope.launch {
            try {
                dbLocations = locationService.getAllLocations().toMutableList()
                Log.d("CreateRouteActivity", "Locations fetched: ${dbLocations.size} items")
                callback() // Execute callback when locations are loaded
            } catch (e: Exception) {
                Log.e("CreateRouteActivity", "Error fetching locations: ${e.message}")
                Toast.makeText(
                    this@CreateRouteActivity,
                    "Error fetching locations",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }


    private fun saveCurrentRoute(context: Context) {
        if (!verifyRouteData(context)) return // Exit if verification fails

        // Assuming you have all the necessary data
        val origin = LocationDataDto(
            latitude = originLatLng!!.latitude,
            longitude = originLatLng!!.longitude,
            googlePlaceName = originGooglePlaceName,
            customName = originCustomName,
            placeId = originPlaceId
        )

        val destination = LocationDataDto(
            latitude = destinationLatLng!!.latitude,
            longitude = destinationLatLng!!.longitude,
            googlePlaceName = destinationGooglePlaceName,
            customName = destinationCustomName,
            placeId = destinationPlaceId
        )

        val routeWaypoints = waypointDetails.mapIndexed { index, waypoint ->
            WaypointDataDto(
                location = LocationDataDto(
                    latitude = waypoint.latLng.latitude,
                    longitude = waypoint.latLng.longitude,
                    googlePlaceName = waypoint.googlePlaceName,
                    customName = waypoint.customName,
                    placeId = waypoint.placeId
                ),
                price = waypoint.price!!,
                order = index
            )
        }

        val routeData = routeService.createRouteData(
            origin = origin,
            destination = destination,
            waypoints = routeWaypoints,
            distance = routeDistance,
            duration = routeDuration,
            polyline = polyline?.points?.toString() ?: "",
            price = routePrice
        )

        if (routeWaypoints.size > 1) {
            showRouteConfirmationDialog(context, origin, routeWaypoints, destination)
        } else {
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    when (val result = routeService.saveRoute(routeData)) {
                        is RouteService.SaveResult.Success-> {
                            Toast.makeText(context, "Route saved successfully", Toast.LENGTH_SHORT)
                                .show()
                        }

                        is RouteService.SaveResult.Error -> {
                            Toast.makeText(
                                context,
                                "Error saving route: ${result.message}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }

                        is RouteService.SaveResult.ExistingLocation -> {
                            Toast.makeText(
                                this@CreateRouteActivity,
                                "Location already exists: ${result.id}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }

                    }
                    Log.d("CreateRouteActivity", "Route saved successfully")
                } catch (e: Exception) {
                    Log.e("CreateRouteActivity", "Error saving route: ${e.message}")
                    Toast.makeText(context, "Error saving route", Toast.LENGTH_SHORT).show()
                }
            }

        }
    }

    private fun buildRouteSummary(
        origin: LocationDataDto,
        waypoints: List<WaypointDataDto>,
        destination: LocationDataDto
    ): String {
        val waypointDetails = waypoints.mapIndexed { index, waypoint ->
            "➡ Stop #${index + 1}: ${waypoint.location.googlePlaceName} (Price: ${waypoint.price})"
        }.joinToString("\n")

        return """
    📍 Origin: ${origin.googlePlaceName}

    🛑 Waypoints (Drag to reorder):
    $waypointDetails

    📍 Destination: ${destination.googlePlaceName}

    💡 Tip: You can change the order of stops by selecting 'Reorder Waypoint' in the waypoint options.
""".trimIndent()
    }

    private fun showRouteConfirmationDialog(
        context: Context,
        origin: LocationDataDto,
        waypoints: List<WaypointDataDto>,
        destination: LocationDataDto
    ) {
        val routeDetails = buildRouteSummary(origin, waypoints, destination)

        AlertDialog.Builder(context)
            .setTitle("Confirm Route")
            .setMessage(routeDetails)
            .setPositiveButton("Save") { _, _ ->
                saveRoute(origin, waypoints, destination)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

  private fun saveRoute(
      origin: LocationDataDto,
      waypoints: List<WaypointDataDto>,
      destination: LocationDataDto
  ) {
      val routeData = routeService.createRouteData(
          origin = origin,
          destination = destination,
          waypoints = waypoints,
          distance = routeDistance,
          duration = routeDuration,
          polyline = polyline?.points?.toString() ?: "",
          price = routePrice
      )

      CoroutineScope(Dispatchers.Main).launch {
          try {
              when (val result = routeService.saveRoute(routeData)) {
                  is RouteService.SaveResult.Success -> {
                      Toast.makeText(
                          this@CreateRouteActivity,
                          "Route saved successfully",
                          Toast.LENGTH_SHORT
                      ).show()
                  }
                  is RouteService.SaveResult.Error -> {
                      Toast.makeText(
                          this@CreateRouteActivity,
                          "Error saving route: ${result.message}",
                          Toast.LENGTH_SHORT
                      ).show()
                  }
                  is RouteService.SaveResult.ExistingLocation -> {
                      Toast.makeText(
                          this@CreateRouteActivity,
                          "Location already exists: ${result.id}",
                          Toast.LENGTH_SHORT
                      ).show()
                  }
              }
          } catch (e: Exception) {
              Log.e("CreateRouteActivity", "Error saving route: ${e.message}")
              Toast.makeText(this@CreateRouteActivity, "Error saving route", Toast.LENGTH_SHORT)
                  .show()
          }
      }
  }

    // Verifier function to check route validity
    private fun verifyRouteData(context: Context): Boolean {
        // Check if origin and destination exist
        if (originLatLng == null || destinationLatLng == null) {
            Toast.makeText(context, "Origin and Destination are required!", Toast.LENGTH_SHORT)
                .show()
            return false
        }


        // Validate location fields for origin and destination
        if (!isValidLocation(originLatLng, originPlaceId, originGooglePlaceName)) {
            Toast.makeText(
                context,
                "Invalid Origin: Must have latLng or placeId, and googlePlaceName!",
                Toast.LENGTH_SHORT
            ).show()
            return false
        }
        if (!isValidLocation(destinationLatLng, destinationPlaceId, destinationGooglePlaceName)) {
            Toast.makeText(
                context,
                "Invalid Destination: Must have latLng or placeId, and googlePlaceName!",
                Toast.LENGTH_SHORT
            ).show()
            return false
        }
        if (routePrice <= 0) {
            Toast.makeText(context, "Route must have a valid price!", Toast.LENGTH_SHORT).show()
            return false
        }

        // Check waypoints
        for (waypoint in waypointDetails) {
            if (!isValidLocation(waypoint.latLng, waypoint.placeId, waypoint.googlePlaceName)) {
                Toast.makeText(
                    context,
                    "Invalid Waypoint: Must have latLng or placeId, and googlePlaceName!",
                    Toast.LENGTH_SHORT
                ).show()
                return false
            }
            if (waypoint.price == null || waypoint.price!! <= 0) {
                Toast.makeText(
                    context,
                    "Each waypoint must have a valid price!",
                    Toast.LENGTH_SHORT
                ).show()
                return false
            }
        }

        return true // All checks passed
    }

    // Helper function to validate a location
    private fun isValidLocation(
        latLng: LatLng?,
        placeId: String?,
        googlePlaceName: String?
    ): Boolean {
        return (latLng != null || !placeId.isNullOrBlank()) && !googlePlaceName.isNullOrBlank()
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_create_route)


        useDbLocationsSwitch = findViewById(R.id.use_db_locations_switch)
        useDbLocationsSwitch.setOnCheckedChangeListener { _, isChecked ->
            useDbLocations = isChecked
            Log.d("CreateRouteActivity", "Use DB Locations toggled: $useDbLocations")

            if (useDbLocations) {
                loadDatabaseLocations {
                    setupAutoComplete() // Only setup after locations are loaded
                }
            } else {
                setupAutoComplete() // No need to load locations, just setup
            }
        }

        routeService = RouteService()
        locationService = LocationService()
        mapView = findViewById(R.id.map_view)
        originInput = findViewById(R.id.origin_input)
        routePriceInput = findViewById(R.id.price_input)
        destinationInput = findViewById(R.id.destination_input)
        saveButton = findViewById(R.id.save_route_button)
        saveButton.setOnClickListener {
            saveCurrentRoute(this)
        }
        setupRoutePriceInput()
        addWaypointButton = findViewById(R.id.add_waypoint_button)
        addWaypointButton.setOnClickListener {
            showWaypointInputDialog()
        }


        if (!Places.isInitialized()) {
            Places.initialize(applicationContext, apiKey)
        }
        placesClient = Places.createClient(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync(this)
        mapTypeButton = findViewById(R.id.map_type_toggle_button)
        setupMapTypeToggle()
        if (useDbLocations) {
            loadDatabaseLocations {
                setupAutoComplete()
            }
        } else {
            setupAutoComplete()
        }
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
//        finish()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) { // Ensures it doesn't close on screen rotation
            mapView.onStop()
//            finish()
        }
    }

    private fun setupRoutePriceInput() {
        routePriceInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, after: Int) {}

            override fun afterTextChanged(s: Editable?) {
                val priceText = s.toString()

                if (priceText.isNotEmpty()) {
                    try {
                        val price = priceText.toDouble()
                        routePrice = price

                        // Optional: Validate and format the input
                        if (price < 0) {
                            routePriceInput.error = "Price cannot be negative"
                        } else {
                            routePriceInput.error = null
                        }
                    } catch (e: NumberFormatException) {
                        routePriceInput.error = "Invalid price"
                        routePrice = 0.0
                    }
                } else {
                    routePrice = 0.0
                }
            }
        })
    }

    private fun setupMapTypeToggle() {
        mapTypeButton.setOnClickListener {
            // Toggle between MAP_TYPE_NORMAL and MAP_TYPE_SATELLITE
            when (googleMap.mapType) {
                GoogleMap.MAP_TYPE_NORMAL -> {
                    googleMap.mapType = GoogleMap.MAP_TYPE_HYBRID
                    mapTypeButton.setImageResource(R.drawable.ic_map_view) // Use a map icon
                }

                GoogleMap.MAP_TYPE_HYBRID -> {
                    googleMap.mapType = GoogleMap.MAP_TYPE_NORMAL
                    mapTypeButton.setImageResource(R.drawable.ic_satellite_view) // Use a satellite icon
                }

                else -> {
                    googleMap.mapType = GoogleMap.MAP_TYPE_NORMAL
                    mapTypeButton.setImageResource(R.drawable.ic_map_view)
                }
            }
        }
    }

    private fun showWaypointInputDialog() {
        val input = AutoCompleteTextView(this)
        input.setHint("Enter waypoint address")
        val dialog = AlertDialog.Builder(this)
            .setTitle("Add Waypoint")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .create()

        input.setAdapter(null)
        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {}
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (s != null && s.length > 2) {
                    fetchAutoCompleteSuggestions(s.toString()) { suggestions ->
                        val adapter = ArrayAdapter(
                            this@CreateRouteActivity,
                            android.R.layout.simple_dropdown_item_1line,
                            suggestions.map { it.first }  // Use place name for display
                        )
                        input.setAdapter(adapter)
                        adapter.notifyDataSetChanged()
                    }
                }
            }
        })

        // Add item click listener to handle place selection
        input.setOnItemClickListener { _, _, position, _ ->
            val placeName = input.adapter.getItem(position) as String

            // Find the corresponding place details
            fetchAutoCompleteSuggestions(placeName) { suggestions ->
                val selectedPlace = suggestions.find { it.first == placeName }
                if (selectedPlace != null) {
                    getLatLngFromPlace(selectedPlace.second) { latLng ->
                        // Add the waypoint with fetched details
                        updateWaypointMarker(latLng, placeName, selectedPlace.second)
                        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
                        dialog.dismiss()
                        drawRouteIfNeeded()
                    }
                }
            }
        }

        dialog.show()
    }

    private fun updateWaypointMarker(
        latLng: LatLng,
        googlePlaceName: String? = null,
        placeId: String? = null
    ) {
        val newIndex = waypointDetails.size

        val marker = googleMap.addMarker(
            MarkerOptions()
                .position(latLng)
                .title(googlePlaceName ?: "Waypoint #$newIndex")
                .draggable(true)
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
        )

        val waypointDetail = WaypointDetail(
            marker = marker,
            latLng = latLng,
            googlePlaceName = googlePlaceName,
            index = newIndex,
            placeId = placeId  // Store the place ID
        )

        waypointDetails.add(waypointDetail)
        waypoints.add(latLng)
        waypointMarkers.add(marker)

        // Set up marker click listener
        googleMap.setOnMarkerClickListener { clickedMarker ->
            when (clickedMarker) {
                originMarker -> {
                    showLocationNameDialog("Origin", clickedMarker)
                    true
                }

                destinationMarker -> {
                    showLocationNameDialog("Destination", clickedMarker)
                    true
                }

                else -> {
                    // Existing waypoint marker click behavior
                    val selectedWaypointDetail = waypointDetails.find { it.marker == clickedMarker }
                    selectedWaypointDetail?.let {
                        showWaypointOptionsDialog(it)
                    }
                    true
                }
            }
        }

        // Set up marker drag listener
        googleMap.setOnMarkerDragListener(object : GoogleMap.OnMarkerDragListener {
            override fun onMarkerDragStart(draggedMarker: Marker) {
                // Optional: Add any start of drag handling
            }

            override fun onMarkerDrag(draggedMarker: Marker) {
                // Optional: Add any during drag handling
            }

            override fun onMarkerDragEnd(draggedMarker: Marker) {
                // Update the waypoint's location when dragged
                val newPosition = draggedMarker.position
                if (waypointMarkers.contains(draggedMarker)) {
                    val index = waypointMarkers.indexOf(draggedMarker)
                    waypoints[index] = draggedMarker.position
                    waypointDetails[index].latLng = newPosition
                    clearPreviousRoute()
                    drawRouteIfNeeded()
                }
            }
        })
    }

    private fun clearPreviousRoute() {
        // Remove the existing polyline
        polyline?.remove()
        polyline = null

        // Optionally, reset route-related variables if needed
        // This ensures a completely fresh route calculation
    }

   private fun showWaypointOptionsDialog(waypointDetail: WaypointDetail) {
    val options = arrayOf(
        "Edit Waypoint Name",
        "Edit Stop Price",
        "Remove Waypoint",
        "Reorder Waypoint",
        "save location"
    )

    AlertDialog.Builder(this)
        .setTitle("Waypoint #${waypointDetail.index} Options")
        .setItems(options) { _, which ->
            when (which) {
                0 -> showEditWaypointNameDialog(waypointDetail)
                1 -> showEditWaypointPriceDialog(waypointDetail)
                2 -> removeWaypoint(waypointDetail)
                3 -> showReorderWaypointDialog(waypointDetail)
                4 -> {
                    CoroutineScope(Dispatchers.Main).launch {
                        val locationData = locationService.createLocation(
                            waypointDetail.latLng,
                            waypointDetail.googlePlaceName,
                            waypointDetail.customName,
                            waypointDetail.placeId
                        )
                        try {
                            Log.d("CreateRouteActivity", "Saving location: $locationData")
                            when (val result = locationService.saveLocation(locationData)) {
                                is LocationService.SaveResult.Success -> Toast.makeText(
                                    this@CreateRouteActivity,
                                    "Location saved!",
                                    Toast.LENGTH_SHORT
                                ).show()

                                is LocationService.SaveResult.Error -> Toast.makeText(
                                    this@CreateRouteActivity,
                                    "Error: ${result.message}",
                                    Toast.LENGTH_LONG
                                ).show()

                                is LocationService.SaveResult.ExistingLocation -> {
                                    Toast.makeText(
                                        this@CreateRouteActivity,
                                        "Location already exists: ${result.id}",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        } catch (e: Exception) {
                            Toast.makeText(
                                this@CreateRouteActivity,
                                "Error saving location: ${e.message}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        }
        .setNegativeButton("Cancel", null)
        .create()
        .show()
}
    private fun showEditWaypointPriceDialog(waypointDetail: WaypointDetail) {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 30, 30, 30)
        }

        val priceInput = EditText(this).apply {
            hint = "Enter stop price"
            setText(
                if (waypointDetail.price != null && waypointDetail.price!! > 0.0)
                    waypointDetail.price.toString()
                else
                    ""
            )
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        dialogView.addView(priceInput)

        AlertDialog.Builder(this)
            .setTitle("Price to:${waypointDetail.customName ?: waypointDetail.googlePlaceName}")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val price = priceInput.text.toString().toDoubleOrNull() ?: 0.0
                waypointDetail.price = price
                clearPreviousRoute()
                drawRouteIfNeeded()
                Toast.makeText(this, "Waypoint price updated", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .create()
            .show()

    }

    @SuppressLint("SetTextI18n")
    private fun showEditWaypointNameDialog(waypointDetail: WaypointDetail) {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 30, 30, 30)
        }

        // Show original Google place name as reference
        val googlePlaceLabel = TextView(this).apply {
            text = "Original Location: ${waypointDetail.googlePlaceName ?: waypointDetail.index}"
            setTypeface(null, Typeface.ITALIC)
        }
        dialogView.addView(googlePlaceLabel)

        val customNameInput = EditText(this).apply {
            hint = "Enter custom location name"
            setText(waypointDetail.customName ?: "")
        }
        dialogView.addView(customNameInput)

        AlertDialog.Builder(this)
            .setTitle("Edit Waypoint Name #${waypointDetail.index}")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val customName = customNameInput.text.toString().trim()

                // Update waypoint detail
                waypointDetail.customName = customName.ifEmpty { null }

                // Update marker title and snippet
                waypointDetail.marker?.let { marker ->
                    val title = customName.ifEmpty {
                        waypointDetail.googlePlaceName ?: "Waypoint #${waypointDetail.index}"
                    }
                    marker.title = title

                    // Combine custom info if available
                    val snippetParts = mutableListOf<String>()
                    waypointDetail.customName?.let { snippetParts.add("Custom: $it") }
                    waypointDetail.price?.let { snippetParts.add("Price: $$it") }

                    marker.snippet = snippetParts.joinToString(" | ")
                    marker.showInfoWindow()
                }

                Toast.makeText(this, "Waypoint name updated", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .create()
            .show()
    }

    private fun showReorderWaypointDialog(currentWaypoint: WaypointDetail) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Reorder Waypoint")
            .setMessage("Current waypoint is at index ${currentWaypoint.index}")

        // Dynamically create input for new index
        val input = EditText(this).apply {
            hint = "Enter new index (0-${waypointDetails.size - 1})"
            inputType = InputType.TYPE_CLASS_NUMBER
        }

        dialog.setView(input)
            .setPositiveButton("Reorder") { _, _ ->
                val newIndexStr = input.text.toString()
                if (newIndexStr.isNotEmpty()) {
                    val newIndex = newIndexStr.toIntOrNull()
                    if (newIndex != null && newIndex in 0 until waypointDetails.size) {
                        reorderWaypoint(currentWaypoint, newIndex)
                    } else {
                        Toast.makeText(this, "Invalid index", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
            .show()
    }

    private fun reorderWaypoint(waypointToMove: WaypointDetail, newIndex: Int) {
        // Remove the waypoint from its current position
        waypointDetails.remove(waypointToMove)
        waypoints.remove(waypointToMove.latLng)
        waypointMarkers.remove(waypointToMove.marker)
        waypointToMove.marker?.remove()

        // Insert the waypoint at the new index
        waypointDetails.add(newIndex, waypointToMove)
        waypoints.add(newIndex, waypointToMove.latLng)

        // Reassign indices and redraw markers
        redrawWaypointMarkers()

        // Redraw route with new waypoint order
        drawRouteIfNeeded()

        Toast.makeText(this, "Waypoint reordered", Toast.LENGTH_SHORT).show()
    }

    private fun redrawWaypointMarkers() {
        // Remove existing waypoint markers
        waypointMarkers.forEach { it?.remove() }
        waypointMarkers.clear()

        // Redraw markers with updated indices
        waypointDetails.forEachIndexed { index, waypointDetail ->
            val marker = googleMap.addMarker(
                MarkerOptions()
                    .position(waypointDetail.latLng)
                    .title("Waypoint #$index")
                    .draggable(true)
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
            )
            waypointDetail.marker = marker
            waypointDetail.index = index
            waypointMarkers.add(marker)
        }
    }

    private fun removeWaypoint(waypointDetail: WaypointDetail) {
        // Remove the marker from the map
        waypointDetail.marker?.remove()

        // Remove from the lists
        waypointDetails.remove(waypointDetail)
        waypoints.remove(waypointDetail.latLng)
        waypointMarkers.remove(waypointDetail.marker)

        // Reassign indices and redraw markers
        redrawWaypointMarkers()

        // Redraw route without the removed waypoint
        drawRouteIfNeeded()

        Toast.makeText(this, "Waypoint removed", Toast.LENGTH_SHORT).show()
    }


    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        googleMap.uiSettings.isZoomControlsEnabled = true
        googleMap.uiSettings.isMyLocationButtonEnabled = false
        googleMap.mapType = GoogleMap.MAP_TYPE_NORMAL

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            googleMap.isMyLocationEnabled = true
        }

        // Add marker drag listener for origin, destination, and waypoints
        googleMap.setOnMarkerDragListener(object : GoogleMap.OnMarkerDragListener {
            override fun onMarkerDragStart(draggedMarker: Marker) {
                // Optional: Pre-drag handling
            }

            override fun onMarkerDrag(draggedMarker: Marker) {
                // Optional: During drag handling
            }

            override fun onMarkerDragEnd(draggedMarker: Marker) {
                val newPosition = draggedMarker.position

                // Handle origin marker drag
                if (draggedMarker == originMarker) {
                    originLatLng = newPosition
                    clearPreviousRoute()
                    drawRouteIfNeeded()
                }

                // Handle destination marker drag
                if (draggedMarker == destinationMarker) {
                    destinationLatLng = newPosition
                    clearPreviousRoute()
                    drawRouteIfNeeded()
                }

                // Handle waypoint marker drag
                if (waypointMarkers.contains(draggedMarker)) {
                    val index = waypointMarkers.indexOf(draggedMarker)

                    // Update the specific waypoint detail
                    waypointDetails[index].latLng = newPosition
                    waypoints[index] = newPosition

                    clearPreviousRoute()
                    drawRouteIfNeeded()
                }
            }
        })

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                val userLatLng = LatLng(location.latitude, location.longitude)
                googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(userLatLng, 15f))
            }
        }
    }

    private fun setupAutoComplete() {
        // Ensure dbLocations is populated before setting up autocomplete
        if (useDbLocations) {
            try {
                // Load locations from the database
                loadDatabaseLocations {
                    Log.d(
                        "CreateRouteActivity",
                        "Database locations loaded: ${dbLocations.size} items"
                    )
                }

                // Only after loading locations, set up the autocomplete
                setupAutoCompleteInput(originInput) { latLng, placeName, placeId ->
                    originLatLng = latLng
                    originPlaceId = placeId
                    updateMarker(originMarker, latLng, "Origin", placeName)
                    drawRouteIfNeeded()
                }

                setupAutoCompleteInput(destinationInput) { latLng, placeName, placeId ->
                    destinationLatLng = latLng
                    destinationPlaceId = placeId
                    updateMarker(destinationMarker, latLng, "Destination", placeName)
                    drawRouteIfNeeded()
                }
            } catch (e: Exception) {
                Log.e("CreateRouteActivity", "Error fetching locations: ${e.message}")
                Toast.makeText(
                    this@CreateRouteActivity,
                    "Error loading locations",
                    Toast.LENGTH_SHORT
                ).show()

                // Fall back to API-based autocomplete if database loading fails
                useDbLocations = false
                setupAutoCompleteInputs()
            }

        } else {
            // If useDbLocations is false, just use the API directly
            setupAutoCompleteInputs()
        }
    }

    private fun setupAutoCompleteInputs() {
        setupAutoCompleteInput(originInput) { latLng, placeName, placeId ->
            originLatLng = latLng
            originPlaceId = placeId
            updateMarker(originMarker, latLng, "Origin", placeName)
            drawRouteIfNeeded()
        }

        setupAutoCompleteInput(destinationInput) { latLng, placeName, placeId ->
            destinationLatLng = latLng
            destinationPlaceId = placeId
            updateMarker(destinationMarker, latLng, "Destination", placeName)
            drawRouteIfNeeded()
        }
    }

    private fun setupAutoCompleteInput(
        inputField: AutoCompleteTextView,
        onPlaceSelected: (LatLng, String?, String?) -> Unit
    ) {
        inputField.setAdapter(null)
        inputField.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {}
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (s != null && s.length > 1) {
                    // Check the switch state to determine data source
                    if (useDbLocations && dbLocations.isNotEmpty()) {
                        // Filter database locations based on input text - ensure proper type handling
                        val filteredLocations = dbLocations.filter { location ->
                            val googleName = location.googlePlaceName
                            val customName = location.customName
                            (googleName?.contains(s.toString(), ignoreCase = true) == true) ||
                                    (customName?.contains(s.toString(), ignoreCase = true) == true)
                        }

                        // Create adapter with filtered database locations
                        val adapter = ArrayAdapter(
                            this@CreateRouteActivity,
                            android.R.layout.simple_dropdown_item_1line,
                            filteredLocations.mapNotNull { it.googlePlaceName ?: it.customName }
                        )
                        inputField.setAdapter(adapter)
                        adapter.notifyDataSetChanged()
                    } else {
                        // Use Places API for suggestions
                        fetchAutoCompleteSuggestions(s.toString()) { suggestions ->
                            val adapter = ArrayAdapter(
                                this@CreateRouteActivity,
                                android.R.layout.simple_dropdown_item_1line,
                                suggestions.map { it.first }
                            )
                            inputField.setAdapter(adapter)
                            adapter.notifyDataSetChanged()
                        }
                    }
                }
            }
        })

        // Rest of your item click listener logic remains the same
        inputField.setOnItemClickListener { _, _, position, _ ->
            val placeName = inputField.adapter.getItem(position) as String
            // Find the corresponding place details
            if (useDbLocations && dbLocations.isNotEmpty()) {
                Log.d("CreateRouteActivity", "setupAutoCompleteInput: $dbLocations")
                val selectedPlace = dbLocations.find {
                    it.googlePlaceName == placeName || it.customName == placeName
                }
                if (selectedPlace != null) {
                    getLatLangDb(
                        selectedPlace.placeId ?: "",
                        dbLocations,
                    ) { latLng ->
                        onPlaceSelected(latLng, placeName, selectedPlace.placeId)
                    }
                }
            } else {
                fetchAutoCompleteSuggestions(placeName) { suggestions ->
                    val selectedPlace = suggestions.find { it.first == placeName }
                    if (selectedPlace != null) {
                        getLatLngFromPlace(selectedPlace.second) { latLng ->
                            onPlaceSelected(latLng, placeName, selectedPlace.second)
                        }
                    }
                }
            }
        }
    }

    private fun getLatLangDb(
        myPlace: String,
        dblocations: List<LocationDataDto>,
        callback: (LatLng) -> Unit
    ) {
        Log.d("CreateRouteActivity", "getLatLangDb: $myPlace")
        val selectedPlace = dblocations.find { it.placeId == myPlace }
        if (selectedPlace != null) {
            val latLng = LatLng(selectedPlace.latitude, selectedPlace.longitude)
            callback(latLng)
        } else {
            Toast.makeText(this, "Location not found in database", Toast.LENGTH_SHORT).show()
        }


    }


    private fun fetchAutoCompleteSuggestions(
        query: String,
        callback: (List<Pair<String, String>>) -> Unit
    ) {
        val request = FindAutocompletePredictionsRequest.builder()
            .setCountries("RW")  // Set country filter if needed
            .setSessionToken(token)  // Use session token for the session
            .setQuery(query)  // Set the query to search for
            .build()

        placesClient.findAutocompletePredictions(request)
            .addOnSuccessListener { response ->
                // Extract the predicted places from the response
                val suggestions = mutableListOf<Pair<String, String>>()

                for (prediction in response.autocompletePredictions) {
                    val placeName = prediction.getPrimaryText(null).toString()
                    val placeAddress = prediction.getSecondaryText(null).toString()
                    val placeId = prediction.placeId

                    // Combine primary and secondary text for display, store placeId
                    suggestions.add(Pair("$placeName, $placeAddress", placeId))
                }

                callback(suggestions)  // Return the suggestions to the callback
            }
            .addOnFailureListener { exception ->
                Toast.makeText(
                    this,
                    "Error fetching places: ${exception.message}",
                    Toast.LENGTH_SHORT
                ).show()
                callback(emptyList())
            }
    }

    private fun getLatLngFromPlace(placeId: String, callback: (LatLng) -> Unit) {
        // Use Places API to get more comprehensive place details
        val placeFields = listOf(
            Place.Field.ID,
            Place.Field.DISPLAY_NAME,
            Place.Field.LOCATION,
            Place.Field.FORMATTED_ADDRESS,
            Place.Field.PLUS_CODE
        )

        val request = FetchPlaceRequest.newInstance(placeId, placeFields)

        placesClient.fetchPlace(request)
            .addOnSuccessListener { response ->
                val place = response.place

                // Directly extract LatLng from the place
                val latLng = place.location

                if (latLng != null) {
                    callback(latLng)
                } else {
                    // Fallback to geocoder if no LatLng found
                    try {
                        val geocoder = Geocoder(this)
                        val addresses = geocoder.getFromLocationName(place.name ?: placeId, 1)

                        if (!addresses.isNullOrEmpty()) {
                            val location = addresses[0]
                            val fallbackLatLng = LatLng(location.latitude, location.longitude)
                            callback(fallbackLatLng)
                        } else {
                            Toast.makeText(
                                this,
                                "Could not find location coordinates",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(
                            this,
                            "Error finding location: ${e.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .addOnFailureListener {
                // Fallback to geocoder if Places API request fails
                try {
                    val geocoder = Geocoder(this)
                    val addresses = geocoder.getFromLocationName(placeId, 1)?.toList()

                    if (!addresses.isNullOrEmpty()) {
                        val location = addresses[0]
                        val fallbackLatLng = LatLng(location.latitude, location.longitude)
                        callback(fallbackLatLng)
                    } else {
                        Toast.makeText(
                            this,
                            "Could not find location: $placeId",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(
                        this,
                        "Error finding location: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
    }

    private fun updateMarker(
        marker: Marker?,
        latLng: LatLng,
        title: String,
        googlePlaceName: String? = null
    ) {
        // Remove existing marker if present
        marker?.remove()

        // Store Google place name based on title
        when (title) {
            "Origin" -> originGooglePlaceName = googlePlaceName
            "Destination" -> destinationGooglePlaceName = googlePlaceName
        }

        // Create a new draggable marker
        val newMarker = googleMap.addMarker(
            MarkerOptions()
                .position(latLng)
                .title(googlePlaceName ?: title)
                .draggable(true)
                .icon(
                    when (title) {
                        "Origin" -> BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)
                        "Destination" -> BitmapDescriptorFactory.defaultMarker(
                            BitmapDescriptorFactory.HUE_RED
                        )

                        else -> BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)
                    }
                )
        )

        // Set up marker click listener for origin and destination
        newMarker?.let {
            googleMap.setOnMarkerClickListener { clickedMarker ->
                if (clickedMarker == originMarker || clickedMarker == destinationMarker) {
                    showLocationNameDialog(
                        if (clickedMarker == originMarker) "Origin" else "Destination",
                        clickedMarker
                    )
                    true
                } else {
                    // Existing waypoint marker click behavior
                    val selectedWaypointDetail = waypointDetails.find { it.marker == clickedMarker }
                    selectedWaypointDetail?.let {
                        showWaypointOptionsDialog(it)
                    }
                    true
                }
            }
        }

        // Update respective marker reference and location
        if (title == "Origin") {
            originMarker = newMarker
            originLatLng = latLng
        } else if (title == "Destination") {
            destinationMarker = newMarker
            destinationLatLng = latLng
        }

        // Move camera to the new marker
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 12f))
    }

    // Updated dialog method to use separate Google place names
    @SuppressLint("SetTextI18n")
    private fun showLocationNameDialog(locationType: String, marker: Marker) {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 30, 30, 30)
        }

        // Retrieve correct Google place name based on location type
        val googlePlaceName = when (locationType) {
            "Origin" -> originGooglePlaceName
            "Destination" -> destinationGooglePlaceName
            else -> null
        }

        // Show original Google place name as reference
        val googlePlaceLabel = TextView(this).apply {
            text = "Original Location: ${googlePlaceName ?: "Unknown"}"
            setTypeface(null, Typeface.ITALIC)
        }
        dialogView.addView(googlePlaceLabel)

        val customNameInput = EditText(this).apply {
            hint = "Enter custom $locationType name"

            // Retrieve existing custom name if any
            val existingCustomName = when (locationType) {
                "Origin" -> originCustomName
                "Destination" -> destinationCustomName
                else -> null
            }
            setText(existingCustomName ?: "")
        }
        dialogView.addView(customNameInput)

        AlertDialog.Builder(this)
            .setTitle("Edit $locationType Name")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val customName = customNameInput.text.toString().trim()

                // Update custom name based on location type
                when (locationType) {
                    "Origin" -> {
                        originCustomName = customName.ifEmpty { null }
                        updateOriginMarkerDisplay(marker, originGooglePlaceName, originCustomName)
                    }

                    "Destination" -> {
                        destinationCustomName = customName.ifEmpty { null }
                        updateDestinationMarkerDisplay(
                            marker,
                            destinationGooglePlaceName,
                            destinationCustomName
                        )
                    }
                }

                Toast.makeText(this, "$locationType name updated", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("add to locations") { _, _ ->
                val (custName, gPlaceName, placeId) = when (locationType) {
                    "Origin" -> Triple(originCustomName, originGooglePlaceName, originPlaceId)
                    else -> Triple(
                        destinationCustomName,
                        destinationGooglePlaceName,
                        destinationPlaceId
                    )
                }

                val locationData = locationService.createLocation(
                    marker.position,
                    gPlaceName,
                    custName,
                    placeId
                )
                CoroutineScope(Dispatchers.Main).launch {
                    try {
                        locationService.saveLocation(locationData)
                        Log.d("CreateRouteActivity", "Location saved successfully")
                    } catch (e: Exception) {
                        Log.e("CreateRouteActivity", "Error saving location: ${e.message}")
                        Toast.makeText(
                            this@CreateRouteActivity,
                            "Error saving location",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .create()
            .show()
    }

    private fun updateOriginMarkerDisplay(
        marker: Marker,
        googlePlaceName: String?,
        customName: String?
    ) {
        marker.title = customName ?: googlePlaceName ?: "Origin"
        marker.snippet = customName?.let { "Custom: $it" }
        marker.showInfoWindow()
    }

    private fun updateDestinationMarkerDisplay(
        marker: Marker,
        googlePlaceName: String?,
        customName: String?
    ) {
        marker.title = customName ?: googlePlaceName ?: "Destination"
        marker.snippet = customName?.let { "Custom: $it" }
        marker.showInfoWindow()
    }


    private fun drawRouteIfNeeded() {
        // Ensure we only draw route if origin and destination are set
        if (originLatLng != null && destinationLatLng != null) {
            // Use the current state of waypoints, which should reflect dragged positions
            val currentWaypoints = waypointDetails.map { it.latLng }
            drawRoute(originLatLng!!, destinationLatLng!!, currentWaypoints)
        }
    }


    private fun drawRoute(
        origin: LatLng,
        destination: LatLng,
        waypoints: List<LatLng> = emptyList()
    ) {
        // Clear previous polyline if it exists
        polyline?.remove()

        // Find the TextView to display route info (add this to your layout)

        val url = "https://routes.googleapis.com/directions/v2:computeRoutes"
        val apiKey = apiKey // Ensure your API key is set properly

        // Create the JSON body for the POST request with waypoints
        val jsonBody = MapHelpers().createJsonBody(origin, destination, waypoints, apiKey)

        Log.d("CreateRouteActivity", "JSON Body: $jsonBody")

        // Start a new thread for network operations
        Thread {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("X-Goog-Api-Key", apiKey)
                connection.setRequestProperty(
                    "X-Goog-FieldMask",
                    "routes.duration,routes.distanceMeters,routes.polyline.encodedPolyline,routes.optimized_intermediate_waypoint_index"
                )
                connection.doOutput = true

                // Send the JSON body
                val outputStream = connection.outputStream
                outputStream.write(jsonBody.toByteArray())
                outputStream.flush()
                outputStream.close()

                // Get the response
                val responseCode = connection.responseCode
                val inputStream = if (responseCode == HttpURLConnection.HTTP_OK) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
                val reader = InputStreamReader(inputStream)
                val response = reader.readText()
                Log.d("CreateRouteActivity", "Response Code: $responseCode")
                Log.d("CreateRouteActivity", "Response: $response")
                reader.close()

                // Parse the route data from the response
                val routeInfo = parseRouteInfo(response)
                routeDistance = routeInfo.distanceKm
                routeDuration = routeInfo.durationSeconds
                val path = MapHelpers().parseRoute(response)

                runOnUiThread {
                    // Draw the new route
                    polyline = googleMap.addPolyline(
                        PolylineOptions().addAll(path).width(10f).color(Color.BLUE)
                    )

                    // Update route info TextView
                    updateRouteInfoDisplay(routeInfo)
                }
            } catch (e: Exception) {
                Log.e("CreateRouteActivity", "Error drawing route: ${e.message}")
                runOnUiThread {
                    Toast.makeText(
                        applicationContext,
                        "Failed to get route: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }.start()
    }

    // New method to parse route information
    private fun parseRouteInfo(response: String): RouteInfo {
        try {
            val jsonObject = JSONObject(response)
            val routesArray = jsonObject.getJSONArray("routes")

            if (routesArray.length() > 0) {
                val firstRoute = routesArray.getJSONObject(0)

                val distanceMeters = firstRoute.getInt("distanceMeters")
                val durationString = firstRoute.getString("duration")


                return RouteInfo(
                    distanceKm = distanceMeters / 1000.0,
                    durationSeconds = durationString.replace("s", "").toInt()
                )
            }
        } catch (e: Exception) {
            Log.e("CreateRouteActivity", "Error parsing route info: ${e.message}")
        }

        // Return default/empty route info if parsing fails
        return RouteInfo(0.0, 0)
    }

    // Data class to hold route information
    data class RouteInfo(
        val distanceKm: Double,
        val durationSeconds: Int
    )

    // Method to update route info display
    @SuppressLint("DefaultLocale")
    private fun updateRouteInfoDisplay(routeInfo: RouteInfo) {
        val routeInfoTextView: TextView = findViewById(R.id.route_info_text_view)

        // Format distance to 2 decimal places
        val formattedDistance = String.format("%.2f", routeInfo.distanceKm)

        // Convert duration to more readable format
        val (hours, minutes, seconds) = formatDuration(routeInfo.durationSeconds)

        // Construct the display text
        val displayText = buildString {
            append("Distance: $formattedDistance km")
            if (hours > 0) append(" | Duration: $hours h ")
            if (minutes > 0) append("$minutes m ")
            if (seconds > 0) append("$seconds s")
        }

        routeInfoTextView.text = displayText
        routeInfoTextView.visibility = View.VISIBLE
    }

    // Helper function to break down seconds into hours, minutes, seconds
    private fun formatDuration(totalSeconds: Int): Triple<Int, Int, Int> {
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return Triple(hours, minutes, seconds)
    }
}
