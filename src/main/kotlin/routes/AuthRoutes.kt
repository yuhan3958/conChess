package com.yuhan8954.routes

import com.yuhan8954.auth.GoogleOAuthService
import com.yuhan8954.auth.UserSession
import com.yuhan8954.common.ApiError
import com.yuhan8954.persistence.sqlite.SqliteStore
import com.yuhan8954.user.AuthenticatedUser
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import java.security.SecureRandom
import java.util.Base64
import org.slf4j.LoggerFactory

/** Registers login, logout, and current-user routes. */
fun Route.authRoutes(oauth: GoogleOAuthService, store: SqliteStore) {
    val logger = LoggerFactory.getLogger("AuthRoutes")

    get("/auth/google/login") {
        val state = oauth.createState()
        val csrf = call.sessions.get<UserSession>()?.csrfToken ?: randomToken()
        call.sessions.set(UserSession(userId = null, csrfToken = csrf, oauthState = state))
        call.respondRedirect(oauth.loginUrl(state))
    }

    get("/auth/google/callback") {
        val code = call.request.queryParameters["code"]
        val state = call.request.queryParameters["state"]
        val session = call.sessions.get<UserSession>()
        if (code.isNullOrBlank() || state.isNullOrBlank() || session?.oauthState != state) {
            call.respond(HttpStatusCode.BadRequest, ApiError("OAUTH_STATE_INVALID", "OAuth login could not be verified."))
            return@get
        }
        val userId = runCatching { oauth.finishLogin(code, state) }.getOrElse { cause ->
            logger.warn("Google OAuth login failed: {}", cause.message)
            call.respond(HttpStatusCode.Unauthorized, ApiError("OAUTH_LOGIN_FAILED", "Google login failed."))
            return@get
        }
        call.sessions.clear<UserSession>()
        call.sessions.set(UserSession(userId = userId, csrfToken = randomToken(), oauthState = null))
        call.respondRedirect("/")
    }

    post("/auth/logout") {
        call.requireCsrf()
        call.sessions.clear<UserSession>()
        call.respond(mapOf("ok" to true))
    }

    get("/api/me") {
        val session = call.sessions.get<UserSession>()
        val user = session?.userId?.let(store::findUser)
        if (user == null) {
            call.respond(HttpStatusCode.Unauthorized, ApiError("AUTH_REQUIRED", "Login is required."))
            return@get
        }
        call.respond(
            MeResponse(
                user = AuthenticatedUser(user.id, user.displayName, user.profileImageUrl),
                email = user.email,
                csrfToken = session.csrfToken,
            ),
        )
    }
}

@kotlinx.serialization.Serializable
data class MeResponse(
    val user: AuthenticatedUser,
    val email: String?,
    val csrfToken: String,
)

private fun randomToken(): String {
    val bytes = ByteArray(24)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
