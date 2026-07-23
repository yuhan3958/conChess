package com.yuhan8954.game

import com.yuhan8954.engine.model.*
import com.yuhan8954.engine.service.ContinuousChessEngine
import com.yuhan8954.game.ai.AiDifficulty
import com.yuhan8954.game.ai.AiMovePlanner
import com.yuhan8954.persistence.sqlite.SqliteStore
import com.yuhan8954.persistence.sqlite.StoredGame
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

/** Coordinates server-authoritative game commands with persistence and per-game locking. */
class GameApplicationService(
    private val store: SqliteStore,
    private val engine: ContinuousChessEngine,
    private val aiUserId: Long? = null,
    private val aiMovePlanner: AiMovePlanner? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val processedRequests = ConcurrentHashMap<String, Long>()
    private val aiDifficulties = ConcurrentHashMap<String, AiDifficulty>()

    suspend fun getGameForUser(gameId: String, userId: Long): GameView? {
        val stored = store.getGame(gameId) ?: return null
        val color = stored.colorOf(userId) ?: return null
        return GameView(
            stored.id,
            color,
            stored.whiteUserId,
            stored.blackUserId,
            stored.state,
            stored.drawOfferByUserId,
            isAiGame = stored.whiteUserId == aiUserId || stored.blackUserId == aiUserId,
            aiDifficulty = aiDifficultyFor(stored.id),
        )
    }

    fun createAiGame(userId: Long, options: CreateAiGameOptions = CreateAiGameOptions()): GameView {
        val ai = aiUserId ?: error("AI user is not configured.")
        val userColor = options.userColor
        val whiteUserId = if (userColor == PieceColor.WHITE) userId else ai
        val blackUserId = if (userColor == PieceColor.BLACK) userId else ai
        val timeControl = TimeControl(options.initialSeconds.coerceIn(30, 86_400), options.incrementSeconds.coerceIn(0, 3_600))
        val initialState = engine.createInitialState("ai-${options.difficulty.name.lowercase()}-${java.util.UUID.randomUUID()}", GameStatus.ACTIVE, timeControl)
        aiDifficulties[initialState.gameId] = options.difficulty
        store.createStandaloneGame(initialState, whiteUserId, blackUserId, clock.instant())
        val state = if (userColor == PieceColor.BLACK) {
            maybeApplyAiMove(StoredGame(initialState.gameId, whiteUserId, blackUserId, initialState, GameStatus.ACTIVE.name, GameResult.UNFINISHED.name, null, null, null, null, 0), initialState)?.state ?: initialState
        } else {
            initialState
        }
        return GameView(state.gameId, userColor, whiteUserId, blackUserId, state, null, isAiGame = true, aiDifficulty = options.difficulty)
    }

    suspend fun move(userId: Long, gameId: String, command: MoveCommand): GameActionResult = lock(gameId).withLock {
        command.requestId?.let { requestId ->
            val key = "$gameId:$userId:$requestId"
            if (processedRequests.containsKey(key)) {
                val stored = store.getGame(gameId) ?: return@withLock GameActionResult.Failure("GAME_NOT_FOUND", "Game was not found.")
                return@withLock GameActionResult.Duplicate(stored.state)
            }
        }
        val stored = store.getGame(gameId) ?: return@withLock GameActionResult.Failure("GAME_NOT_FOUND", "Game was not found.")
        val color = stored.colorOf(userId) ?: return@withLock GameActionResult.Failure("FORBIDDEN", "You are not a player in this game.")
        if (remainingSeconds(stored.state, color) <= 0) {
            val finalState = stored.state.copy(
                status = GameStatus.FINISHED,
                result = if (color == PieceColor.WHITE) GameResult.BLACK_WIN else GameResult.WHITE_WIN,
                endReason = GameEndReason.TIMEOUT,
                version = stored.state.version + 1,
            )
            store.finishGame(gameId, finalState, clock.instant())
            return@withLock GameActionResult.GameFinished(finalState)
        }
        when (val result = engine.validateAndApplyMove(stored.state, color, command)) {
            is EngineResult.Failure -> GameActionResult.IllegalMove(result.code, result.message, stored.state)
            is EngineResult.Success -> {
                store.saveMove(result.state, result.move)
                command.requestId?.let { processedRequests["$gameId:$userId:$it"] = result.state.version }
                val aiMove = maybeApplyAiMove(stored, result.state)
                GameActionResult.MoveApplied(aiMove?.state ?: result.state, result.move, aiMove?.move)
            }
        }
    }

    suspend fun resign(userId: Long, gameId: String): GameActionResult = finishByUser(gameId, userId, GameEndReason.RESIGNATION) { color ->
        if (color == PieceColor.WHITE) GameResult.BLACK_WIN else GameResult.WHITE_WIN
    }

    suspend fun offerDraw(userId: Long, gameId: String): GameActionResult = lock(gameId).withLock {
        val stored = store.getGame(gameId) ?: return@withLock GameActionResult.Failure("GAME_NOT_FOUND", "Game was not found.")
        stored.colorOf(userId) ?: return@withLock GameActionResult.Failure("FORBIDDEN", "You are not a player in this game.")
        if (stored.state.status != GameStatus.ACTIVE) return@withLock GameActionResult.Failure("GAME_NOT_ACTIVE", "Game is not active.")
        if (stored.drawOfferByUserId != null) return@withLock GameActionResult.Failure("DRAW_ALREADY_OFFERED", "A draw offer is already pending.")
        store.setDrawOffer(gameId, userId)
        GameActionResult.DrawOffered(stored.state, userId)
    }

    suspend fun declineDraw(userId: Long, gameId: String): GameActionResult = lock(gameId).withLock {
        val stored = store.getGame(gameId) ?: return@withLock GameActionResult.Failure("GAME_NOT_FOUND", "Game was not found.")
        stored.colorOf(userId) ?: return@withLock GameActionResult.Failure("FORBIDDEN", "You are not a player in this game.")
        if (stored.drawOfferByUserId == null || stored.drawOfferByUserId == userId) {
            return@withLock GameActionResult.Failure("NO_DRAW_TO_DECLINE", "No opponent draw offer is pending.")
        }
        store.setDrawOffer(gameId, null)
        GameActionResult.DrawDeclined(stored.state)
    }

    suspend fun acceptDraw(userId: Long, gameId: String): GameActionResult = lock(gameId).withLock {
        val stored = store.getGame(gameId) ?: return@withLock GameActionResult.Failure("GAME_NOT_FOUND", "Game was not found.")
        stored.colorOf(userId) ?: return@withLock GameActionResult.Failure("FORBIDDEN", "You are not a player in this game.")
        if (stored.drawOfferByUserId == null || stored.drawOfferByUserId == userId) {
            return@withLock GameActionResult.Failure("NO_DRAW_TO_ACCEPT", "No opponent draw offer is pending.")
        }
        val finalState = stored.state.copy(
            status = GameStatus.FINISHED,
            result = GameResult.DRAW,
            endReason = GameEndReason.DRAW_AGREEMENT,
            version = stored.state.version + 1,
        )
        store.finishGame(gameId, finalState, clock.instant())
        GameActionResult.GameFinished(finalState)
    }

    private suspend fun finishByUser(
        gameId: String,
        userId: Long,
        reason: GameEndReason,
        resultFor: (PieceColor) -> GameResult,
    ): GameActionResult = lock(gameId).withLock {
        val stored = store.getGame(gameId) ?: return@withLock GameActionResult.Failure("GAME_NOT_FOUND", "Game was not found.")
        val color = stored.colorOf(userId) ?: return@withLock GameActionResult.Failure("FORBIDDEN", "You are not a player in this game.")
        if (stored.state.status == GameStatus.FINISHED) return@withLock GameActionResult.GameFinished(stored.state)
        val finalState = stored.state.copy(
            status = GameStatus.FINISHED,
            result = resultFor(color),
            endReason = reason,
            version = stored.state.version + 1,
        )
        store.finishGame(gameId, finalState, clock.instant())
        GameActionResult.GameFinished(finalState)
    }

    private fun lock(gameId: String): Mutex = locks.computeIfAbsent(gameId) { Mutex() }

    private fun remainingSeconds(state: GameState, color: PieceColor): Long {
        val gameClock = state.clock ?: return Long.MAX_VALUE
        if (state.turn != color || gameClock.turnStartedAt == null) {
            return if (color == PieceColor.WHITE) gameClock.whiteRemainingSeconds else gameClock.blackRemainingSeconds
        }
        val elapsed = java.time.Duration.between(gameClock.turnStartedAt, clock.instant()).seconds.coerceAtLeast(0)
        val base = if (color == PieceColor.WHITE) gameClock.whiteRemainingSeconds else gameClock.blackRemainingSeconds
        return (base - elapsed).coerceAtLeast(0)
    }

    private fun aiDifficultyFor(gameId: String): AiDifficulty? =
        aiDifficulties[gameId] ?: AiDifficulty.entries.firstOrNull { gameId.startsWith("ai-${it.name.lowercase()}-") }

    private fun maybeApplyAiMove(stored: StoredGame, state: GameState): AiAppliedMove? {
        if (aiUserId == null || aiMovePlanner == null) return null
        val aiColor = when (aiUserId) {
            stored.whiteUserId -> PieceColor.WHITE
            stored.blackUserId -> PieceColor.BLACK
            else -> return null
        }
        if (state.status != GameStatus.ACTIVE || state.turn != aiColor) return null
        val command = aiMovePlanner.chooseMove(state, aiColor, aiDifficultyFor(stored.id) ?: AiDifficulty.NORMAL) ?: return null
        return when (val result = engine.validateAndApplyMove(state, aiColor, command)) {
            is EngineResult.Success -> {
                store.saveMove(result.state, result.move)
                AiAppliedMove(result.state, result.move)
            }
            is EngineResult.Failure -> null
        }
    }
}

