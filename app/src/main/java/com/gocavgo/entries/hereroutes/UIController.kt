package com.gocavgo.entries.hereroutes

import android.annotation.SuppressLint
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.AutoCompleteTextView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.gocavgo.entries.R
import com.gocavgo.entries.model.CalcRouteResult

class UIController(
    private val context: Context,
    private val searchContainer: LinearLayout,
    private val searchOverlay: android.view.ViewGroup,
    private val placeDetailsContainer: LinearLayout,
    private val routeSummaryContainer: LinearLayout,
    private val routeActionsContainer: LinearLayout,
    private val locationActionsContainer: LinearLayout,
    private val searchAutoComplete: AutoCompleteTextView,
    private val clearSearchButton: ImageButton? = null,
    private val searchHintText: TextView,
    private val backSearchButton: ImageButton? = null,
    private val tvPlaceTitle: TextView,
    private val tvPlaceAddress: TextView,
    private val tvPlaceType: TextView,
    private val tvRouteDistance: TextView,
    private val tvRouteDuration: TextView,
) {
    private val logTag = UIController::class.java.simpleName

    // Inline place-edit form views (bottom menu shown on single tap of a marker)
    private lateinit var placeEditContainer: LinearLayout
    private lateinit var placeInfoBlock: android.view.View
    private lateinit var etPlaceCustomName: com.google.android.material.textfield.TextInputEditText
    private lateinit var etPlaceName: com.google.android.material.textfield.TextInputEditText
    private lateinit var spinnerPlaceDistrict: AutoCompleteTextView
    private lateinit var btnSavePlace: Button

    var onRouteStopsDbDataRequest: (() -> Triple<DbPlace?, DbPlace?, List<DbPlace>>)? = null
    private var currentOriginDbPlace: DbPlace? = null
    private var currentDestinationDbPlace: DbPlace? = null
    private var currentWaypointDbPlaces: List<DbPlace> = emptyList()

    // Route summary views
    private lateinit var routeDetailsContainer: ScrollView
    private lateinit var routeStopsContainer: LinearLayout
    private lateinit var tvRouteOriginDestination: TextView
    private lateinit var tvWaypointCount: TextView
    private var isRouteSummaryExpanded = false
    private lateinit var routeConfigurationContainer: LinearLayout
    private lateinit var databaseSearchToggle: androidx.appcompat.widget.SwitchCompat
    private var isDatabaseSearchEnabled = false
    private lateinit var etRouteName: com.google.android.material.textfield.TextInputEditText
    private lateinit var etRoutePrice: com.google.android.material.textfield.TextInputEditText
    private lateinit var cbCityRoute: android.widget.CheckBox
    private lateinit var waypointPricesContainer: LinearLayout
    private lateinit var waypointPriceInputsContainer: LinearLayout
    private val waypointPriceInputs = mutableListOf<com.google.android.material.textfield.TextInputEditText>()

    // Route data — now uses CalcRouteResult instead of HERE Route
    private var currentRouteResult: CalcRouteResult? = null

// Callbacks
    var onLocationActionRequested: ((String) -> Unit)? = null
    var onSelectionStateRequest: (() -> Triple<Boolean, Boolean, Boolean>)? = null
    var onDestinationSelectedRequest: (() -> Boolean)? = null
    var onRouteDataRequest: (() -> Pair<Boolean, Boolean>)? = null
    var onAutoSuggestRequested: ((String) -> Unit)? = null
    /** Called when the search FAB should show (true) or hide (false) */
    var onSearchFabVisibilityChanged: ((Boolean) -> Unit)? = null
    /** Called with edited custom name / place name / district when "Save Place" is tapped */
    var onPlaceEditSaveRequested: ((String?, String, String?) -> Unit)? = null
    /** Called when a marker-action menu button is tapped; action: "move", "remove", or "cancel_edit" */
    var onPlaceEditMenuActionRequested: ((String) -> Unit)? = null

    init {
        setupSearchOverlay()
        setupLocationActionButtons()
        setupEnhancedRouteSummary()
        setupDatabaseSearchToggle()
        setupPlaceEditForm()
    }

    private fun setupEnhancedRouteSummary() {
        routeDetailsContainer = routeSummaryContainer.findViewById(R.id.route_details_container)
            ?: createRouteDetailsContainer()
        routeStopsContainer = routeDetailsContainer.findViewById(R.id.route_stops_container)
            ?: createRouteStopsContainer()
        tvRouteOriginDestination = createRouteSummaryTextView()
        tvWaypointCount = createWaypointCountTextView()
        setupRouteConfiguration()

        setupRouteSummarySwipeGesture()
    }

    /** Swipe up on the bottom panel to expand; swipe down to collapse back. */
    private fun setupRouteSummarySwipeGesture() {
        val gestureDetector = android.view.GestureDetector(context, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val deltaY = e2.y - e1.y
                val absDeltaY = kotlin.math.abs(deltaY)
                val absDeltaX = kotlin.math.abs(e2.x - e1.x)
                if (absDeltaY < 100 || absDeltaY <= absDeltaX) return false
                if (deltaY < 0) expandRouteSummary() else collapseRouteSummary()
                return true
            }
        })
        routeSummaryContainer.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun expandRouteSummary() {
        val routeData = onRouteDataRequest?.invoke()
        if (routeData?.first != true) return
        routeSummaryContainer.visibility = View.VISIBLE
        routeDetailsContainer.visibility = View.VISIBLE
        updateDetailedRouteView()
        isRouteSummaryExpanded = true
        routeSummaryContainer.layoutParams.height = (context.resources.displayMetrics.heightPixels * 0.80f).toInt()
        routeSummaryContainer.layoutParams = routeSummaryContainer.layoutParams
        routeDetailsContainer.post { routeDetailsContainer.scrollTo(0, 0) }
    }

    private fun collapseRouteSummary() {
        routeDetailsContainer.visibility = View.GONE
        isRouteSummaryExpanded = false
        routeSummaryContainer.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        routeSummaryContainer.layoutParams = routeSummaryContainer.layoutParams
    }

    private fun setupRouteConfiguration() {
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
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(20, 20, 20, 20)
        }
        val innerContainer = routeDetailsContainer.getChildAt(0) as? LinearLayout
        if (innerContainer != null && innerContainer.childCount > 0) {
            innerContainer.addView(container, 1)
        } else {
            innerContainer?.addView(container)
        }
        return container
    }

    private fun setupDatabaseSearchToggle() {
        databaseSearchToggle = searchOverlay.findViewById(R.id.database_search_toggle)
        databaseSearchToggle.setOnCheckedChangeListener { _, isChecked ->
            isDatabaseSearchEnabled = isChecked
        }
    }

    fun isDatabaseSearchMode(): Boolean = isDatabaseSearchEnabled

    private fun createRouteDetailsContainer(): ScrollView {
        val container = ScrollView(context).apply {
            id = R.id.route_details_container
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            visibility = View.GONE
            isFillViewport = true
            scrollBarStyle = View.SCROLLBARS_OUTSIDE_OVERLAY
        }
        val innerContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        container.addView(innerContainer)
        routeSummaryContainer.addView(container)
        return container
    }

    private fun createRouteStopsContainer(): LinearLayout {
        val container = LinearLayout(context).apply {
            id = R.id.route_stops_container
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        (routeDetailsContainer.getChildAt(0) as? LinearLayout)?.addView(container)
        return container
    }

    private fun createRouteSummaryTextView(): TextView = TextView(context).apply {
        id = R.id.tv_route_origin_destination
        textSize = 14f
        setTextColor(context.getColor(R.color.ui_text_primary))
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun createWaypointCountTextView(): TextView = TextView(context).apply {
        id = R.id.tv_waypoint_count
        textSize = 12f
        setTextColor(context.getColor(R.color.ui_text_secondary))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    fun setupSearchFunctionality(searchManager: SearchManager) {
        searchManager.setupAutoCompleteTextView(searchAutoComplete)
        searchAutoComplete.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim() ?: ""
                if (searchManager.isUserInput) {
                    if (query.length >= SearchManager.MIN_SEARCH_LENGTH) onAutoSuggestRequested?.invoke(query)
                    else { searchManager.clearSuggestions(); searchAutoComplete.dismissDropDown() }
                }
                clearSearchButton?.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        searchAutoComplete.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) showSearchOverlay()
        }
        clearSearchButton?.setOnClickListener { clearSearchState() }
    }

    private fun setupSearchOverlay() {
        searchContainer.setOnClickListener { showSearchOverlay() }
        backSearchButton?.setOnClickListener { closeSearchOverlay() }
        // Tap scrim (outside the search card) closes the overlay
        searchOverlay.setOnClickListener { closeSearchOverlay() }
    }

    private fun setupLocationActionButtons() {
        locationActionsContainer.findViewById<Button>(R.id.btn_save_location)?.setOnClickListener { onLocationActionRequested?.invoke("save_location") }
        locationActionsContainer.findViewById<Button>(R.id.btn_add_destination)?.setOnClickListener { onLocationActionRequested?.invoke("add_destination") }
        locationActionsContainer.findViewById<Button>(R.id.btn_add_waypoint)?.setOnClickListener { onLocationActionRequested?.invoke("add_waypoint") }
        routeActionsContainer.findViewById<Button>(R.id.btn_save_route)?.setOnClickListener { onLocationActionRequested?.invoke("save_route") }
        routeActionsContainer.findViewById<Button>(R.id.btn_clear_route)?.setOnClickListener { onLocationActionRequested?.invoke("clear_route") }
    }

    private fun setupPlaceEditForm() {
        placeEditContainer = placeDetailsContainer.findViewById(R.id.place_edit_container)
            ?: createPlaceEditContainer()
        placeInfoBlock = placeDetailsContainer.findViewById(R.id.place_info_block)
            ?: placeDetailsContainer.getChildAt(0)
        etPlaceCustomName = placeEditContainer.findViewById(R.id.et_place_custom_name)
        etPlaceName = placeEditContainer.findViewById(R.id.et_place_name)
        spinnerPlaceDistrict = placeEditContainer.findViewById(R.id.spinner_place_district)
        btnSavePlace = placeEditContainer.findViewById(R.id.btn_save_place)

        val adapter = ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, DbPlace.RWANDAN_DISTRICTS)
        spinnerPlaceDistrict.setAdapter(adapter)
        spinnerPlaceDistrict.setOnClickListener { spinnerPlaceDistrict.showDropDown() }

        btnSavePlace.setOnClickListener {
            val customName = etPlaceCustomName.text?.toString()?.trim()
            val placeName = etPlaceName.text?.toString()?.trim()
            val district = spinnerPlaceDistrict.text?.toString()?.trim()
            val validDistrict = if (!district.isNullOrBlank() && DbPlace.RWANDAN_DISTRICTS.contains(district)) district else null
            if (placeName.isNullOrBlank()) {
                CustomToast.showWarning(context, "Place name is required")
            } else {
                onPlaceEditSaveRequested?.invoke(customName?.ifBlank { null }, placeName, validDistrict)
            }
        }

        val menuButton = placeEditContainer.findViewById<ImageButton>(R.id.btn_place_edit_menu)
        menuButton?.setOnClickListener {
            val options = arrayOf("Move", "Remove from map", "Cancel edit")
            androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle("Marker options")
                .setItems(options) { _, which ->
                    when (options[which]) {
                        "Move" -> onPlaceEditMenuActionRequested?.invoke("move")
                        "Remove from map" -> onPlaceEditMenuActionRequested?.invoke("remove")
                        "Cancel edit" -> onPlaceEditMenuActionRequested?.invoke("cancel_edit")
                    }
                }
                .setNegativeButton("Dismiss", null)
                .show()
        }
    }

    private fun createPlaceEditContainer(): LinearLayout {
        val container = LinearLayout(context).apply {
            id = R.id.place_edit_container
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(20, 20, 20, 20)
            visibility = View.GONE
        }
        placeDetailsContainer.addView(container)
        return container
    }

    /** Populates and shows the inline bottom edit form for the given place. */
    fun showPlaceEditForm(dbPlace: DbPlace) {
        onSearchFabVisibilityChanged?.invoke(false)
        etPlaceCustomName.setText(dbPlace.customName ?: "")
        etPlaceName.setText(dbPlace.placeName)
        spinnerPlaceDistrict.setText(dbPlace.customDistrict ?: dbPlace.displayDistrict ?: "", false)
        placeEditContainer.visibility = View.VISIBLE
        placeInfoBlock.visibility = View.GONE
        locationActionsContainer.visibility = View.GONE
        placeDetailsContainer.visibility = View.VISIBLE
        routeSummaryContainer.visibility = View.GONE
        routeActionsContainer.visibility = View.GONE
        searchContainer.visibility = View.GONE
        hideSearchOverlay()
        tvPlaceTitle.text = dbPlace.getName()
        tvPlaceAddress.text = dbPlace.address ?: dbPlace.customDistrict ?: ""
    }

    fun hidePlaceEditForm() {
        placeEditContainer.visibility = View.GONE
    }

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
        val existingValues = waypointPriceInputs.map { it.text?.toString() ?: "" }
        val currentInputCount = waypointPriceInputs.size
        val requiredInputCount = waypoints.size

        when {
            requiredInputCount > currentInputCount -> {
                for (index in currentInputCount until requiredInputCount) {
                    waypointPriceInputsContainer.addView(createWaypointRow(waypoints[index], index))
                }
            }
            requiredInputCount < currentInputCount -> {
                for (i in currentInputCount - 1 downTo requiredInputCount) {
                    waypointPriceInputsContainer.removeViewAt(i)
                    if (i < waypointPriceInputs.size) waypointPriceInputs.removeAt(i)
                }
            }
            else -> {
                waypoints.forEachIndexed { index, dbPlace ->
                    val row = waypointPriceInputsContainer.getChildAt(index) as? LinearLayout
                    row?.findViewWithTag<com.google.android.material.textfield.TextInputLayout>("price_input_$index")?.hint = "Price for ${dbPlace.getName()}"
                    row?.findViewWithTag<androidx.appcompat.widget.SwitchCompat>("passthrough_toggle_$index")?.isChecked = dbPlace.isPassThrough
                }
            }
        }
        existingValues.forEachIndexed { index, value ->
            if (index < waypointPriceInputs.size && value.isNotEmpty()) waypointPriceInputs[index].setText(value)
        }
    }

    private fun createWaypointRow(dbPlace: DbPlace, index: Int): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 16 }
        }
        val toggle = androidx.appcompat.widget.SwitchCompat(context).apply {
            tag = "passthrough_toggle_$index"
            text = "Pass-through"
            isChecked = dbPlace.isPassThrough
            setOnCheckedChangeListener { _, isChecked ->
                dbPlace.isPassThrough = isChecked
                row.findViewWithTag<com.google.android.material.textfield.TextInputLayout>("price_input_$index")?.visibility = if (isChecked) View.GONE else View.VISIBLE
            }
        }
        val inputLayout = com.google.android.material.textfield.TextInputLayout(context, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            tag = "price_input_$index"
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            hint = "Price for ${dbPlace.getName()}"
            visibility = if (dbPlace.isPassThrough) View.GONE else View.VISIBLE
        }
        val editText = com.google.android.material.textfield.TextInputEditText(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
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
        val routeData = onRouteStopsDbDataRequest?.invoke()
        val (origin, destination, waypoints) = routeData ?: Triple(null, null, emptyList())
        val routeResult = currentRouteResult

        val missing = mutableListOf<String>()
        if (origin == null && destination == null && waypoints.isEmpty()) {
            missing.add("No stops added - long-press the map or search to add an origin.")
        } else {
            if (origin == null) missing.add("Origin is not set")
            else if (!origin.hasCompleteRouteData) missing.add("Origin '${origin.getName()}': ${origin.getCompleteRouteDataError() ?: "missing custom name/district"}")
            if (destination == null) missing.add("Destination is not set")
            else if (!destination.hasCompleteRouteData) missing.add("Destination '${destination.getName()}': ${destination.getCompleteRouteDataError() ?: "missing custom name/district"}")
            waypoints.forEachIndexed { index, wp ->
                if (!wp.hasCompleteRouteData) missing.add("Waypoint ${index + 1} '${wp.getName()}': ${wp.getCompleteRouteDataError() ?: "missing custom name/district"}")
            }
        }
        if (origin != null && destination != null && routeResult?.route == null) {
            missing.add("Route has not been calculated yet")
        }

        val routePriceText = etRoutePrice.text?.toString()?.trim()
        if (routePriceText.isNullOrEmpty()) missing.add("Route price is required")
        else {
            val priceValue = routePriceText.toDoubleOrNull()
            if (priceValue == null || priceValue < 0) missing.add("Please enter a valid route price")
        }

        waypointPriceInputs.forEachIndexed { index, editText ->
            val priceText = editText.text?.toString()?.trim()
            val wpName = waypoints.getOrNull(index)?.getName() ?: "waypoint ${index + 1}"
            if (priceText.isNullOrEmpty()) missing.add("Price missing for $wpName")
            else {
                val priceValue = priceText.toDoubleOrNull()
                if (priceValue == null || priceValue < 0) missing.add("Invalid price for $wpName")
            }
        }

        if (missing.isNotEmpty()) {
            androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle("Cannot save route")
                .setMessage("The route is incomplete:\n\n" + missing.joinToString("\n") { "• $it" })
                .setPositiveButton("OK", null)
                .show()
            return null
        }

        val finalOrigin = origin ?: return null
        val finalDestination = destination ?: return null
        val finalRoute = routeResult?.route ?: return null
        val routePrice = routePriceText?.toDoubleOrNull() ?: 0.0

        val waypointPrices = mutableListOf<Double>()
        waypointPriceInputs.forEachIndexed { index, editText ->
            waypointPrices.add(editText.text?.toString()?.trim()?.toDoubleOrNull() ?: 0.0)
        }

        val waypointsWithPrices = waypoints.mapIndexed { index, dbPlace -> WaypointWithPrice(dbPlace, waypointPrices.getOrElse(index) { 0.0 }) }
        val distanceText = if (finalRoute.totalDistance >= 1000) String.format("%.1f km", finalRoute.totalDistance / 1000.0) else "${finalRoute.totalDistance.toInt()} m"
        val durationText = formatDuration(finalRoute.totalDuration.toLong())

        return FinalRoute(
            origin = finalOrigin,
            destination = finalDestination,
            routePrice = routePrice,
            routeName = etRouteName.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            isCityRoute = cbCityRoute.isChecked,
            distance = distanceText,
            duration = durationText,
            waypoints = waypointsWithPrices
        )
    }

    private fun populateRouteStopsContainerFromDbPlaces() {
        routeStopsContainer.removeAllViews()
        currentOriginDbPlace?.let { addRouteStopViewFromDbPlace(it, "Origin", android.R.drawable.ic_menu_mylocation) }
        currentWaypointDbPlaces.forEachIndexed { index, dbPlace ->
            addConnectorLine()
            addRouteStopViewFromDbPlace(dbPlace, "Waypoint ${index + 1}", android.R.drawable.ic_menu_add)
        }
        if (currentDestinationDbPlace != null) {
            addConnectorLine()
            currentDestinationDbPlace?.let { addRouteStopViewFromDbPlace(it, "Destination", android.R.drawable.ic_menu_directions) }
        }
    }

    private fun addRouteStopViewFromDbPlace(dbPlace: DbPlace, type: String, iconResource: Int) {
        val stopView = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(16, 12, 16, 12)
        }
        val icon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(32, 32).apply { setMargins(0, 0, 16, 0) }
            setImageResource(iconResource)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        val textContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val nameText = TextView(context).apply {
            text = dbPlace.getName()
            textSize = 16f
            setTextColor(context.getColor(R.color.ui_text_primary))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val typeText = TextView(context).apply {
            text = if (dbPlace.displayDistrict != null) "$type • ${dbPlace.displayDistrict}" else type
            textSize = 12f
            setTextColor(context.getColor(R.color.ui_text_secondary))
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
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(32, 0, 16, 0)
        }
        val line = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(2, 24)
            setBackgroundColor(context.getColor(R.color.ui_divider))
        }
        connector.addView(line)
        routeStopsContainer.addView(connector)
    }

    fun showSearchOverlay() {
        searchOverlay.visibility = View.VISIBLE
        onSearchFabVisibilityChanged?.invoke(false)
        searchAutoComplete.requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(searchAutoComplete, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideSearchOverlay() {
        searchOverlay.visibility = View.GONE
        onSearchFabVisibilityChanged?.invoke(true)
        searchAutoComplete.clearFocus()
        searchAutoComplete.dismissDropDown()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(searchAutoComplete.windowToken, 0)
    }

    fun clearSearchState() {
        searchAutoComplete.setText("")
        searchAutoComplete.dismissDropDown()
        clearSearchButton?.visibility = View.GONE
    }

    /**
     * User-initiated dismissal of the search overlay. If a location is already
     * placed on the map, the bottom bar (place details / route summary) is
     * restored instead of leaving only the search FAB.
     */
    fun closeSearchOverlay() {
        clearSearchState()
        hideSearchOverlay()
        restorePlaceDetailsAfterSearchClose()
    }

    private fun restorePlaceDetailsAfterSearchClose() {
        val (originDb, destinationDb, waypointsDb) = onRouteStopsDbDataRequest?.invoke() ?: Triple(null, null, emptyList())
        val selection = onSelectionStateRequest?.invoke() ?: Triple(true, false, false)
        val (isSelectingOrigin, isSelectingDestination, isSelectingWaypoint) = selection

        when {
            // Full route built → restore route summary / route actions
            originDb != null && destinationDb != null -> updateActionButtonsVisibility()
            // Selecting destination with origin already set → show origin popup
            isSelectingDestination && originDb != null && destinationDb == null -> showPlaceDetails(originDb)
            // Selecting waypoint → restore route actions
            isSelectingWaypoint && (originDb != null || destinationDb != null) -> updateActionButtonsVisibility()
            // Only a single location placed
            originDb != null && destinationDb == null -> showPlaceDetails(originDb)
            destinationDb != null && originDb == null -> showPlaceDetails(destinationDb)
            // Nothing selected → keep the empty search-only state
        }
    }

    fun showPlaceDetails(dbPlace: DbPlace) {
        onSearchFabVisibilityChanged?.invoke(false)
        tvPlaceTitle.text = dbPlace.getName()
        tvPlaceAddress.text = dbPlace.address ?: dbPlace.customDistrict
        placeInfoBlock.visibility = View.VISIBLE
        val typeWithDistrict = dbPlace.areaType?.let { areaType ->
            if (dbPlace.displayDistrict != null) "$areaType • ${dbPlace.displayDistrict}" else areaType
        }
        typeWithDistrict?.let { tvPlaceType.text = it; tvPlaceType.visibility = View.VISIBLE }
            ?: run { tvPlaceType.visibility = View.GONE }
        placeDetailsContainer.visibility = View.VISIBLE
        searchContainer.visibility = View.GONE
        hideSearchOverlay()
        updateActionButtonsVisibility()
    }

    fun updateActionButtonsVisibility() {
        searchContainer.visibility = View.GONE
        if (::placeEditContainer.isInitialized) {
            placeEditContainer.visibility = View.GONE
            placeInfoBlock.visibility = View.VISIBLE
        }
        val selectionState = onSelectionStateRequest?.invoke()
        val (isSelectingOrigin, isSelectingDestination, isSelectingWaypoint) = selectionState ?: Triple(true, false, false)
        val destinationSelected = onDestinationSelectedRequest?.invoke() ?: false
        val addDestinationButton = locationActionsContainer.findViewById<Button>(R.id.btn_add_destination)
        val addWaypointButton = locationActionsContainer.findViewById<Button>(R.id.btn_add_waypoint)

        val showDestinationButton = isSelectingDestination && !destinationSelected

        when {
            isSelectingOrigin -> {
                locationActionsContainer.visibility = View.VISIBLE
                routeActionsContainer.visibility = View.GONE
                routeSummaryContainer.visibility = View.GONE
                addDestinationButton?.visibility = View.GONE
                addWaypointButton?.visibility = View.GONE
            }
            isSelectingDestination -> {
                val routeData = onRouteDataRequest?.invoke()
                val hasRoute = routeData?.first == true
                if (hasRoute) {
                    placeDetailsContainer.visibility = View.GONE
                    locationActionsContainer.visibility = View.GONE
                    routeActionsContainer.visibility = View.VISIBLE
                    showRouteSummary()
                    updateSaveRouteButtonState()
                } else {
                    placeDetailsContainer.visibility = View.VISIBLE
                    locationActionsContainer.visibility = View.VISIBLE
                    routeActionsContainer.visibility = View.GONE
                    routeSummaryContainer.visibility = View.GONE
                    addDestinationButton?.visibility = if (showDestinationButton) View.VISIBLE else View.GONE
                    addWaypointButton?.visibility = View.GONE
                }
            }
            isSelectingWaypoint -> {
                placeDetailsContainer.visibility = View.GONE
                locationActionsContainer.visibility = View.GONE
                routeActionsContainer.visibility = View.VISIBLE
                routeSummaryContainer.visibility = View.VISIBLE
                updateSaveRouteButtonState()
            }
        }
    }

    /** Leaves the Save Route button clickable but dimmed until every stop has custom name + district data. */
    private fun updateSaveRouteButtonState() {
        val saveButton = routeActionsContainer.findViewById<Button>(R.id.btn_save_route) ?: return
        val routeData = onRouteStopsDbDataRequest?.invoke()
        val (origin, destination, waypoints) = routeData ?: Triple(null, null, emptyList())
        val allStops = listOfNotNull(origin, destination) + waypoints
        val allComplete = allStops.isNotEmpty() && allStops.all { it.hasCompleteRouteData }
        saveButton.isEnabled = true
        saveButton.alpha = if (allComplete) 1f else 0.5f
        saveButton.text = if (allComplete) "Save" else "Save (incomplete)"
    }

    private fun showRouteSummary() {
        val routeData = onRouteDataRequest?.invoke()
        if (routeData?.first == true) {
            routeSummaryContainer.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            routeSummaryContainer.layoutParams = routeSummaryContainer.layoutParams
            routeSummaryContainer.visibility = View.VISIBLE
            isRouteSummaryExpanded = false
            routeDetailsContainer.visibility = View.GONE
        }
    }

    @SuppressLint("DefaultLocale")
    fun updateRouteSummaryWithActualData(routeResult: CalcRouteResult) {
        currentRouteResult = routeResult
        val route = routeResult.route ?: return

        (context as? android.app.Activity)?.runOnUiThread {
            val distanceText = if (route.totalDistance >= 1000) String.format("%.1f km", route.totalDistance / 1000.0) else "${route.totalDistance.toInt()} m"
            val durationText = formatDuration(route.totalDuration.toLong())

            tvRouteDistance.text = distanceText
            tvRouteDuration.text = durationText
            updateRouteSummaryText()
            showRouteSummary()
            updateSaveRouteButtonState()
        }
    }

    /** Re-evaluates the Save Route button enabled state (call after stops change). */
    fun refreshSaveRouteButtonState() { updateSaveRouteButtonState() }

    @SuppressLint("SetTextI18n")
    private fun updateRouteSummaryText() {
        val routeData = onRouteStopsDbDataRequest?.invoke()
        routeData?.let { (originDb, destinationDb, waypointsDb) ->
            tvRouteOriginDestination.text = "${originDb?.getName() ?: "Unknown"} → ${destinationDb?.getName() ?: "Unknown"}"
            if (waypointsDb.isNotEmpty()) {
                tvWaypointCount.text = "${waypointsDb.size} waypoint${if (waypointsDb.size > 1) "s" else ""}"
                tvWaypointCount.visibility = View.VISIBLE
            } else {
                tvWaypointCount.visibility = View.GONE
            }
            addRouteSummaryTextViews()
        }
    }

    private fun addRouteSummaryTextViews() {
        val existingLayout = routeSummaryContainer.getChildAt(0) as? LinearLayout
        val textContainer = existingLayout?.getChildAt(1) as? LinearLayout
        textContainer?.let { container ->
            if (container.findViewById<TextView>(R.id.tv_route_origin_destination) == null) container.addView(tvRouteOriginDestination, 0)
            if (container.findViewById<TextView>(R.id.tv_waypoint_count) == null) container.addView(tvWaypointCount, 1)
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

    fun switchToDestinationSelection() { switchToDestinationSelectionSilently(); showSearchHideDetails() }
    fun switchToDestinationSelectionSilently() { clearSearchState(); searchAutoComplete.hint = "Search destination"; searchHintText.text = "Search destination" }
    fun switchToWaypointSelection() { clearSearchState(); searchAutoComplete.hint = "Search for waypoint..."; searchHintText.text = "Search for waypoint..."; showSearchHideDetails() }

    private fun showSearchHideDetails() {
        searchContainer.visibility = View.VISIBLE
        placeDetailsContainer.visibility = View.GONE
        showSearchOverlay()
        searchAutoComplete.requestFocus()
    }

    fun resetToInitialState() {
        clearSearchState()
        onSearchFabVisibilityChanged?.invoke(true)
        searchAutoComplete.hint = "Search pickup location"
        searchHintText.text = "Search pickup location"
        searchContainer.visibility = View.VISIBLE
        placeDetailsContainer.visibility = View.GONE
        if (::placeEditContainer.isInitialized) placeEditContainer.visibility = View.GONE
        routeSummaryContainer.visibility = View.GONE
        routeActionsContainer.visibility = View.GONE
        hideSearchOverlay()
        isRouteSummaryExpanded = false
        routeDetailsContainer.visibility = View.GONE
        routeDetailsContainer.scrollTo(0, 0)
        routeSummaryContainer.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        routeSummaryContainer.layoutParams = routeSummaryContainer.layoutParams
        currentOriginDbPlace = null
        currentDestinationDbPlace = null
        currentWaypointDbPlaces = emptyList()
        currentRouteResult = null
        etRouteName.setText("")
        etRoutePrice.setText("")
        cbCityRoute.isChecked = false
        waypointPricesContainer.visibility = View.GONE
        waypointPriceInputsContainer.removeAllViews()
        waypointPriceInputs.clear()
    }
}
