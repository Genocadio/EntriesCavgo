package com.gocavgo.entries.dataclass

data class RouteDataDto (
    val origin: LocationDataDto,
    val destination: LocationDataDto,
    val distance: Double,
    val duration: Int,
    val polyline: String,
    val price: Double,
    val name: String,
    val waypoints: List<WaypointDataDto>,
    val isCityRoute: Boolean
)