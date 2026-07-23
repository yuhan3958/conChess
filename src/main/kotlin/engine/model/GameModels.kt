package com.yuhan8954.engine.model

import kotlinx.serialization.Serializable
import java.time.Instant

/** Continuous Chess piece kind. */
@Serializable
enum class PieceType { KING, QUEEN, ROOK, BISHOP, KNIGHT, PAWN }

/** Player color. */
@Serializable
enum class PieceColor {
    WHITE,
    BLACK;

    fun opposite(): PieceColor = if (this == WHITE) BLACK else WHITE
}

/** A point-like chess piece on the continuous board. */
@Serializable
data class Piece(
    val id: String,
    val type: PieceType,
    val color: PieceColor,
    val position: Position,
    val hasMoved: Boolean = false,
)

/** Lifecycle status for a game. */
@Serializable
enum class GameStatus { WAITING_FOR_OPPONENT, ACTIVE, FINISHED }

/** Final result from the game perspective. */
@Serializable
enum class GameResult { WHITE_WIN, BLACK_WIN, DRAW, UNFINISHED }

/** Reason a game ended. */
@Serializable
enum class GameEndReason { RESIGNATION, DRAW_AGREEMENT, DISCONNECT_TIMEOUT, TIMEOUT, ADMINISTRATIVE, UNKNOWN }

/** Optional special move marker for moves that move more than one piece or capture off-target. */
@Serializable
enum class SpecialMoveType { CASTLE_KINGSIDE, CASTLE_QUEENSIDE, EN_PASSANT }

/** Per-game clock configuration. */
@Serializable
data class TimeControl(
    val initialSeconds: Long = 600,
    val incrementSeconds: Long = 0,
)

/** Remaining time state for both players. */
@Serializable
data class GameClock(
    val timeControl: TimeControl = TimeControl(),
    val whiteRemainingSeconds: Long = 600,
    val blackRemainingSeconds: Long = 600,
    @Serializable(with = InstantIsoSerializer::class)
    val turnStartedAt: Instant? = null,
)

/** A validated and applied move. */
@Serializable
data class MoveRecord(
    val sequence: Int,
    val pieceId: String,
    val pieceType: PieceType,
    val color: PieceColor,
    val start: Position,
    val end: Position,
    val xExpression: String,
    val yExpression: String,
    val capturedPieceId: String?,
    val specialMove: SpecialMoveType? = null,
    @Serializable(with = InstantIsoSerializer::class)
    val createdAt: Instant,
)

/** Full server-authoritative game state. */
@Serializable
data class GameState(
    val gameId: String,
    val pieces: List<Piece>,
    val turn: PieceColor,
    val status: GameStatus,
    val result: GameResult,
    val endReason: GameEndReason?,
    val moveHistory: List<MoveRecord>,
    val whiteInCheck: Boolean,
    val blackInCheck: Boolean,
    val version: Long,
    val clock: GameClock? = null,
)

/** Client command for a move request. */
data class MoveCommand(
    val pieceId: String,
    val targetXExpression: String,
    val targetYExpression: String,
    val expectedVersion: Long,
    val requestId: String? = null,
    val specialMove: SpecialMoveType? = null,
)

/** Engine-level move result. */
sealed interface EngineResult {
    data class Success(val state: GameState, val move: MoveRecord) : EngineResult
    data class Failure(val code: MoveErrorCode, val message: String) : EngineResult
}

/** Move validation result used by piece movement rules. */
sealed interface MoveValidationResult {
    data class Legal(val normalizedTarget: Position, val capturedPiece: Piece?) : MoveValidationResult
    data class Illegal(val code: MoveErrorCode, val message: String) : MoveValidationResult
}

/** Machine-readable move error code. */
@Serializable
enum class MoveErrorCode {
    GAME_NOT_ACTIVE,
    NOT_YOUR_TURN,
    PIECE_NOT_FOUND,
    NOT_YOUR_PIECE,
    TARGET_OUTSIDE_BOARD,
    TARGET_OCCUPIED_BY_ALLY,
    INVALID_PIECE_MOVEMENT,
    PATH_BLOCKED,
    INTEGER_COORDINATE_REQUIRED,
    KING_WOULD_BE_IN_CHECK,
    KING_CAPTURE_NOT_ALLOWED,
    CASTLING_NOT_ALLOWED,
    STALE_GAME_VERSION,
    INVALID_EXPRESSION,
}
