package com.yuhan8954.application

import com.yuhan8954.auth.CurrentUserProvider
import com.yuhan8954.auth.GoogleHttpOAuthClient
import com.yuhan8954.auth.GoogleOAuthService
import com.yuhan8954.auth.UserSession
import com.yuhan8954.common.ApiError
import com.yuhan8954.engine.parser.SafeMathExpressionParser
import com.yuhan8954.engine.service.ContinuousChessEngine
import com.yuhan8954.game.ai.AiMovePlanner
import com.yuhan8954.game.GameApplicationService
import com.yuhan8954.persistence.DatabaseFactory
import com.yuhan8954.persistence.migration.SchemaMigrator
import com.yuhan8954.persistence.sqlite.SqliteStore
import com.yuhan8954.room.FriendlyRoomService
import com.yuhan8954.room.HostPlaysWhiteStrategy
import com.yuhan8954.room.InviteCodeGenerator
import com.yuhan8954.routes.authRoutes
import com.yuhan8954.routes.gameRoutes
import com.yuhan8954.routes.historyRoutes
import com.yuhan8954.routes.adminRoutes
import com.yuhan8954.routes.roomRoutes
import com.yuhan8954.websocket.GameSessionManager
import com.yuhan8954.websocket.GameWebSocketHandler
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sessions.SessionTransportTransformerMessageAuthentication
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import io.ktor.server.sessions.sessions
import io.ktor.server.websocket.WebSockets
import io.ktor.server.http.content.staticFiles
import io.ktor.server.http.content.staticResources
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** Ktor application module wiring configuration, persistence, routes, and WebSockets. */
fun Application.module() {
    val config = AppConfig.fromEnvironment()
    val databaseFactory = DatabaseFactory(config.databasePath)
    SchemaMigrator(databaseFactory).migrate()
    val store = SqliteStore(databaseFactory)
    val engine = ContinuousChessEngine(SafeMathExpressionParser())
    val aiUser = store.ensureAiUser(java.time.Clock.systemUTC().instant())
    val currentUser = CurrentUserProvider(store)
    val oauth = GoogleOAuthService(config, store, GoogleHttpOAuthClient(config))
    val rooms = FriendlyRoomService(store, engine, InviteCodeGenerator(), HostPlaysWhiteStrategy(), config)
    val games = GameApplicationService(store, engine, aiUser.id, AiMovePlanner(engine))
    val sessions = GameSessionManager()
    Files.createDirectories(Path.of(config.profileImageStoragePath))

    install(DefaultHeaders)
    install(CallLogging)
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
    }
    install(CORS) {
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowHeader(HttpHeaders.ContentType)
        allowHeader("X-CSRF-Token")
        allowCredentials = true
        anyHost()
    }
    install(Sessions) {
        cookie<UserSession>("CONCHESS_SESSION") {
            cookie.httpOnly = true
            cookie.secure = config.secureCookies
            cookie.extensions["SameSite"] = "Lax"
            transform(SessionTransportTransformerMessageAuthentication(sha256(config.sessionSecret)))
        }
    }
    install(WebSockets) {
        maxFrameSize = 8192
        masking = false
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            val code = when (cause) {
                is com.yuhan8954.auth.AuthException -> cause.code
                else -> "INTERNAL_ERROR"
            }
            val status = when (cause) {
                is com.yuhan8954.auth.AuthException -> cause.status
                else -> HttpStatusCode.InternalServerError
            }
            call.respond(status, ApiError(code, if (status == HttpStatusCode.InternalServerError) "Internal server error." else cause.message.orEmpty()))
        }
    }

    routing {
        staticResources("/static", "static")
        staticFiles("/profile-images", File(config.profileImageStoragePath))
        get("/") { call.respondText(indexHtml(), ContentType.Text.Html) }
        get("/game/join/{inviteCode}") { call.respondText(indexHtml(), ContentType.Text.Html) }
        get("/game/{gameId}") { call.respondText(indexHtml(), ContentType.Text.Html) }
        get("/history") { call.respondText(indexHtml(), ContentType.Text.Html) }
        get("/admin") { call.respondText(indexHtml(), ContentType.Text.Html) }
        authRoutes(oauth, store, config.profileImageStoragePath)
        adminRoutes(currentUser, store)
        roomRoutes(currentUser, rooms)
        gameRoutes(currentUser, games, store)
        historyRoutes(currentUser, store)
        GameWebSocketHandler(currentUser, games, sessions).register(this)
    }
}

private fun sha256(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())

private fun indexHtml(): String =
    object {}.javaClass.getResource("/static/index.html")?.readText() ?: "<!doctype html><title>Continuous Chess</title>"
