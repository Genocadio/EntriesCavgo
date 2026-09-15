package com.gocavgo.entries.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
data class CalcRouteResult(
    @SerialName("route") val route: CalcRouteDto? = null,
    @SerialName("instructions") val instructions: CalcInstructions? = null
)

@Serializable
data class CalcRouteDto(
    @SerialName("polyline") val polyline: List<List<Double>> = emptyList(),
    @SerialName("cumulativeDistances") val cumulativeDistances: List<Double> = emptyList(),
    @SerialName("totalDistance") val totalDistance: Double = 0.0,
    @SerialName("totalDuration") val totalDuration: Double = 0.0,
    @SerialName("legStopIndices") val legStopIndices: List<Int> = emptyList(),
    @SerialName("legCumulativeDistances") val legCumulativeDistances: List<Double> = emptyList(),
    @SerialName("legDurations") val legDurations: List<Double> = emptyList()
)

@Serializable
data class CalcInstructions(
    @SerialName("steps") val steps: List<CalcInstructionStep> = emptyList()
)

@Serializable
data class CalcInstructionStep(
    @SerialName("distance") val distance: Double = 0.0,
    @SerialName("duration") val duration: Double = 0.0,
    @SerialName("instruction") val instruction: String = "",
    @SerialName("maneuver") val maneuver: String = "",
    @SerialName("location") val location: List<Double>? = null
)

@Serializable
data class CalcRouteRequest(
    @SerialName("waypoints") val waypoints: List<CalcWaypoint>,
    @SerialName("includeInstructions") val includeInstructions: Boolean = false
)

@Serializable
data class CalcWaypoint(
    @SerialName("latitude") val latitude: Double,
    @SerialName("longitude") val longitude: Double
)
