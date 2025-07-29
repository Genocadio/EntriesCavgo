package com.gocavgo.entries.hereroutes

import android.content.Context
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
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

class SearchManager(private val context: Context) {

    private lateinit var searchEngine: SearchEngine
    private val TAG = SearchManager::class.java.simpleName

    // Search related variables
    val suggestions = arrayListOf<Suggestion>()
    lateinit var suggestionsAdapter: ArrayAdapter<String>
    var isUserInput = true

    // Callbacks
    var onPlaceSelected: ((Place) -> Unit)? = null
    var onSearchError: ((String) -> Unit)? = null
    var onSuggestionsUpdated: ((List<String>) -> Unit)? = null

    init {
        initializeSearchEngine()
        initializeSuggestionsAdapter()
    }

    private fun initializeSearchEngine() {
        try {
            searchEngine = SearchEngine()
        } catch (e: InstantiationErrorException) {
            throw RuntimeException("Initialization of SearchEngine failed: " + e.error.name)
        }
    }

    private fun initializeSuggestionsAdapter() {
        suggestionsAdapter = ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, arrayListOf<String>())
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

            if (position < suggestions.size) {
                val selectedSuggestion = suggestions[position]
                val suggestionTitle = selectedSuggestion.title

                Log.d(TAG, "Selected suggestion: $suggestionTitle")

                searchAutoComplete.setText(suggestionTitle)

                // Handle place selection
                selectedSuggestion.place?.let { place ->
                    place.geoCoordinates?.let { coordinates ->
                        Log.d(TAG, "Place selected from suggestion: ${place.title} at $coordinates")
                        onPlaceSelected?.invoke(place)
                    }
                } ?: run {
                    Log.d(TAG, "No place in suggestion, performing text search")
                    performTextSearch(suggestionTitle, searchAutoComplete)
                }

                // Clear suggestions after successful selection
                clearSuggestions()
                searchAutoComplete.dismissDropDown()
                Log.d(TAG, "Item selected - suggestions cleared and dropdown dismissed")
            } else {
                Log.w(TAG, "Invalid suggestion position: $position, suggestions size: ${suggestions.size}")
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
        suggestionsAdapter.clear()
        if (suggestionTitles.isNotEmpty()) {
            suggestionsAdapter.addAll(suggestionTitles)
            Log.d(TAG, "Added ${suggestionTitles.size} suggestion titles to adapter")
            onSuggestionsUpdated?.invoke(suggestionTitles)
        } else {
            Log.d(TAG, "No valid suggestion titles found")
        }
        suggestionsAdapter.notifyDataSetChanged()
    }

    fun clearSuggestions() {
        suggestions.clear()
        suggestionsAdapter.clear()
        suggestionsAdapter.notifyDataSetChanged()
    }

    fun showDropdown(searchAutoComplete: AutoCompleteTextView) {
        if (suggestionsAdapter.count > 0) {
            searchAutoComplete.showDropDown()
            Log.d(TAG, "Dropdown shown with suggestions")
        } else {
            searchAutoComplete.dismissDropDown()
            Log.d(TAG, "No suggestions - dropdown dismissed")
        }
    }
}