/** User-specific game view. */
@kotlinx.serialization.Serializable
data class GameView(
    val gameId: String,
    val yourColor: PieceColor,
    val whiteUserId: Long?,
    val blackUserId: Long?,
    val state: GameState,
    val drawOfferByUserId: Long?,
    val isAiGame: Boolean = false,
    val aiDifficulty: AiDifficulty? = null,
)

/** AI game creation options. */
@kotlinx.serialization.Serializable
data class CreateAiGameOptions(
    val userColor: PieceColor = PieceColor.WHITE,
    val initialSeconds: Long = 600,
    val incrementSeconds: Long = 0,
    val difficulty: AiDifficulty = AiDifficulty.NORMAL,
)

/** Service-level result for game commands. */
sealed interface GameActionResult {
    data class MoveApplied(val state: GameState, val move: MoveRecord, val aiMove: MoveRecord? = null) : GameActionResult
    data class Duplicate(val state: GameState) : GameActionResult
    data class IllegalMove(val code: MoveErrorCode, val message: String, val currentState: GameState) : GameActionResult
    data class DrawOffered(val state: GameState, val offeredByUserId: Long) : GameActionResult
    data class DrawDeclined(val state: GameState) : GameActionResult
    data class GameFinished(val state: GameState) : GameActionResult
    data class Failure(val code: String, val message: String) : GameActionResult
}

/** AI move applied immediately after a human move. */
data class AiAppliedMove(val state: GameState, val move: MoveRecord)
