package com.gocavgo.entries.dataclass

import kotlinx.serialization.Serializable

@Serializable
data class SaveRouteRequest(
    val id: Int? = null,
    val name: String? = null,
    val navigRouteId: Int? = null,
    val distance_meters: Int,
    val estimated_duration_seconds: Int,
    val origin_id: Int,
    val destination_id: Int,
    val route_price: Double,
    val city_route: Boolean,
    val waypoints: List<SaveRouteWaypoint> = emptyList()
)

@Serializable
data class SaveRouteWaypoint(
    val location_id: Int,
    val order: Int,
    val price: Double?,
    val is_pass_through: Boolean
)