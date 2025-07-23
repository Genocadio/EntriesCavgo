package com.gocavgo.entries.service

import android.util.Log
import com.gocavgo.entries.dataclass.LocationDataDto
import com.gocavgo.entries.dataclass.RouteDataDto
import com.gocavgo.entries.dataclass.WaypointDataDto

class RouteService {

    sealed class SaveResult {
        object Success : SaveResult()
        data class Error(val message: String) : SaveResult()
        data class ExistingLocation(val id: String) : SaveResult()
    }

    fun createRouteData(
        origin: LocationDataDto,
        destination: LocationDataDto,
        waypoints: List<WaypointDataDto>,
        distance: Double,
        duration: Int,
        polyline: String,
        price: Double,
        name: String = "Dummy Route",
        isCityRoute: Boolean = false
    ): RouteDataDto {
        val route = RouteDataDto(
            origin = origin,
            destination = destination,
            distance = distance,
            duration = duration,
            polyline = polyline,
            price = price,
            name = name,
            waypoints = waypoints,
            isCityRoute = isCityRoute
        )
        Log.d("DummyRouteService", "Created route: $route")
        return route
    }

    fun saveRoute(route: RouteDataDto): SaveResult {
        return SaveResult.Success
    }
}