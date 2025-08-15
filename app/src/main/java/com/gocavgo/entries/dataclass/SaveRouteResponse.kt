package com.gocavgo.entries.dataclass

import kotlinx.serialization.Serializable

@Serializable
data class SaveRouteResponse(
    val id: Int,
    val name: String?,
    val distance_meters: Int,
    val estimated_duration_seconds: Int,
    val google_route_id: String?,
    val origin_id: Int,
    val destination_id: Int,
    val route_price: Double,
    val city_route: Boolean,
    val created_at: String,
    val updated_at: String,
    val origin: SavePlaceResponse,
    val destination: SavePlaceResponse,
    val waypoints: List<RouteWaypoint>
)