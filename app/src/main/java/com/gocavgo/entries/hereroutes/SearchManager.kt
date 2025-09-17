package com.gocavgo.entries.hereroutes

import android.content.Context
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Filter
import com.here.sdk.core.GeoCoordinates
import com.here.sdk.core.LanguageCode
import com.here.sdk.core.errors.InstantiationErrorException
import com.here.sdk.search.Place
import com.here.sdk.search.SearchCallback
import com.here.sdk.search.SearchEngine
import com.here.sdk.search.SearchError
import com.here.sdk.search.SearchOptions
import com.here.sdk.search.SuggestCallback
import com.here.sdk.search.Suggestion
import com.here.sdk.search.TextQuery
import com.gocavgo.entries.service.RemoteDataManager
import com.gocavgo.entries.service.RemoteResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SearchManager(private val context: Context) {

    private lateinit var searchEngine: SearchEngine
    private val TAG = SearchManager::class.java.simpleName
    private val remoteDataManager = RemoteDataManager.getInstance()
    private val searchScope = CoroutineScope(Dispatchers.Main)

    // Search related variables
    val suggestions = arrayListOf<Suggestion>()
    var onDbPlaceSelected: ((DbPlace) -> Unit)? = null
    lateinit var suggestionsAdapter: ArrayAdapter<String>
    var isUserInput = true

    // Callbacks
    var onPlaceSelected: ((Place) -> Unit)? = null
    var onSearchError: ((String) -> Unit)? = null
    var onSuggestionsUpdated: ((List<String>) -> Unit)? = null
    private val dbSearchResults = arrayListOf<DbPlace>()


    init {
        initializeSearchEngine()
        initializeSuggestionsAdapter()
    }

    fun performDatabaseSearch(query: String) {
        Log.d(TAG, "Performing database search for: $query")

        searchScope.launch {
            try {
                // Request up to 10 results but we'll limit display to 7
                when (val result = remoteDataManager.getLocations(search = query, limit = 10)) {
                    is RemoteResult.Success -> {
                        val dbPlaces = result.data.data
                        Log.d(TAG, "Database search returned ${dbPlaces.size} results")

                        if (dbPlaces.isNotEmpty()) {
                            // Limit to 7 suggestions max, or show all if fewer than 7
                            val limitedDbPlaces = if (dbPlaces.size > 7) {
                                dbPlaces.take(7)
                            } else {
                                dbPlaces
                            }

                            // Convert DbPlace results to display format with improved formatting
                            val suggestionTitles = limitedDbPlaces.map { dbPlace ->
                                formatDbPlaceSuggestion(dbPlace)
                            }

                            Log.d(TAG, "Displaying ${suggestionTitles.size} suggestions: $suggestionTitles")

                            // Store DbPlaces in a way that can be retrieved by index
                            updateDatabaseSuggestions(suggestionTitles, limitedDbPlaces)

                        } else {
                            Log.d(TAG, "No database results found for: $query")
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

    /**
     * Format DbPlace for display in suggestions with clear distinction between custom and original names
     */
    private fun formatDbPlaceSuggestion(dbPlace: DbPlace): String {
        val originalName = dbPlace.placeName
        val customName = dbPlace.customName
        val district = dbPlace.displayDistrict

        return when {
            // If there's a custom name and it's different from the original
            !customName.isNullOrBlank() && customName != originalName -> {
                if (district != null) {
                    "$customName - $district"
                } else {
                    customName
                }
            }
            // If no custom name or custom name is same as original, show Google place name
            else -> {
                if (district != null) {
                    "$originalName - $district"
                } else {
                    originalName
                }
            }
        }
    }

    private fun initializeSearchEngine() {
        try {
            searchEngine = SearchEngine()
        } catch (e: InstantiationErrorException) {
            throw RuntimeException("Initialization of SearchEngine failed: " + e.error.name)
        }
    }

    private fun initializeSuggestionsAdapter() {
        suggestionsAdapter = object : ArrayAdapter<String>(
            context,
            android.R.layout.simple_dropdown_item_1line,
            arrayListOf<String>()
        ) {
            override fun getFilter(): Filter {
                return object : Filter() {
                    override fun performFiltering(constraint: CharSequence?): FilterResults {
                        // Don't filter - return all items as we handle filtering ourselves
                        val results = FilterResults()
                        val allItems = mutableListOf<String>()
                        for (i in 0 until count) {
                            getItem(i)?.let { allItems.add(it) }
                        }
                        results.values = allItems
                        results.count = allItems.size
                        return results
                    }

                    @Suppress("UNCHECKED_CAST")
                    override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                        if (results?.count ?: 0 > 0) {
                            notifyDataSetChanged()
                        } else {
                            notifyDataSetInvalidated()
                        }
                    }
                }
            }
        }
    }

    fun setupAutoCompleteTextView(searchAutoComplete: AutoCompleteTextView) {
        searchAutoComplete.setAdapter(suggestionsAdapter)
        searchAutoComplete.threshold = 1

        // Keep suggestions visible when focused
        searchAutoComplete.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus && searchAutoComplete.text.isNotEmpty() && suggestionsAdapter.count > 0) {
                searchAutoComplete.showDropDown()
            }
        }

        // Show dropdown when clicking on the field
        searchAutoComplete.setOnClickListener {
            if (searchAutoComplete.text.isNotEmpty() && suggestionsAdapter.count > 0) {
                searchAutoComplete.showDropDown()
            }
        }

        // Handle item selection from suggestions
        searchAutoComplete.setOnItemClickListener { parent, view, position, id ->
            Log.d(TAG, "Suggestion item clicked at position: $position")

            isUserInput = false

            // Check if we're in database search mode and have database results
            if (dbSearchResults.isNotEmpty() && position < dbSearchResults.size) {
                val selectedDbPlace = dbSearchResults[position]
                // Use getName() which returns custom name if available, otherwise original name
                val placeName = selectedDbPlace.getName()

                Log.d(TAG, "Selected database place: $placeName ${selectedDbPlace.id}")
                Log.d(TAG, "Selected database place ID: ${selectedDbPlace.id}")
                searchAutoComplete.setText(placeName)

                // Invoke database place selection callback
                onDbPlaceSelected?.invoke(selectedDbPlace)

                // Clear suggestions and dropdown
                clearSuggestions()
                searchAutoComplete.dismissDropDown()
                Log.d(TAG, "Database place selected - suggestions cleared")

            } else if (position < suggestions.size) {
                // Original HERE SDK suggestion handling
                val selectedSuggestion = suggestions[position]
                val suggestionTitle = selectedSuggestion.title

                Log.d(TAG, "Selected HERE suggestion: $suggestionTitle")
                searchAutoComplete.setText(suggestionTitle)

                selectedSuggestion.place?.let { place ->
                    place.geoCoordinates?.let { coordinates ->
                        Log.d(TAG, "Place selected from HERE suggestion: ${place.title} at $coordinates")
                        onPlaceSelected?.invoke(place)
                    }
                } ?: run {
                    Log.d(TAG, "No place in HERE suggestion, performing text search")
                    performTextSearch(suggestionTitle, searchAutoComplete)
                }

                clearSuggestions()
                searchAutoComplete.dismissDropDown()
                Log.d(TAG, "HERE suggestion selected - suggestions cleared")

            } else {
                Log.w(TAG, "Invalid suggestion position: $position")
            }

            isUserInput = true
        }

        // Handle search action
        searchAutoComplete.setOnEditorActionListener { textView, actionId, event ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                val query = searchAutoComplete.text.toString().trim()
                Log.d(TAG, "Search action triggered with query: $query")

                // Hide keyboard but keep suggestions visible
                searchAutoComplete.post {
                    if (suggestionsAdapter.count > 0) {
                        searchAutoComplete.showDropDown()
                        Log.d(TAG, "Dropdown reshown after hiding keyboard")
                    }
                }

                Log.d(TAG, "Search button pressed - keyboard hidden but suggestions kept visible")
                true
            } else {
                false
            }
        }
    }

    fun performAutoSuggest(query: String, centerCoordinates: GeoCoordinates) {
        val searchOptions = SearchOptions().apply {
            languageCode = LanguageCode.EN_US
            maxItems = 10
        }

        val textQuery = TextQuery(query, TextQuery.Area(centerCoordinates))

        searchEngine.suggestByText(textQuery, searchOptions, object : SuggestCallback {
            override fun onSuggestCompleted(searchError: SearchError?, list: List<Suggestion>?) {
                if (searchError != null) {
                    Log.d(TAG, "Autosuggest Error: ${searchError.name}")
                    clearSuggestions()
                    return
                }

                list?.let { suggestionList ->
                    Log.d(TAG, "Received ${suggestionList.size} suggestions for query: $query")

                    suggestions.clear()
                    suggestions.addAll(suggestionList)

                    val suggestionTitles = suggestionList.mapNotNull { suggestion ->
                        val title = suggestion.title
                        val place = suggestion.place

                        when {
                            place != null -> {
                                val address = place.address.addressText
                                if (address.isNotEmpty() && address != title) {
                                    "$title - $address"
                                } else {
                                    title
                                }
                            }
                            title.isNotEmpty() -> title
                            else -> null
                        }
                    }.filter { it.isNotEmpty() }

                    updateSuggestions(suggestionTitles)
                } ?: run {
                    Log.d(TAG, "Suggestion list is null")
                    clearSuggestions()
                }
            }
        })
    }

    fun performTextSearch(query: String, searchAutoComplete: AutoCompleteTextView) {
        Log.d(TAG, "Performing text search for: $query")

        // We need center coordinates from the calling activity
        // This will be provided through a callback
        onTextSearchRequested?.invoke(query)
    }

    var onTextSearchRequested: ((String) -> Unit)? = null

    fun executeTextSearch(query: String, centerCoordinates: GeoCoordinates) {
        val searchOptions = SearchOptions().apply {
            languageCode = LanguageCode.EN_US
            maxItems = 20
        }

        val textQuery = TextQuery(query, TextQuery.Area(centerCoordinates))

        searchEngine.searchByText(textQuery, searchOptions, object : SearchCallback {
            override fun onSearchCompleted(searchError: SearchError?, list: List<Place>?) {
                if (searchError != null) {
                    Log.e(TAG, "Search Error: ${searchError.name}")
                    onSearchError?.invoke("Search failed: ${searchError.name}")
                    return
                }

                list?.let { places ->
                    Log.d(TAG, "Search completed with ${places.size} results")

                    if (places.isNotEmpty()) {
                        val firstPlace = places[0]
                        Log.d(TAG, "First search result: ${firstPlace.title} at ${firstPlace.geoCoordinates}")
                        firstPlace.geoCoordinates?.let { coordinates ->
                            onPlaceSelected?.invoke(firstPlace)
                        }
                    } else {
                        Log.d(TAG, "No search results found")
                        onSearchError?.invoke("No results found for '$query'")
                    }
                } ?: run {
                    Log.e(TAG, "Search results list is null")
                    onSearchError?.invoke("Search failed: No results")
                }
            }
        })
    }

    private fun updateSuggestions(suggestionTitles: List<String>) {
        Log.d(TAG, "updateSuggestions called with ${suggestionTitles.size} titles")
        suggestionsAdapter.clear()
        Log.d(TAG, "Adapter cleared. Count after clear: ${suggestionsAdapter.count}")
        
        if (suggestionTitles.isNotEmpty()) {
            suggestionTitles.forEachIndexed { index, title ->
                Log.d(TAG, "Adding suggestion $index: $title")
                suggestionsAdapter.add(title)
            }
            Log.d(TAG, "Added ${suggestionTitles.size} suggestion titles to adapter")
            Log.d(TAG, "Suggestion titles: $suggestionTitles")
            Log.d(TAG, "Adapter count after adding: ${suggestionsAdapter.count}")
            onSuggestionsUpdated?.invoke(suggestionTitles)
        } else {
            Log.d(TAG, "No valid suggestion titles found")
        }
        suggestionsAdapter.notifyDataSetChanged()
        Log.d(TAG, "Adapter notified of data change. Final adapter count: ${suggestionsAdapter.count}")
    }

    fun clearSuggestions() {
        suggestions.clear()
        dbSearchResults.clear()  // Add this line
        suggestionsAdapter.clear()
        suggestionsAdapter.notifyDataSetChanged()
    }
    fun showDropdown(searchAutoComplete: AutoCompleteTextView) {
        Log.d(TAG, "showDropdown called. Adapter count: ${suggestionsAdapter.count}")
        if (suggestionsAdapter.count > 0) {
            searchAutoComplete.showDropDown()
            Log.d(TAG, "Dropdown shown with ${suggestionsAdapter.count} suggestions")
        } else {
            searchAutoComplete.dismissDropDown()
            Log.d(TAG, "No suggestions - dropdown dismissed")
        }
    }
}