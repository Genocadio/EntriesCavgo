package com.gocavgo.entries.manageroutes

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gocavgo.entries.R
import com.gocavgo.entries.dataclass.SaveRouteResponse
import com.gocavgo.entries.service.RemoteDataManager
import com.gocavgo.entries.service.RemoteResult
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import com.gocavgo.entries.hereroutes.FinalRoute
import com.gocavgo.entries.hereroutes.DbPlace
import com.gocavgo.entries.hereroutes.WaypointWithPrice
import com.gocavgo.entries.dataclass.RouteWaypoint
import com.gocavgo.entries.hereroutes.CustomToast

class RouteManagementActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "RouteManagementActivity"
        private const val SEARCH_DELAY = 500L
    }

    // UI Components
    private lateinit var searchEditText: EditText
    private lateinit var originFilterSpinner: Spinner
    private lateinit var destinationFilterSpinner: Spinner
    private lateinit var cityRouteFilterSpinner: Spinner
    private lateinit var originProvinceSpinner: Spinner
    private lateinit var destinationProvinceSpinner: Spinner
    private lateinit var routesRecyclerView: RecyclerView
    private lateinit var loadingProgressBar: ProgressBar
    private lateinit var emptyStateTextView: TextView
    private lateinit var clearFiltersButton: Button
    private lateinit var refreshButton: ImageButton

    // Data and Adapter
    private lateinit var routeAdapter: RouteManagementAdapter
    private val routes = mutableListOf<SaveRouteResponse>()
    private var currentPage = 1
    private var isLoading = false
    private var hasMorePages = true
    private var searchJob: Job? = null

    // Filters
    private var currentSearchTerm: String? = null
    private var currentCityRouteFilter: Boolean? = null
    private var currentOriginProvinceFilter: String? = null
    private var currentDestinationProvinceFilter: String? = null

    // Remote Data Manager
    private lateinit var remoteDataManager: RemoteDataManager

    // Province options
    private val provinces = listOf("All Provinces", "Kigali City", "North", "South", "East", "West")
    private val cityRouteOptions = listOf("All Routes", "City Routes Only", "Inter-City Routes Only")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_route_management)

        initializeViews()
        setupRecyclerView()
        setupFilters()
        setupSearchListener()

        remoteDataManager = RemoteDataManager.getInstance()

        loadRoutes(refresh = true)
    }

    private fun initializeViews() {
        searchEditText = findViewById(R.id.searchEditText)
        originFilterSpinner = findViewById(R.id.originFilterSpinner)
        destinationFilterSpinner = findViewById(R.id.destinationFilterSpinner)
        cityRouteFilterSpinner = findViewById(R.id.cityRouteFilterSpinner)
        originProvinceSpinner = findViewById(R.id.originProvinceSpinner)
        destinationProvinceSpinner = findViewById(R.id.destinationProvinceSpinner)
        routesRecyclerView = findViewById(R.id.routesRecyclerView)
        loadingProgressBar = findViewById(R.id.loadingProgressBar)
        emptyStateTextView = findViewById(R.id.emptyStateTextView)
        clearFiltersButton = findViewById(R.id.clearFiltersButton)
        refreshButton = findViewById(R.id.refreshButton)

        // Setup click listeners
        clearFiltersButton.setOnClickListener { clearAllFilters() }
        refreshButton.setOnClickListener { loadRoutes(refresh = true) }

        // Setup toolbar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Manage Routes"
    }

    private fun setupRecyclerView() {
        routeAdapter = RouteManagementAdapter(
            routes = routes,
            onEditRoute = { route -> showEditRouteDialog(route) },
            onDeleteRoute = { route -> showDeleteConfirmationDialog(route) }
        )

        routesRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@RouteManagementActivity)
            adapter = routeAdapter

            // Add scroll listener for pagination
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)

                    val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                    val visibleItemCount = layoutManager.childCount
                    val totalItemCount = layoutManager.itemCount
                    val firstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition()

                    if (!isLoading && hasMorePages) {
                        if ((visibleItemCount + firstVisibleItemPosition) >= totalItemCount
                            && firstVisibleItemPosition >= 0) {
                            loadRoutes(refresh = false)
                        }
                    }
                }
            })
        }
    }

    private fun setupFilters() {
        // Setup province spinners
        val provinceAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, provinces)
        provinceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        originProvinceSpinner.adapter = provinceAdapter
        destinationProvinceSpinner.adapter = provinceAdapter

        // Setup city route filter
        val cityRouteAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, cityRouteOptions)
        cityRouteAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        cityRouteFilterSpinner.adapter = cityRouteAdapter

        // Add listeners
        originProvinceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                currentOriginProvinceFilter = if (position == 0) null else provinces[position]
                loadRoutes(refresh = true)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        destinationProvinceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                currentDestinationProvinceFilter = if (position == 0) null else provinces[position]
                loadRoutes(refresh = true)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        cityRouteFilterSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                currentCityRouteFilter = when (position) {
                    0 -> null // All routes
                    1 -> true // City routes only
                    2 -> false // Inter-city routes only
                    else -> null
                }
                loadRoutes(refresh = true)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupSearchListener() {
        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    delay(SEARCH_DELAY)
                    val searchTerm = s?.toString()?.trim()
                    currentSearchTerm = if (searchTerm.isNullOrEmpty()) null else searchTerm
                    loadRoutes(refresh = true)
                }
            }
        })
    }

    private fun loadRoutes(refresh: Boolean = false) {
        if (isLoading) return

        if (refresh) {
            currentPage = 1
            hasMorePages = true
            routes.clear()
            routeAdapter.notifyDataSetChanged()
        }

        isLoading = true
        showLoading(true)

        lifecycleScope.launch {
            try {
                Log.d(TAG, "Loading routes - Page: $currentPage, Search: $currentSearchTerm")

                val result = remoteDataManager.getRoutes(
                    origin = null, // We'll use province filters instead
                    destination = null,
                    cityRoute = currentCityRouteFilter,
                    originProvince = currentOriginProvinceFilter,
                    destinationProvince = currentDestinationProvinceFilter,
                    page = currentPage,
                    limit = 20
                )

                when (result) {
                    is RemoteResult.Success -> {
                        val newRoutes = result.data.data

                        if (refresh) {
                            routes.clear()
                        }

                        // Filter by search term if provided (client-side filtering)
                        val filteredRoutes = if (currentSearchTerm != null) {
                            newRoutes.filter { route ->
                                route.name?.contains(currentSearchTerm!!, ignoreCase = true) == true ||
                                        route.origin.google_place_name.contains(currentSearchTerm!!, ignoreCase = true) ||
                                        route.destination.google_place_name.contains(currentSearchTerm!!, ignoreCase = true) ||
                                        route.origin.custom_name?.contains(currentSearchTerm!!, ignoreCase = true) == true ||
                                        route.destination.custom_name?.contains(currentSearchTerm!!, ignoreCase = true) == true
                            }
                        } else {
                            newRoutes
                        }

                        routes.addAll(filteredRoutes)
                        routeAdapter.notifyDataSetChanged()

                        // Update pagination info
                        hasMorePages = currentPage < result.data.pagination.total_pages
                        currentPage++

                        updateEmptyState()

                        Log.d(TAG, "Loaded ${filteredRoutes.size} routes successfully")
                    }

                    is RemoteResult.Error -> {
                        Log.e(TAG, "Failed to load routes: ${result.message}")
                        showError("Failed to load routes: ${result.message}")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error loading routes", e)
                showError("Error loading routes: ${e.message}")
            } finally {
                isLoading = false
                showLoading(false)
            }
        }
    }

    private fun showEditRouteDialog(route: SaveRouteResponse) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_route, null)

        val routeNameEditText = dialogView.findViewById<EditText>(R.id.routeNameEditText)
        val routePriceEditText = dialogView.findViewById<EditText>(R.id.routePriceEditText)
        val waypointsRecyclerView = dialogView.findViewById<RecyclerView>(R.id.waypointsRecyclerView)

        // Populate current values
        routeNameEditText.setText(route.name)
        routePriceEditText.setText(route.route_price.toString())

        // Setup waypoints RecyclerView
        val waypointAdapter = WaypointEditAdapter(
            waypoints = route.waypoints.toMutableList(),
            onRemoveWaypoint = { position ->
                // Handle waypoint removal
                Log.d(TAG, "Removing waypoint at position $position")
            },
            onPriceChanged = { position, newPrice ->
                // Handle price change
                Log.d(TAG, "Price changed for waypoint $position: $newPrice")
            }
        )

        waypointsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@RouteManagementActivity)
            adapter = waypointAdapter
        }

        AlertDialog.Builder(this)
            .setTitle("Edit Route: ${route.name}")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val newName = routeNameEditText.text.toString().trim()
                val newPriceText = routePriceEditText.text.toString().trim()

                if (newName.isEmpty()) {
                    showError("Route name cannot be empty")
                    return@setPositiveButton
                }

                val newPrice = try {
                    newPriceText.toDouble()
                } catch (e: NumberFormatException) {
                    showError("Invalid price format")
                    return@setPositiveButton
                }

                updateRoute(route, newName, newPrice, waypointAdapter.waypoints)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateRoute(
        originalRoute: SaveRouteResponse,
        newName: String,
        newPrice: Double,
        updatedWaypoints: List<RouteWaypoint>
    ) {
        showLoading(true)

        lifecycleScope.launch {
            try {
                // Convert SaveRouteResponse to FinalRoute for updating
                val finalRoute = convertToFinalRoute(originalRoute, newName, newPrice, updatedWaypoints)

                val result = remoteDataManager.updateRoute(originalRoute.id, finalRoute)

                when (result) {
                    is RemoteResult.Success -> {
                        Log.d(TAG, "Route updated successfully")
                        showSuccess("Route updated successfully")
                        loadRoutes(refresh = true) // Refresh the list
                    }

                    is RemoteResult.Error -> {
                        Log.e(TAG, "Failed to update route: ${result.message}")
                        showError("Failed to update route: ${result.message}")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error updating route", e)
                showError("Error updating route: ${e.message}")
            } finally {
                showLoading(false)
            }
        }
    }

    private fun showDeleteConfirmationDialog(route: SaveRouteResponse) {
        AlertDialog.Builder(this)
            .setTitle("Delete Route")
            .setMessage("Are you sure you want to delete the route '${route.name}'? This action cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                deleteRoute(route)
            }
            .setNegativeButton("Cancel", null)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show()
    }

   private fun deleteRoute(route: SaveRouteResponse) {
       showLoading(true)

       lifecycleScope.launch {
           try {
               val result = remoteDataManager.deleteRoute(route.id)

               when (result) {
                   is RemoteResult.Success -> {
                       Log.d(TAG, "Route deleted successfully: ${route.name}")
                       showSuccess("Route '${route.name}' deleted successfully")
                       loadRoutes(refresh = true) // Refresh the list to remove deleted route
                   }

                   is RemoteResult.Error -> {
                       Log.e(TAG, "Failed to delete route: ${result.message}")
                       showError("Failed to delete route: ${result.message}")
                   }
               }

           } catch (e: Exception) {
               Log.e(TAG, "Error deleting route", e)
               showError("Error deleting route: ${e.message}")
           } finally {
               showLoading(false)
           }
       }
   }

    private fun convertToFinalRoute(
        saveRouteResponse: SaveRouteResponse,
        newName: String,
        newPrice: Double,
        updatedWaypoints: List<RouteWaypoint>
    ): FinalRoute {
        // Convert SavePlaceResponse to DbPlace
        val origin = DbPlace.create(
            placeName = saveRouteResponse.origin.google_place_name,
            latitude = saveRouteResponse.origin.latitude,
            longitude = saveRouteResponse.origin.longitude,
            placeId = saveRouteResponse.origin.place_id,
            customName = saveRouteResponse.origin.custom_name,
            customDistrict = saveRouteResponse.origin.district
        ).copy(id = saveRouteResponse.origin.id.toString())

        val destination = DbPlace.create(
            placeName = saveRouteResponse.destination.google_place_name,
            latitude = saveRouteResponse.destination.latitude,
            longitude = saveRouteResponse.destination.longitude,
            placeId = saveRouteResponse.destination.place_id,
            customName = saveRouteResponse.destination.custom_name,
            customDistrict = saveRouteResponse.destination.district
        ).copy(id = saveRouteResponse.destination.id.toString())

        // Convert waypoints
        val waypointsWithPrice = updatedWaypoints.map { waypoint ->
            val dbPlace = DbPlace.create(
                placeName = waypoint.location.google_place_name,
                latitude = waypoint.location.latitude,
                longitude = waypoint.location.longitude,
                placeId = waypoint.location.place_id,
                customName = waypoint.location.custom_name,
                customDistrict = waypoint.location.district
            ).copy(id = waypoint.location.id.toString())

            WaypointWithPrice(dbPlace = dbPlace, price = waypoint.price)
        }

        // Convert distance and duration back to string format
        val distanceKm = saveRouteResponse.distance_meters / 1000.0
        val distanceStr = String.format("%.1f km", distanceKm)

        val durationHours = saveRouteResponse.estimated_duration_seconds / 3600
        val durationMinutes = (saveRouteResponse.estimated_duration_seconds % 3600) / 60
        val durationStr = when {
            durationHours > 0 -> "${durationHours}h ${durationMinutes}min"
            else -> "${durationMinutes}min"
        }

        return FinalRoute(
            id = saveRouteResponse.id.toString(),
            origin = origin,
            destination = destination,
            routePrice = newPrice,
            routeName = newName,
            isCityRoute = saveRouteResponse.city_route,
            distance = distanceStr,
            duration = durationStr,
            waypoints = waypointsWithPrice
        )
    }

    private fun clearAllFilters() {
        searchEditText.setText("")
        originProvinceSpinner.setSelection(0)
        destinationProvinceSpinner.setSelection(0)
        cityRouteFilterSpinner.setSelection(0)

        currentSearchTerm = null
        currentCityRouteFilter = null
        currentOriginProvinceFilter = null
        currentDestinationProvinceFilter = null

        loadRoutes(refresh = true)
    }

    private fun showLoading(show: Boolean) {
        loadingProgressBar.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun updateEmptyState() {
        if (routes.isEmpty() && !isLoading) {
            emptyStateTextView.visibility = View.VISIBLE
            emptyStateTextView.text = if (hasActiveFilters()) {
                "No routes found matching your filters"
            } else {
                "No routes available"
            }
        } else {
            emptyStateTextView.visibility = View.GONE
        }
    }

    private fun hasActiveFilters(): Boolean {
        return currentSearchTerm != null ||
                currentCityRouteFilter != null ||
                currentOriginProvinceFilter != null ||
                currentDestinationProvinceFilter != null
    }

    private fun showError(message: String) {
        CustomToast.showError(this, message)
    }

    private fun showSuccess(message: String) {
        CustomToast.showSuccess(this, message)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        searchJob?.cancel()
    }
}