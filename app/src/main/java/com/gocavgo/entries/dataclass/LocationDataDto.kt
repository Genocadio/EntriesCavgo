package com.gocavgo.entries.dataclass

data class LocationDataDto(
    val id: String? = null,
    val latitude: Double,
    val longitude: Double,
    val googlePlaceName: String? = null,
    val customName: String? = null,
    val placeId: String? = null
)