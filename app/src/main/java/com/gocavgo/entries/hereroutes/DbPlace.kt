package com.gocavgo.entries.hereroutes

import android.util.Log
import com.here.sdk.core.GeoCoordinates
import com.here.sdk.search.Place

/**
 * Custom place data type that stores location information independently of HERE SDK Place
 * Can be created from HERE SDK Place but doesn't maintain strong dependency
 *
 * FIXED: Proper coordinate handling to ensure moved coordinates are saved correctly
 */
data class DbPlace(
    // Core place data (independent of HERE SDK)
    private var _placeName: String,
    private var _originalLatitude: Double,
    private var _originalLongitude: Double,
    private var _placeId: String? = null,
    private var _placeType: String? = null,
    private var _areaType: String? = null,
    private var _address: String? = null,
    private var _edited: Boolean = false, // Track if this place has been edited

    // Custom user fields
    var customName: String? = null,
    var customDistrict: String? = null,
    var id: String? = null, // Optional custom ID
    var code: String? = null, // Optional custom code


    // Current coordinates (can be updated from original)
    private var _currentLatitude: Double = _originalLatitude,
    private var _currentLongitude: Double = _originalLongitude,

    // Optional reference to original Place (for compatibility, but not required)
    private var _originalPlace: Place? = null
) {

    // Getters for core data
    val placeName: String
        get() = _placeName

    val placeId: String?
        get() = _placeId

    // FIXED: These getters now return the current coordinates (which may be moved)
    val latitude: Double
        get() = _currentLatitude

    val longitude: Double
        get() = _currentLongitude

    val placeType: String?
        get() = _placeType

    val areaType: String?
        get() = _areaType

    val address: String?
        get() = _address

    val edited: Boolean
        get() = _edited

    // Setters for core data
    fun setPlaceName(name: String) {
        _placeName = name
    }

    fun setCoordinates(lat: Double, lng: Double) {
        _currentLatitude = lat
        _currentLongitude = lng
    }
    fun setEdited(isEdited: Boolean) {
        _edited = isEdited
    }

    fun setPlaceId(id: String?) {
        _placeId = id
    }

    fun setPlaceType(type: String?) {
        _placeType = type
    }

    fun setAreaType(type: String?) {
        _areaType = type
    }

    fun setAddress(addr: String?) {
        _address = addr
    }

    // Compatibility methods for HERE SDK integration
    val geoCoordinates: GeoCoordinates
        get() = GeoCoordinates(latitude, longitude)

    val originalPlace: Place?
        get() = _originalPlace

    // District handling
    val displayDistrict: String?
        get() = customDistrict

    // Auto-mapped province based on district
    val province: String?
        get() = displayDistrict?.let { district ->
            DISTRICT_TO_PROVINCE_MAP[district.lowercase()]
        }

    fun withUpdatedCoordinates(newLatitude: Double, newLongitude: Double): DbPlace {
        return copy(
            _currentLatitude = newLatitude,
            _currentLongitude = newLongitude
        )
    }

    // FIXED: This method now properly updates the current coordinates in-place
    fun updateCoordinates(newLatitude: Double, newLongitude: Double) {
        Log.d("DbPlace", "updateCoordinates called: ${_placeName}")
        Log.d("DbPlace", "  Original coordinates: ($_originalLatitude, $_originalLongitude)")
        Log.d("DbPlace", "  Previous current coordinates: ($_currentLatitude, $_currentLongitude)")
        Log.d("DbPlace", "  New coordinates: ($newLatitude, $newLongitude)")
        Log.d("DbPlace", "  BEFORE UPDATE - Edited flag: $_edited")
        _currentLatitude = newLatitude
        _currentLongitude = newLongitude

        Log.d("DbPlace", "  After update - Current coordinates (via getters): ($latitude, $longitude)")
        Log.d("DbPlace", "  Has moved coordinates: $hasMovedCoordinates")
        Log.d("DbPlace", "  AFTER UPDATE - Edited flag: $_edited")
    }

    // FIXED: Access methods for coordinate debugging
    fun getCurrentLatitude(): Double = _currentLatitude
    fun getCurrentLongitude(): Double = _currentLongitude
    fun getOriginalLatitude(): Double = _originalLatitude
    fun getOriginalLongitude(): Double = _originalLongitude

    val isCustomized: Boolean
        get() = !customName.isNullOrBlank() || !customDistrict.isNullOrBlank()

    // FIXED: Check if coordinates have been moved from original position
    val hasMovedCoordinates: Boolean
        get() = kotlin.math.abs(_currentLatitude - _originalLatitude) > 0.0001 ||
                kotlin.math.abs(_currentLongitude - _originalLongitude) > 0.0001

    /**
     * Get the name to display - custom name first if available, else original title
     */
    fun getName(): String {
        return customName?.takeIf { it.isNotBlank() } ?: placeName
    }

    /**
     * Extract district information from the address if available
     */
    private fun extractDistrictFromAddress(): String? {
        val addressText = address ?: return null
        val addressParts = addressText.split(",").map { it.trim() }

        // Try to find district-like information from address
        return when {
            addressParts.size >= 3 -> addressParts[addressParts.size - 2] // Second to last part
            addressParts.size == 2 -> addressParts[0] // First part if only 2 parts
            else -> null
        }
    }

    /**
     * Create a copy with updated custom fields
     */
    fun withCustomData(customName: String? = null, customDistrict: String? = null): DbPlace {
        return copy(
            customName = customName ?: this.customName,
            customDistrict = customDistrict ?: this.customDistrict
        )
    }

    /**
     * FIXED: Create a copy with current coordinates as the new base coordinates
     * This is used when saving a place after it has been moved
     */
    fun withMovedCoordinatesAsBase(): DbPlace {
        return DbPlace(
            _placeName = _placeName,
            _originalLatitude = _currentLatitude,    // Current becomes new original
            _originalLongitude = _currentLongitude,  // Current becomes new original
            _placeId = if (hasMovedCoordinates) null else _placeId, // Clear ID if moved
            _placeType = _placeType,
            _areaType = _areaType,
            _address = _address,
            customName = customName,
            customDistrict = customDistrict,
            id = id,
            code = code,
            _currentLatitude = _currentLatitude,     // Keep current coordinates
            _currentLongitude = _currentLongitude,   // Keep current coordinates
            _originalPlace = null // Clear original place reference
        )
    }

    /**
     * Get original coordinates (for debugging)
     */
    fun getOriginalCoordinates(): Pair<Double, Double> = Pair(_originalLatitude, _originalLongitude)

    /**
     * Get current coordinates (for debugging)
     */
    fun getCurrentCoordinatesDebug(): Pair<Double, Double> = Pair(_currentLatitude, _currentLongitude)

    /**
     * FIXED: Enhanced debug method with proper coordinate tracking
     */
    fun debugCoordinateState(): String {
        return buildString {
            appendLine("=== DbPlace Coordinate Debug ===")
            appendLine("Place Name: ${getName()}")
            appendLine("Original coordinates: ${getOriginalCoordinates()}")
            appendLine("Current coordinates: ${getCurrentCoordinatesDebug()}")
            appendLine("Current coordinates (getters): ($latitude, $longitude)")
            appendLine("Has moved coordinates: $hasMovedCoordinates")
            appendLine("Distance moved: ${if (hasMovedCoordinates) calculateDistanceMoved() else "0.0"}")
            appendLine("================================")
        }
    }

    /**
     * Calculate distance moved from original position
     */
    private fun calculateDistanceMoved(): Double {
        val latDiff = _currentLatitude - _originalLatitude
        val lngDiff = _currentLongitude - _originalLongitude
        return kotlin.math.sqrt(latDiff * latDiff + lngDiff * lngDiff)
    }

    /**
     * FIXED: Create a copy for saving that ensures current coordinates are preserved
     * This is the most important method for saving moved places correctly
     */
    fun createSaveCopy(): DbPlace {
        Log.d("DbPlace", "createSaveCopy for ${getName()}:")
        Log.d("DbPlace", "  Original coordinates: ($_originalLatitude, $_originalLongitude)")
        Log.d("DbPlace", "  Current coordinates: ($_currentLatitude, $_currentLongitude)")
        Log.d("DbPlace", "  Has moved coordinates: $hasMovedCoordinates")
        Log.d("DbPlace", "  Current edited flag: $_edited")

        // Create save copy with current coordinates as the main coordinates
        return DbPlace(
            _placeName = _placeName,
            _originalLatitude = _currentLatitude,    // Save current as new original
            _originalLongitude = _currentLongitude,  // Save current as new original
            _placeId = if (hasMovedCoordinates) null else _placeId, // Clear ID if coordinates changed
            _placeType = _placeType,
            _areaType = _areaType,
            _address = _address,
            customName = customName,
            _edited = _edited,
            customDistrict = customDistrict,
            id = id,
            code = code,
            _currentLatitude = _currentLatitude,     // Maintain current coordinates
            _currentLongitude = _currentLongitude,   // Maintain current coordinates
            _originalPlace = null // Remove reference for saving
        ).also { savedCopy ->
            Log.d("DbPlace", "  Created save copy:")
            Log.d("DbPlace", "    New original coordinates: (${savedCopy._originalLatitude}, ${savedCopy._originalLongitude})")
            Log.d("DbPlace", "    Current coordinates: (${savedCopy._currentLatitude}, ${savedCopy._currentLongitude})")
            Log.d("DbPlace", "    Save copy has moved coordinates: ${savedCopy.hasMovedCoordinates}")
            Log.d("DbPlace", "    Save copy edited flag: ${savedCopy._edited}")
        }
    }

    /**
     * Create a copy with updated coordinates using GeoCoordinates (for compatibility)
     */
    fun withUpdatedCoordinates(newCoordinates: GeoCoordinates): DbPlace {
        return withUpdatedCoordinates(newCoordinates.latitude, newCoordinates.longitude)
    }

    /**
     * Create a copy with updated coordinates and custom data
     */
    fun withUpdatedCoordinatesAndCustomData(
        newLatitude: Double,
        newLongitude: Double,
        customName: String? = null,
        customDistrict: String? = null
    ): DbPlace {
        return copy(
            _currentLatitude = newLatitude,
            _currentLongitude = newLongitude,
            customName = customName ?: this.customName,
            customDistrict = customDistrict ?: this.customDistrict
        )
    }

    /**
     * Reset coordinates to original position and clear custom fields
     */
    fun resetToOriginal(): DbPlace {
        return copy(
            customName = null,
            customDistrict = null,
            _currentLatitude = _originalLatitude,
            _currentLongitude = _originalLongitude
        )
    }

    /**
     * Validate that the place has required district/province information
     */
    fun hasValidLocationData(): Boolean {
        return displayDistrict != null && province != null
    }

    /**
     * Get validation error message for missing location data
     */
    fun getLocationValidationError(): String? {
        return when {
            displayDistrict == null -> "District information is missing"
            province == null -> "Province information is missing"
            else -> null
        }
    }

    /**
     * FIXED: Enhanced logging with clear coordinate tracking
     */
    fun toLogString(): String {
        return buildString {
            appendLine("Place: ${getName()}")
            appendLine("  Original Name: $placeName")
            if (isCustomized) {
                customName?.let { appendLine("  Custom Name: $it") }
                customDistrict?.let { appendLine("  Custom District: $it") }
            }

            appendLine("  District: ${displayDistrict ?: "Unknown"}")
            province?.let { appendLine("  Province: $it") }
            placeType?.let { appendLine("  Type: $it") }
            areaType?.let { appendLine("  Area Type: $it") }
            placeId?.let { appendLine("  ID: $it") }

            // Clear coordinate display
            appendLine("  Current Coordinates: $latitude, $longitude")
            if (hasMovedCoordinates) {
                appendLine("  Original Coordinates: $_originalLatitude, $_originalLongitude")
                appendLine("  Movement Distance: ${calculateDistanceMoved()}")
            } else {
                appendLine("  (No movement from original position)")
            }

            address?.let { appendLine("  Address: $it") }
        }
    }

    companion object {
        /**
         * Rwandan Districts
         */
        val RWANDAN_DISTRICTS = listOf(
            "Nyarugenge",
            "Gasabo",
            "Kicukiro",
            "Nyanza",
            "Gisagara",
            "Nyaruguru",
            "Huye",
            "Nyamagabe",
            "Ruhango",
            "Muhanga",
            "Kamonyi",
            "Karongi",
            "Rutsiro",
            "Rubavu",
            "Nyabihu",
            "Ngororero",
            "Rusizi",
            "Nyamasheke",
            "Rulindo",
            "Gakenke",
            "Musanze",
            "Burera",
            "Gicumbi",
            "Rwamagana",
            "Nyagatare",
            "Gatsibo",
            "Kayonza",
            "Kirehe",
            "Ngoma",
            "Bugesera"
        ).sorted()

        /**
         * District to Province mapping for Rwanda
         */
        private val DISTRICT_TO_PROVINCE_MAP = mapOf(
            // Kigali City
            "nyarugenge" to "Kigali",
            "gasabo" to "Kigali",
            "kicukiro" to "Kigali",

            // Southern Province
            "nyanza" to "South",
            "gisagara" to "South",
            "nyaruguru" to "South",
            "huye" to "South",
            "nyamagabe" to "South",
            "ruhango" to "South",
            "muhanga" to "South",
            "kamonyi" to "South",

            // Western Province
            "karongi" to "West",
            "rutsiro" to "West",
            "rubavu" to "West",
            "nyabihu" to "West",
            "ngororero" to "West",
            "rusizi" to "West",
            "nyamasheke" to "West",

            // Northern Province
            "rulindo" to "North",
            "gakenke" to "North",
            "musanze" to "North",
            "burera" to "North",
            "gicumbi" to "North",

            // Eastern Province
            "rwamagana" to "East",
            "nyagatare" to "East",
            "gatsibo" to "East",
            "kayonza" to "East",
            "kirehe" to "East",
            "ngoma" to "East",
            "bugesera" to "East"
        )

        /**
         * Create DbPlace from HERE SDK Place (compatibility method)
         */
        fun fromPlace(place: Place): DbPlace {
            val coords = place.geoCoordinates
            val lat = coords?.latitude ?: 0.0
            val lng = coords?.longitude ?: 0.0

            return DbPlace(
                _placeName = place.title,
                _originalLatitude = lat,
                _originalLongitude = lng,
                _placeId = place.id,
                _placeType = place.placeType?.toString(),
                _areaType = place.areaType?.toString(),
                _address = place.address.addressText,
                _currentLatitude = lat,  // Initialize current to original
                _currentLongitude = lng, // Initialize current to original
                _originalPlace = place
            )
        }

        /**
         * Create DbPlace with immediate custom data from HERE SDK Place
         */
        fun fromPlace(place: Place, customName: String?, customDistrict: String?): DbPlace {
            val coords = place.geoCoordinates
            val lat = coords?.latitude ?: 0.0
            val lng = coords?.longitude ?: 0.0

            return DbPlace(
                _placeName = place.title,
                _originalLatitude = lat,
                _originalLongitude = lng,
                _placeId = place.id,
                _placeType = place.placeType?.toString(),
                _areaType = place.areaType?.toString(),
                _address = place.address.addressText,
                customName = customName,
                customDistrict = customDistrict,
                _currentLatitude = lat,  // Initialize current to original
                _currentLongitude = lng, // Initialize current to original
                _originalPlace = place
            )
        }

        /**
         * Create DbPlace manually without HERE SDK Place
         */
        fun create(
            placeName: String,
            latitude: Double,
            longitude: Double,
            placeId: String? = null,
            placeType: String? = null,
            areaType: String? = null,
            address: String? = null,
            customName: String? = null,
            customDistrict: String? = null
        ): DbPlace {
            return DbPlace(
                _placeName = placeName,
                _originalLatitude = latitude,
                _originalLongitude = longitude,
                _placeId = placeId,
                _placeType = placeType,
                _areaType = areaType,
                _address = address,
                customName = customName,
                customDistrict = customDistrict,
                _currentLatitude = latitude,  // Initialize current to original
                _currentLongitude = longitude // Initialize current to original
            )
        }
    }
}