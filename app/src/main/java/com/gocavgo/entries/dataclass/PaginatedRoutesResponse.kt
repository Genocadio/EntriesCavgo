package com.gocavgo.entries.dataclass

import kotlinx.serialization.Serializable

@Serializable
data class PaginatedRoutesResponse(
    val data: List<SaveRouteResponse>,
    val pagination: PaginationInfo
)