package com.gocavgo.entries.hereroutes

import android.Manifest
import android.os.Bundle
import android.util.Log
import android.widget.Button
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
import com.here.sdk.core.GeoOrientationUpdate
import com.here.sdk.core.Location
import com.here.sdk.core.engine.AuthenticationMode
import com.here.sdk.core.engine.SDKNativeEngine
import com.here.sdk.core.engine.SDKOptions
import com.here.sdk.core.errors.InstantiationErrorException
import com.here.sdk.gestures.TapListener
import com.here.sdk.mapview.LocationIndicator
import com.here.sdk.mapview.MapMeasure
import com.here.sdk.mapview.MapScheme
import com.here.sdk.mapview.MapView
import java.util.Calendar
import java.util.Date

class ManageRoute : AppCompatActivity() {

    private var mapView: MapView? = null
    private val locationIndicatorList = arrayListOf<LocationIndicator>()
    private lateinit var locationClient: FusedLocationProviderClient
    private val TAG = ManageRoute::class.java.simpleName
    private lateinit var mapStyleButton: Button

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
        locationClient = LocationServices.getFusedLocationProviderClient(this)
        enableEdgeToEdge()
        setContentView(R.layout.activity_manage_route)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.manageroutes)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        initializeViews()
        setupMapStyleButton()

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
        mapStyleButton = findViewById(R.id.btn_map_style) // Add this button to your layout
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
        val location: Location = Location(geoCoordinates)
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
            Log.d(TAG, "Tap at: ${geoCoordinates?.altitude}")
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