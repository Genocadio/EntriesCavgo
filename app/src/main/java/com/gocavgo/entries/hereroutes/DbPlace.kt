package com.gocavgo.entries.hereroutes

import android.util.Log
import com.gocavgo.entries.model.LocationIqResult

/**
 * Custom place data type that stores location information.
 * Independent of any map SDK — uses plain lat/lng doubles.
 */
data class DbPlace(
    private var _placeName: String,
    private var _originalLatitude: Double,
    private var _originalLongitude: Double,
    private var _placeId: String? = null,
    private var _placeType: String? = null,
    private var _areaType: String? = null,
    private var _address: String? = null,
    private var _edited: Boolean = false,

    var customName: String? = null,
    var customDistrict: String? = null,
    var isPassThrough: Boolean = false,
    var id: String? = null,
    var code: String? = null,

    private var _currentLatitude: Double = _originalLatitude,
    private var _currentLongitude: Double = _originalLongitude
) {
    val placeName: String get() = _placeName
    val placeId: String? get() = _placeId
    val latitude: Double get() = _currentLatitude
    val longitude: Double get() = _currentLongitude
    val placeType: String? get() = _placeType
    val areaType: String? get() = _areaType
    val address: String? get() = _address
    val edited: Boolean get() = _edited

    fun setPlaceName(name: String) { _placeName = name }
    fun setCoordinates(lat: Double, lng: Double) { _currentLatitude = lat; _currentLongitude = lng }
    fun setEdited(isEdited: Boolean) { _edited = isEdited }
    fun setPlaceId(id: String?) { _placeId = id }
    fun setPlaceType(type: String?) { _placeType = type }
    fun setAreaType(type: String?) { _areaType = type }
    fun setAddress(addr: String?) { _address = addr }

    val displayDistrict: String? get() = customDistrict

    val province: String?
        get() = displayDistrict?.let { district -> DISTRICT_TO_PROVINCE_MAP[district.lowercase()] }

    fun updateCoordinates(newLatitude: Double, newLongitude: Double) {
        Log.d("DbPlace", "updateCoordinates: ${_placeName} -> ($newLatitude, $newLongitude)")
        _currentLatitude = newLatitude
        _currentLongitude = newLongitude
    }

    fun getCurrentLatitude(): Double = _currentLatitude
    fun getCurrentLongitude(): Double = _currentLongitude
    fun getOriginalLatitude(): Double = _originalLatitude
    fun getOriginalLongitude(): Double = _originalLongitude

    val isCustomized: Boolean get() = !customName.isNullOrBlank() || !customDistrict.isNullOrBlank()

    val hasMovedCoordinates: Boolean
        get() = kotlin.math.abs(_currentLatitude - _originalLatitude) > 0.0001 ||
                kotlin.math.abs(_currentLongitude - _originalLongitude) > 0.0001

    fun getName(): String {
        return customName?.takeIf { it.isNotBlank() } ?: placeName
    }

    fun withUpdatedCoordinates(newLatitude: Double, newLongitude: Double): DbPlace {
        return copy(_currentLatitude = newLatitude, _currentLongitude = newLongitude)
    }

    fun createSaveCopy(): DbPlace {
        return DbPlace(
            _placeName = _placeName,
            _originalLatitude = _currentLatitude,
            _originalLongitude = _currentLongitude,
            _placeId = if (hasMovedCoordinates) null else _placeId,
            _placeType = _placeType,
            _areaType = _areaType,
            _address = _address,
            customName = customName,
            _edited = _edited,
            customDistrict = customDistrict,
            isPassThrough = isPassThrough,
            id = id,
            code = code,
            _currentLatitude = _currentLatitude,
            _currentLongitude = _currentLongitude
        )
    }

    fun withCustomData(customName: String? = null, customDistrict: String? = null): DbPlace {
        return copy(
            customName = customName ?: this.customName,
            customDistrict = customDistrict ?: this.customDistrict
        )
    }

    fun hasValidLocationData(): Boolean = displayDistrict != null && province != null

    fun getLocationValidationError(): String? = when {
        displayDistrict == null -> "District information is missing"
        province == null -> "Province information is missing"
        else -> null
    }

    /** A route stop is fully saveable only when it has both a custom name and a district. */
    val hasCompleteRouteData: Boolean
        get() = !customName.isNullOrBlank() && !customDistrict.isNullOrBlank()

    fun getCompleteRouteDataError(): String? = when {
        customName.isNullOrBlank() -> "Custom name is missing"
        customDistrict.isNullOrBlank() -> "District is missing"
        else -> null
    }

    fun debugCoordinateState(): String = buildString {
        appendLine("Place: ${getName()}")
        appendLine("  Original: ($_originalLatitude, $_originalLongitude)")
        appendLine("  Current: ($_currentLatitude, $_currentLongitude)")
        appendLine("  Moved: $hasMovedCoordinates")
    }

    companion object {
        val RWANDAN_DISTRICTS = listOf(
            "Nyarugenge", "Gasabo", "Kicukiro", "Nyanza", "Gisagara", "Nyaruguru",
            "Huye", "Nyamagabe", "Ruhango", "Muhanga", "Kamonyi", "Karongi",
            "Rutsiro", "Rubavu", "Nyabihu", "Ngororero", "Rusizi", "Nyamasheke",
            "Rulindo", "Gakenke", "Musanze", "Burera", "Gicumbi", "Rwamagana",
            "Nyagatare", "Gatsibo", "Kayonza", "Kirehe", "Ngoma", "Bugesera"
        ).sorted()

        private val DISTRICT_TO_PROVINCE_MAP = mapOf(
            "nyarugenge" to "Kigali", "gasabo" to "Kigali", "kicukiro" to "Kigali",
            "nyanza" to "South", "gisagara" to "South", "nyaruguru" to "South",
            "huye" to "South", "nyamagabe" to "South", "ruhango" to "South",
            "muhanga" to "South", "kamonyi" to "South",
            "karongi" to "West", "rutsiro" to "West", "rubavu" to "West",
            "nyabihu" to "West", "ngororero" to "West", "rusizi" to "West", "nyamasheke" to "West",
            "rulindo" to "North", "gakenke" to "North", "musanze" to "North",
            "burera" to "North", "gicumbi" to "North",
            "rwamagana" to "East", "nyagatare" to "East", "gatsibo" to "East",
            "kayonza" to "East", "kirehe" to "East", "ngoma" to "East", "bugesera" to "East"
        )

        fun fromLocationIqResult(result: LocationIqResult): DbPlace {
            return DbPlace(
                _placeName = result.placeName,
                _originalLatitude = result.latitude,
                _originalLongitude = result.longitude,
                _placeId = result.placeId,
                _address = result.addressText,
                _currentLatitude = result.latitude,
                _currentLongitude = result.longitude
            )
        }

        fun create(
            placeName: String,
            latitude: Double,
            longitude: Double,
            placeId: String? = null,
            placeType: String? = null,
            areaType: String? = null,
            address: String? = null,
            customName: String? = null,
            customDistrict: String? = null,
            isPassThrough: Boolean = false
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
                isPassThrough = isPassThrough,
                _currentLatitude = latitude,
                _currentLongitude = longitude
            )
        }
    }
}
