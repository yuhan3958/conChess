package com.yuhan8954.websocket

import io.ktor.websocket.Frame
import io.ktor.websocket.send
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/** Tracks live WebSocket sessions by game and broadcasts server events. */
class GameSessionManager {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val sessions = ConcurrentHashMap<String, MutableMap<Long, MutableSet<io.ktor.server.websocket.DefaultWebSocketServerSession>>>()

    suspend fun connect(gameId: String, userId: Long, session: io.ktor.server.websocket.DefaultWebSocketServerSession) {
        val users = sessions.computeIfAbsent(gameId) { ConcurrentHashMap() }
        val userSessions = users.computeIfAbsent(userId) { ConcurrentHashMap.newKeySet() }
        userSessions.add(session)
        broadcast(gameId, ServerSocketMessage(WsServerType.PLAYER_JOINED, gameId = gameId, userId = userId))
    }

    suspend fun disconnect(gameId: String, userId: Long, session: io.ktor.server.websocket.DefaultWebSocketServerSession) {
        sessions[gameId]?.get(userId)?.remove(session)
        broadcast(gameId, ServerSocketMessage(WsServerType.PLAYER_DISCONNECTED, gameId = gameId, userId = userId))
    }

    suspend fun send(session: io.ktor.server.websocket.DefaultWebSocketServerSession, message: ServerSocketMessage) {
        session.send(Frame.Text(json.encodeToString(message)))
    }

    suspend fun broadcast(gameId: String, message: ServerSocketMessage) {
        sessions[gameId]?.values?.flatten()?.forEach { session ->
            runCatching { send(session, message) }
        }
    }
}
