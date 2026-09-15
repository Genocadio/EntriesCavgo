package com.gocavgo.entries.manageplace

import android.Manifest
import android.os.Bundle
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.gocavgo.entries.BuildConfig
import com.gocavgo.entries.R
import com.gocavgo.entries.hereroutes.DbPlace
import com.gocavgo.entries.hereroutes.MapController
import com.gocavgo.entries.service.LocationIqClient
import com.gocavgo.entries.service.RemoteDataManager
import com.gocavgo.entries.service.RemoteResult
import com.gocavgo.entries.dataclass.SavePlaceRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView

class PlaceMapActivity : AppCompatActivity() {

    private val TAG = PlaceMapActivity::class.java.simpleName
    private var mapView: MapView? = null
    private lateinit var locationClient: FusedLocationProviderClient
    private lateinit var mapController: MapController

    private val locationIqClient: LocationIqClient by lazy {
        val token = try {
            val field = BuildConfig::class.java.getField("LOCATIONIQ_API_KEY")
            field.get(null) as String
        } catch (e: Exception) { "" }
        LocationIqClient(token)
    }

    private val styleUrls = listOf(
        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
    )
    private val tileExtension = ".png"
    private val styleNames = listOf("Esri World Imagery")

    private val remoteDataManager = RemoteDataManager.getInstance()
    private val uiScope = CoroutineScope(Dispatchers.Main)

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        locationClient = LocationServices.getFusedLocationProviderClient(this)
        setContentView(R.layout.activity_place_map)

        mapView = findViewById(R.id.map_view)

        val myLocationFab = findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.fab_my_location)

        mapController = MapController(this, mapView!!, null, myLocationFab, locationClient, styleUrls, styleNames, tileExtension)

        val placeId = intent.getStringExtra("place_id")
        val latitude = intent.getDoubleExtra("latitude", 0.0)
        val longitude = intent.getDoubleExtra("longitude", 0.0)
        val placeName = intent.getStringExtra("place_name") ?: "Place"
        val customName = intent.getStringExtra("custom_name")
        val district = intent.getStringExtra("district")

        val startCoords = LatLng(latitude, longitude)
        val dbPlace = DbPlace.create(placeName = placeName, latitude = latitude, longitude = longitude, customName = customName, customDistrict = district).copy(id = placeId)

        mapController.onMapReady = {
            mapController.loadMapSceneWithoutIndicator(startCoords)
            mapController.clearLocationIndicators()
            mapController.clearSearchMarkers()
            mapController.addSearchMarker(startCoords, dbPlace, "waypoint")
            mapController.moveMapToLocation(startCoords)
        }

        mapController.onMarkerActionRequested = { action, actionMarker ->
            when (action) {
                "move" -> mapController.startMarkerMove(actionMarker)
                "save" -> savePlace(dbPlace)
            }
        }

        val etCustomName = findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_custom_name)
        val btnSave = findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_save)
        val tvPlaceName = findViewById<android.widget.TextView>(R.id.tv_place_name)
        val tvCoordinates = findViewById<android.widget.TextView>(R.id.tv_coordinates)

        tvPlaceName.text = dbPlace.placeName
        tvCoordinates.text = "${"%.5f".format(latitude)}, ${"%.5f".format(longitude)}"
        etCustomName.setText(customName ?: "")

        btnSave.setOnClickListener {
            dbPlace.customName = etCustomName.text?.toString()?.takeIf { it.isNotBlank() }
            savePlace(dbPlace)
        }

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

        mapController.onMarkerMoved = { _, newCoordinates ->
            tvCoordinates.text = "${"%.5f".format(newCoordinates.latitude)}, ${"%.5f".format(newCoordinates.longitude)}"
            tvPlaceName.text = dbPlace.placeName
            reverseUpdateUi(dbPlace, newCoordinates) { updateSaveVisibility() }
        }
    }

    private fun reverseUpdateUi(dbPlace: DbPlace, coords: LatLng, onDone: (() -> Unit)? = null) {
        lifecycleScope.launch {
            val result = locationIqClient.reverseGeocode(coords.latitude, coords.longitude)
            if (result != null) {
                val newTitle = result.placeName.ifBlank { result.addressText }
                dbPlace.setPlaceName(newTitle)
                dbPlace.setPlaceId(result.placeId)
                dbPlace.updateCoordinates(coords.latitude, coords.longitude)
                withContext(Dispatchers.Main) {
                    findViewById<android.widget.TextView>(R.id.tv_place_name).text = dbPlace.placeName
                    findViewById<android.widget.TextView>(R.id.tv_coordinates).text = "${"%.5f".format(coords.latitude)}, ${"%.5f".format(coords.longitude)}"
                }
            } else {
                dbPlace.updateCoordinates(coords.latitude, coords.longitude)
            }
            onDone?.invoke()
        }
    }

    private fun savePlace(dbPlace: DbPlace) {
        val placeIdInt = dbPlace.id?.toIntOrNull()
        val request = SavePlaceRequest(
            latitude = dbPlace.latitude, longitude = dbPlace.longitude,
            google_place_name = dbPlace.placeName, custom_name = dbPlace.customName,
            province = dbPlace.province, district = dbPlace.customDistrict, place_id = dbPlace.placeId
        )
        uiScope.launch {
            if (placeIdInt != null) {
                when (val result = remoteDataManager.updateWaypoint(placeIdInt, request)) {
                    is RemoteResult.Success -> { Log.d(TAG, "Waypoint updated: ${result.data.id}"); finish() }
                    is RemoteResult.Error -> Log.e(TAG, "Failed to update: ${result.message}")
                }
            } else {
                when (val result = remoteDataManager.savePlace(dbPlace)) {
                    is RemoteResult.Success -> finish()
                    is RemoteResult.Error -> Log.e(TAG, "Failed to save: ${result.message}")
                }
            }
        }
    }

    override fun onPause() { mapView?.onPause(); super.onPause() }
    override fun onResume() { mapView?.onResume(); super.onResume() }
    override fun onDestroy() { super.onDestroy() }
}
