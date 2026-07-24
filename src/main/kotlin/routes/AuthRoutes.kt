package com.yuhan8954.routes

import com.yuhan8954.auth.GoogleOAuthService
import com.yuhan8954.auth.UserSession
import com.yuhan8954.common.ApiError
import com.yuhan8954.persistence.sqlite.SqliteStore
import com.yuhan8954.user.AuthenticatedUser
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.patch
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import java.util.UUID
import org.slf4j.LoggerFactory

/** Registers login, logout, and current-user routes. */
fun Route.authRoutes(oauth: GoogleOAuthService, store: SqliteStore, profileImageStoragePath: String, clock: Clock = Clock.systemUTC()) {
    val logger = LoggerFactory.getLogger("AuthRoutes")
    val profileImageDirectory = Path.of(profileImageStoragePath)

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
        if (user.bannedAt != null) {
            call.respond(HttpStatusCode.Forbidden, ApiError("USER_BANNED", "This account is banned."))
            return@get
        }
        call.respond(
            MeResponse(
                user = AuthenticatedUser(user.id, user.displayName, user.profileImageUrl, user.role),
                email = user.email,
                csrfToken = session.csrfToken,
            ),
        )
    }

    patch("/api/me") {
        call.requireCsrf()
        val session = call.sessions.get<UserSession>()
        val userId = session?.userId
        if (userId == null) {
            call.respond(HttpStatusCode.Unauthorized, ApiError("AUTH_REQUIRED", "Login is required."))
            return@patch
        }
        if (store.findUser(userId)?.bannedAt != null) {
            call.respond(HttpStatusCode.Forbidden, ApiError("USER_BANNED", "This account is banned."))
            return@patch
        }
        val request = call.receive<UpdateProfileRequest>()
        val displayName = request.displayName?.trim()
        if (displayName.isNullOrBlank() || displayName.length > 32) {
            call.respond(HttpStatusCode.BadRequest, ApiError("DISPLAY_NAME_INVALID", "Display name must be 1 to 32 characters."))
            return@patch
        }
        val user = store.updateUserProfile(userId, displayName, null, clock.instant())
        if (user == null) {
            call.respond(HttpStatusCode.NotFound, ApiError("USER_NOT_FOUND", "User was not found."))
            return@patch
        }
        call.respond(ProfileUpdateResponse(AuthenticatedUser(user.id, user.displayName, user.profileImageUrl, user.role)))
    }

    post("/api/me/profile-image") {
        call.requireCsrf()
        val session = call.sessions.get<UserSession>()
        val userId = session?.userId
        if (userId == null) {
            call.respond(HttpStatusCode.Unauthorized, ApiError("AUTH_REQUIRED", "Login is required."))
            return@post
        }
        if (store.findUser(userId)?.bannedAt != null) {
            call.respond(HttpStatusCode.Forbidden, ApiError("USER_BANNED", "This account is banned."))
            return@post
        }
        val request = call.receive<UploadProfileImageRequest>()
        val extension = profileImageExtension(request.contentType)
        if (extension == null) {
            call.respond(HttpStatusCode.BadRequest, ApiError("PROFILE_IMAGE_TYPE_INVALID", "Only PNG, JPEG, WebP, and GIF images are allowed."))
            return@post
        }
        val rawBase64 = request.dataBase64.substringAfter(",", request.dataBase64).trim()
        val bytes = runCatching { Base64.getDecoder().decode(rawBase64) }.getOrNull()
        if (bytes == null || bytes.isEmpty() || bytes.size > MAX_PROFILE_IMAGE_BYTES) {
            call.respond(HttpStatusCode.BadRequest, ApiError("PROFILE_IMAGE_INVALID", "Profile image must be a valid image up to 2 MB."))
            return@post
        }
        Files.createDirectories(profileImageDirectory)
        val fileName = "${userId}-${UUID.randomUUID()}.$extension"
        Files.write(profileImageDirectory.resolve(fileName), bytes)
        val imageUrl = "/profile-images/$fileName"
        val user = store.updateUserProfile(userId, store.findUser(userId)?.displayName, imageUrl, clock.instant())
        if (user == null) {
            call.respond(HttpStatusCode.NotFound, ApiError("USER_NOT_FOUND", "User was not found."))
            return@post
        }
        call.respond(ProfileUpdateResponse(AuthenticatedUser(user.id, user.displayName, user.profileImageUrl, user.role)))
    }
}

@kotlinx.serialization.Serializable
data class MeResponse(
    val user: AuthenticatedUser,
    val email: String?,
    val csrfToken: String,
)

@kotlinx.serialization.Serializable
data class UpdateProfileRequest(
    val displayName: String? = null,
)

@kotlinx.serialization.Serializable
data class UploadProfileImageRequest(
    val fileName: String? = null,
    val contentType: String,
    val dataBase64: String,
)

@kotlinx.serialization.Serializable
data class ProfileUpdateResponse(
    val user: AuthenticatedUser,
)

private const val MAX_PROFILE_IMAGE_BYTES = 2 * 1024 * 1024

private fun profileImageExtension(contentType: String): String? =
    when (contentType.lowercase().substringBefore(";").trim()) {
        "image/png" -> "png"
        "image/jpeg" -> "jpg"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        else -> null
    }

private fun randomToken(): String {
    val bytes = ByteArray(24)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
