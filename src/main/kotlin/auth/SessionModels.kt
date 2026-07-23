package com.yuhan8954.auth

/** Server-side trusted cookie session content. */
@kotlinx.serialization.Serializable
data class UserSession(
    val userId: Long? = null,
    val csrfToken: String,
    val oauthState: String? = null,
)
