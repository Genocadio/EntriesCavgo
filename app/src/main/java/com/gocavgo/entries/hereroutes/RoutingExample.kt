package com.gocavgo.entries.hereroutes

import android.content.Context
import android.graphics.Color
import android.util.Log
import android.widget.Toast
import com.gocavgo.entries.BuildConfig
import com.gocavgo.entries.model.CalcRouteResult
import com.gocavgo.entries.service.NavigationRouteClient
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
import org.maplibre.android.annotations.Polyline
import org.maplibre.android.annotations.PolylineOptions

class RoutingExample(private val context: Context, private val mapView: MapView) {

    companion object {
        private val TAG = RoutingExample::class.java.name
        private val ROUTE_COLOR = Color.parseColor("#00897B")
        private const val ROUTE_WIDTH = 8f
    }

    private var map: MapLibreMap? = null
    private val mapPolylines = mutableListOf<Polyline>()
    private val routeScope = CoroutineScope(Dispatchers.Main)

    // Route state
    private var originCoordinates: LatLng? = null
    private var destinationCoordinates: LatLng? = null
    private val waypointsList = arrayListOf<LatLng>()
    private val waypointPassThroughFlags = arrayListOf<Boolean>()
    private var currentRouteResult: CalcRouteResult? = null
    private var suppressRecalculate = false

    // Navigation route client
    private val navRouteClient: NavigationRouteClient by lazy {
        val baseUrl = try {
            val field = com.gocavgo.entries.BuildConfig::class.java.getField("NAVIGATION_BASE_URL")
            field.get(null) as String
        } catch (e: Exception) {
            "https://api.gocavgo.com/maps"
        }
        NavigationRouteClient(baseUrl)
    }

    // Callback
    var onRouteCalculated: ((CalcRouteResult?, String?) -> Unit)? = null

    init {
        mapView.getMapAsync { mapInstance ->
            map = mapInstance
            mapInstance.cameraPosition = CameraPosition.Builder()
                .target(LatLng(52.520798, 13.409408))
                .zoom(10.0)
                .build()
        }
    }

    fun setOrigin(coordinates: LatLng) {
        originCoordinates = coordinates
        Log.d(TAG, "Origin set to: $coordinates")
        clearRoute()
    }

    fun setDestination(coordinates: LatLng) {
        destinationCoordinates = coordinates
        Log.d(TAG, "Destination set to: $coordinates")
        if (originCoordinates != null) calculateRouteWithWaypoints()
        else showDialog("Error", "Please set origin first.")
    }

    fun addWaypoint(coordinates: LatLng, isPassThrough: Boolean = false) {
        if (originCoordinates == null || destinationCoordinates == null) {
            showDialog("Error", "Please set origin and destination first.")
            return
        }
        waypointsList.add(coordinates)
        waypointPassThroughFlags.add(isPassThrough)
        Log.d(TAG, "Waypoint added: $coordinates")
        calculateRouteWithWaypoints()
    }

    fun updateOrigin(coordinates: LatLng) {
        originCoordinates = coordinates
        if (destinationCoordinates != null) calculateRouteWithWaypoints()
    }

    fun updateDestination(coordinates: LatLng) {
        destinationCoordinates = coordinates
        if (originCoordinates != null) calculateRouteWithWaypoints()
    }

    fun updateWaypoint(oldIndex: Int, coordinates: LatLng) {
        if (oldIndex in waypointsList.indices) {
            waypointsList[oldIndex] = coordinates
            if (originCoordinates != null && destinationCoordinates != null) calculateRouteWithWaypoints()
        }
    }

    fun removeWaypoint(index: Int) {
        if (index in waypointsList.indices) {
            waypointsList.removeAt(index)
            waypointPassThroughFlags.removeAt(index)
            if (originCoordinates != null && destinationCoordinates != null) calculateRouteWithWaypoints()
        }
    }

    fun removeWaypointAndRecalculate(index: Int) {
        removeWaypoint(index)
    }

    fun clearRouteData() {
        originCoordinates = null
        destinationCoordinates = null
        waypointsList.clear()
        waypointPassThroughFlags.clear()
        currentRouteResult = null
        clearMap()
    }

