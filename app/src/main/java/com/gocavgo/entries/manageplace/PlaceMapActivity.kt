package com.gocavgo.entries.manageplace

import android.Manifest
import android.os.Bundle
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import com.gocavgo.entries.BuildConfig
import com.gocavgo.entries.R
import com.gocavgo.entries.hereroutes.DbPlace
import com.gocavgo.entries.hereroutes.MapController
import com.gocavgo.entries.service.RemoteDataManager
import com.gocavgo.entries.service.RemoteResult
import com.gocavgo.entries.dataclass.SavePlaceRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.here.sdk.core.GeoCoordinates
import com.here.sdk.core.engine.AuthenticationMode
import com.here.sdk.core.engine.SDKNativeEngine
import com.here.sdk.core.engine.SDKOptions
import com.here.sdk.core.errors.InstantiationErrorException
import com.here.sdk.mapview.MapScheme
import com.here.sdk.mapview.MapView
import com.here.sdk.search.SearchEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PlaceMapActivity : AppCompatActivity() {

    private val TAG = PlaceMapActivity::class.java.simpleName
    private var mapView: MapView? = null
    private lateinit var locationClient: FusedLocationProviderClient
    private lateinit var mapController: MapController
    private lateinit var searchEngine: SearchEngine
    private lateinit var reverseEngine: SearchEngine

    private val mapSchemes = listOf(
        MapScheme.NORMAL_DAY,
        MapScheme.NORMAL_NIGHT
    )
    private val mapSchemeNames = listOf("Normal Day", "Normal Night")

    private val remoteDataManager = RemoteDataManager.getInstance()
    private val uiScope = CoroutineScope(Dispatchers.Main)

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeHERESDK()
        initializeSearch()
        locationClient = LocationServices.getFusedLocationProviderClient(this)
        setContentView(R.layout.activity_place_map)

        mapView = findViewById(R.id.map_view)
        mapView?.onCreate(savedInstanceState)

        val myLocationFab: com.google.android.material.floatingactionbutton.FloatingActionButton = findViewById(R.id.fab_my_location)
        val mapStyleFab: com.google.android.material.floatingactionbutton.FloatingActionButton = findViewById(R.id.fab_map_style)

        mapController = MapController(
            this,
            mapView!!,
            mapStyleFab,
            myLocationFab,
            locationClient,
            mapSchemes,
            mapSchemeNames
        )

        // Extract incoming place
        val placeId = intent.getStringExtra("place_id")
        val latitude = intent.getDoubleExtra("latitude", 0.0)
        val longitude = intent.getDoubleExtra("longitude", 0.0)
        val placeName = intent.getStringExtra("place_name") ?: "Place"
        val customName = intent.getStringExtra("custom_name")

        val startCoords = GeoCoordinates(latitude, longitude)
        val dbPlace = DbPlace.create(
            placeName = placeName,
            latitude = latitude,
            longitude = longitude,
            customName = customName
        ).copy(id = placeId)

        // Ensure map scene is loaded before interacting, without showing current-location indicator
        mapController.loadMapSceneWithoutIndicator(startCoords)
        // Remove any default/current-location indicators & previous markers to keep only one marker
        mapController.clearLocationIndicators()
        mapController.clearSearchMarkers()

        // Show marker and enable drag-to-move via context menu
        val marker = mapController.addSearchMarker(startCoords, dbPlace, "waypoint")
        mapController.moveMapToLocation(startCoords)

        mapController.onMarkerActionRequested = { action, markerRef ->
            when (action) {
                "move" -> mapController.startMarkerMove(markerRef)
                "save" -> savePlace(dbPlace)
                "edit" -> { /* no-op here, editing limited to custom name via bottom panel */ }
                "remove" -> { /* ignore remove in this screen */ }
            }
        }

        // Marker moved handler set later to only update UI and show Save button

        // Save button in bottom panel
        val etCustomName: com.google.android.material.textfield.TextInputEditText = findViewById(R.id.et_custom_name)
        val btnSave: com.google.android.material.button.MaterialButton = findViewById(R.id.btn_save)
        val tvPlaceName: android.widget.TextView = findViewById(R.id.tv_place_name)
        val tvCoordinates: android.widget.TextView = findViewById(R.id.tv_coordinates)

        tvPlaceName.text = dbPlace.placeName
        tvCoordinates.text = "${"%.5f".format(latitude)}, ${"%.5f".format(longitude)}"
        etCustomName.setText(customName ?: "")

        btnSave.setOnClickListener {
            val updatedCustom = etCustomName.text?.toString()?.takeIf { it.isNotBlank() }
            dbPlace.customName = updatedCustom
            savePlace(dbPlace)
        }

        // Show Save button only when there are changes
        val originalLat = dbPlace.latitude
        val originalLng = dbPlace.longitude
        val originalCustom = dbPlace.customName

        fun updateSaveVisibility() {
            val coordsChanged = (dbPlace.latitude != originalLat) || (dbPlace.longitude != originalLng)
            val customChanged = (dbPlace.customName ?: "") != (originalCustom ?: "")
            btnSave.visibility = if (coordsChanged || customChanged) android.view.View.VISIBLE else android.view.View.GONE
        }

        etCustomName.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                dbPlace.customName = s?.toString()?.takeIf { it.isNotBlank() }
                updateSaveVisibility()
            }
        })

        // When marker moves, immediately update coordinates text, then reverse-search to update name
        mapController.onMarkerMoved = { movedMarker, newCoordinates ->
            // Immediate UI feedback for coordinates
            findViewById<android.widget.TextView>(R.id.tv_coordinates).text = "${"%.5f".format(newCoordinates.latitude)}, ${"%.5f".format(newCoordinates.longitude)}"
            // Optional: temporary hint while fetching name
            findViewById<android.widget.TextView>(R.id.tv_place_name).text = dbPlace.placeName

            reverseUpdateUi(dbPlace, newCoordinates) {
                updateSaveVisibility()
            }
        }
    }

    private fun reverseUpdateUi(dbPlace: DbPlace, coords: GeoCoordinates, onDone: (() -> Unit)? = null) {
        val options = com.here.sdk.search.SearchOptions().apply {
            languageCode = com.here.sdk.core.LanguageCode.EN_GB
            maxItems = 1
        }
        reverseEngine.searchByCoordinates(coords, options, object : com.here.sdk.search.SearchCallback {
            override fun onSearchCompleted(error: com.here.sdk.search.SearchError?, list: List<com.here.sdk.search.Place>?) {
                val first = list?.firstOrNull()
                if (error == null && first != null) {
                    val newTitle = first.title.ifBlank { first.address.addressText }
                    dbPlace.setPlaceName(newTitle)
                    dbPlace.updateCoordinates(coords.latitude, coords.longitude)
                    // We do not allow district edit here per requirements

                    // Update UI labels
                    runOnUiThread {
                        findViewById<android.widget.TextView>(R.id.tv_place_name).text = dbPlace.placeName
                        findViewById<android.widget.TextView>(R.id.tv_coordinates).text = "${"%.5f".format(coords.latitude)}, ${"%.5f".format(coords.longitude)}"
                    }
                    onDone?.let { it() }
                } else {
                    // Still update coordinates change even without reverse geocode
                    dbPlace.updateCoordinates(coords.latitude, coords.longitude)
                    onDone?.let { it() }
                }
            }
        })
    }

    private fun savePlace(dbPlace: DbPlace) {
        val placeIdInt = dbPlace.id?.toIntOrNull()
        val request = SavePlaceRequest(
            latitude = dbPlace.latitude,
            longitude = dbPlace.longitude,
            google_place_name = dbPlace.placeName,
            custom_name = dbPlace.customName,
            province = dbPlace.province,
            district = dbPlace.customDistrict,
            place_id = dbPlace.placeId
        )

        uiScope.launch {
            if (placeIdInt != null) {
                when (val result = remoteDataManager.updateWaypoint(placeIdInt, request)) {
                    is RemoteResult.Success -> {
                        Log.d(TAG, "Waypoint/place updated: ${result.data.id}")
                        finish()
                    }
                    is RemoteResult.Error -> {
                        Log.e(TAG, "Failed to update place: ${result.message}")
                    }
                }
            } else {
                when (val result = remoteDataManager.savePlace(dbPlace)) {
                    is RemoteResult.Success -> finish()
                    is RemoteResult.Error -> Log.e(TAG, "Failed to save new place: ${result.message}")
                }
            }
        }
    }

    private fun initializeSearch() {
        try {
            searchEngine = SearchEngine()
            reverseEngine = SearchEngine()
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
        mapView?.onDestroy()
        disposeHERESDK()
        super.onDestroy()
    }
}


