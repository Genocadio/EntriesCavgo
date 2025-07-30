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
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.view.ViewGroup
import com.gocavgo.entries.R
import com.here.sdk.search.Place
import com.here.sdk.routing.Route
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
    private val TAG = UIController::class.java.simpleName

    // Route summary views
    private lateinit var routeExpandCollapseButton: ImageButton
    private lateinit var routeDetailsContainer: LinearLayout
    private lateinit var routeStopsContainer: LinearLayout
    private lateinit var tvRouteOriginDestination: TextView
    private lateinit var tvWaypointCount: TextView
    private var isRouteSummaryExpanded = false

    // Route data for detailed view
    private var currentOriginPlace: Place? = null
    private var currentDestinationPlace: Place? = null
    private var currentWaypointPlaces: List<Place> = emptyList()
    private var currentRoute: Route? = null

    // Callbacks
    var onLocationActionRequested: ((String) -> Unit)? = null
    var onSelectionStateRequest: (() -> Triple<Boolean, Boolean, Boolean>)? = null
    var onRouteDataRequest: (() -> Pair<Boolean, Boolean>)? = null
    var onAutoSuggestRequested: ((String) -> Unit)? = null
    var onRouteStopsDataRequest: (() -> Triple<Place?, Place?, List<Place>>)? = null

    init {
        setupSearchOverlay()
        setupLocationActionButtons()
        setupEnhancedRouteSummary()
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

        routeExpandCollapseButton.setOnClickListener {
            toggleRouteSummarySize()
        }
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

    private fun createRouteDetailsContainer(): LinearLayout {
        val container = LinearLayout(context).apply {
            id = R.id.route_details_container
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            visibility = View.GONE
        }

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

        routeDetailsContainer.addView(container)
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
                        Log.d(TAG, "Performing auto-suggest for: $query")
                        onAutoSuggestRequested?.invoke(query)
                    } else if (query.isEmpty()) {
                        searchManager.clearSuggestions()
                        searchAutoComplete.dismissDropDown()
                        Log.d(TAG, "Search box empty - suggestions cleared and dropdown dismissed")
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
            Log.d(TAG, "Clear search button clicked")
            clearSearchState()
            Log.d(TAG, "Search cleared")
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
        val displayMetrics = context.resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels

        if (isRouteSummaryExpanded) {
            // Collapse to minimal size (10% of screen height)
            val minimalHeight = (screenHeight * 0.10f).toInt()
            animateRouteSummaryHeight(minimalHeight)
            routeExpandCollapseButton.setImageResource(android.R.drawable.ic_menu_more)
            routeDetailsContainer.visibility = View.GONE
            isRouteSummaryExpanded = false
        } else {
            // Expand to 70% of screen
            animateRouteSummaryHeight((screenHeight * 0.7f).toInt())
            routeExpandCollapseButton.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            routeDetailsContainer.visibility = View.VISIBLE
            updateDetailedRouteView()
            isRouteSummaryExpanded = true
        }
    }

    private fun animateRouteSummaryHeight(targetHeight: Int) {
        val currentHeight = routeSummaryContainer.height
        val animator = ValueAnimator.ofInt(currentHeight, targetHeight)

        animator.addUpdateListener { animation ->
            val animatedValue = animation.animatedValue as Int
            val layoutParams = routeSummaryContainer.layoutParams
            layoutParams.height = animatedValue
            routeSummaryContainer.layoutParams = layoutParams
        }

        animator.duration = 300
        animator.start()
    }

    private fun updateDetailedRouteView() {
        val routeData = onRouteStopsDataRequest?.invoke()
        routeData?.let { (origin, destination, waypoints) ->
            currentOriginPlace = origin
            currentDestinationPlace = destination
            currentWaypointPlaces = waypoints

            populateRouteStopsContainer()
        }
    }

    private fun populateRouteStopsContainer() {
        routeStopsContainer.removeAllViews()

        // Add origin
        currentOriginPlace?.let { place ->
            addRouteStopView(place, "Origin", android.R.drawable.ic_menu_mylocation)
        }

        // Add waypoints
        currentWaypointPlaces.forEachIndexed { index, place ->
            addConnectorLine()
            addRouteStopView(place, "Waypoint ${index + 1}", android.R.drawable.ic_menu_add)
        }

        // Add destination
        if (currentDestinationPlace != null) {
            addConnectorLine()
            currentDestinationPlace?.let { place ->
                addRouteStopView(place, "Destination", android.R.drawable.ic_menu_directions)
            }
        }
    }

    private fun addRouteStopView(place: Place, type: String, iconResource: Int) {
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

        // Place name
        val nameText = TextView(context).apply {
            text = place.title
            textSize = 16f
            setTextColor(context.getColor(android.R.color.black))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        // Type label
        val typeText = TextView(context).apply {
            text = type
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
        Log.d(TAG, "Search state cleared")
    }

    fun showPlaceDetails(place: Place) {
        tvPlaceTitle.text = place.title
        tvPlaceAddress.text = place.address.addressText

        place.areaType?.let { areaType ->
            tvPlaceType.text = areaType.toString()
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
                // FIXED: Hide place details when destination is selected and route is created
                placeDetailsContainer.visibility = View.GONE
                locationActionsContainer.visibility = View.GONE
                routeActionsContainer.visibility = View.VISIBLE

                val routeData = onRouteDataRequest?.invoke()
                if (routeData?.first == true) {
                    showRouteSummary()
                }
            }

            isSelectingWaypoint -> {
                // FIXED: Hide place details when adding waypoints
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
            // FIXED: Set initial height to 10% of screen and ensure it stays minimized
            val displayMetrics = context.resources.displayMetrics
            val screenHeight = displayMetrics.heightPixels
            val minimalHeight = (screenHeight * 0.10f).toInt()

            val layoutParams = routeSummaryContainer.layoutParams
            layoutParams.height = minimalHeight
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

            // Update route summary text
            updateRouteSummaryText()

            // FIXED: Show route summary with proper minimal height
            showRouteSummary()

            Log.d(TAG, "Route summary updated: $distanceText, $durationText")
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateRouteSummaryText() {
        val routeData = onRouteStopsDataRequest?.invoke()
        routeData?.let { (origin, destination, waypoints) ->

            // Update origin → destination text
            val originName = origin?.title ?: "Unknown"
            val destinationName = destination?.title ?: "Unknown"
            tvRouteOriginDestination.text = "$originName → $destinationName"

            // Update waypoint count
            if (waypoints.isNotEmpty()) {
                tvWaypointCount.text = "${waypoints.size} waypoint${if (waypoints.size > 1) "s" else ""}"
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
        currentOriginPlace = null
        currentDestinationPlace = null
        currentWaypointPlaces = emptyList()
        currentRoute = null

        Log.d(TAG, "UI reset to initial state")
    }
}