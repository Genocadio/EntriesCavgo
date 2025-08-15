package com.gocavgo.entries.manageplace

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.gocavgo.entries.R
import com.gocavgo.entries.databinding.ActivityPlaceManagementBinding
import com.gocavgo.entries.databinding.DialogEditPlaceManagementBinding
import com.gocavgo.entries.hereroutes.CustomToast
import com.gocavgo.entries.hereroutes.DbPlace
import com.gocavgo.entries.service.RemoteDataManager
import com.gocavgo.entries.service.RemoteResult
import kotlinx.coroutines.launch
class PlaceManagementActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlaceManagementBinding
    private lateinit var placesAdapter: PlacesAdapter
    private val remoteDataManager = RemoteDataManager.getInstance()

    private var currentPage = 1
    private val pageSize = 20
    private var isLoading = false
    private var hasMorePages = true

    // Filter variables
    private var currentSearchQuery: String? = null
    private var selectedProvince: String? = null
    private var selectedDistrict: String? = null

    // Province and district lists
    private val provinces = listOf("All Provinces", "Kigali City", "North", "South", "East", "West")
    private val allDistricts = DbPlace.RWANDAN_DISTRICTS
    private var filteredDistricts = allDistricts

    companion object {
        private const val TAG = "PlaceManagementActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlaceManagementBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        setupRecyclerView()
        setupFilters()
        setupSearchFunctionality()
        loadPlaces()
    }

    private fun setupUI() {
        // Setup toolbar if you have one
        supportActionBar?.apply {
            title = "Manage Places"
            setDisplayHomeAsUpEnabled(true)
        }
    }

    private fun setupRecyclerView() {
        placesAdapter = PlacesAdapter(
            onEditClick = { place -> showEditPlaceDialog(place) },
            onDeleteClick = { place -> showDeleteConfirmationDialog(place) }
        )

        binding.placesRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@PlaceManagementActivity)
            adapter = placesAdapter
        }
    }

    private fun setupFilters() {
        // Setup province spinner
        val provinceAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, provinces)
        provinceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.provinceFilterSpinner.adapter = provinceAdapter

        binding.provinceFilterSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedProvince = if (position == 0) null else provinces[position]
                updateDistrictFilter()
                resetAndLoadPlaces()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Setup district spinner
        setupDistrictSpinner()

        // Setup clear filters button
        binding.clearFiltersButton.setOnClickListener {
            clearAllFilters()
        }

        // Setup refresh button
        binding.refreshButton.setOnClickListener {
            resetAndLoadPlaces()
        }
    }

    private fun setupDistrictSpinner() {
        val districtAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item,
            mutableListOf("All Districts").apply { addAll(filteredDistricts) })
        districtAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.districtFilterSpinner.adapter = districtAdapter

        binding.districtFilterSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedDistrict = if (position == 0) null else filteredDistricts[position - 1]
                resetAndLoadPlaces()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun updateDistrictFilter() {
        filteredDistricts = when (selectedProvince) {
            "Kigali City" -> allDistricts.filter { district ->
                listOf("Kigali", "Nyarugenge", "Gasabo", "Kicukiro").contains(district)
            }
            "North" -> allDistricts.filter { district ->
                listOf("Rulindo", "Gakenke", "Musanze", "Burera", "Gicumbi").contains(district)
            }
            "South" -> allDistricts.filter { district ->
                listOf("Nyanza", "Gisagara", "Nyaruguru", "Huye", "Nyamagabe",
                    "Ruhango", "Muhanga", "Kamonyi").contains(district)
            }
            "East" -> allDistricts.filter { district ->
                listOf("Rwamagana", "Nyagatare", "Gatsibo", "Kayonza",
                    "Kirehe", "Ngoma", "Bugesera").contains(district)
            }
            "West" -> allDistricts.filter { district ->
                listOf("Karongi", "Rutsiro", "Rubavu", "Nyabihu",
                    "Ngororero", "Rusizi", "Nyamasheke").contains(district)
            }
            else -> allDistricts
        }

        setupDistrictSpinner()
        selectedDistrict = null // Reset district selection when province changes
    }

    private fun setupSearchFunctionality() {
        binding.searchEditText.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                currentSearchQuery = s?.toString()?.takeIf { it.isNotBlank() }
                resetAndLoadPlaces()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun clearAllFilters() {
        // Clear search
        binding.searchEditText.setText("")

        // Reset spinners
        binding.provinceFilterSpinner.setSelection(0)
        binding.districtFilterSpinner.setSelection(0)

        // Reset filter variables
        currentSearchQuery = null
        selectedProvince = null
        selectedDistrict = null

        resetAndLoadPlaces()
    }

    private fun resetAndLoadPlaces() {
        currentPage = 1
        hasMorePages = true
        placesAdapter.clearPlaces()
        loadPlaces()
    }

    private fun loadPlaces() {
        if (isLoading || !hasMorePages) return

        isLoading = true
        showLoading(true)

        lifecycleScope.launch {
            try {
                Log.d(TAG, "Loading places - Page: $currentPage, Search: $currentSearchQuery, Province: $selectedProvince, District: $selectedDistrict")

                val result = remoteDataManager.getLocations(
                    search = currentSearchQuery,
                    page = currentPage,
                    limit = pageSize
                )

                when (result) {
                    is RemoteResult.Success -> {
                        val places = result.data.data
                        val filteredPlaces = filterPlacesByProvinceAndDistrict(places)

                        if (currentPage == 1) {
                            placesAdapter.setPlaces(filteredPlaces)
                        } else {
                            placesAdapter.addPlaces(filteredPlaces)
                        }

                        hasMorePages = currentPage < result.data.pagination.total_pages
                        currentPage++

                        updateEmptyState(placesAdapter.itemCount == 0)

                        Log.d(TAG, "Loaded ${filteredPlaces.size} places, total in adapter: ${placesAdapter.itemCount}")
                    }

                    is RemoteResult.Error -> {
                        Log.e(TAG, "Error loading places: ${result.message}")
                        CustomToast.showError(this@PlaceManagementActivity, "Error loading places: ${result.message}")
                        updateEmptyState(placesAdapter.itemCount == 0)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception loading places", e)
                CustomToast.showError(this@PlaceManagementActivity, "Error loading places: ${e.message}")
                updateEmptyState(placesAdapter.itemCount == 0)
            } finally {
                isLoading = false
                showLoading(false)
            }
        }
    }

    private fun filterPlacesByProvinceAndDistrict(places: List<DbPlace>): List<DbPlace> {
        return places.filter { place ->
            val matchesProvince = selectedProvince == null || place.province == selectedProvince
            val matchesDistrict = selectedDistrict == null || place.displayDistrict == selectedDistrict
            matchesProvince && matchesDistrict
        }
    }

    private fun showLoading(show: Boolean) {
        binding.loadingProgressBar.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        binding.emptyStateLayout.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.placesRecyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE

        // Update empty state message based on filters
        val message = when {
            currentSearchQuery != null || selectedProvince != null || selectedDistrict != null ->
                "No places match your search criteria"
            else -> "No places available"
        }
        binding.emptyStateTextView.text = message
    }

    private fun showEditPlaceDialog(place: DbPlace) {
        val dialogBinding = DialogEditPlaceManagementBinding.inflate(layoutInflater)

        // Setup district spinner in dialog
        val districtAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, allDistricts)
        districtAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        dialogBinding.districtSpinner.adapter = districtAdapter

        // Pre-fill form with current place data
        dialogBinding.customNameEditText.setText(place.customName)
        dialogBinding.googlePlaceNameTextView.text = place.placeName
        dialogBinding.coordinatesTextView.text = "${place.latitude}, ${place.longitude}"
        dialogBinding.placeIdTextView.text = place.placeId ?: "Not available"
        dialogBinding.locationCodeTextView.text = place.code ?: "N/A"

        // Set current district selection
        place.displayDistrict?.let { currentDistrict ->
            val districtIndex = allDistricts.indexOf(currentDistrict)
            if (districtIndex >= 0) {
                dialogBinding.districtSpinner.setSelection(districtIndex)
            }
        }

        // Update province display when district changes
        dialogBinding.districtSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedDistrict = allDistricts[position]
                val province = getProvinceForDistrict(selectedDistrict)
                dialogBinding.provinceDisplayTextView.text = province
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Initialize province display
        place.displayDistrict?.let { district ->
            dialogBinding.provinceDisplayTextView.text = getProvinceForDistrict(district)
        }

        AlertDialog.Builder(this)
            .setTitle("Edit Place")
            .setView(dialogBinding.root)
            .setPositiveButton("Save") { _, _ ->
                val customName = dialogBinding.customNameEditText.text.toString().takeIf { it.isNotBlank() }
                val selectedDistrictIndex = dialogBinding.districtSpinner.selectedItemPosition
                val newDistrict = allDistricts[selectedDistrictIndex]

                updatePlace(place, customName, newDistrict)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun getProvinceForDistrict(district: String): String {
        return when (district.lowercase()) {
            "kigali", "nyarugenge", "gasabo", "kicukiro" -> "Kigali City"
            "nyanza", "gisagara", "nyaruguru", "huye", "nyamagabe",
            "ruhango", "muhanga", "kamonyi" -> "South"
            "karongi", "rutsiro", "rubavu", "nyabihu",
            "ngororero", "rusizi", "nyamasheke" -> "West"
            "rulindo", "gakenke", "musanze", "burera", "gicumbi" -> "North"
            "rwamagana", "nyagatare", "gatsibo", "kayonza",
            "kirehe", "ngoma", "bugesera" -> "East"
            else -> "Unknown"
        }
    }

    private fun updatePlace(originalPlace: DbPlace, customName: String?, newDistrict: String) {
        lifecycleScope.launch {
            try {
                showLoading(true)

                // Create updated place with new custom data
                val updatedPlace = originalPlace.copy(
                    customName = customName,
                    customDistrict = newDistrict
                )

                val placeId = originalPlace.id?.toIntOrNull().toString()
                if (placeId == null) {
                    CustomToast.showError(this@PlaceManagementActivity, "Error: Place ID is invalid")
                    return@launch
                }

                val result = remoteDataManager.updatePlace(placeId, updatedPlace)

                when (result) {
                    is RemoteResult.Success -> {
                        CustomToast.showSuccess(this@PlaceManagementActivity, "Place updated successfully")
                        resetAndLoadPlaces() // Refresh the list
                    }
                    is RemoteResult.Error -> {
                        CustomToast.showError(this@PlaceManagementActivity, "Error updating place: ${result.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception updating place", e)
                CustomToast.showError(this@PlaceManagementActivity, "Error updating place: ${e.message}")
            } finally {
                showLoading(false)
            }
        }
    }

    private fun showDeleteConfirmationDialog(place: DbPlace) {
        AlertDialog.Builder(this)
            .setTitle("Delete Place")
            .setMessage("Are you sure you want to delete '${place.getName()}'?\n\nThis action cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                deletePlace(place)
            }
            .setNegativeButton("Cancel", null)
            .setIcon(R.drawable.ic_delete)
            .show()
    }

    private fun deletePlace(place: DbPlace) {
        lifecycleScope.launch {
            try {
                showLoading(true)

                val placeId = place.id?.toIntOrNull()
                if (placeId == null) {
                    CustomToast.showError(this@PlaceManagementActivity, "Error: Place ID is invalid")
                    return@launch
                }

                val result = remoteDataManager.deletePlace(placeId)

                when (result) {
                    is RemoteResult.Success -> {
                        CustomToast.showSuccess(this@PlaceManagementActivity, "Place deleted successfully")
                        resetAndLoadPlaces() // Refresh the list
                    }
                    is RemoteResult.Error -> {
                        CustomToast.showError(this@PlaceManagementActivity, "Error deleting place: ${result.message}")

                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception deleting place", e)
                CustomToast.showError(this@PlaceManagementActivity, "Error deleting place: ${e.message}")
            } finally {
                showLoading(false)
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up if needed
    }
}