package com.gocavgo.entries.hereroutes

import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Filter
import android.widget.TextView
import com.gocavgo.entries.R
import com.gocavgo.entries.model.LocationIqResult
import com.gocavgo.entries.service.LocationIqClient
import com.gocavgo.entries.service.RemoteDataManager
import com.gocavgo.entries.service.RemoteResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SearchManager(private val context: Context) {

    private val TAG = SearchManager::class.java.simpleName
    private val remoteDataManager = RemoteDataManager.getInstance()
    private val searchScope = CoroutineScope(Dispatchers.Main)

    // LocationIQ client — initialized with token from BuildConfig
    private val locationIqClient: LocationIqClient by lazy {
        val token = try {
            val field = com.gocavgo.entries.BuildConfig::class.java.getField("LOCATIONIQ_API_KEY")
            field.get(null) as String
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get LOCATIONIQ_API_KEY from BuildConfig", e)
            ""
        }
        LocationIqClient(token)
    }

    // Search state
    val locationIqResults = arrayListOf<LocationIqResult>()
    var onDbPlaceSelected: ((DbPlace) -> Unit)? = null
    lateinit var suggestionsAdapter: ArrayAdapter<String>
    var isUserInput = true
    private var autoCompleteTextView: AutoCompleteTextView? = null

    // Debounce jobs — cancel previous search when a new keystroke arrives
    private var autocompleteJob: Job? = null
    private var dbSearchJob: Job? = null
    private val DEBOUNCE_MS = 350L

    // Callbacks
    var onPlaceSelected: ((LocationIqResult) -> Unit)? = null
    var onSearchError: ((String) -> Unit)? = null
    var onSuggestionsUpdated: ((List<String>) -> Unit)? = null
    private val dbSearchResults = arrayListOf<DbPlace>()

    init {
        initializeSuggestionsAdapter()
    }

    fun performDatabaseSearch(query: String) {
        Log.d(TAG, "Performing database search for: $query")

        dbSearchJob?.cancel()
        dbSearchJob = searchScope.launch {
            delay(DEBOUNCE_MS)
            try {
                when (val result = remoteDataManager.getLocations(search = query, limit = 10)) {
                    is RemoteResult.Success -> {
                        val dbPlaces = result.data.data
                        Log.d(TAG, "Database search returned ${dbPlaces.size} results")

                        if (dbPlaces.isNotEmpty()) {
                            val limitedDbPlaces = if (dbPlaces.size > 7) dbPlaces.take(7) else dbPlaces
                            val suggestionTitles = limitedDbPlaces.map { dbPlace -> formatDbPlaceSuggestion(dbPlace) }
                            updateDatabaseSuggestions(suggestionTitles, limitedDbPlaces)
                        } else {
                            clearSuggestions()
                        }
                    }
                    is RemoteResult.Error -> {
                        Log.e(TAG, "Database search error: ${result.message}")
                        onSearchError?.invoke("Database search failed: ${result.message}")
                        clearSuggestions()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during database search: ${e.message}", e)
                onSearchError?.invoke("Database search error: ${e.message}")
                clearSuggestions()
            }
        }
    }

    private fun updateDatabaseSuggestions(suggestionTitles: List<String>, dbPlaces: List<DbPlace>) {
        dbSearchResults.clear()
        dbSearchResults.addAll(dbPlaces)
        updateSuggestions(suggestionTitles)
    }

    private fun formatDbPlaceSuggestion(dbPlace: DbPlace): String {
        val originalName = dbPlace.placeName
        val customName = dbPlace.customName
        val district = dbPlace.displayDistrict

        return when {
            !customName.isNullOrBlank() && customName != originalName -> {
                if (district != null) "$customName - $district" else customName
            }
            else -> {
                if (district != null) "$originalName - $district" else originalName
            }
        }
    }

    private fun initializeSuggestionsAdapter() {
        suggestionsAdapter = object : ArrayAdapter<String>(
            context, R.layout.search_suggestion_item, arrayListOf<String>()
        ) {
            override fun getFilter(): Filter {
                return object : Filter() {
                    override fun performFiltering(constraint: CharSequence?): FilterResults {
                        val results = FilterResults()
                        val items = ArrayList<String>()
                        for (i in 0 until count) {
                            val item = getItem(i) ?: continue
                            items.add(item)
                        }
                        results.values = items
                        results.count = items.size
                        return results
                    }

                    override fun publishResults(constraint: CharSequence?, results: FilterResults) {
                        notifyDataSetChanged()
                    }
                }
            }

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                return createCustomView(position, convertView, parent)
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                return createCustomView(position, convertView, parent)
            }

            private fun createCustomView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: LayoutInflater.from(context)
                    .inflate(R.layout.search_suggestion_item, parent, false)
                val text = getItem(position) ?: ""
                val title = view.findViewById<TextView>(R.id.suggestion_title)
                val subtitle = view.findViewById<TextView>(R.id.suggestion_subtitle)

                val parts = text.split(" - ", limit = 2)
                title.text = parts[0].trim()
                if (parts.size > 1 && parts[1].isNotBlank()) {
                    subtitle.text = parts[1].trim()
                    subtitle.visibility = View.VISIBLE
                } else {
                    subtitle.visibility = View.GONE
                }

                return view
            }
        }
    }

    fun setupAutoCompleteTextView(searchAutoComplete: AutoCompleteTextView) {
        autoCompleteTextView = searchAutoComplete
        searchAutoComplete.setAdapter(suggestionsAdapter)
        searchAutoComplete.threshold = SearchManager.MIN_SEARCH_LENGTH

        searchAutoComplete.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && searchAutoComplete.text.isNotEmpty() && suggestionsAdapter.count > 0) {
                searchAutoComplete.showDropDown()
            }
        }

        searchAutoComplete.setOnClickListener {
            if (searchAutoComplete.text.isNotEmpty() && suggestionsAdapter.count > 0) {
                searchAutoComplete.showDropDown()
            }
        }

        searchAutoComplete.setOnItemClickListener { _, _, position, _ ->
            isUserInput = false

            // Check if we have database results
            if (dbSearchResults.isNotEmpty() && position < dbSearchResults.size) {
                val selectedDbPlace = dbSearchResults[position]
                val placeName = selectedDbPlace.getName()
                searchAutoComplete.setText(placeName)
                onDbPlaceSelected?.invoke(selectedDbPlace)
                clearSuggestions()
                searchAutoComplete.dismissDropDown()
            } else if (position < locationIqResults.size) {
                // LocationIQ result
                val result = locationIqResults[position]
                searchAutoComplete.setText(result.placeName)
                onPlaceSelected?.invoke(result)
                clearSuggestions()
                searchAutoComplete.dismissDropDown()
            }

            isUserInput = true
        }

        searchAutoComplete.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                searchAutoComplete.post {
                    if (suggestionsAdapter.count > 0) searchAutoComplete.showDropDown()
                }
                true
            } else false
        }
    }

    /**
     * Perform LocationIQ autocomplete search
     */
    fun performAutoSuggest(query: String) {
        autocompleteJob?.cancel()
        autocompleteJob = searchScope.launch {
            delay(DEBOUNCE_MS)
            val results = locationIqClient.autocomplete(query, limit = 10, countryCodes = "rw")
            locationIqResults.clear()
            locationIqResults.addAll(results)

            val titles = results.map { result ->
                val place = result.placeName
                val address = result.displayAddress
                if (address.isNotEmpty() && address != place) "$place - $address" else place
            }.filter { it.isNotEmpty() }

            updateSuggestions(titles)
        }
    }

    /**
     * Forward search via LocationIQ
     */
    fun performTextSearch(query: String) {
        searchScope.launch {
            val results = locationIqClient.search(query, limit = 10, countryCodes = "rw")
            if (results.isNotEmpty()) {
                onPlaceSelected?.invoke(results.first())
            } else {
                onSearchError?.invoke("No results found for '$query'")
            }
        }
    }

    private fun updateSuggestions(suggestionTitles: List<String>) {
        suggestionsAdapter.clear()
        if (suggestionTitles.isNotEmpty()) {
            suggestionsAdapter.addAll(suggestionTitles)
            onSuggestionsUpdated?.invoke(suggestionTitles)
            autoCompleteTextView?.let { showDropdown(it) }
        }
    }

    fun clearSuggestions() {
        autocompleteJob?.cancel()
        dbSearchJob?.cancel()
        locationIqResults.clear()
        dbSearchResults.clear()
        suggestionsAdapter.clear()
    }

    fun showDropdown(searchAutoComplete: AutoCompleteTextView) {
        if (suggestionsAdapter.count > 0) {
            // Re-run the adapter filter so the popup discards its stale
            // (empty) snapshot and repopulates from the freshly added data.
            if (!searchAutoComplete.isPopupShowing) {
                val text = searchAutoComplete.text?.toString() ?: ""
                suggestionsAdapter.filter.filter(text)
            }
            searchAutoComplete.showDropDown()
        } else {
            searchAutoComplete.dismissDropDown()
        }
    }

    companion object {
        const val MIN_SEARCH_LENGTH = 3
    }
}
