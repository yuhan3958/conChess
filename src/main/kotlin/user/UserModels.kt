package com.yuhan8954.user

import com.yuhan8954.engine.model.InstantIsoSerializer
import kotlinx.serialization.Serializable
import java.time.Instant

/** Internal user record keyed by Google subject, not email. */
@Serializable
data class User(
    val id: Long,
    val googleSubject: String,
    val email: String?,
    val displayName: String?,
    val profileImageUrl: String?,
    @Serializable(with = InstantIsoSerializer::class)
    val createdAt: Instant,
    @Serializable(with = InstantIsoSerializer::class)
    val updatedAt: Instant,
    @Serializable(with = InstantIsoSerializer::class)
    val lastLoginAt: Instant,
)

/** User identity trusted from the server session. */
@Serializable
data class AuthenticatedUser(
    val userId: Long,
    val displayName: String?,
    val profileImageUrl: String?,
)
