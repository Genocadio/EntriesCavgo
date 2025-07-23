package com.gocavgo.entries.service

import android.util.Log
import com.gocavgo.entries.dataclass.LocationDataDto

class LocationService {
    private val locations = mutableListOf(
        LocationDataDto(
            id = "loc1",
            latitude = -1.9441,
            longitude = 30.0619,
            googlePlaceName = "Kigali Convention Centre",
            customName = "KCC",
            placeId = "placeid1"
        ),
        LocationDataDto(
            id = "loc2",
            latitude = -1.9500,
            longitude = 30.0588,
            googlePlaceName = "Kigali Genocide Memorial",
            customName = null,
            placeId = "placeid2"
        )
    )

    sealed class SaveResult {
        object Success : SaveResult()
        data class Error(val message: String) : SaveResult()
        data class ExistingLocation(val id: String) : SaveResult()
    }

    fun getAllLocations(): List<LocationDataDto> = locations

    fun getLocationById(id: String): LocationDataDto? =
        locations.find { it.id == id }

    fun createLocation(
        latLng: com.google.android.gms.maps.model.LatLng,
        googlePlaceName: String?,
        customName: String?,
        placeId: String?
    ): LocationDataDto {
        return LocationDataDto(
            latitude = latLng.latitude,
            longitude = latLng.longitude,
            googlePlaceName = googlePlaceName,
            customName = customName,
            placeId = placeId
        )
    }

    fun saveLocation(location: LocationDataDto): SaveResult {
        if (locations.any { it.latitude == location.latitude && it.longitude == location.longitude }) {
            Log.d(
                "LocationService",
                "Location already exists: ${location.latitude}, ${location.longitude}"
            )
            return SaveResult.ExistingLocation(location.id ?: "unknown")
        }
        val newId = "loc${locations.size + 1}"
        val newLocation = location.copy(id = newId)
        locations.add(newLocation)
        Log.d("LocationService", "Location saved: $newLocation")
        return SaveResult.Success
    }
}