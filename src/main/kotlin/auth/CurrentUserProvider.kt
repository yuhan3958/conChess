package com.yuhan8954.auth

import com.yuhan8954.persistence.sqlite.SqliteStore
import com.yuhan8954.user.AuthenticatedUser
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
        return AuthenticatedUser(user.id, user.displayName, user.profileImageUrl)
    }

    suspend fun optionalUser(call: ApplicationCall): AuthenticatedUser? =
        runCatching { requireUser(call) }.getOrNull()
}

/** Authentication boundary exception converted by StatusPages. */
class AuthException(
    val status: HttpStatusCode,
    val code: String,
    override val message: String,
) : BadRequestException(message)
