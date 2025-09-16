package com.gocavgo.entries.hereroutes

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageView
import android.annotation.SuppressLint
import android.view.ViewGroup
import android.widget.ScrollView
import com.gocavgo.entries.R
import com.here.sdk.routing.Route
import androidx.core.view.isNotEmpty

class UIController(
    private val context: Context,
    private val searchContainer: LinearLayout,
    private val searchOverlay: LinearLayout,
    private val placeDetailsContainer: LinearLayout,
    private val routeSummaryContainer: LinearLayout,
    private val routeActionsContainer: LinearLayout,
    private val locationActionsContainer: LinearLayout,
    private val searchAutoComplete: AutoCompleteTextView,
    private val clearSearchButton: ImageButton,
    private val searchHintText: TextView,
    private val backSearchButton: ImageButton,
    private val tvPlaceTitle: TextView,
    private val tvPlaceAddress: TextView,
    private val tvPlaceType: TextView,
    private val tvRouteDistance: TextView,
    private val tvRouteDuration: TextView,
) {
    private val logTag = UIController::class.java.simpleName

    // UPDATED: Use DbPlace objects exclusively
    var onRouteStopsDbDataRequest: (() -> Triple<DbPlace?, DbPlace?, List<DbPlace>>)? = null
    private var currentOriginDbPlace: DbPlace? = null
    private var currentDestinationDbPlace: DbPlace? = null
    private var currentWaypointDbPlaces: List<DbPlace> = emptyList()

    // Route summary views
    private lateinit var routeExpandCollapseButton: ImageButton
    private lateinit var routeDetailsContainer: ScrollView
    private lateinit var routeStopsContainer: LinearLayout
    private lateinit var tvRouteOriginDestination: TextView
    private lateinit var tvWaypointCount: TextView
    private var isRouteSummaryExpanded = false
    private lateinit var routeConfigurationContainer: LinearLayout
    private lateinit var databaseSearchToggle: androidx.appcompat.widget.SwitchCompat
    private lateinit var searchModeIndicator: TextView
    private var isDatabaseSearchEnabled = false
    private lateinit var etRouteName: com.google.android.material.textfield.TextInputEditText
    private lateinit var etRoutePrice: com.google.android.material.textfield.TextInputEditText
    private lateinit var cbCityRoute: android.widget.CheckBox
    private lateinit var waypointPricesContainer: LinearLayout
    private lateinit var waypointPriceInputsContainer: LinearLayout
    private val waypointPriceInputs = mutableListOf<com.google.android.material.textfield.TextInputEditText>()

    // Route data - no HERE SDK Place dependency
    private var currentRoute: Route? = null

    // Callbacks
    var onLocationActionRequested: ((String) -> Unit)? = null
    var onSelectionStateRequest: (() -> Triple<Boolean, Boolean, Boolean>)? = null
    var onRouteDataRequest: (() -> Pair<Boolean, Boolean>)? = null
    var onAutoSuggestRequested: ((String) -> Unit)? = null

    init {
        setupSearchOverlay()
        setupLocationActionButtons()
        setupEnhancedRouteSummary()
        setupDatabaseSearchToggle()
    }

    private fun setupEnhancedRouteSummary() {
        // Find or create the route summary views
        routeExpandCollapseButton = routeSummaryContainer.findViewById(R.id.btn_expand_route)
            ?: createExpandCollapseButton()

        routeDetailsContainer = routeSummaryContainer.findViewById(R.id.route_details_container)
            ?: createRouteDetailsContainer()

        routeStopsContainer = routeDetailsContainer.findViewById(R.id.route_stops_container)
            ?: createRouteStopsContainer()

        // Create route summary text views
        tvRouteOriginDestination = createRouteSummaryTextView()
        tvWaypointCount = createWaypointCountTextView()
        setupRouteConfiguration()

        routeExpandCollapseButton.setOnClickListener {
            toggleRouteSummarySize()
        }
    }

    private fun setupRouteConfiguration() {
        // Find the inner LinearLayout inside the NestedScrollView
        val innerContainer = routeDetailsContainer.getChildAt(0) as? LinearLayout

        routeConfigurationContainer = innerContainer?.findViewById(R.id.route_configuration_container)
            ?: createRouteConfigurationContainer()

        etRouteName = routeConfigurationContainer.findViewById(R.id.et_route_name)
        etRoutePrice = routeConfigurationContainer.findViewById(R.id.et_route_price)
        cbCityRoute = routeConfigurationContainer.findViewById(R.id.cb_city_route)
        waypointPricesContainer = routeConfigurationContainer.findViewById(R.id.waypoint_prices_container)
        waypointPriceInputsContainer = routeConfigurationContainer.findViewById(R.id.waypoint_price_inputs_container)
    }

    private fun createRouteConfigurationContainer(): LinearLayout {
        val container = LinearLayout(context).apply {
            id = R.id.route_configuration_container
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(20, 20, 20, 20)
        }

        // Add to the inner LinearLayout of the NestedScrollView
        val innerContainer = routeDetailsContainer.getChildAt(0) as? LinearLayout
        if (innerContainer != null && innerContainer.isNotEmpty()) {
            innerContainer.addView(container, 1) // Add after header
        } else {
            innerContainer?.addView(container)
        }

        return container
    }

    private fun setupDatabaseSearchToggle() {
        // Reference the toggle from the search overlay layout
        databaseSearchToggle = searchOverlay.findViewById(R.id.database_search_toggle)
        searchModeIndicator = searchOverlay.findViewById(R.id.search_mode_indicator)

        databaseSearchToggle.setOnCheckedChangeListener { _, isChecked ->
            isDatabaseSearchEnabled = isChecked
            updateSearchModeIndicator(isChecked)
            Log.d(logTag, "Database search mode: ${if (isChecked) "Enabled" else "Disabled"}")
        }

        // Initialize with default state
        updateSearchModeIndicator(false)
    }


    private fun createSearchModeIndicator(): TextView {
        val indicator = TextView(context).apply {
            id = R.id.search_mode_indicator
            textSize = 12f
            setTextColor(context.getColor(android.R.color.darker_gray))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 16
            }
        }

        routeConfigurationContainer.addView(indicator, 1) // Add after toggle
        return indicator
    }

    private fun updateSearchModeIndicator(isDatabaseMode: Boolean) {
        searchModeIndicator.text = if (isDatabaseMode) {
            "🔍 Searching saved locations only"
        } else {
            "🌍 Searching all locations via Maps"
        }
    }

    fun isDatabaseSearchMode(): Boolean = isDatabaseSearchEnabled
    private fun createDatabaseSearchToggle(): androidx.appcompat.widget.SwitchCompat {
        val toggle = androidx.appcompat.widget.SwitchCompat(context).apply {
            id = R.id.database_search_toggle
            text = "Use Saved Locations"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 16
                bottomMargin = 8
            }
        }

        routeConfigurationContainer.addView(toggle, 0) // Add at top of configuration
        return toggle
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun createExpandCollapseButton(): ImageButton {
        val button = ImageButton(context).apply {
            id = R.id.btn_expand_route
            background = context.getDrawable(android.R.drawable.btn_default)
            setImageResource(android.R.drawable.ic_menu_more)
            contentDescription = "Expand route details"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val existingLayout = routeSummaryContainer.getChildAt(0) as? LinearLayout
        existingLayout?.addView(button)

        return button
    }

    private fun createRouteDetailsContainer(): ScrollView {
        val container = ScrollView(context).apply {
            id = R.id.route_details_container
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            visibility = View.GONE
            isFillViewport = true
            scrollBarStyle = View.SCROLLBARS_OUTSIDE_OVERLAY
        }

        // Create the inner LinearLayout for content
        val innerContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        container.addView(innerContainer)
        routeSummaryContainer.addView(container)
        return container
    }

    private fun createRouteStopsContainer(): LinearLayout {
        val container = LinearLayout(context).apply {
            id = R.id.route_stops_container
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Find the inner LinearLayout inside the NestedScrollView
        val innerContainer = (routeDetailsContainer.getChildAt(0) as? LinearLayout)
        innerContainer?.addView(container)

        return container
    }

    private fun createRouteSummaryTextView(): TextView {
        return TextView(context).apply {
            id = R.id.tv_route_origin_destination
            textSize = 14f
            setTextColor(context.getColor(android.R.color.black))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun createWaypointCountTextView(): TextView {
        return TextView(context).apply {
            id = R.id.tv_waypoint_count
            textSize = 12f
            setTextColor(context.getColor(android.R.color.darker_gray))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    fun setupSearchFunctionality(searchManager: SearchManager) {
        searchManager.setupAutoCompleteTextView(searchAutoComplete)

        searchAutoComplete.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim() ?: ""

                if (searchManager.isUserInput) {
                    if (query.isNotEmpty()) {
                        Log.d(logTag, "Performing auto-suggest for: $query")
                        onAutoSuggestRequested?.invoke(query)
                    } else if (query.isEmpty()) {
                        searchManager.clearSuggestions()
                        searchAutoComplete.dismissDropDown()
                        Log.d(logTag, "Search box empty - suggestions cleared and dropdown dismissed")
                    }
                }

                clearSearchButton.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        searchAutoComplete.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                showSearchOverlay()
            }
        }

        clearSearchButton.setOnClickListener {
            Log.d(logTag, "Clear search button clicked")
            clearSearchState()
            Log.d(logTag, "Search cleared")
        }
    }

    private fun setupSearchOverlay() {
        searchContainer.setOnClickListener {
            showSearchOverlay()
        }

        backSearchButton.setOnClickListener {
            hideSearchOverlay()
        }
    }

    private fun setupLocationActionButtons() {
        val saveLocationButton = locationActionsContainer.findViewById<Button>(R.id.btn_save_location)
        val addDestinationButton = locationActionsContainer.findViewById<Button>(R.id.btn_add_destination)
        val addWaypointButton = locationActionsContainer.findViewById<Button>(R.id.btn_add_waypoint)
        val saveRouteButton = routeActionsContainer.findViewById<Button>(R.id.btn_save_route)
        val clearRouteButton = routeActionsContainer.findViewById<Button>(R.id.btn_clear_route)

        saveLocationButton?.setOnClickListener {
            onLocationActionRequested?.invoke("save_location")
        }

        addDestinationButton?.setOnClickListener {
            onLocationActionRequested?.invoke("add_destination")
        }

        addWaypointButton?.setOnClickListener {
            onLocationActionRequested?.invoke("add_waypoint")
        }

        saveRouteButton?.setOnClickListener {
            onLocationActionRequested?.invoke("save_route")
        }

        clearRouteButton?.setOnClickListener {
            onLocationActionRequested?.invoke("clear_route")
        }
    }

    private fun toggleRouteSummarySize() {
        if (isRouteSummaryExpanded) {
            // Collapse - just hide the details container, keep buttons visible
            routeExpandCollapseButton.setImageResource(android.R.drawable.ic_menu_more)
            routeDetailsContainer.visibility = View.GONE
            isRouteSummaryExpanded = false

            // Reset container to wrap_content so buttons stay visible
            val layoutParams = routeSummaryContainer.layoutParams
            layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            routeSummaryContainer.layoutParams = layoutParams

        } else {
            // Expand - show the details container
            val displayMetrics = context.resources.displayMetrics
            val screenHeight = displayMetrics.heightPixels
            val expandedHeight = (screenHeight * 0.80f).toInt()

            routeExpandCollapseButton.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            routeDetailsContainer.visibility = View.VISIBLE
            updateDetailedRouteView()
            isRouteSummaryExpanded = true

            // Set fixed height for expanded state
            val layoutParams = routeSummaryContainer.layoutParams
            layoutParams.height = expandedHeight
            routeSummaryContainer.layoutParams = layoutParams

            // Scroll to top of the route details after expansion
            routeDetailsContainer.post {
                routeDetailsContainer.scrollTo(0, 0)
            }
        }
    }

    // UPDATED: Use DbPlace data exclusively
    private fun updateDetailedRouteView() {
        val routeData = onRouteStopsDbDataRequest?.invoke()
        routeData?.let { (originDb, destinationDb, waypointsDb) ->
            currentOriginDbPlace = originDb
            currentDestinationDbPlace = destinationDb
            currentWaypointDbPlaces = waypointsDb

            populateRouteStopsContainerFromDbPlaces()
            updateWaypointPriceInputs(waypointsDb)
        }
    }

    private fun updateWaypointPriceInputs(waypoints: List<DbPlace>) {
        if (waypoints.isEmpty()) {
            waypointPricesContainer.visibility = View.GONE
            waypointPriceInputsContainer.removeAllViews()
            waypointPriceInputs.clear()
            return
        }

        waypointPricesContainer.visibility = View.VISIBLE

        // Store existing values before clearing
        val existingValues = waypointPriceInputs.map { it.text?.toString() ?: "" }

        // Rebuild inputs and toggles to reflect current flags
        val currentInputCount = waypointPriceInputs.size
        val requiredInputCount = waypoints.size

        when {
            requiredInputCount > currentInputCount -> {
                // Add new inputs for additional waypoints
                for (index in currentInputCount until requiredInputCount) {
                    val dbPlace = waypoints[index]
                    val row = createWaypointRow(dbPlace, index)
                    waypointPriceInputsContainer.addView(row)
                }
            }
            requiredInputCount < currentInputCount -> {
                // Remove excess inputs
                for (i in currentInputCount - 1 downTo requiredInputCount) {
                    waypointPriceInputsContainer.removeViewAt(i)
                    if (i < waypointPriceInputs.size) {
                        waypointPriceInputs.removeAt(i)
                    }
                }
            }
            // If counts are equal, just update labels if needed
            else -> {
                // Update existing input labels to match current waypoints
                waypoints.forEachIndexed { index, dbPlace ->
                    val row = waypointPriceInputsContainer.getChildAt(index) as? LinearLayout
                    val inputLayout = row?.findViewWithTag<com.google.android.material.textfield.TextInputLayout>("price_input_$index")
                    inputLayout?.hint = "Price for ${dbPlace.getName()}"
                    val toggle = row?.findViewWithTag<androidx.appcompat.widget.SwitchCompat>("passthrough_toggle_$index")
                    toggle?.isChecked = dbPlace.isPassThrough
                    // Hide price for pass-through
                    inputLayout?.visibility = if (dbPlace.isPassThrough) View.GONE else View.VISIBLE
                }
            }
        }

        // Restore existing values where possible
        existingValues.forEachIndexed { index, value ->
            if (index < waypointPriceInputs.size && value.isNotEmpty()) {
                waypointPriceInputs[index].setText(value)
            }
        }
    }

    private fun createWaypointRow(dbPlace: DbPlace, index: Int): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 16
            }
        }

        val toggle = androidx.appcompat.widget.SwitchCompat(context).apply {
            tag = "passthrough_toggle_$index"
            text = "Pass-through"
            isChecked = dbPlace.isPassThrough
            setOnCheckedChangeListener { _, isChecked ->
                dbPlace.isPassThrough = isChecked
                // Hide/show price input
                val input = row.findViewWithTag<com.google.android.material.textfield.TextInputLayout>("price_input_$index")
                input?.visibility = if (isChecked) View.GONE else View.VISIBLE
                // Route recompute handled by ManageRoute when route changes; here we can signal visually only
            }
        }

        val inputLayout = com.google.android.material.textfield.TextInputLayout(
            context, null, com.google.android.material.R.attr.textInputOutlinedStyle
        ).apply {
            tag = "price_input_$index"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            hint = "Price for ${dbPlace.getName()}"
            visibility = if (dbPlace.isPassThrough) View.GONE else View.VISIBLE
        }

        val editText = com.google.android.material.textfield.TextInputEditText(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            maxLines = 1
        }

        inputLayout.addView(editText)
        waypointPriceInputs.add(editText)

        row.addView(toggle)
        row.addView(inputLayout)
        return row
    }

    @SuppressLint("DefaultLocale")
    fun validateAndCreateFinalRoute(): FinalRoute? {
        // Get fresh route data instead of using cached references
        val routeData = onRouteStopsDbDataRequest?.invoke()
        val (origin, destination, waypoints) = routeData ?: Triple(null, null, emptyList())
        val route = currentRoute

        if (origin == null || destination == null || route == null) {
            CustomToast.showWarning(context, "Route data is incomplete")
            return null
        }

        // Validate required fields
        val routePriceText = etRoutePrice.text?.toString()?.trim()
        if (routePriceText.isNullOrEmpty()) {
            CustomToast.showWarning(context, "Route price is required")
            return null
        }

        val routePrice = routePriceText.toDoubleOrNull()
        if (routePrice == null || routePrice < 0) {
            CustomToast.showError(context, "Please enter a valid route price")
            return null
        }

        // Validate origin location data
        if (!origin.hasValidLocationData()) {
            val errorMessage = origin.getLocationValidationError()
                ?: "Origin location is missing district or province information"
            CustomToast.showWarning(context,"Cannot save route origin: $errorMessage")
            return null
        }

        // Validate destination location data
        if (!destination.hasValidLocationData()) {
            val errorMessage = destination.getLocationValidationError()
                ?: "Destination location is missing district or province information"
            CustomToast.showWarning(context,"Cannot save route dest: $errorMessage")
            return null
        }

        // Validate waypoint location data
        waypoints.forEachIndexed { index, waypoint ->
            if (!waypoint.hasValidLocationData()) {
                val errorMessage = waypoint.getLocationValidationError()
                    ?: "Waypoint location is missing district or province information"
                val waypointName = waypoint.getName()
                CustomToast.showWarning(context, "Cannot save route waypoint: '$waypointName' - $errorMessage")
                return null
            }
        }

        // Validate waypoint prices if waypoints exist
        val waypointPrices = mutableListOf<Double>()
        waypointPriceInputs.forEachIndexed { index, editText ->
            val priceText = editText.text?.toString()?.trim()
            if (priceText.isNullOrEmpty()) {
                val waypointName = waypoints.getOrNull(index)?.getName() ?: "Waypoint ${index + 1}"
                CustomToast.showWarning(context, "Please enter a price for $waypointName")
                return null
            }

            val price = priceText.toDoubleOrNull()
            if (price == null || price < 0) {
                val waypointName = waypoints.getOrNull(index)?.getName() ?: "Waypoint ${index + 1}"
                CustomToast.showWarning(context, "Please enter a valid price for $waypointName")
                return null
            }

            waypointPrices.add(price)
        }

        // Create waypoints with prices
        val waypointsWithPrices = waypoints.mapIndexed { index, dbPlace ->
            WaypointWithPrice(dbPlace, waypointPrices.getOrElse(index) { 0.0 })
        }

        // Get optional fields
        val routeName = etRouteName.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val isCityRoute = cbCityRoute.isChecked

        // Format distance and duration from current route
        val distanceText = if (route.lengthInMeters >= 1000) {
            String.format("%.1f km", route.lengthInMeters / 1000.0)
        } else {
            "${route.lengthInMeters.toInt()} m"
        }

        val durationText = formatDuration(route.duration.seconds)

        return FinalRoute(
            origin = origin,
            destination = destination,
            routePrice = routePrice,
            routeName = routeName,
            isCityRoute = isCityRoute,
            distance = distanceText,
            duration = durationText,
            waypoints = waypointsWithPrices
        )
    }

    // Add this helper method to show toast messages

    // UPDATED: Primary method for populating route stops using DbPlace exclusively
    private fun populateRouteStopsContainerFromDbPlaces() {
        routeStopsContainer.removeAllViews()

        // Add origin
        currentOriginDbPlace?.let { dbPlace ->
            addRouteStopViewFromDbPlace(dbPlace, "Origin", android.R.drawable.ic_menu_mylocation)
        }

        // Add waypoints
        currentWaypointDbPlaces.forEachIndexed { index, dbPlace ->
            addConnectorLine()
            addRouteStopViewFromDbPlace(dbPlace, "Waypoint ${index + 1}", android.R.drawable.ic_menu_add)
        }

        // Add destination
        if (currentDestinationDbPlace != null) {
            addConnectorLine()
            currentDestinationDbPlace?.let { dbPlace ->
                addRouteStopViewFromDbPlace(dbPlace, "Destination", android.R.drawable.ic_menu_directions)
            }
        }
    }

    // UPDATED: Enhanced to show custom names and districts from DbPlace
    private fun addRouteStopViewFromDbPlace(dbPlace: DbPlace, type: String, iconResource: Int) {
        val stopView = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(16, 12, 16, 12)
        }

        // Icon
        val icon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(32, 32).apply {
                setMargins(0, 0, 16, 0)
            }
            setImageResource(iconResource)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }

        // Text container
        val textContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        // UPDATED: Use DbPlace.getName() for custom names
        val nameText = TextView(context).apply {
            text = dbPlace.getName() // This will show custom name if set, otherwise original name
            textSize = 16f
            setTextColor(context.getColor(android.R.color.black))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        // UPDATED: Enhanced type label with district from DbPlace
        val typeText = TextView(context).apply {
            val typeWithDistrict = if (dbPlace.displayDistrict != null) {
                "$type • ${dbPlace.displayDistrict}"
            } else {
                type
            }
            text = typeWithDistrict
            textSize = 12f
            setTextColor(context.getColor(android.R.color.darker_gray))
        }

        textContainer.addView(typeText)
        textContainer.addView(nameText)

        stopView.addView(icon)
        stopView.addView(textContainer)

        routeStopsContainer.addView(stopView)
    }

    private fun addConnectorLine() {
        val connector = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(32, 0, 16, 0) // Align with icon center
        }

        val line = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                2,
                24
            )
            setBackgroundColor(context.getColor(android.R.color.darker_gray))
        }

        connector.addView(line)
        routeStopsContainer.addView(connector)
    }

    fun showSearchOverlay() {
        searchOverlay.visibility = View.VISIBLE
        searchAutoComplete.requestFocus()

        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(searchAutoComplete, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideSearchOverlay() {
        searchOverlay.visibility = View.GONE
        searchAutoComplete.clearFocus()
        searchAutoComplete.dismissDropDown()

        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(searchAutoComplete.windowToken, 0)
    }

    fun clearSearchState() {
        searchAutoComplete.setText("")
        searchAutoComplete.dismissDropDown()
        clearSearchButton.visibility = View.GONE
        Log.d(logTag, "Search state cleared")
    }

    // UPDATED: Now accepts only DbPlace, with optional compatibility for existing code
    fun showPlaceDetails(originalPlace: com.here.sdk.search.Place? = null, dbPlace: DbPlace) {
        // UPDATED: Use DbPlace exclusively, ignore originalPlace parameter
        val displayPlace = dbPlace

        // UPDATED: Use DbPlace.getName() to show custom names in place details
        tvPlaceTitle.text = displayPlace.getName() // Will show custom name if set

        // UPDATED: Show address from DbPlace
        tvPlaceAddress.text = displayPlace.address ?: displayPlace.customDistrict

        // UPDATED: Show district information if available
        val typeWithDistrict = displayPlace.areaType?.let { areaType ->
            if (displayPlace.displayDistrict != null) {
                "$areaType • ${displayPlace.displayDistrict}"
            } else {
                areaType
            }
        }

        typeWithDistrict?.let { typeText ->
            tvPlaceType.text = typeText
            tvPlaceType.visibility = View.VISIBLE
        } ?: run {
            tvPlaceType.visibility = View.GONE
        }

        placeDetailsContainer.visibility = View.VISIBLE
        searchContainer.visibility = View.GONE

        updateActionButtonsVisibility()
    }


    private fun updateActionButtonsVisibility() {
        val selectionState = onSelectionStateRequest?.invoke()
        val (isSelectingOrigin, isSelectingDestination, isSelectingWaypoint) = selectionState ?: Triple(true, false, false)

        val addDestinationButton = locationActionsContainer.findViewById<Button>(R.id.btn_add_destination)
        val addWaypointButton = locationActionsContainer.findViewById<Button>(R.id.btn_add_waypoint)

        when {
            isSelectingOrigin -> {
                locationActionsContainer.visibility = View.VISIBLE
                routeActionsContainer.visibility = View.GONE
                routeSummaryContainer.visibility = View.GONE

                addDestinationButton?.visibility = View.VISIBLE
                addWaypointButton?.visibility = View.GONE
            }

            isSelectingDestination -> {
                // Hide place details when destination is selected and route is created
                placeDetailsContainer.visibility = View.GONE
                locationActionsContainer.visibility = View.GONE
                routeActionsContainer.visibility = View.VISIBLE

                val routeData = onRouteDataRequest?.invoke()
                if (routeData?.first == true) {
                    showRouteSummary()
                }
            }

            isSelectingWaypoint -> {
                // Hide place details when adding waypoints
                placeDetailsContainer.visibility = View.GONE
                locationActionsContainer.visibility = View.GONE
                routeActionsContainer.visibility = View.VISIBLE
                routeSummaryContainer.visibility = View.VISIBLE
            }
        }
    }

    private fun showRouteSummary() {
        val routeData = onRouteDataRequest?.invoke()
        if (routeData?.first == true) {
            // Don't set restrictive height - let it wrap content to show buttons
            val layoutParams = routeSummaryContainer.layoutParams
            layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            routeSummaryContainer.layoutParams = layoutParams

            routeSummaryContainer.visibility = View.VISIBLE

            // Ensure route details are collapsed initially
            isRouteSummaryExpanded = false
            routeDetailsContainer.visibility = View.GONE
            routeExpandCollapseButton.setImageResource(android.R.drawable.ic_menu_more)
        }
    }

    @SuppressLint("DefaultLocale")
    fun updateRouteSummaryWithActualData(route: Route) {
        currentRoute = route

        (context as? android.app.Activity)?.runOnUiThread {
            val estimatedTravelTimeInSeconds = route.duration.seconds
            val lengthInMeters = route.lengthInMeters

            val distanceText = if (lengthInMeters >= 1000) {
                String.format("%.1f km", lengthInMeters / 1000.0)
            } else {
                "$lengthInMeters m"
            }

            val durationText = formatDuration(estimatedTravelTimeInSeconds)

            tvRouteDistance.text = distanceText
            tvRouteDuration.text = durationText

            // UPDATED: Update route summary text using DbPlace names
            updateRouteSummaryText()

            // Show route summary with proper minimal height
            showRouteSummary()

            Log.d(logTag, "Route summary updated: $distanceText, $durationText")
        }
    }

    // UPDATED: Use DbPlace.getName() for route summary text
    @SuppressLint("SetTextI18n")
    private fun updateRouteSummaryText() {
        val routeData = onRouteStopsDbDataRequest?.invoke()
        routeData?.let { (originDb, destinationDb, waypointsDb) ->

            // UPDATED: Use DbPlace.getName() to show custom names in route summary
            val originName = originDb?.getName() ?: "Unknown"
            val destinationName = destinationDb?.getName() ?: "Unknown"
            tvRouteOriginDestination.text = "$originName → $destinationName"

            // Update waypoint count
            if (waypointsDb.isNotEmpty()) {
                tvWaypointCount.text = "${waypointsDb.size} waypoint${if (waypointsDb.size > 1) "s" else ""}"
                tvWaypointCount.visibility = View.VISIBLE
            } else {
                tvWaypointCount.visibility = View.GONE
            }

            // Add these views to the route summary if not already added
            addRouteSummaryTextViews()
        }
    }

    private fun addRouteSummaryTextViews() {
        // Find the existing route summary layout and add our text views
        val existingLayout = routeSummaryContainer.getChildAt(0) as? LinearLayout
        val textContainer = existingLayout?.getChildAt(1) as? LinearLayout

        textContainer?.let { container ->
            // Check if our text views are already added
            if (container.findViewById<TextView>(R.id.tv_route_origin_destination) == null) {
                container.addView(tvRouteOriginDestination, 0)
            }
            if (container.findViewById<TextView>(R.id.tv_waypoint_count) == null) {
                container.addView(tvWaypointCount, 1)
            }
        }
    }

    private fun formatDuration(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60

        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "$minutes mins"
            else -> "< 1 min"
        }
    }

    fun switchToDestinationSelection() {
        clearSearchState()
        searchAutoComplete.hint = "Search for destination..."
        searchHintText.text = "Search for destination..."
        showSearchHideDetails()
    }

    fun switchToWaypointSelection() {
        clearSearchState()
        searchAutoComplete.hint = "Search for waypoint..."
        searchHintText.text = "Search for waypoint..."
        showSearchHideDetails()
    }

    private fun showSearchHideDetails() {
        searchContainer.visibility = View.VISIBLE
        placeDetailsContainer.visibility = View.GONE
        showSearchOverlay()
        searchAutoComplete.requestFocus()
    }

    fun resetToInitialState() {
        clearSearchState()
        searchAutoComplete.hint = "Search for origin location..."
        searchHintText.text = "Search for origin location..."

        searchContainer.visibility = View.VISIBLE
        placeDetailsContainer.visibility = View.GONE
        routeSummaryContainer.visibility = View.GONE
        routeActionsContainer.visibility = View.GONE
        hideSearchOverlay()

        // Reset route summary state
        isRouteSummaryExpanded = false
        routeDetailsContainer.visibility = View.GONE
        routeDetailsContainer.scrollTo(0, 0) // Reset scroll position

        // Reset layout params
        val layoutParams = routeSummaryContainer.layoutParams
        layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        routeSummaryContainer.layoutParams = layoutParams

        // UPDATED: Clear DbPlace references instead of Place objects
        currentOriginDbPlace = null
        currentDestinationDbPlace = null
        currentWaypointDbPlaces = emptyList()
        currentRoute = null

        // Clear form inputs
        etRouteName.setText("")
        etRoutePrice.setText("")
        cbCityRoute.isChecked = false
        waypointPricesContainer.visibility = View.GONE
        waypointPriceInputsContainer.removeAllViews()
        waypointPriceInputs.clear()

        Log.d(logTag, "UI reset to initial state")
    }
}