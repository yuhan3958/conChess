package com.yuhan8954.auth

import com.yuhan8954.application.AppConfig
import com.yuhan8954.persistence.sqlite.SqliteStore
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Verified Google profile fields used to create or update an internal user. */
data class GoogleProfile(
    val subject: String,
    val email: String?,
    val displayName: String?,
    val profileImageUrl: String?,
)

/** External OAuth client boundary for production Google calls and tests. */
interface OAuthClient {
    fun exchangeAndVerify(code: String, expectedState: String): GoogleProfile
}

/** Login service coordinating OAuth verification and internal user upsert. */
class GoogleOAuthService(
    private val config: AppConfig,
    private val store: SqliteStore,
    private val oauthClient: OAuthClient,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun createState(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun loginUrl(state: String): String {
        return "https://accounts.google.com/o/oauth2/v2/auth?" + formEncode(
            "client_id" to config.googleClientId,
            "redirect_uri" to config.googleRedirectUri,
            "response_type" to "code",
            "scope" to "openid email profile",
            "state" to state,
            "prompt" to "select_account",
        )
    }

    fun finishLogin(code: String, state: String): Long {
        val profile = oauthClient.exchangeAndVerify(code, state)
        val user = store.upsertGoogleUser(profile.subject, profile.email, profile.displayName, profile.profileImageUrl, clock.instant())
        return user.id
    }
}

/** Production Google OAuth implementation using Java's HttpClient. */
class GoogleHttpOAuthClient(private val config: AppConfig) : OAuthClient {
    private val client = HttpClient.newHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    override fun exchangeAndVerify(code: String, expectedState: String): GoogleProfile {
        require(config.googleClientId.isNotBlank() && config.googleClientSecret.isNotBlank()) {
            "Google OAuth is not configured."
        }
        val tokenBody = formEncode(
            "code" to code,
            "client_id" to config.googleClientId,
            "client_secret" to config.googleClientSecret,
            "redirect_uri" to config.googleRedirectUri,
            "grant_type" to "authorization_code",
        )
        val tokenResponse = post("https://oauth2.googleapis.com/token", tokenBody)
        val tokenJson = json.parseToJsonElement(tokenResponse).jsonObject
        val idToken = tokenJson["id_token"]?.jsonPrimitive?.content ?: error("Missing Google id_token.")
        val tokenInfo = get("https://oauth2.googleapis.com/tokeninfo?id_token=${urlEncode(idToken)}")
        val tokenInfoJson = json.parseToJsonElement(tokenInfo).jsonObject
        val issuer = tokenInfoJson["iss"]?.jsonPrimitive?.content
        val audience = tokenInfoJson["aud"]?.jsonPrimitive?.content
        require(issuer == "https://accounts.google.com" || issuer == "accounts.google.com") { "Invalid Google token issuer." }
        require(audience == config.googleClientId) { "Invalid Google token audience." }
        val subject = tokenInfoJson["sub"]?.jsonPrimitive?.content ?: error("Missing Google subject.")
        return GoogleProfile(
            subject = subject,
            email = tokenInfoJson["email"]?.jsonPrimitive?.content,
            displayName = tokenInfoJson["name"]?.jsonPrimitive?.content,
            profileImageUrl = tokenInfoJson["picture"]?.jsonPrimitive?.content,
        )
    }

    private fun post(url: String, body: String): String {
        val request = HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        require(response.statusCode() in 200..299) { "Google OAuth token exchange failed." }
        return response.body()
    }

    private fun get(url: String): String {
        val response = client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString())
        require(response.statusCode() in 200..299) { "Google token verification failed." }
        return response.body()
    }

}

private fun formEncode(vararg pairs: Pair<String, String>): String =
    pairs.joinToString("&") { "${urlEncode(it.first)}=${urlEncode(it.second)}" }

private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
