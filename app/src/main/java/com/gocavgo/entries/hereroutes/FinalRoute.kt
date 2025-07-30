package com.gocavgo.entries.hereroutes

data class FinalRoute(
        val origin: DbPlace,
        val destination: DbPlace,
        val routePrice: Double, // Mandatory route price
        val routeName: String? = null, // Optional route name
        val isCityRoute: Boolean = false, // Default false
        val distance: String,
        val duration: String,
        val waypoints: List<WaypointWithPrice> = emptyList()
) {
fun toLogString(): String {
    val sb = StringBuilder()
    sb.appendLine("=== FINAL ROUTE DETAILS ===")
    sb.appendLine("Route Name: ${routeName ?: "Unnamed Route"}")
    sb.appendLine("City Route: $isCityRoute")
    sb.appendLine("Route Price: $routePrice")
    sb.appendLine("Distance: $distance")
    sb.appendLine("Duration: $duration")
    sb.appendLine()
    sb.appendLine("Origin: ${origin.getName()}")
    sb.appendLine("  ${origin.address}")
    sb.appendLine()

    if (waypoints.isNotEmpty()) {
        sb.appendLine("Waypoints (${waypoints.size}):")
        waypoints.forEachIndexed { index, waypoint ->
                sb.appendLine("  ${index + 1}. ${waypoint.dbPlace.getName()} - Price: ${waypoint.price}")
            sb.appendLine("     ${waypoint.dbPlace.address}")
        }
        sb.appendLine()
    }

    sb.appendLine("Destination: ${destination.getName()}")
    sb.appendLine("  ${destination.address}")
    sb.appendLine("=============================")

    return sb.toString()
}

fun getTotalPrice(): Double {
    return routePrice + waypoints.sumOf { it.price }
}
}

data class WaypointWithPrice(
        val dbPlace: DbPlace,
        val price: Double
)