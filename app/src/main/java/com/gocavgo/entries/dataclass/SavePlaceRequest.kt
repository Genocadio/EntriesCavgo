package com.gocavgo.entries.dataclass

import kotlinx.serialization.Serializable

@Serializable
data class SavePlaceRequest(
    val latitude: Double?,
    val longitude: Double?,
    val google_place_name: String,
    val custom_name: String?,
    val province: String?,
    val district: String?,
    val place_id: String?
)