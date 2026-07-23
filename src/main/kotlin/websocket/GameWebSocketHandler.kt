package com.yuhan8954.websocket

import com.yuhan8954.auth.CurrentUserProvider
import com.yuhan8954.engine.model.MoveCommand
import com.yuhan8954.game.GameActionResult
import com.yuhan8954.game.GameApplicationService
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Ktor WebSocket route handler for game commands and real-time synchronization. */
class GameWebSocketHandler(
    private val currentUserProvider: CurrentUserProvider,
    private val gameService: GameApplicationService,
    private val sessions: GameSessionManager,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun register(route: Route) {
        route.webSocket("/ws/games/{gameId}") {
            val user = currentUserProvider.requireUser(call)
            val gameId = call.parameters["gameId"] ?: return@webSocket close()
            val view = gameService.getGameForUser(gameId, user.userId)
                ?: return@webSocket close()
            sessions.connect(gameId, user.userId, this)
            sessions.send(this, ServerSocketMessage(WsServerType.CONNECTED, gameId = gameId, userId = user.userId))
            sessions.send(this, ServerSocketMessage(WsServerType.GAME_STATE, gameId = gameId, state = view.state, drawOfferByUserId = view.drawOfferByUserId))
            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val text = frame.readText()
                    if (text.length > 8192) {
                        sessions.send(this, ServerSocketMessage(WsServerType.ERROR, gameId = gameId, errorCode = "MESSAGE_TOO_LARGE", message = "WebSocket message is too large."))
                        continue
                    }
                    handleMessage(user.userId, gameId, text)
                }
            } finally {
                sessions.disconnect(gameId, user.userId, this)
            }
        }
    }

    private suspend fun handleMessage(userId: Long, gameId: String, text: String) {
        val message = try {
            json.decodeFromString<ClientSocketMessage>(text)
        } catch (_: SerializationException) {
            sessions.broadcast(gameId, ServerSocketMessage(WsServerType.ERROR, gameId = gameId, errorCode = "INVALID_MESSAGE", message = "Invalid WebSocket message."))
            return
        }
        when (message.type) {
            WsClientType.JOIN_GAME -> {
                val view = gameService.getGameForUser(gameId, userId)
                sessions.broadcast(gameId, ServerSocketMessage(WsServerType.GAME_STATE, requestId = message.requestId, gameId = gameId, state = view?.state))
            }
            WsClientType.MOVE -> {
                val command = MoveCommand(
                    pieceId = message.pieceId.orEmpty(),
                    targetXExpression = message.targetX.orEmpty(),
                    targetYExpression = message.targetY.orEmpty(),
                    expectedVersion = message.expectedVersion ?: -1L,
                    requestId = message.requestId,
                    specialMove = message.specialMove,
                )
                when (val result = gameService.move(userId, gameId, command)) {
                    is GameActionResult.MoveApplied -> {
                        sessions.broadcast(gameId, ServerSocketMessage(WsServerType.GAME_STATE_UPDATED, message.requestId, gameId, state = result.state, move = result.aiMove ?: result.move))
                        if (result.state.whiteInCheck || result.state.blackInCheck) {
                            sessions.broadcast(gameId, ServerSocketMessage(WsServerType.CHECK, gameId = gameId, state = result.state))
                        }
                    }
                    is GameActionResult.Duplicate -> sessions.broadcast(gameId, ServerSocketMessage(WsServerType.GAME_STATE, message.requestId, gameId, state = result.state))
                    is GameActionResult.IllegalMove -> sessions.broadcast(gameId, ServerSocketMessage(WsServerType.MOVE_REJECTED, message.requestId, gameId, state = result.currentState, errorCode = result.code.name, message = result.message))
                    is GameActionResult.GameFinished -> sessions.broadcast(gameId, ServerSocketMessage(WsServerType.GAME_FINISHED, message.requestId, gameId, state = result.state))
                    is GameActionResult.Failure -> sessions.broadcast(gameId, ServerSocketMessage(WsServerType.ERROR, message.requestId, gameId, errorCode = result.code, message = result.message))
                    else -> Unit
                }
            }
            WsClientType.RESIGN -> finishEvent(message, gameService.resign(userId, gameId), gameId)
            WsClientType.OFFER_DRAW -> when (val result = gameService.offerDraw(userId, gameId)) {
                is GameActionResult.DrawOffered -> sessions.broadcast(gameId, ServerSocketMessage(WsServerType.DRAW_OFFERED, message.requestId, gameId, state = result.state, drawOfferByUserId = result.offeredByUserId))
                is GameActionResult.Failure -> sessions.broadcast(gameId, ServerSocketMessage(WsServerType.ERROR, message.requestId, gameId, errorCode = result.code, message = result.message))
                else -> Unit
            }
            WsClientType.ACCEPT_DRAW -> finishEvent(message, gameService.acceptDraw(userId, gameId), gameId)
            WsClientType.DECLINE_DRAW -> when (val result = gameService.declineDraw(userId, gameId)) {
                is GameActionResult.DrawDeclined -> sessions.broadcast(gameId, ServerSocketMessage(WsServerType.DRAW_DECLINED, message.requestId, gameId, state = result.state))
                is GameActionResult.Failure -> sessions.broadcast(gameId, ServerSocketMessage(WsServerType.ERROR, message.requestId, gameId, errorCode = result.code, message = result.message))
                else -> Unit
            }
            WsClientType.PING -> sessions.broadcast(gameId, ServerSocketMessage(WsServerType.PONG, message.requestId, gameId))
        }
    }

    private suspend fun finishEvent(message: ClientSocketMessage, result: GameActionResult, gameId: String) {
        when (result) {
            is GameActionResult.GameFinished -> sessions.broadcast(gameId, ServerSocketMessage(WsServerType.GAME_FINISHED, message.requestId, gameId, state = result.state))
            is GameActionResult.Failure -> sessions.broadcast(gameId, ServerSocketMessage(WsServerType.ERROR, message.requestId, gameId, errorCode = result.code, message = result.message))
            else -> Unit
        }
    }
}
