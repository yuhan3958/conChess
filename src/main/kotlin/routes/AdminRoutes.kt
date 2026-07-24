package com.yuhan8954.routes

import com.yuhan8954.auth.CurrentUserProvider
import com.yuhan8954.common.ApiError
import com.yuhan8954.engine.model.InstantIsoSerializer
import com.yuhan8954.persistence.sqlite.SqliteStore
import com.yuhan8954.user.User
import com.yuhan8954.user.UserRole
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import java.time.Clock
import java.time.Instant

/** Registers admin-only account and history routes. Role changes intentionally have no API. */
fun Route.adminRoutes(currentUser: CurrentUserProvider, store: SqliteStore, clock: Clock = Clock.systemUTC()) {
    get("/api/admin/users") {
        currentUser.requireAdmin(call)
        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 0
        val size = call.request.queryParameters["size"]?.toIntOrNull() ?: 50
        call.respond(AdminUsersResponse(store.listUsers(page, size.coerceIn(1, 100)).map(::AdminUserSummary)))
    }

    get("/api/admin/users/{userId}/stats") {
        currentUser.requireAdmin(call)
        val userId = call.parameters["userId"]?.toLongOrNull()
        if (userId == null || store.findUser(userId) == null) {
            call.respond(HttpStatusCode.NotFound, ApiError("USER_NOT_FOUND", "User was not found."))
            return@get
        }
        call.respond(store.statsFor(userId))
    }

    get("/api/admin/users/{userId}/matches") {
        currentUser.requireAdmin(call)
        val userId = call.parameters["userId"]?.toLongOrNull()
        if (userId == null || store.findUser(userId) == null) {
            call.respond(HttpStatusCode.NotFound, ApiError("USER_NOT_FOUND", "User was not found."))
            return@get
        }
        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 0
        val size = call.request.queryParameters["size"]?.toIntOrNull() ?: 20
        call.respond(store.matchesFor(userId, page, size))
    }

    post("/api/admin/users/{userId}/ban") {
        call.requireCsrf()
        currentUser.requireAdmin(call)
        val userId = call.parameters["userId"]?.toLongOrNull()
        val user = userId?.let { store.setUserBan(it, clock.instant()) }
        if (user == null) {
            call.respond(HttpStatusCode.NotFound, ApiError("USER_NOT_FOUND", "User was not found."))
            return@post
        }
        call.respond(AdminUserResponse(AdminUserSummary(user)))
    }

    post("/api/admin/users/{userId}/unban") {
        call.requireCsrf()
        currentUser.requireAdmin(call)
        val userId = call.parameters["userId"]?.toLongOrNull()
        val user = userId?.let { store.setUserBan(it, null) }
        if (user == null) {
            call.respond(HttpStatusCode.NotFound, ApiError("USER_NOT_FOUND", "User was not found."))
            return@post
        }
        call.respond(AdminUserResponse(AdminUserSummary(user)))
    }
}

@Serializable
data class AdminUsersResponse(val users: List<AdminUserSummary>)

@Serializable
data class AdminUserResponse(val user: AdminUserSummary)

@Serializable
data class AdminUserSummary(
    val id: Long,
    val email: String?,
    val displayName: String?,
    val profileImageUrl: String?,
    val role: UserRole,
    @Serializable(with = InstantIsoSerializer::class)
    val bannedAt: Instant?,
    @Serializable(with = InstantIsoSerializer::class)
    val createdAt: Instant,
    @Serializable(with = InstantIsoSerializer::class)
    val lastLoginAt: Instant,
) {
    constructor(user: User) : this(
        id = user.id,
        email = user.email,
        displayName = user.displayName,
        profileImageUrl = user.profileImageUrl,
        role = user.role,
        bannedAt = user.bannedAt,
        createdAt = user.createdAt,
        lastLoginAt = user.lastLoginAt,
    )
}
