package com.yuhan8954.common

import kotlinx.serialization.Serializable

/** Common API error response with machine and user-readable details. */
@Serializable
data class ApiError(
    val code: String,
    val message: String,
    val requestId: String? = null,
)

/** Common status response. */
@Serializable
data class StatusResponse(
    val ok: Boolean,
    val message: String? = null,
)
