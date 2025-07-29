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
    private val btnStartNavigation: Button
) {
    private val TAG = UIController::class.java.simpleName

    // Callbacks
    var onLocationActionRequested: ((String) -> Unit)? = null
    var onSelectionStateRequest: (() -> Triple<Boolean, Boolean, Boolean>)? = null
    var onRouteDataRequest: (() -> Pair<Boolean, Boolean>)? = null
    var onAutoSuggestRequested: ((String) -> Unit)? = null  // NEW: For auto-suggestions only

    init {
        setupSearchOverlay()
        setupLocationActionButtons()
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
                        // FIXED: Call auto-suggest instead of direct search
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

            if (placeDetailsContainer.visibility != View.VISIBLE) {
                // Clear search markers through callback
                // This would need to be handled by the main activity
            }

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

        btnStartNavigation.setOnClickListener {
            onLocationActionRequested?.invoke("start_navigation")
        }
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
                locationActionsContainer.visibility = View.VISIBLE
                routeActionsContainer.visibility = View.VISIBLE
                routeSummaryContainer.visibility = View.GONE

                addDestinationButton?.visibility = View.GONE
                addWaypointButton?.visibility = View.VISIBLE

                val routeData = onRouteDataRequest?.invoke()
                if (routeData?.first == true) {
                    showRouteSummary()
                }
            }

            isSelectingWaypoint -> {
                locationActionsContainer.visibility = View.VISIBLE
                routeActionsContainer.visibility = View.VISIBLE
                routeSummaryContainer.visibility = View.VISIBLE
            }
        }
    }

    private fun showRouteSummary() {
        val routeData = onRouteDataRequest?.invoke()
        if (routeData?.first == true) {
            routeSummaryContainer.visibility = View.VISIBLE
        }
    }

    fun updateRouteSummaryWithActualData(route: Route) {
        (context as? android.app.Activity)?.runOnUiThread {
            val estimatedTravelTimeInSeconds = route.duration.seconds
            val lengthInMeters = route.lengthInMeters

            val distanceText = if (lengthInMeters >= 1000) {
                String.format("%.1f km", lengthInMeters / 1000.0)
            } else {
                "${lengthInMeters} m"
            }

            val durationText = formatDuration(estimatedTravelTimeInSeconds)

            tvRouteDistance.text = distanceText
            tvRouteDuration.text = durationText
            routeSummaryContainer.visibility = View.VISIBLE

            Log.d(TAG, "Route summary updated: $distanceText, $durationText")
        }
    }

    private fun formatDuration(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60

        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes} mins"
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
        routeSummaryContainer.visibility = View.GONE
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

        Log.d(TAG, "UI reset to initial state")
    }
}