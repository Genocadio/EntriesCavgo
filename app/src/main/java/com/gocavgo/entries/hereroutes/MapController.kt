package com.gocavgo.entries.hereroutes

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.VectorDrawable
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.gocavgo.entries.R
import com.google.android.gms.location.FusedLocationProviderClient
import com.here.sdk.core.*
import com.here.sdk.gestures.GestureState
import com.here.sdk.gestures.LongPressListener
import com.here.sdk.gestures.TapListener
import com.here.sdk.mapview.*
import java.io.ByteArrayOutputStream
import java.util.*
import kotlin.collections.ArrayList
import androidx.core.graphics.createBitmap

class MapController(
    private val context: Context,
    private val mapView: MapView,
    private val mapStyleFab: com.google.android.material.floatingactionbutton.FloatingActionButton,
    private val myLocationFab: com.google.android.material.floatingactionbutton.FloatingActionButton,
    private val locationClient: FusedLocationProviderClient,
    private val mapSchemes: List<MapScheme>,
    private val mapSchemeNames: List<String>
) {
    private val TAG = MapController::class.java.simpleName
    private val locationIndicatorList = arrayListOf<LocationIndicator>()
    private val searchMarkers = arrayListOf<MapMarker>()
    private var currentSchemeIndex = 0

    // Marker movement variables
    private var isMovingMarker = false
    private var markerToMove: MapMarker? = null
    private var moveStatusText: android.widget.TextView? = null

    // Callbacks
    var onLocationReceived: ((GeoCoordinates) -> Unit)? = null
    var onMarkerActionRequested: ((String, MapMarker) -> Unit)? = null
    var onMarkerMoved: ((MapMarker, GeoCoordinates) -> Unit)? = null

    // Cache for converted marker images to avoid repeated conversions
    private val markerImageCache = mutableMapOf<Int, MapImage>()

    init {
        setupMapStyleButton()
        setupMyLocationButton()
        setupGestureHandlers()
        createMoveStatusText()
    }

    private fun setupMapStyleButton() {
        currentSchemeIndex = getTimeBasedSchemeIndex()

        mapStyleFab.setOnClickListener {
            currentSchemeIndex = (currentSchemeIndex + 1) % mapSchemes.size
            changeMapScheme(mapSchemes[currentSchemeIndex])
            Toast.makeText(context, mapSchemeNames[currentSchemeIndex], Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupMyLocationButton() {
        myLocationFab.setOnClickListener {
            if (isMovingMarker) {
                cancelMarkerMove()
                return@setOnClickListener
            }

            val fineLocationPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
            val coarseLocationPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )

            if (fineLocationPermission == PackageManager.PERMISSION_GRANTED ||
                coarseLocationPermission == PackageManager.PERMISSION_GRANTED) {
                getLocation { coordinates ->
                    coordinates?.let {
                        onLocationReceived?.invoke(it)
                    }
                }
            } else {
                Toast.makeText(context, "Location permission is required", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupGestureHandlers() {
        mapView.gestures.tapListener = TapListener { touchPoint ->
            if (isMovingMarker) {
                return@TapListener
            }

            val geoCoordinates = mapView.viewToGeoCoordinates(touchPoint)
            Log.d(TAG, "Tap at: ${geoCoordinates?.latitude}, ${geoCoordinates?.longitude}")
            pickMapMarker(touchPoint)
        }

        mapView.gestures.longPressListener = LongPressListener { gestureState, touchPoint ->
            if (gestureState == GestureState.BEGIN) {
                val geoCoordinates = mapView.viewToGeoCoordinates(touchPoint)
                Log.d(TAG, "LongPress detected at: $geoCoordinates")

                if (isMovingMarker && markerToMove != null) {
                    geoCoordinates?.let { newCoordinates ->
                        moveMarkerToLocation(newCoordinates)
                    }
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun createMoveStatusText() {
        moveStatusText = android.widget.TextView(context).apply {
            text = "Long press on map to place marker at new location"
            textSize = 16f
            setTextColor(ContextCompat.getColor(context, android.R.color.white))
            setBackgroundColor(ContextCompat.getColor(context, android.R.color.holo_blue_dark))
            setPadding(20, 20, 20, 20)
            visibility = android.view.View.GONE
        }

        if (context is android.app.Activity) {
            val mainLayout = context.findViewById<androidx.constraintlayout.widget.ConstraintLayout>(
                R.id.manageroutes)
            mainLayout?.addView(moveStatusText)

            val layoutParams = moveStatusText?.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            layoutParams?.apply {
                topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                topMargin = 100
            }
            moveStatusText?.layoutParams = layoutParams
        }
    }

    /**
     * Convert vector drawable to MapImage with proper scaling for different screen densities
     */
    private fun createMapImageFromVectorDrawable(drawableRes: Int, sizeInDp: Int = 32): MapImage {
        // Check cache first
        markerImageCache[drawableRes]?.let { return it }

        try {
            // Get the vector drawable
            val vectorDrawable = ContextCompat.getDrawable(context, drawableRes) as? VectorDrawable
                ?: throw IllegalArgumentException("Resource $drawableRes is not a VectorDrawable")

            // Calculate size in pixels based on screen density
            val density = context.resources.displayMetrics.density
            val sizeInPixels = (sizeInDp * density).toInt()

            // Create a bitmap from the vector drawable
            val bitmap = createBitmap(sizeInPixels, sizeInPixels)

            val canvas = Canvas(bitmap)
            vectorDrawable.setBounds(0, 0, sizeInPixels, sizeInPixels)
            vectorDrawable.draw(canvas)

            // Convert bitmap to byte array
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            val byteArray = stream.toByteArray()

            // Create MapImage from byte array
            val mapImage = MapImage(byteArray, ImageFormat.PNG)

            // Cache the result
            markerImageCache[drawableRes] = mapImage

            Log.d(TAG, "Created MapImage from vector drawable $drawableRes with size ${sizeInPixels}px (${sizeInDp}dp)")
            return mapImage

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create MapImage from vector drawable $drawableRes: ${e.message}")
            // Fallback to system drawable
            return MapImageFactory.fromResource(context.resources, android.R.drawable.ic_menu_mylocation)
        }
    }

    fun loadMapScene(geoCoordinates: GeoCoordinates) {
        val mapScheme = getTimeBasedMapScheme()
        mapView.mapScene.loadScene(mapScheme) { mapError ->
            if (mapError == null) {
                val distanceInMeters = (1000 * 10).toDouble()
                val mapMeasureZoom = MapMeasure(MapMeasure.Kind.DISTANCE_IN_METERS, distanceInMeters)
                mapView.camera.lookAt(geoCoordinates, mapMeasureZoom)
                showLocationIndicatorPedestrian(geoCoordinates)
            } else {
                Log.d(TAG, "Loading map failed: mapError: " + mapError.name)
            }
        }
    }

    private fun changeMapScheme(mapScheme: MapScheme) {
        mapView.mapScene.loadScene(mapScheme) { mapError ->
            if (mapError == null) {
                Log.d(TAG, "Map scheme changed successfully to: ${mapSchemeNames[currentSchemeIndex]}")
            } else {
                Log.d(TAG, "Failed to change map scheme: " + mapError.name)
            }
        }
    }

    private fun getTimeBasedSchemeIndex(): Int {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        return if (hour >= 18 || hour < 6) 1 else 0 // Night or Day
    }

    private fun getTimeBasedMapScheme(): MapScheme {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        return if (hour >= 18 || hour < 6) {
            MapScheme.NORMAL_NIGHT
        } else {
            MapScheme.NORMAL_DAY
        }
    }

    /**
     * Enhanced addSearchMarker method with marker type support and vector drawable conversion
     * @param coordinates Location coordinates
     * @param dbPlace DbPlace object containing place information
     * @param markerType Type of marker: "origin", "destination", "waypoint"
     */
    fun addSearchMarker(coordinates: GeoCoordinates, dbPlace: DbPlace, markerType: String = "waypoint") {
        try {
            // Select appropriate marker image based on type
            val markerDrawableRes = when (markerType.lowercase()) {
                "origin" -> R.drawable.ic_map_marker_red
                "destination" -> R.drawable.ic_flag_marker_red
                "waypoint" -> R.drawable.ic_map_marker_blue
                else -> R.drawable.ic_map_marker_blue // Default to blue for unknown types
            }

            // Create MapImage from vector drawable with proper scaling
            val mapImage = createMapImageFromVectorDrawable(markerDrawableRes, 32) // 32dp size
            val mapMarker = MapMarker(coordinates, mapImage, Anchor2D(0.5, 1.0))

            // Use DbPlace.getName() for the marker title
            val markerTitle = dbPlace.getName()

            val metadata = Metadata()
            metadata.setString("marker_title", markerTitle)
            metadata.setString("marker_type", "search_result")
            metadata.setString("location_type", markerType) // Store the location type
            mapMarker.metadata = metadata

            mapView.mapScene.addMapMarker(mapMarker)
            searchMarkers.add(mapMarker)

            Log.d(TAG, "Successfully added $markerType marker: '$markerTitle' at $coordinates")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add search marker: ${e.message}")
        }
    }

    /**
     * Legacy method for backward compatibility - now uses DbPlace and vector drawable conversion
     */
    fun addSearchMarker(coordinates: GeoCoordinates, title: String) {
        try {
            // Create MapImage from vector drawable with proper scaling
            val mapImage = createMapImageFromVectorDrawable(R.drawable.ic_map_marker_blue, 32)
            val mapMarker = MapMarker(coordinates, mapImage, Anchor2D(0.5, 1.0))

            val metadata = Metadata()
            metadata.setString("marker_title", title)
            metadata.setString("marker_type", "search_result")
            metadata.setString("location_type", "waypoint") // Default to waypoint
            mapMarker.metadata = metadata

            mapView.mapScene.addMapMarker(mapMarker)
            searchMarkers.add(mapMarker)

            Log.d(TAG, "Successfully added legacy marker: '$title' at $coordinates")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add legacy search marker: ${e.message}")
        }
    }

    fun clearSearchMarkers() {
        searchMarkers.forEach { marker ->
            mapView.mapScene.removeMapMarker(marker)
        }
        searchMarkers.clear()
        Log.d(TAG, "Cleared search markers")
    }

    fun moveMapToLocation(coordinates: GeoCoordinates) {
        val distanceInMeters = (1000 * 2).toDouble()
        val mapMeasureZoom = MapMeasure(MapMeasure.Kind.DISTANCE_IN_METERS, distanceInMeters)
        mapView.camera.lookAt(coordinates, mapMeasureZoom)
    }

    fun showLocationIndicatorPedestrian(geoCoordinates: GeoCoordinates) {
        unTiltMap()
        addLocationIndicator(geoCoordinates, LocationIndicator.IndicatorStyle.PEDESTRIAN)
    }

    private fun unTiltMap() {
        val bearing = mapView.camera.state.orientationAtTarget.bearing
        val tilt = 0.0
        mapView.camera.setOrientationAtTarget(GeoOrientationUpdate(bearing, tilt))
    }

    private fun addLocationIndicator(
        geoCoordinates: GeoCoordinates,
        indicatorStyle: LocationIndicator.IndicatorStyle
    ) {
        val locationIndicator = LocationIndicator()
        locationIndicator.locationIndicatorStyle = indicatorStyle

        val location = Location(geoCoordinates)
        location.time = Date()
        location.bearingInDegrees = getRandom(0.0, 360.0)

        locationIndicator.updateLocation(location)
        locationIndicator.enable(mapView)
        locationIndicatorList.add(locationIndicator)
    }

    private fun getRandom(min: Double, max: Double): Double {
        return min + Math.random() * (max - min)
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

    private fun pickMapMarker(touchPoint: Point2D) {
        val originInPixels = Point2D(touchPoint.x, touchPoint.y)
        val sizeInPixels = Size2D(1.0, 1.0)
        val rectangle = Rectangle2D(originInPixels, sizeInPixels)

        val contentTypesToPickFrom = ArrayList<MapScene.MapPickFilter.ContentType>()
        contentTypesToPickFrom.add(MapScene.MapPickFilter.ContentType.MAP_ITEMS)

        val filter = MapScene.MapPickFilter(contentTypesToPickFrom)

        mapView.pick(filter, rectangle) { mapPickResult ->
            if (mapPickResult == null) {
                return@pick
            }

            val pickMapItemsResult = mapPickResult.mapItems
            val mapMarkerList = pickMapItemsResult?.markers

            if (mapMarkerList?.isNotEmpty() == true) {
                val pickedMarker = mapMarkerList[0]
                showMarkerContextMenu(pickedMarker)
            }
        }
    }

    private fun showMarkerContextMenu(marker: MapMarker) {
        val metadata = marker.metadata
        val markerTitle = metadata?.getString("marker_title") ?: "Unknown Location"
        val locationType = metadata?.getString("location_type") ?: "waypoint"

        val options = arrayOf("Remove", "Move", "Edit", "Save")

        AlertDialog.Builder(context)
            .setTitle("$locationType: $markerTitle")
            .setItems(options) { dialog, which ->
                when (which) {
                    0 -> onMarkerActionRequested?.invoke("remove", marker)
                    1 -> onMarkerActionRequested?.invoke("move", marker)
                    2 -> onMarkerActionRequested?.invoke("edit", marker)
                    3 -> onMarkerActionRequested?.invoke("save", marker)
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    fun removeMarker(marker: MapMarker) {
        mapView.mapScene.removeMapMarker(marker)
        searchMarkers.remove(marker)

        val metadata = marker.metadata
        val markerTitle = metadata?.getString("marker_title") ?: "marker"
        val locationType = metadata?.getString("location_type") ?: "location"

        Toast.makeText(context, "Removed $locationType: $markerTitle", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "Marker removed: $locationType - $markerTitle")
    }

    @SuppressLint("SetTextI18n")
    fun startMarkerMove(marker: MapMarker) {
        isMovingMarker = true
        markerToMove = marker

        val metadata = marker.metadata
        val markerTitle = metadata?.getString("marker_title") ?: "marker"
        val locationType = metadata?.getString("location_type") ?: "location"

        moveStatusText?.visibility = android.view.View.VISIBLE
        moveStatusText?.text = "Moving '$markerTitle' ($locationType) - Long press on map to place at new location. Tap 'My Location' button to cancel."

        Toast.makeText(context, "Move mode activated for: $markerTitle", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "Started moving marker: $locationType - $markerTitle")
    }

    fun editMarker(marker: MapMarker) {
        val metadata = marker.metadata
        val currentTitle = metadata?.getString("marker_title") ?: "Unknown Location"

        val editText = android.widget.EditText(context)
        editText.setText(currentTitle)
        editText.selectAll()

        AlertDialog.Builder(context)
            .setTitle("Edit Marker Name")
            .setView(editText)
            .setPositiveButton("Save") { dialog, _ ->
                val newTitle = editText.text.toString().trim()
                if (newTitle.isNotEmpty()) {
                    val updatedMetadata = marker.metadata ?: Metadata()
                    updatedMetadata.setString("marker_title", newTitle)
                    marker.metadata = updatedMetadata

                    Toast.makeText(context, "Marker renamed to: $newTitle", Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "Marker renamed from '$currentTitle' to '$newTitle'")
                } else {
                    Toast.makeText(context, "Name cannot be empty", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()

        editText.requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(editText, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    fun saveMarker(marker: MapMarker) {
        val metadata = marker.metadata
        val markerTitle = metadata?.getString("marker_title") ?: "Unknown Location"
        val locationType = metadata?.getString("location_type") ?: "location"
        val coordinates = marker.coordinates

        Log.d(TAG, "Saved marker: $locationType - $markerTitle at ${coordinates.latitude}, ${coordinates.longitude}")
        Toast.makeText(context, "Saved $locationType: $markerTitle", Toast.LENGTH_SHORT).show()
    }

    private fun moveMarkerToLocation(newCoordinates: GeoCoordinates) {
        markerToMove?.let { marker ->
            val metadata = marker.metadata
            val markerTitle = metadata?.getString("marker_title") ?: "marker"
            val locationType = metadata?.getString("location_type") ?: "location"
            val oldCoordinates = marker.coordinates

            // IMPORTANT: Call the callback BEFORE updating marker coordinates
            // This allows the callback to match old coordinates with route points
            onMarkerMoved?.invoke(marker, newCoordinates)

            // Update marker coordinates AFTER the callback has processed the route update
            marker.coordinates = newCoordinates
            cancelMarkerMove()

            Log.d(TAG, "Marker '$markerTitle' ($locationType) moved from: ${oldCoordinates.latitude}, ${oldCoordinates.longitude} to: ${newCoordinates.latitude}, ${newCoordinates.longitude}")
        }
    }

    fun cancelMarkerMove() {
        isMovingMarker = false
        markerToMove = null
        moveStatusText?.visibility = android.view.View.GONE
        Log.d(TAG, "Marker move cancelled")
    }

    /**
     * Clear the marker image cache if needed (e.g., when changing themes)
     */
    fun clearMarkerImageCache() {
        markerImageCache.clear()
        Log.d(TAG, "Marker image cache cleared")
    }
}