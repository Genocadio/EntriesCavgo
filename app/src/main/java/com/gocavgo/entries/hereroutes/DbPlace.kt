package com.gocavgo.entries.hereroutes

import com.here.sdk.core.GeoCoordinates
import com.here.sdk.search.Place

/**
 * Custom place data type that wraps HERE SDK Place with additional user-defined fields
 */
data class DbPlace(
    val originalPlace: Place,
    var customName: String? = null,
    var customDistrict: String? = null,
    var updatedCoordinates: GeoCoordinates? = null // NEW: Track coordinate updates
) {
    // Original place properties (delegated to HERE SDK Place)
    val placeName: String
        get() = originalPlace.title

    val placeId: String?
        get() = originalPlace.id

    // NEW: Use updated coordinates if available, otherwise fall back to original
    val geoCoordinates: GeoCoordinates?
        get() = updatedCoordinates ?: originalPlace.geoCoordinates

    val address: String
        get() = originalPlace.address.addressText

    val placeType: String?
        get() = originalPlace.placeType?.toString()

    val areaType: String?
        get() = originalPlace.areaType?.toString()

    val displayDistrict: String?
        get() = customDistrict?.takeIf { it.isNotBlank() } ?: extractDistrictFromAddress()

    val isCustomized: Boolean
        get() = !customName.isNullOrBlank() || !customDistrict.isNullOrBlank()

    // NEW: Check if coordinates have been moved
    val hasMovedCoordinates: Boolean
        get() = updatedCoordinates != null

    /**
     * Get the name to display - custom name first if available, else original title
     */
    fun getName(): String {
        return customName?.takeIf { it.isNotBlank() } ?: placeName
    }

    /**
     * Extract district information from the original address if available
     */
    private fun extractDistrictFromAddress(): String? {
        val addressText = originalPlace.address.addressText
        val addressParts = addressText.split(",").map { it.trim() }

        // Try to find district-like information from address
        // This is a simple heuristic - you might want to improve this logic
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
     * NEW: Create a copy with updated coordinates
     */
    fun withUpdatedCoordinates(newCoordinates: GeoCoordinates): DbPlace {
        return copy(updatedCoordinates = newCoordinates)
    }

    /**
     * NEW: Create a copy with updated coordinates and custom data
     */
    fun withUpdatedCoordinatesAndCustomData(
        newCoordinates: GeoCoordinates,
        customName: String? = null,
        customDistrict: String? = null
    ): DbPlace {
        return copy(
            updatedCoordinates = newCoordinates,
            customName = customName ?: this.customName,
            customDistrict = customDistrict ?: this.customDistrict
        )
    }

    /**
     * Reset custom fields to original place data
     */
    fun resetToOriginal(): DbPlace {
        return copy(customName = null, customDistrict = null, updatedCoordinates = null)
    }

    /**
     * Get formatted string for logging/display
     */
    fun toLogString(): String {
        return buildString {
            appendLine("Place: ${getName()}")
            appendLine("  Original Name: $placeName")
            if (isCustomized) {
                customName?.let { appendLine("  Custom Name: $it") }
                customDistrict?.let { appendLine("  Custom District: $it") }
            }
            appendLine("  Address: $address")
            appendLine("  District: ${displayDistrict ?: "Unknown"}")
            placeType?.let { appendLine("  Type: $it") }
            areaType?.let { appendLine("  Area Type: $it") }
            placeId?.let { appendLine("  ID: $it") }

            // Show both original and updated coordinates if different
            val currentCoords = geoCoordinates
            val originalCoords = originalPlace.geoCoordinates

            currentCoords?.let {
                appendLine("  Current Coordinates: ${it.latitude}, ${it.longitude}")
                if (hasMovedCoordinates && originalCoords != null) {
                    appendLine("  Original Coordinates: ${originalCoords.latitude}, ${originalCoords.longitude}")
                }
            }
        }
    }

    companion object {
        /**
         * Rwandan Districts
         */
        val RWANDAN_DISTRICTS = listOf(
            "Kigali",
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
         * Create DbPlace from HERE SDK Place
         */
        fun fromPlace(place: Place): DbPlace {
            return DbPlace(originalPlace = place)
        }

        /**
         * Create DbPlace with immediate custom data
         */
        fun fromPlace(place: Place, customName: String?, customDistrict: String?): DbPlace {
            return DbPlace(
                originalPlace = place,
                customName = customName,
                customDistrict = customDistrict
            )
        }
    }
}