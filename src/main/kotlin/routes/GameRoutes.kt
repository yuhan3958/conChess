package com.yuhan8954.routes

import com.yuhan8954.auth.CurrentUserProvider
import com.yuhan8954.common.ApiError
import com.yuhan8954.engine.model.MoveCommand
import com.yuhan8954.engine.model.SpecialMoveType
import com.yuhan8954.game.CreateAiGameOptions
import com.yuhan8954.game.GameActionResult
import com.yuhan8954.game.GameApplicationService
import com.yuhan8954.persistence.sqlite.SqliteStore
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

/** Registers game command and query API routes. */
fun Route.gameRoutes(currentUser: CurrentUserProvider, games: GameApplicationService, store: SqliteStore) {
    post("/api/ai-games") {
        call.requireCsrf()
        val user = currentUser.requireUser(call)
        val options = call.receive<CreateAiGameOptions>()
        call.respond(HttpStatusCode.Created, games.createAiGame(user.userId, options))
    }

    get("/api/games/{gameId}") {
        val user = currentUser.requireUser(call)
        val view = games.getGameForUser(call.parameters["gameId"].orEmpty(), user.userId)
        if (view == null) call.respond(HttpStatusCode.NotFound, ApiError("GAME_NOT_FOUND", "Game was not found."))
        else call.respond(view)
    }

    post("/api/games/{gameId}/move") {
        call.requireCsrf()
        val user = currentUser.requireUser(call)
        val body = call.receive<MoveRequest>()
        val gameId = call.parameters["gameId"].orEmpty()
        when (val result = games.move(user.userId, gameId, MoveCommand(body.pieceId, body.targetX, body.targetY, body.expectedVersion, body.requestId, body.specialMove))) {
            is GameActionResult.MoveApplied -> call.respond(MoveResponse(result.state, result.move, result.aiMove))
            is GameActionResult.Duplicate -> call.respond(mapOf("state" to result.state))
            is GameActionResult.IllegalMove -> call.respond(HttpStatusCode.Conflict, ApiError(result.code.name, result.message))
            is GameActionResult.GameFinished -> call.respond(HttpStatusCode.Conflict, mapOf("state" to result.state))
            is GameActionResult.Failure -> call.respond(HttpStatusCode.Conflict, ApiError(result.code, result.message))
            else -> call.respond(HttpStatusCode.Conflict, ApiError("INVALID_GAME_ACTION", "Invalid game action."))
        }
    }

    post("/api/games/{gameId}/resign") {
        call.requireCsrf()
        call.finishResponse(games.resign(currentUser.requireUser(call).userId, call.parameters["gameId"].orEmpty()))
    }

    post("/api/games/{gameId}/draw-offer") {
        call.requireCsrf()
        val user = currentUser.requireUser(call)
        when (val result = games.offerDraw(user.userId, call.parameters["gameId"].orEmpty())) {
            is GameActionResult.DrawOffered -> call.respond(mapOf("state" to result.state, "drawOfferByUserId" to result.offeredByUserId))
            is GameActionResult.Failure -> call.respond(HttpStatusCode.Conflict, ApiError(result.code, result.message))
            else -> call.respond(HttpStatusCode.Conflict, ApiError("DRAW_OFFER_FAILED", "Draw offer failed."))
        }
    }

    post("/api/games/{gameId}/draw-accept") {
        call.requireCsrf()
        call.finishResponse(games.acceptDraw(currentUser.requireUser(call).userId, call.parameters["gameId"].orEmpty()))
    }

    post("/api/games/{gameId}/draw-decline") {
        call.requireCsrf()
        val user = currentUser.requireUser(call)
        when (val result = games.declineDraw(user.userId, call.parameters["gameId"].orEmpty())) {
            is GameActionResult.DrawDeclined -> call.respond(mapOf("state" to result.state))
            is GameActionResult.Failure -> call.respond(HttpStatusCode.Conflict, ApiError(result.code, result.message))
            else -> call.respond(HttpStatusCode.Conflict, ApiError("DRAW_DECLINE_FAILED", "Draw decline failed."))
        }
    }

    get("/api/games/{gameId}/moves") {
        val user = currentUser.requireUser(call)
        val gameId = call.parameters["gameId"].orEmpty()
        if (games.getGameForUser(gameId, user.userId) == null) {
            call.respond(HttpStatusCode.NotFound, ApiError("GAME_NOT_FOUND", "Game was not found."))
        } else {
            call.respond(store.listMoves(gameId))
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.finishResponse(result: GameActionResult) {
    when (result) {
        is GameActionResult.GameFinished -> respond(mapOf("state" to result.state))
        is GameActionResult.Failure -> respond(HttpStatusCode.Conflict, ApiError(result.code, result.message))
        else -> respond(HttpStatusCode.Conflict, ApiError("GAME_FINISH_FAILED", "Game finish failed."))
    }
}

@Serializable
data class MoveRequest(
    val pieceId: String,
    val targetX: String,
    val targetY: String,
    val expectedVersion: Long,
    val requestId: String? = null,
    val specialMove: SpecialMoveType? = null,
)

@Serializable
data class MoveResponse(
    val state: com.yuhan8954.engine.model.GameState,
    val move: com.yuhan8954.engine.model.MoveRecord,
    val aiMove: com.yuhan8954.engine.model.MoveRecord? = null,
)
