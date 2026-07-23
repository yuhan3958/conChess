package engine

import com.yuhan8954.engine.model.*
import com.yuhan8954.engine.parser.SafeMathExpressionParser
import com.yuhan8954.engine.service.ContinuousChessEngine
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ContinuousChessEngineTest {
    private val engine = ContinuousChessEngine(SafeMathExpressionParser())

    @Test
    fun `knight moves on sqrt5 circle and ignores blockers`() {
        val state = stateWith(
            Piece("wk", PieceType.KING, PieceColor.WHITE, numericPosition(4.0, 0.0)),
            Piece("bk", PieceType.KING, PieceColor.BLACK, numericPosition(4.0, 7.0)),
            Piece("n", PieceType.KNIGHT, PieceColor.WHITE, numericPosition(0.0, 0.0)),
            Piece("blocker", PieceType.PAWN, PieceColor.BLACK, numericPosition(1.0, 1.0)),
        )
        val result = engine.validateAndApplyMove(state, PieceColor.WHITE, MoveCommand("n", "sqrt(5)/2", "sqrt(15)/2", 0))
        assertIs<EngineResult.Success>(result)
        assertEquals(1L, result.state.version)
    }

    @Test
    fun `rook validates continuous straight movement and path blocking`() {
        val blocked = stateWith(
            Piece("wk", PieceType.KING, PieceColor.WHITE, numericPosition(4.0, 0.0)),
            Piece("bk", PieceType.KING, PieceColor.BLACK, numericPosition(4.0, 7.0)),
            Piece("r", PieceType.ROOK, PieceColor.WHITE, numericPosition(1.2, 3.5)),
            Piece("p", PieceType.PAWN, PieceColor.BLACK, numericPosition(3.0, 3.5)),
        )
        val result = engine.validateAndApplyMove(blocked, PieceColor.WHITE, MoveCommand("r", "6.8", "3.5", 0))
        assertIs<EngineResult.Failure>(result)
        assertEquals(MoveErrorCode.PATH_BLOCKED, result.code)
    }

    @Test
    fun `bishop queen pawn king and promotion rules work`() {
        val bishop = stateWith(
            Piece("wk", PieceType.KING, PieceColor.WHITE, numericPosition(4.0, 0.0)),
            Piece("bk", PieceType.KING, PieceColor.BLACK, numericPosition(4.0, 7.0)),
            Piece("b", PieceType.BISHOP, PieceColor.WHITE, numericPosition(1.0, 1.0)),
        )
        assertIs<EngineResult.Success>(engine.validateAndApplyMove(bishop, PieceColor.WHITE, MoveCommand("b", "2.5", "2.5", 0)))

        val queen = stateWith(
            Piece("wk", PieceType.KING, PieceColor.WHITE, numericPosition(4.0, 0.0)),
            Piece("bk", PieceType.KING, PieceColor.BLACK, numericPosition(4.0, 7.0)),
            Piece("q", PieceType.QUEEN, PieceColor.WHITE, numericPosition(1.0, 1.0)),
        )
        assertIs<EngineResult.Failure>(engine.validateAndApplyMove(queen, PieceColor.WHITE, MoveCommand("q", "3", "2", 0)))

        val pawn = stateWith(
            Piece("wk", PieceType.KING, PieceColor.WHITE, numericPosition(4.0, 0.0)),
            Piece("bk", PieceType.KING, PieceColor.BLACK, numericPosition(4.0, 7.0)),
            Piece("p", PieceType.PAWN, PieceColor.WHITE, numericPosition(0.0, 6.0), hasMoved = true),
        )
        val promoted = assertIs<EngineResult.Success>(engine.validateAndApplyMove(pawn, PieceColor.WHITE, MoveCommand("p", "0", "7", 0)))
        assertEquals(PieceType.QUEEN, promoted.state.pieces.first { it.id == "p" }.type)
    }

    @Test
    fun `check is detected and self exposing move is rejected`() {
        val checked = engine.applyCheckFlags(
            stateWith(
                Piece("wk", PieceType.KING, PieceColor.WHITE, numericPosition(4.0, 0.0)),
                Piece("bk", PieceType.KING, PieceColor.BLACK, numericPosition(0.0, 7.0)),
                Piece("br", PieceType.ROOK, PieceColor.BLACK, numericPosition(4.0, 7.0)),
            ),
        )
        assertTrue(checked.whiteInCheck)

        val pinned = stateWith(
            Piece("wk", PieceType.KING, PieceColor.WHITE, numericPosition(4.0, 0.0)),
            Piece("bk", PieceType.KING, PieceColor.BLACK, numericPosition(0.0, 7.0)),
            Piece("wr", PieceType.ROOK, PieceColor.WHITE, numericPosition(4.0, 1.0)),
            Piece("br", PieceType.ROOK, PieceColor.BLACK, numericPosition(4.0, 7.0)),
        )
        val result = engine.validateAndApplyMove(pinned, PieceColor.WHITE, MoveCommand("wr", "5", "1", 0))
        assertIs<EngineResult.Failure>(result)
        assertEquals(MoveErrorCode.KING_WOULD_BE_IN_CHECK, result.code)
    }

    @Test
    fun `turn and stale version are rejected`() {
        val state = engine.createInitialState("g", GameStatus.ACTIVE)
        assertEquals(MoveErrorCode.NOT_YOUR_TURN, (engine.validateAndApplyMove(state, PieceColor.BLACK, MoveCommand("black-pawn-0", "0", "5", 0)) as EngineResult.Failure).code)
        assertEquals(MoveErrorCode.STALE_GAME_VERSION, (engine.validateAndApplyMove(state, PieceColor.WHITE, MoveCommand("white-pawn-0", "0", "2", 2)) as EngineResult.Failure).code)
    }

    @Test
    fun `castling moves king and rook when path is clear`() {
        val state = stateWith(
            Piece("white-king", PieceType.KING, PieceColor.WHITE, numericPosition(4.0, 0.0)),
            Piece("white-rook-king", PieceType.ROOK, PieceColor.WHITE, numericPosition(7.0, 0.0)),
            Piece("black-king", PieceType.KING, PieceColor.BLACK, numericPosition(4.0, 7.0)),
        )
        val result = assertIs<EngineResult.Success>(
            engine.validateAndApplyMove(state, PieceColor.WHITE, MoveCommand("white-king", "6", "0", 0, specialMove = SpecialMoveType.CASTLE_KINGSIDE)),
        )
        assertEquals(SpecialMoveType.CASTLE_KINGSIDE, result.move.specialMove)
        assertTrue(result.state.pieces.any { it.id == "white-king" && samePosition(it.position, numericPosition(6.0, 0.0)) })
        assertTrue(result.state.pieces.any { it.id == "white-rook-king" && samePosition(it.position, numericPosition(5.0, 0.0)) })
    }

    @Test
    fun `en passant captures pawn from adjacent square`() {
        val lastMove = MoveRecord(
            sequence = 1,
            pieceId = "black-pawn",
            pieceType = PieceType.PAWN,
            color = PieceColor.BLACK,
            start = numericPosition(5.0, 6.0),
            end = numericPosition(5.0, 4.0),
            xExpression = "5",
            yExpression = "4",
            capturedPieceId = null,
            createdAt = Instant.EPOCH,
        )
        val state = stateWith(
            Piece("white-king", PieceType.KING, PieceColor.WHITE, numericPosition(4.0, 0.0)),
            Piece("black-king", PieceType.KING, PieceColor.BLACK, numericPosition(4.0, 7.0)),
            Piece("white-pawn", PieceType.PAWN, PieceColor.WHITE, numericPosition(4.0, 4.0), hasMoved = true),
            Piece("black-pawn", PieceType.PAWN, PieceColor.BLACK, numericPosition(5.0, 4.0), hasMoved = true),
        ).copy(moveHistory = listOf(lastMove), version = 1)
        val result = assertIs<EngineResult.Success>(
            engine.validateAndApplyMove(state, PieceColor.WHITE, MoveCommand("white-pawn", "5", "5", 1)),
        )
        assertEquals(SpecialMoveType.EN_PASSANT, result.move.specialMove)
        assertTrue(result.state.pieces.none { it.id == "black-pawn" })
    }

    private fun stateWith(vararg pieces: Piece): GameState = GameState(
        gameId = "g",
        pieces = pieces.toList(),
        turn = PieceColor.WHITE,
        status = GameStatus.ACTIVE,
        result = GameResult.UNFINISHED,
        endReason = null,
        moveHistory = emptyList(),
        whiteInCheck = false,
        blackInCheck = false,
        version = 0,
    )
}
