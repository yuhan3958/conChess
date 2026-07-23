package com.yuhan8954.game.ai

import com.yuhan8954.engine.model.EngineResult
import com.yuhan8954.engine.model.GameState
import com.yuhan8954.engine.model.MoveCommand
import com.yuhan8954.engine.model.NumericPosition
import com.yuhan8954.engine.model.Piece
import com.yuhan8954.engine.model.PieceColor
import com.yuhan8954.engine.model.PieceType
import com.yuhan8954.engine.model.approximatelyEqual
import com.yuhan8954.engine.model.formatNumber
import com.yuhan8954.engine.model.isInsideBoard
import com.yuhan8954.engine.service.ContinuousChessEngine
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.sqrt

/** AI difficulty levels exposed by game creation options. */
@Serializable
enum class AiDifficulty { EASY, NORMAL, HARD }

/** Chooses a simple deterministic AI move from a finite set of legal candidate targets. */
class AiMovePlanner(private val engine: ContinuousChessEngine) {
    fun chooseMove(state: GameState, color: PieceColor, difficulty: AiDifficulty): MoveCommand? {
        val legalMoves = state.pieces
            .filter { it.color == color }
            .flatMap { piece -> candidatesFor(state, piece).map { target -> piece to target } }
            .mapNotNull { (piece, target) ->
                val command = MoveCommand(piece.id, formatNumber(target.x), formatNumber(target.y), state.version, "ai-${state.version}-${piece.id}")
                when (val result = engine.validateAndApplyMove(state, color, command)) {
                    is EngineResult.Success -> PlannedMove(
                        command = command,
                        captures = result.move.capturedPieceId != null,
                        score = scoreMove(state, result.state, result.move.capturedPieceId, color),
                    )
                    is EngineResult.Failure -> null
                }
            }
        return when (difficulty) {
            AiDifficulty.EASY -> legalMoves.firstOrNull()?.command
            AiDifficulty.NORMAL -> legalMoves.firstOrNull { it.captures }?.command ?: legalMoves.firstOrNull()?.command
            AiDifficulty.HARD -> legalMoves.maxByOrNull { it.score }?.command
        }
    }

    private fun candidatesFor(state: GameState, piece: Piece): List<NumericPosition> {
        val own = piece.position.x.numericValue to piece.position.y.numericValue
        val targets = buildList {
            when (piece.type) {
                PieceType.PAWN -> pawnCandidates(piece).forEach(::add)
                PieceType.KING -> kingCandidates(piece).forEach(::add)
                PieceType.KNIGHT -> knightCandidates(piece).forEach(::add)
                PieceType.ROOK -> slidingIntegerCandidates(piece, rook = true, bishop = false).forEach(::add)
                PieceType.BISHOP -> slidingIntegerCandidates(piece, rook = false, bishop = true).forEach(::add)
                PieceType.QUEEN -> slidingIntegerCandidates(piece, rook = true, bishop = true).forEach(::add)
            }
            state.pieces
                .filter { it.color != piece.color }
                .map { it.position }
                .filter { attacksLine(piece, it.x.numericValue, it.y.numericValue) || piece.type == PieceType.KNIGHT }
                .forEach { add(NumericPosition(it.x.numericValue, it.y.numericValue)) }
        }
        return targets
            .filter { isInsideBoard(it) }
            .filterNot { approximatelyEqual(it.x, own.first) && approximatelyEqual(it.y, own.second) }
            .distinctBy { "${formatNumber(it.x)}:${formatNumber(it.y)}" }
    }

    private fun pawnCandidates(piece: Piece): List<NumericPosition> {
        val x = piece.position.x.numericValue
        val y = piece.position.y.numericValue
        val direction = if (piece.color == PieceColor.WHITE) 1.0 else -1.0
        return listOf(
            NumericPosition(x, y + direction),
            NumericPosition(x, y + 2.0 * direction),
            NumericPosition(x - 1.0, y + direction),
            NumericPosition(x + 1.0, y + direction),
        )
    }

    private fun kingCandidates(piece: Piece): List<NumericPosition> {
        val x = piece.position.x.numericValue
        val y = piece.position.y.numericValue
        return (-1..1).flatMap { dx ->
            (-1..1).mapNotNull { dy ->
                if (dx == 0 && dy == 0) null else NumericPosition(x + dx, y + dy)
            }
        }
    }

    private fun knightCandidates(piece: Piece): List<NumericPosition> {
        val x = piece.position.x.numericValue
        val y = piece.position.y.numericValue
        val r = sqrt(5.0)
        val diagonalX = sqrt(5.0) / 2.0
        val diagonalY = sqrt(15.0) / 2.0
        return listOf(
            NumericPosition(x + 1, y + 2),
            NumericPosition(x + 2, y + 1),
            NumericPosition(x - 1, y + 2),
            NumericPosition(x - 2, y + 1),
            NumericPosition(x + 1, y - 2),
            NumericPosition(x + 2, y - 1),
            NumericPosition(x - 1, y - 2),
            NumericPosition(x - 2, y - 1),
            NumericPosition(x + r, y),
            NumericPosition(x - r, y),
            NumericPosition(x + diagonalX, y + diagonalY),
            NumericPosition(x - diagonalX, y - diagonalY),
        )
    }

    private fun slidingIntegerCandidates(piece: Piece, rook: Boolean, bishop: Boolean): List<NumericPosition> {
        val x = piece.position.x.numericValue
        val y = piece.position.y.numericValue
        return buildList {
            if (rook) {
                for (i in 0..7) {
                    add(NumericPosition(i.toDouble(), y))
                    add(NumericPosition(x, i.toDouble()))
                }
            }
            if (bishop) {
                for (i in 0..7) {
                    val d = i.toDouble()
                    add(NumericPosition(x + d, y + d))
                    add(NumericPosition(x + d, y - d))
                    add(NumericPosition(x - d, y + d))
                    add(NumericPosition(x - d, y - d))
                }
            }
        }
    }

    private fun attacksLine(piece: Piece, x: Double, y: Double): Boolean {
        val sx = piece.position.x.numericValue
        val sy = piece.position.y.numericValue
        val dx = abs(x - sx)
        val dy = abs(y - sy)
        return when (piece.type) {
            PieceType.ROOK -> dx <= 1e-7 || dy <= 1e-7
            PieceType.BISHOP -> abs(dx - dy) <= 1e-7
            PieceType.QUEEN -> dx <= 1e-7 || dy <= 1e-7 || abs(dx - dy) <= 1e-7
            else -> false
        }
    }

    private fun scoreMove(before: GameState, after: GameState, capturedPieceId: String?, color: PieceColor): Int {
        val capturedValue = capturedPieceId
            ?.let { id -> before.pieces.firstOrNull { it.id == id }?.type }
            ?.let(::pieceValue)
            ?: 0
        val checkBonus = if (color == PieceColor.WHITE && after.blackInCheck || color == PieceColor.BLACK && after.whiteInCheck) 3 else 0
        return capturedValue * 10 + checkBonus
    }

    private fun pieceValue(type: PieceType): Int = when (type) {
        PieceType.PAWN -> 1
        PieceType.KNIGHT, PieceType.BISHOP -> 3
        PieceType.ROOK -> 5
        PieceType.QUEEN -> 9
        PieceType.KING -> 100
    }

    private data class PlannedMove(val command: MoveCommand, val captures: Boolean, val score: Int)
}
