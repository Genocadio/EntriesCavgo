package com.gocavgo.entries.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
data class LocationIqResult(
    @SerialName("place_id") val placeId: String? = null,
    @SerialName("osm_id") val osmId: String? = null,
    @SerialName("lat") val lat: String = "0",
    @SerialName("lon") val lon: String = "0",
    @SerialName("display_name") val displayName: String = "",
    @SerialName("display_place") val displayPlace: String = "",
    @SerialName("display_address") val displayAddress: String = "",
    @SerialName("address") val address: Map<String, String>? = null,
    @SerialName("boundingbox") val boundingbox: List<String>? = null
) {
    val latitude: Double get() = lat.toDoubleOrNull() ?: 0.0
    val longitude: Double get() = lon.toDoubleOrNull() ?: 0.0

    val placeName: String get() = displayPlace.ifBlank { displayName }
    val addressText: String get() = displayAddress.ifBlank { displayName }
}
