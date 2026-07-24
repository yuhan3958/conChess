package com.yuhan8954.auth

import com.yuhan8954.persistence.sqlite.SqliteStore
import com.yuhan8954.user.AuthenticatedUser
import com.yuhan8954.user.UserRole
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions

/** Reads the authenticated user from the server-managed session. */
class CurrentUserProvider(private val store: SqliteStore) {
    suspend fun requireUser(call: ApplicationCall): AuthenticatedUser {
        val userId = call.sessions.get<UserSession>()?.userId
            ?: throw AuthException(HttpStatusCode.Unauthorized, "AUTH_REQUIRED", "Login is required.")
        val user = store.findUser(userId)
            ?: throw AuthException(HttpStatusCode.Unauthorized, "SESSION_USER_NOT_FOUND", "Login session is no longer valid.")
        if (user.bannedAt != null) {
            throw AuthException(HttpStatusCode.Forbidden, "USER_BANNED", "This account is banned.")
        }
        return AuthenticatedUser(user.id, user.displayName, user.profileImageUrl, user.role)
    }

    suspend fun optionalUser(call: ApplicationCall): AuthenticatedUser? =
        runCatching { requireUser(call) }.getOrNull()

    suspend fun requireAdmin(call: ApplicationCall): AuthenticatedUser {
        val user = requireUser(call)
        if (user.role != UserRole.ADMIN) {
            throw AuthException(HttpStatusCode.Forbidden, "ADMIN_REQUIRED", "Admin role is required.")
        }
        return user
    }
}

/** Authentication boundary exception converted by StatusPages. */
class AuthException(
    val status: HttpStatusCode,
    val code: String,
    override val message: String,
) : BadRequestException(message)
