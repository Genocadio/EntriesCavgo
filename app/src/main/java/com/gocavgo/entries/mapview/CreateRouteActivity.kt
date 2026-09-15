package com.gocavgo.entries.mapview

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.gocavgo.entries.BuildConfig
import com.gocavgo.entries.R
import com.gocavgo.entries.dataclass.LocationDataDto
import com.gocavgo.entries.dataclass.WaypointDataDto
import com.gocavgo.entries.service.LocationIqClient
import com.gocavgo.entries.service.LocationService
import com.gocavgo.entries.service.NavigationRouteClient
import com.gocavgo.entries.service.RouteService
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.Polyline
import org.maplibre.android.annotations.PolylineOptions
import android.widget.ArrayAdapter
import android.widget.Switch
import javax.inject.Inject

@AndroidEntryPoint
class CreateRouteActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private var map: MapLibreMap? = null
    private lateinit var originInput: AutoCompleteTextView
    private lateinit var routePriceInput: EditText
    private lateinit var destinationInput: AutoCompleteTextView
    private lateinit var saveButton: Button
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private val locationIqClient: LocationIqClient by lazy {
        val token = try { BuildConfig::class.java.getField("LOCATIONIQ_API_KEY").get(null) as String } catch (e: Exception) { "" }
        LocationIqClient(token)
    }
    private val navRouteClient: NavigationRouteClient by lazy {
        val baseUrl = try { BuildConfig::class.java.getField("NAVIGATION_BASE_URL").get(null) as String } catch (e: Exception) { "https://api.gocavgo.com/maps" }
        NavigationRouteClient(baseUrl)
    }

    private var originCustomName: String? = null
    private var destinationCustomName: String? = null
    private var originPlaceName: String? = null
    private var destinationPlaceName: String? = null
    private var routeDistance: Double = 0.0
    private var routeDuration: Int = 0

    @Inject lateinit var locationService: LocationService
    @Inject lateinit var routeService: RouteService
    private var dbLocations = mutableListOf<LocationDataDto>()

    private var originLatLng: LatLng? = null
    private var destinationLatLng: LatLng? = null
    private var originMarker: Marker? = null
    private var destinationMarker: Marker? = null
    private var routePolyline: Polyline? = null

    private lateinit var addWaypointButton: Button
    private val waypoints = mutableListOf<LatLng>()
    private val waypointMarkers = mutableListOf<Marker?>()
    private val waypointDetails = mutableListOf<WaypointDetail>()
    private var routePrice: Double = 0.0
    private var originPlaceId: String? = null
    private var destinationPlaceId: String? = null
    private var useDbLocations = false
    @SuppressLint("UseSwitchCompatOrMaterialCode") private lateinit var useDbLocationsSwitch: Switch

    data class WaypointDetail(
        var marker: Marker?, var latLng: LatLng, var googlePlaceName: String? = null,
        var customName: String? = null, var price: Double? = null, var index: Int = -1, var placeId: String? = null
    )

    private val ARC_GIS_SATELLITE_STYLE = "asset://satellite_style.json"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_create_route)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        useDbLocationsSwitch = findViewById(R.id.use_db_locations_switch)
        mapView = findViewById(R.id.map_view)
        originInput = findViewById(R.id.origin_input)
        routePriceInput = findViewById(R.id.price_input)
        destinationInput = findViewById(R.id.destination_input)
        saveButton = findViewById(R.id.save_route_button)
        addWaypointButton = findViewById(R.id.add_waypoint_button)

        mapView.onCreate(null)
        mapView.getMapAsync { mapInstance ->
            map = mapInstance
            mapInstance.setStyle(Style.Builder().fromUri(ARC_GIS_SATELLITE_STYLE)) { 
                mapInstance.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(-1.9403, 29.8739))
                    .zoom(15.0)
                    .build()
                setupMapClickListener(mapInstance)
                fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                    if (location != null) mapInstance.cameraPosition = CameraPosition.Builder()
                        .target(LatLng(location.latitude, location.longitude))
                        .build()
                }
            }
        }

        useDbLocationsSwitch.setOnCheckedChangeListener { _, isChecked ->
            useDbLocations = isChecked
            if (useDbLocations) loadDatabaseLocations { setupAutoComplete() } else setupAutoComplete()
        }

        saveButton.setOnClickListener { saveCurrentRoute(this) }
        addWaypointButton.setOnClickListener { showWaypointInputDialog() }
        setupRoutePriceInput()

        if (useDbLocations) loadDatabaseLocations { setupAutoComplete() } else setupAutoComplete()
    }

    private fun setupMapClickListener(mapInstance: MapLibreMap) {
        mapInstance.addOnMapClickListener { latLng ->
            false
        }
    }

    @SuppressLint("SetTextI18n")
    private fun setupRoutePriceInput() {
        routePriceInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                routePrice = s?.toString()?.toDoubleOrNull() ?: 0.0
                if (routePrice < 0) routePriceInput.error = "Price cannot be negative"
            }
        })
    }

    private fun loadDatabaseLocations(callback: () -> Unit) {
        lifecycleScope.launch {
            try { dbLocations = locationService.getAllLocations().toMutableList(); callback() }
            catch (e: Exception) { Toast.makeText(this@CreateRouteActivity, "Error loading locations", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun setupAutoComplete() {
        setupAutoCompleteInput(originInput) { latLng, name, placeId ->
            originLatLng = latLng; originPlaceId = placeId; updateMarker(originMarker, latLng, "Origin", name); drawRouteIfNeeded()
        }
        setupAutoCompleteInput(destinationInput) { latLng, name, placeId ->
            destinationLatLng = latLng; destinationPlaceId = placeId; updateMarker(destinationMarker, latLng, "Destination", name); drawRouteIfNeeded()
        }
    }

    private fun setupAutoCompleteInput(inputField: AutoCompleteTextView, onPlaceSelected: (LatLng, String?, String?) -> Unit) {
        inputField.setAdapter(null)
        inputField.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {}
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (s != null && s.length > 1) {
                    if (useDbLocations && dbLocations.isNotEmpty()) {
                        val filtered = dbLocations.filter { it.googlePlaceName?.contains(s, true) == true || it.customName?.contains(s, true) == true }
                        val adapter = ArrayAdapter(this@CreateRouteActivity, android.R.layout.simple_dropdown_item_1line, filtered.mapNotNull { it.googlePlaceName ?: it.customName })
                        inputField.setAdapter(adapter); adapter.notifyDataSetChanged()
                    } else {
                        lifecycleScope.launch {
                            val results = locationIqClient.autocomplete(s.toString(), limit = 10, countryCodes = "rw")
                            val titles = results.map { it.placeName }.filter { it.isNotEmpty() }
                            val adapter = ArrayAdapter(this@CreateRouteActivity, android.R.layout.simple_dropdown_item_1line, titles)
                            inputField.setAdapter(adapter); adapter.notifyDataSetChanged()
                        }
                    }
                }
            }
        })
        inputField.setOnItemClickListener { _, _, position, _ ->
            val selectedName = inputField.adapter.getItem(position) as String
            if (useDbLocations && dbLocations.isNotEmpty()) {
                val selected = dbLocations.find { it.googlePlaceName == selectedName || it.customName == selectedName }
                if (selected != null) onPlaceSelected(LatLng(selected.latitude, selected.longitude), selectedName, selected.placeId)
            } else {
                lifecycleScope.launch {
                    val results = locationIqClient.search(selectedName, limit = 5, countryCodes = "rw")
                    val match = results.find { it.placeName == selectedName || it.displayName.contains(selectedName, true) }
                    if (match != null) onPlaceSelected(LatLng(match.latitude, match.longitude), selectedName, match.placeId)
                    else if (results.isNotEmpty()) onPlaceSelected(LatLng(results[0].latitude, results[0].longitude), selectedName, results[0].placeId)
                }
            }
        }
    }

    private fun updateMarker(marker: Marker?, latLng: LatLng, title: String, placeName: String? = null) {
        map?.let { mapInstance ->
            marker?.let { mapInstance.removeMarker(it) }
            when (title) { "Origin" -> originPlaceName = placeName; "Destination" -> destinationPlaceName = placeName }
            val newMarker = mapInstance.addMarker(
                MarkerOptions()
                    .position(latLng)
                    .title(placeName ?: title)
                    .snippet(title)
            )
            if (title == "Origin") { originMarker = newMarker; originLatLng = latLng }
            else if (title == "Destination") { destinationMarker = newMarker; destinationLatLng = latLng }
            mapInstance.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 12.0))
        }
    }

    private fun updateWaypointMarker(latLng: LatLng, placeName: String? = null, placeId: String? = null) {
        val idx = waypointDetails.size
        val newMarker = map?.addMarker(
            MarkerOptions()
                .position(latLng)
                .title(placeName ?: "Waypoint #$idx")
                .snippet("waypoint")
        )
        waypointDetails.add(WaypointDetail(newMarker, latLng, placeName, index = idx, placeId = placeId))
        waypoints.add(latLng); waypointMarkers.add(newMarker)
    }

    private fun clearPreviousRoute() {
        routePolyline?.let { map?.removePolyline(it) }; routePolyline = null
    }

    private fun drawRouteIfNeeded() {
        if (originLatLng != null && destinationLatLng != null) {
            val allWaypoints = mutableListOf<Pair<Double, Double>>()
            allWaypoints.add(Pair(originLatLng!!.latitude, originLatLng!!.longitude))
            waypoints.forEach { allWaypoints.add(Pair(it.latitude, it.longitude)) }
            allWaypoints.add(Pair(destinationLatLng!!.latitude, destinationLatLng!!.longitude))

            lifecycleScope.launch {
                val result = navRouteClient.calculateRoute(allWaypoints)
                if (result?.route != null) {
                    val route = result.route!!
                    routeDistance = route.totalDistance / 1000.0
                    routeDuration = route.totalDuration.toInt()
                    val points = route.polyline.map { LatLng(it[0], it[1]) }
                    clearPreviousRoute()
                    routePolyline = map?.addPolyline(
                        PolylineOptions()
                            .addAll(points)
                            .color(Color.BLUE)
                            .width(10f)
                    )
                    map?.let { mapInstance ->
                        if (points.size >= 2) {
                            val builder = LatLngBounds.Builder()
                            points.forEach { builder.include(it) }
                            mapInstance.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 100))
                        }
                    }
                    updateRouteInfoDisplay()
                }
            }
        }
    }

    private fun updateRouteInfoDisplay() {
        val tv = findViewById<TextView>(R.id.route_info_text_view)
        val hours = routeDuration / 3600; val mins = (routeDuration % 3600) / 60; val secs = routeDuration % 60
        tv.text = buildString {
            append("Distance: ${String.format("%.2f", routeDistance)} km")
            if (hours > 0) append(" | Duration: ${hours}h ")
            if (mins > 0) append("${mins}m ")
            if (secs > 0) append("${secs}s")
        }
        tv.visibility = View.VISIBLE
    }

    private fun showWaypointInputDialog() {
        val input = AutoCompleteTextView(this).apply { hint = "Enter waypoint address" }
        val dialog = AlertDialog.Builder(this).setTitle("Add Waypoint").setView(input).setNegativeButton("Cancel", null).create()
        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {}
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (s != null && s.length > 2) {
                    lifecycleScope.launch {
                        val results = locationIqClient.autocomplete(s.toString(), limit = 5, countryCodes = "rw")
                        input.setAdapter(ArrayAdapter(this@CreateRouteActivity, android.R.layout.simple_dropdown_item_1line, results.map { it.placeName }))
                    }
                }
            }
        })
        input.setOnItemClickListener { _, _, position, _ ->
            val name = input.adapter.getItem(position) as String
            lifecycleScope.launch {
                val results = locationIqClient.search(name, limit = 3, countryCodes = "rw")
                val match = results.firstOrNull()
                if (match != null) {
                    val ll = LatLng(match.latitude, match.longitude)
                    updateWaypointMarker(ll, name, match.placeId)
                    map?.animateCamera(CameraUpdateFactory.newLatLngZoom(ll, 15.0))
                    dialog.dismiss(); drawRouteIfNeeded()
                }
            }
        }
        dialog.show()
    }

    private fun showWaypointOptionsDialog(detail: WaypointDetail) {
        val options = arrayOf("Edit Name", "Edit Price", "Remove", "Reorder", "Save Location")
        AlertDialog.Builder(this).setTitle("Waypoint #${detail.index}").setItems(options) { _, which ->
            when (which) {
                0 -> showEditWaypointNameDialog(detail)
                1 -> showEditWaypointPriceDialog(detail)
                2 -> removeWaypoint(detail)
                3 -> showReorderWaypointDialog(detail)
                4 -> {
                    lifecycleScope.launch {
                        val loc = locationService.createLocation(detail.latLng, detail.googlePlaceName, detail.customName, detail.placeId)
                        locationService.saveLocation(loc)
                        Toast.makeText(this@CreateRouteActivity, "Location saved!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }.setNegativeButton("Cancel", null).show()
    }

    private fun showEditWaypointPriceDialog(detail: WaypointDetail) {
        val et = EditText(this).apply { hint = "Enter price"; setText(detail.price?.toString() ?: ""); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        AlertDialog.Builder(this).setTitle("Price to ${detail.customName ?: detail.googlePlaceName}").setView(et)
            .setPositiveButton("Save") { _, _ -> detail.price = et.text.toString().toDoubleOrNull() ?: 0.0; clearPreviousRoute(); drawRouteIfNeeded() }
            .setNegativeButton("Cancel", null).show()
    }

    @SuppressLint("SetTextI18n")
    private fun showEditWaypointNameDialog(detail: WaypointDetail) {
        val et = EditText(this).apply { hint = "Custom name"; setText(detail.customName ?: "") }
        val label = TextView(this).apply { text = "Original: ${detail.googlePlaceName ?: detail.index}"; setTypeface(null, Typeface.ITALIC) }
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(30, 30, 30, 30); addView(label); addView(et) }
        AlertDialog.Builder(this).setTitle("Edit Waypoint #${detail.index}").setView(container)
            .setPositiveButton("Save") { _, _ ->
                detail.customName = et.text.toString().trim().ifEmpty { null }
                detail.marker?.title = detail.customName ?: detail.googlePlaceName ?: "Waypoint #${detail.index}"
                Toast.makeText(this, "Waypoint name updated", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showReorderWaypointDialog(detail: WaypointDetail) {
        val et = EditText(this).apply { hint = "New index (0-${waypointDetails.size - 1})"; inputType = InputType.TYPE_CLASS_NUMBER }
        AlertDialog.Builder(this).setTitle("Reorder Waypoint").setView(et)
            .setPositiveButton("Reorder") { _, _ ->
                val newIdx = et.text.toString().toIntOrNull()
                if (newIdx != null && newIdx in waypointDetails.indices) {
                    waypointDetails.remove(detail); waypoints.remove(detail.latLng); waypointMarkers.remove(detail.marker)
                    detail.marker?.let { map?.removeMarker(it) }
                    waypointDetails.add(newIdx, detail); waypoints.add(newIdx, detail.latLng)
                    redrawWaypointMarkers(); drawRouteIfNeeded()
                } else Toast.makeText(this, "Invalid index", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun removeWaypoint(detail: WaypointDetail) {
        detail.marker?.let { map?.removeMarker(it) }
        waypointDetails.remove(detail); waypoints.remove(detail.latLng); waypointMarkers.remove(detail.marker)
        redrawWaypointMarkers(); drawRouteIfNeeded()
    }

    private fun redrawWaypointMarkers() {
        waypointMarkers.forEach { it?.let { m -> map?.removeMarker(m) } }; waypointMarkers.clear()
        val mapInstance = map ?: return
        waypointDetails.forEachIndexed { idx, detail ->
            val newMarker = mapInstance.addMarker(
                MarkerOptions()
                    .position(detail.latLng)
                    .title("Waypoint #$idx")
                    .snippet("waypoint")
            )
            detail.marker = newMarker; detail.index = idx; waypointMarkers.add(newMarker)
        }
    }

    private fun showLocationNameDialog(locationType: String, marker: Marker) {
        val existingName = when (locationType) { "Origin" -> originCustomName; "Destination" -> destinationCustomName; else -> null }
        val placeName = when (locationType) { "Origin" -> originPlaceName; "Destination" -> destinationPlaceName; else -> null }
        val et = EditText(this).apply { hint = "Custom $locationType name"; setText(existingName ?: "") }
        val label = TextView(this).apply { text = "Original: ${placeName ?: "Unknown"}"; setTypeface(null, Typeface.ITALIC) }
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(30, 30, 30, 30); addView(label); addView(et) }
        AlertDialog.Builder(this).setTitle("Edit $locationType Name").setView(container)
            .setPositiveButton("Save") { _, _ ->
                val name = et.text.toString().trim().ifEmpty { null }
                when (locationType) { "Origin" -> originCustomName = name; "Destination" -> destinationCustomName = name }
                marker.title = name ?: placeName ?: locationType
                Toast.makeText(this, "$locationType name updated", Toast.LENGTH_SHORT).show()
            }.setNeutralButton("Add to Locations") { _, _ ->
                val ll = marker.position
                lifecycleScope.launch {
                    val loc = locationService.createLocation(ll, placeName, customName = et.text.toString().trim().ifEmpty { null }, placeId = when (locationType) { "Origin" -> originPlaceId; else -> destinationPlaceId })
                    locationService.saveLocation(loc)
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun verifyRouteData(context: android.content.Context): Boolean {
        if (originLatLng == null || destinationLatLng == null) { Toast.makeText(context, "Origin and Destination required!", Toast.LENGTH_SHORT).show(); return false }
        if (routePrice <= 0) { Toast.makeText(context, "Valid route price required!", Toast.LENGTH_SHORT).show(); return false }
        return true
    }

    private fun saveCurrentRoute(context: android.content.Context) {
        if (!verifyRouteData(context)) return
        val origin = LocationDataDto(latitude = originLatLng!!.latitude, longitude = originLatLng!!.longitude, googlePlaceName = originPlaceName, customName = originCustomName, placeId = originPlaceId)
        val dest = LocationDataDto(latitude = destinationLatLng!!.latitude, longitude = destinationLatLng!!.longitude, googlePlaceName = destinationPlaceName, customName = destinationCustomName, placeId = destinationPlaceId)
        val wpList = waypointDetails.mapIndexed { i, w -> WaypointDataDto(LocationDataDto(latitude = w.latLng.latitude, longitude = w.latLng.longitude, googlePlaceName = w.googlePlaceName, customName = w.customName, placeId = w.placeId), price = w.price ?: 0.0, order = i) }
        val routeData = routeService.createRouteData(origin, dest, wpList, routeDistance * 1000, routeDuration, "", routePrice)
        CoroutineScope(Dispatchers.Main).launch {
            when (val result = routeService.saveRoute(routeData)) {
                is RouteService.SaveResult.Success -> Toast.makeText(context, "Route saved!", Toast.LENGTH_SHORT).show()
                is RouteService.SaveResult.Error -> Toast.makeText(context, "Error: ${result.message}", Toast.LENGTH_SHORT).show()
                is RouteService.SaveResult.ExistingLocation -> Toast.makeText(context, "Location exists: ${result.id}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onPause() { mapView.onPause(); super.onPause() }
    override fun onResume() { mapView.onResume(); super.onResume() }
    override fun onDestroy() { mapView.onDestroy(); super.onDestroy() }
}
