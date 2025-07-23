package com.gocavgo.entries.dataclass

data class WaypointDataDto(
    val location: LocationDataDto,
    val price: Double,
    val order: Int
)