    fun getCurrentWaypoints(): List<LatLng> {
        val all = arrayListOf<LatLng>()
        originCoordinates?.let { all.add(it) }
        all.addAll(waypointsList)
        destinationCoordinates?.let { all.add(it) }
        return all
    }

    fun isRouteValid(): Boolean = originCoordinates != null && destinationCoordinates != null
    fun hasActiveRoute(): Boolean = originCoordinates != null && destinationCoordinates != null

    /**
     * When false, adding stops will not trigger a recalculation. Use it to batch-add
     * stops (e.g. when importing a shared route) and then call
     * [calculateRouteWithWaypoints] once at the end.
     */
    fun setRecalculationEnabled(enabled: Boolean) {
        suppressRecalculate = !enabled
    }

    fun isOrigin(coordinates: LatLng): Boolean = originCoordinates?.let {
        kotlin.math.abs(it.latitude - coordinates.latitude) < 0.0001 &&
        kotlin.math.abs(it.longitude - coordinates.longitude) < 0.0001
    } ?: false

    fun isDestination(coordinates: LatLng): Boolean = destinationCoordinates?.let {
        kotlin.math.abs(it.latitude - coordinates.latitude) < 0.0001 &&
        kotlin.math.abs(it.longitude - coordinates.longitude) < 0.0001
    } ?: false

    fun findWaypointIndex(coordinates: LatLng): Int {
        return waypointsList.indexOfFirst {
            kotlin.math.abs(it.latitude - coordinates.latitude) < 0.0001 &&
            kotlin.math.abs(it.longitude - coordinates.longitude) < 0.0001
        }
    }

    fun clearRouteAfterOriginDeletion() {
        originCoordinates = null
        clearMap()
        onRouteCalculated?.invoke(null, null)
    }

    fun clearRouteAfterDestinationDeletion() {
        destinationCoordinates = null
        clearMap()
        onRouteCalculated?.invoke(null, null)
    }

    fun calculateRouteWithWaypoints() {
        if (suppressRecalculate) return

        val origin = originCoordinates
        val destination = destinationCoordinates

        if (origin == null || destination == null) {
            clearMap()
            onRouteCalculated?.invoke(null, null)
            return
        }

        // Build waypoints: origin + intermediate + destination
        val allWaypoints = mutableListOf<Pair<Double, Double>>()
        allWaypoints.add(Pair(origin.latitude, origin.longitude))
        waypointsList.forEach { wp -> allWaypoints.add(Pair(wp.latitude, wp.longitude)) }
        allWaypoints.add(Pair(destination.latitude, destination.longitude))

        Log.d(TAG, "Calculating route with ${allWaypoints.size} waypoints")

        routeScope.launch {
            val result = navRouteClient.calculateRoute(allWaypoints)
            if (result != null && result.route != null) {
                currentRouteResult = result
                showRouteOnMap(result)
                onRouteCalculated?.invoke(result, null)
                Log.d(TAG, "Route calculated: ${result.route!!.totalDistance}m, ${result.route!!.totalDuration}s")
            } else {
                Log.w(TAG, "Route calculation returned null")
                onRouteCalculated?.invoke(null, null)
            }
        }
    }

    private fun showRouteOnMap(result: CalcRouteResult) {
        clearRoute()

        val route = result.route ?: return
        val polylinePoints = route.polyline.map { point -> LatLng(point[0], point[1]) }

        if (polylinePoints.size < 2) {
            Log.w(TAG, "Route polyline has < 2 points")
            return
        }

        val polyline = map?.addPolyline(
            PolylineOptions()
                .addAll(polylinePoints)
                .color(ROUTE_COLOR)
                .width(ROUTE_WIDTH)
        )

        polyline?.let { mapPolylines.add(it) }

        // Fit camera to route bounds
        try {
            val bounds = LatLngBounds.Builder()
            polylinePoints.forEach { bounds.include(it) }
            map?.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 100))
        } catch (e: Exception) {
            Log.e(TAG, "Error fitting camera to route: ${e.message}")
        }
    }

    fun clearMap() {
        clearRoute()
    }

    private fun clearRoute() {
        mapPolylines.forEach { map?.removePolyline(it) }
        mapPolylines.clear()
    }

    private fun showDialog(title: String, message: String) {
        android.app.AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .show()
    }
}
