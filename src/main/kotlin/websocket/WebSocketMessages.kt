package com.yuhan8954.websocket

import com.yuhan8954.engine.model.GameState
import com.yuhan8954.engine.model.MoveRecord
import com.yuhan8954.engine.model.SpecialMoveType
import kotlinx.serialization.Serializable

/** Client-to-server WebSocket command type. */
@Serializable
enum class WsClientType { JOIN_GAME, MOVE, RESIGN, OFFER_DRAW, ACCEPT_DRAW, DECLINE_DRAW, PING }

/** Server-to-client WebSocket event type. */
@Serializable
enum class WsServerType {
    CONNECTED,
    PLAYER_JOINED,
    PLAYER_DISCONNECTED,
    GAME_STARTED,
    GAME_STATE,
    GAME_STATE_UPDATED,
    MOVE_REJECTED,
    CHECK,
    DRAW_OFFERED,
    DRAW_DECLINED,
    GAME_FINISHED,
    ERROR,
    PONG,
}

/** Client command envelope; authority-sensitive fields are resolved server-side. */
@Serializable
data class ClientSocketMessage(
    val type: WsClientType,
    val requestId: String? = null,
    val gameId: String? = null,
    val pieceId: String? = null,
    val targetX: String? = null,
    val targetY: String? = null,
    val expectedVersion: Long? = null,
    val specialMove: SpecialMoveType? = null,
)

/** Server event envelope. */
@Serializable
data class ServerSocketMessage(
    val type: WsServerType,
    val requestId: String? = null,
    val gameId: String? = null,
    val userId: Long? = null,
    val state: GameState? = null,
    val move: MoveRecord? = null,
    val errorCode: String? = null,
    val message: String? = null,
    val drawOfferByUserId: Long? = null,
)
