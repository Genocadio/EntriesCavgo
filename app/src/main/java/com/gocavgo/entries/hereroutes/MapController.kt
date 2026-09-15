package com.gocavgo.entries.hereroutes

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.gocavgo.entries.R
import com.google.android.gms.location.FusedLocationProviderClient
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.annotations.PolylineOptions

class MapController(
    private val context: Context,
    private val mapView: MapView,
    private val mapStyleFab: com.google.android.material.floatingactionbutton.FloatingActionButton? = null,
    private val myLocationFab: com.google.android.material.floatingactionbutton.FloatingActionButton? = null,
    private val locationClient: FusedLocationProviderClient,
    @Suppress("UNUSED_PARAMETER") styleUrls: List<String> = emptyList(),
    @Suppress("UNUSED_PARAMETER") styleNames: List<String> = emptyList(),
    @Suppress("UNUSED_PARAMETER") tileExtension: String = ""
) {
    private val TAG = MapController::class.java.simpleName
    private var map: MapLibreMap? = null
    private var style: Style? = null

    private val arcGisSatelliteStyle = "asset://satellite_style.json"

    private var isMovingMarker = false
    private var markerToMove: Marker? = null
    private var moveStatusText: android.widget.TextView? = null

    var onLocationReceived: ((LatLng) -> Unit)? = null
    var onMarkerActionRequested: ((String, Marker) -> Unit)? = null
    var onMarkerMoved: ((Marker, LatLng) -> Unit)? = null
    var onMapLongPress: ((LatLng) -> Unit)? = null
    var onMapReady: (() -> Unit)? = null
    val isMapReady: Boolean get() = map != null

    private val iconCache = mutableMapOf<Int, org.maplibre.android.annotations.Icon>()
    private val searchMarkers = mutableListOf<Marker>()
    private var locationMarker: Marker? = null
    private val iconFactory: org.maplibre.android.annotations.IconFactory by lazy {
        org.maplibre.android.annotations.IconFactory.getInstance(context)
    }

    private fun iconForResource(resId: Int): org.maplibre.android.annotations.Icon {
        return iconCache.getOrPut(resId) {
            iconFactory.fromBitmap(MapMarkerIcons.renderMarkerBitmap(resId, context))
        }
    }

    init {
        mapView.onCreate(null)
        mapView.getMapAsync { mapInstance ->
            map = mapInstance
            mapInstance.uiSettings.isLogoEnabled = false
            mapInstance.uiSettings.isAttributionEnabled = false
            mapInstance.setStyle(Style.Builder().fromUri(arcGisSatelliteStyle)) { styleInstance ->
                style = styleInstance
                setupLocationComponent(mapInstance, styleInstance)
                mapInstance.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(-1.9403, 29.8739))
                    .zoom(13.0)
                    .build()
                setupGestureHandlers()
                setupMyLocationButton()
                createMoveStatusText()
                Log.d(TAG, "MapController initialized with MapLibre (ArcGIS satellite)")
                onMapReady?.invoke()
            }
        }
        mapStyleFab?.visibility = android.view.View.GONE
    }

    private fun setupLocationComponent(mapInstance: MapLibreMap, styleInstance: Style) {
        try {
            val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            if (permission == PackageManager.PERMISSION_GRANTED) {
                val options = LocationComponentActivationOptions.Builder(context, styleInstance).build()
                mapInstance.locationComponent.activateLocationComponent(options)
                mapInstance.locationComponent.isLocationComponentEnabled = true
                mapInstance.locationComponent.cameraMode = CameraMode.NONE
                mapInstance.locationComponent.renderMode = RenderMode.NORMAL
            }
        } catch (e: Exception) {
            Log.w(TAG, "Location component setup failed: ${e.message}")
        }
    }

    private fun setupGestureHandlers() {
        val mapInstance = map ?: return
        mapInstance.addOnMapLongClickListener { latLng ->
            if (isMovingMarker && markerToMove != null) {
                moveMarkerToLocation(latLng)
            } else {
                val nearMarker = findMarkerNear(latLng, 60f)
                if (nearMarker != null) {
                    onMarkerActionRequested?.invoke("show_options", nearMarker)
                } else {
                    onMapLongPress?.invoke(latLng)
                }
            }
            true
        }
        mapInstance.setOnMarkerClickListener(object : org.maplibre.android.maps.MapLibreMap.OnMarkerClickListener {
            override fun onMarkerClick(rawMarker: org.maplibre.android.annotations.Marker): Boolean {
                val wrapper = searchMarkers.find { it.mapMarker === rawMarker }
                if (wrapper != null) {
                    onMarkerActionRequested?.invoke("show_edit", wrapper)
                }
                return true
            }
        })
    }

    /** Finds the search marker whose screen position is within [tolerancePx] of [latLng]. */
    fun findMarkerNear(latLng: LatLng, tolerancePx: Float = 60f): Marker? {
        val mapInstance = map ?: return null
        val touchPoint = mapInstance.projection.toScreenLocation(latLng)
        var closest: Pair<Marker, Float>? = null
        searchMarkers.forEach { marker ->
            val markerPoint = marker.mapMarker?.let { mapInstance.projection.toScreenLocation(it.position) }
                ?: mapInstance.projection.toScreenLocation(marker.position)
            val distance = android.graphics.PointF(touchPoint.x - markerPoint.x, touchPoint.y - markerPoint.y).length()
            if ((closest == null || distance < closest!!.second)) {
                closest = marker to distance
            }
        }
        val (marker, distance) = closest ?: return null
        return if (distance <= tolerancePx) marker else null
    }

    private fun setupMyLocationButton() {
        val fab = myLocationFab ?: return
        fab.setOnClickListener {
            if (isMovingMarker) {
                cancelMarkerMove()
                return@setOnClickListener
            }

            val fineLocationPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            val coarseLocationPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)

            if (fineLocationPermission == PackageManager.PERMISSION_GRANTED ||
                coarseLocationPermission == PackageManager.PERMISSION_GRANTED) {
                getLocation { coordinates ->
                    if (coordinates != null) {
                        onLocationReceived?.invoke(coordinates)
                    } else {
                        Toast.makeText(context, "Unable to get current location", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                Toast.makeText(context, "Location permission is required", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun loadMapScene(center: LatLng) {
        map?.cameraPosition = CameraPosition.Builder()
            .target(center)
            .zoom(13.0)
            .build()
        showLocationIndicator(center)
        Log.d(TAG, "Map scene loaded at $center")
    }

    fun loadMapSceneWithoutIndicator(center: LatLng) {
        map?.cameraPosition = CameraPosition.Builder()
            .target(center)
            .zoom(13.0)
            .build()
        Log.d(TAG, "Map scene loaded (no indicator) at $center")
    }

    fun clearAllMarkers() {
        clearSearchMarkers()
    }

    fun clearSearchMarkers() {
        val mapInstance = map ?: return
        searchMarkers.forEach { marker -> marker.mapMarker?.let { mapInstance.removeMarker(it) } }
        searchMarkers.clear()
        Log.d(TAG, "Cleared search markers")
    }

    fun clearLocationIndicators() {
        clearLocationIndicator()
    }

    /** Disables the live location component (removes the blue dot) once a location is pinned. */
    fun hideLocationComponent() {
        try {
            map?.locationComponent?.let { component ->
                if (component.isLocationComponentEnabled) {
                    component.isLocationComponentEnabled = false
                    Log.d(TAG, "Location component disabled (blue dot hidden)")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to disable location component: ${e.message}")
        }
    }

    fun addSearchMarker(coordinates: LatLng, dbPlace: DbPlace, markerType: String = "waypoint"): Marker {
        val mapInstance = map ?: throw IllegalStateException("Map not initialized")
        val marker = Marker(
            position = coordinates,
            title = dbPlace.getName(),
            snippet = markerType,
            icon = MapMarkerIcons.iconFor(markerType)
        )
        marker.mapMarker = mapInstance.addMarker(
            org.maplibre.android.annotations.MarkerOptions()
                .position(coordinates)
                .title(dbPlace.getName())
                .snippet(markerType)
                .icon(drawableIconFor(markerType))
        )
        searchMarkers.add(marker)
        Log.d(TAG, "Added $markerType marker: '${dbPlace.getName()}' at $coordinates")
        return marker
    }

    fun addSearchMarker(coordinates: LatLng, title: String): Marker {
        val mapInstance = map ?: throw IllegalStateException("Map not initialized")
        val marker = Marker(
            position = coordinates,
            title = title,
            snippet = "waypoint",
            icon = MapMarkerIcons.iconFor("waypoint")
        )
        marker.mapMarker = mapInstance.addMarker(
            org.maplibre.android.annotations.MarkerOptions()
                .position(coordinates)
                .title(title)
                .snippet("waypoint")
                .icon(drawableIconFor("waypoint"))
        )
        searchMarkers.add(marker)
        Log.d(TAG, "Added legacy marker: '$title' at $coordinates")
        return marker
    }

    /** Returns the [Icon] for the given marker type (origin / destination / waypoint / location). */
    private fun drawableIconFor(markerType: String): org.maplibre.android.annotations.Icon {
        val resId = when (markerType.lowercase()) {
            "origin" -> R.drawable.ic_map_marker_red
            "destination" -> R.drawable.ic_flag_marker_red
            "mylocation" -> R.drawable.ic_my_location
            else -> R.drawable.ic_map_marker_blue
        }
        return iconForResource(resId)
    }

    fun moveMapToLocation(coordinates: LatLng) {
        map?.animateCamera(CameraUpdateFactory.newLatLng(coordinates))
    }

    fun showLocationIndicator(geoCoordinates: LatLng) {
        val mapInstance = map ?: return
        locationMarker?.mapMarker?.let { mapInstance.removeMarker(it) }
        val marker = Marker(
            position = geoCoordinates,
            title = "My Location",
            snippet = "mylocation",
            icon = MapMarkerIcons.iconFor("mylocation")
        )
        marker.mapMarker = mapInstance.addMarker(
            org.maplibre.android.annotations.MarkerOptions()
                .position(geoCoordinates)
                .title("My Location")
                .snippet("mylocation")
                .icon(drawableIconFor("mylocation"))
        )
        locationMarker = marker
        Log.d(TAG, "Location indicator placed at $geoCoordinates")
    }

    fun clearLocationIndicator() {
        locationMarker?.mapMarker?.let { map?.removeMarker(it) }
        locationMarker = null
    }

    fun removeMarker(marker: Marker) {
        marker.mapMarker?.let { map?.removeMarker(it) }
        marker.mapMarker = null
        searchMarkers.remove(marker)
        val title = marker.title ?: "marker"
        val snippet = marker.snippet ?: "location"
        Toast.makeText(context, "Removed $snippet: $title", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "Marker removed: $snippet - $title")
    }

    @SuppressLint("SetTextI18n")
    fun startMarkerMove(marker: Marker) {
        isMovingMarker = true
        markerToMove = marker
        moveStatusText?.visibility = android.view.View.VISIBLE
        moveStatusText?.text = "Moving '${marker.title}' - Long press on map to place at new location"
        Toast.makeText(context, "Move mode activated for: ${marker.title}", Toast.LENGTH_SHORT).show()
    }

    fun editMarker(marker: Marker) {
        val currentTitle = marker.title ?: "Unknown Location"
        val editText = android.widget.EditText(context).apply {
            setText(currentTitle)
            selectAll()
        }

        AlertDialog.Builder(context)
            .setTitle("Edit Marker Name")
            .setView(editText)
            .setPositiveButton("Save") { dialog, _ ->
                val newTitle = editText.text.toString().trim()
                if (newTitle.isNotEmpty()) {
                    marker.title = newTitle
                    Toast.makeText(context, "Marker renamed to: $newTitle", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    fun saveMarker(marker: Marker) {
        val title = marker.title ?: "Unknown Location"
        val snippet = marker.snippet ?: "location"
        val pos = marker.position
        Log.d(TAG, "Saved marker: $snippet - $title at ${pos.latitude}, ${pos.longitude}")
        Toast.makeText(context, "Saved $snippet: $title", Toast.LENGTH_SHORT).show()
    }

    private fun moveMarkerToLocation(newCoordinates: LatLng) {
        markerToMove?.let { marker ->
            val oldCoordinates = marker.position
            val title = marker.title ?: "marker"
            val snippet = marker.snippet ?: "location"

            Log.d(TAG, "Moving marker '$title' from $oldCoordinates to $newCoordinates")

            onMarkerMoved?.invoke(marker, newCoordinates)

            marker.position = newCoordinates
            map?.let { mapInstance ->
                marker.mapMarker?.let { mapInstance.removeMarker(it) }
                marker.mapMarker = mapInstance.addMarker(
                    org.maplibre.android.annotations.MarkerOptions()
                        .position(newCoordinates)
                        .title(title)
                        .snippet(snippet)
                        .icon(drawableIconFor(snippet))
                )
            }
            cancelMarkerMove()
            Log.d(TAG, "Marker '$title' moved successfully")
        }
    }

    fun cancelMarkerMove() {
        isMovingMarker = false
        markerToMove = null
        moveStatusText?.visibility = android.view.View.GONE
    }

    fun findMarkerByCoordinates(lat: Double, lon: Double): Marker? {
        return searchMarkers.find { marker ->
            val pos = marker.position
            kotlin.math.abs(pos.latitude - lat) < 0.0001 && kotlin.math.abs(pos.longitude - lon) < 0.0001
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
            val mainLayout = context.findViewById<androidx.constraintlayout.widget.ConstraintLayout>(R.id.manageroutes)
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

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun getLocation(onLocationReceived: (LatLng?) -> Unit) {
        try {
            locationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    val latLng = LatLng(location.latitude, location.longitude)
                    Log.d(TAG, "Current location: $latLng")
                    onLocationReceived(latLng)
                } else {
                    onLocationReceived(null)
                }
            }.addOnFailureListener { e ->
                Log.e(TAG, "Failed to get location: ${e.message}")
                onLocationReceived(null)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission not granted: ${e.message}")
            onLocationReceived(null)
        }
    }

    fun onCreate(savedInstanceState: Bundle?) = mapView.onCreate(savedInstanceState)
    fun onStart() = mapView.onStart()
    fun onResume() = mapView.onResume()
    fun onPause() = mapView.onPause()
    fun onStop() = mapView.onStop()
    fun onDestroy() = mapView.onDestroy()
    fun onSaveInstanceState(outState: Bundle) = mapView.onSaveInstanceState(outState)
}
