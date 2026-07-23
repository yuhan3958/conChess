package com.yuhan8954.engine.service

import com.yuhan8954.engine.model.*
import com.yuhan8954.engine.movement.PieceMovementRules
import com.yuhan8954.engine.parser.MathExpressionParser
import com.yuhan8954.engine.parser.ParseResult
import java.time.Clock

/** Server-authoritative Continuous Chess rules engine independent of Ktor and persistence. */
class ContinuousChessEngine(
    private val expressionParser: MathExpressionParser,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val movementRules = PieceMovementRules()

    /** Creates the standard initial chess position on integer coordinates. */
    fun createInitialState(
        gameId: String,
        status: GameStatus = GameStatus.WAITING_FOR_OPPONENT,
        timeControl: TimeControl? = TimeControl(),
    ): GameState {
        val pieces = buildList {
            addBackRank(PieceColor.WHITE, 0.0)
            for (x in 0..7) add(Piece("white-pawn-$x", PieceType.PAWN, PieceColor.WHITE, numericPosition(x.toDouble(), 1.0)))
            for (x in 0..7) add(Piece("black-pawn-$x", PieceType.PAWN, PieceColor.BLACK, numericPosition(x.toDouble(), 6.0)))
            addBackRank(PieceColor.BLACK, 7.0)
        }
        return GameState(
            gameId = gameId,
            pieces = pieces,
            turn = PieceColor.WHITE,
            status = status,
            result = GameResult.UNFINISHED,
            endReason = null,
            moveHistory = emptyList(),
            whiteInCheck = false,
            blackInCheck = false,
            version = 0L,
            clock = timeControl?.let {
                GameClock(
                    timeControl = it,
                    whiteRemainingSeconds = it.initialSeconds,
                    blackRemainingSeconds = it.initialSeconds,
                    turnStartedAt = if (status == GameStatus.ACTIVE) clock.instant() else null,
                )
            },
        )
    }

    /** Validates a command, applies it immutably, and returns the new state plus move record. */
    fun validateAndApplyMove(state: GameState, actorColor: PieceColor, command: MoveCommand): EngineResult {
        if (state.status != GameStatus.ACTIVE) return EngineResult.Failure(MoveErrorCode.GAME_NOT_ACTIVE, "Game is not active.")
        if (state.version != command.expectedVersion) return EngineResult.Failure(MoveErrorCode.STALE_GAME_VERSION, "Game version is stale.")
        if (state.turn != actorColor) return EngineResult.Failure(MoveErrorCode.NOT_YOUR_TURN, "It is not your turn.")
        val piece = state.pieces.firstOrNull { it.id == command.pieceId }
            ?: return EngineResult.Failure(MoveErrorCode.PIECE_NOT_FOUND, "Piece was not found.")
        if (piece.color != actorColor) return EngineResult.Failure(MoveErrorCode.NOT_YOUR_PIECE, "You cannot move the opponent's piece.")

        val x = parseCoordinate(command.targetXExpression)
            ?: return EngineResult.Failure(MoveErrorCode.INVALID_EXPRESSION, "Invalid x expression.")
        val y = parseCoordinate(command.targetYExpression)
            ?: return EngineResult.Failure(MoveErrorCode.INVALID_EXPRESSION, "Invalid y expression.")
        val target = Position(x, y)

        if (piece.type == PieceType.KING && isCastlingTarget(piece, target, command.specialMove)) {
            return validateAndApplyCastle(state, actorColor, piece, target, command)
        }

        val rule = movementRules.ruleFor(piece.type)
        val validation = rule.validateMove(state, piece, target)
        if (validation is MoveValidationResult.Illegal) return EngineResult.Failure(validation.code, validation.message)
        val legal = validation as MoveValidationResult.Legal
        val movedPiece = promoteIfNeeded(piece.copy(position = legal.normalizedTarget, hasMoved = true))
        val nextPieces = state.pieces
            .filterNot { it.id == piece.id || it.id == legal.capturedPiece?.id }
            .plus(movedPiece)
            .sortedBy { it.id }
        val tentative = state.copy(pieces = nextPieces)
        if (isKingInCheck(tentative, actorColor)) {
            return EngineResult.Failure(MoveErrorCode.KING_WOULD_BE_IN_CHECK, "Move would leave your king in check.")
        }
        val sequence = state.moveHistory.size + 1
        val move = MoveRecord(
            sequence = sequence,
            pieceId = piece.id,
            pieceType = piece.type,
            color = piece.color,
            start = piece.position,
            end = legal.normalizedTarget,
            xExpression = command.targetXExpression,
            yExpression = command.targetYExpression,
            capturedPieceId = legal.capturedPiece?.id,
            specialMove = if (piece.type == PieceType.PAWN && legal.capturedPiece != null && !samePosition(legal.capturedPiece.position, legal.normalizedTarget)) {
                SpecialMoveType.EN_PASSANT
            } else {
                null
            },
            createdAt = clock.instant(),
        )
        val checked = applyCheckFlags(tentative)
        return EngineResult.Success(
            checked.copy(
                turn = actorColor.opposite(),
                moveHistory = state.moveHistory + move,
                version = state.version + 1,
                clock = advanceClock(state, actorColor, move.createdAt),
            ),
            move,
        )
    }

    /** Returns the state with accurate check flags for both kings. */
    fun applyCheckFlags(state: GameState): GameState = state.copy(
        whiteInCheck = isKingInCheck(state, PieceColor.WHITE),
        blackInCheck = isKingInCheck(state, PieceColor.BLACK),
    )

    /** Returns true if the given king is currently attacked by an opponent piece. */
    fun isKingInCheck(state: GameState, color: PieceColor): Boolean {
        val king = state.pieces.firstOrNull { it.type == PieceType.KING && it.color == color } ?: return false
        return state.pieces.any { attacker ->
            attacker.color != color && movementRules.ruleFor(attacker.type).attacks(state, attacker, king.position)
        }
    }

    private fun parseCoordinate(expression: String): Coordinate? {
        return when (val result = expressionParser.parse(expression)) {
            is ParseResult.Success -> Coordinate(result.originalExpression, result.normalizedExpression, result.value)
            is ParseResult.Failure -> null
        }
    }

    private fun promoteIfNeeded(piece: Piece): Piece {
        if (piece.type != PieceType.PAWN) return piece
        val y = piece.position.y.numericValue
        return when {
            piece.color == PieceColor.WHITE && approximatelyEqual(y, 7.0) -> piece.copy(type = PieceType.QUEEN)
            piece.color == PieceColor.BLACK && approximatelyEqual(y, 0.0) -> piece.copy(type = PieceType.QUEEN)
            else -> piece
        }
    }

    private fun isCastlingTarget(piece: Piece, target: Position, specialMove: SpecialMoveType?): Boolean {
        val start = piece.position.numeric()
        val end = target.numeric()
        val explicitCastle = specialMove == SpecialMoveType.CASTLE_KINGSIDE || specialMove == SpecialMoveType.CASTLE_QUEENSIDE
        return explicitCastle || (approximatelyEqual(start.y, end.y) && kotlin.math.abs(end.x - start.x - 2.0) <= EPSILON) ||
            (approximatelyEqual(start.y, end.y) && kotlin.math.abs(end.x - start.x + 2.0) <= EPSILON)
    }

    private fun validateAndApplyCastle(state: GameState, actorColor: PieceColor, king: Piece, target: Position, command: MoveCommand): EngineResult {
        val y = if (actorColor == PieceColor.WHITE) 0.0 else 7.0
        val start = king.position.numeric()
        val end = target.numeric()
        if (king.hasMoved || !approximatelyEqual(start.x, 4.0) || !approximatelyEqual(start.y, y)) {
            return EngineResult.Failure(MoveErrorCode.CASTLING_NOT_ALLOWED, "Castling requires an unmoved king on its starting point.")
        }
        if (!isIntegerCoordinate(end.x) || !isIntegerCoordinate(end.y) || !approximatelyEqual(end.y, y)) {
            return EngineResult.Failure(MoveErrorCode.CASTLING_NOT_ALLOWED, "Castling target must be a valid king castling point.")
        }
        val kingside = approximatelyEqual(end.x, 6.0)
        val queenside = approximatelyEqual(end.x, 2.0)
        if (!kingside && !queenside) {
            return EngineResult.Failure(MoveErrorCode.CASTLING_NOT_ALLOWED, "Castling target must be c-file or g-file.")
        }
        if (isKingInCheck(state, actorColor)) {
            return EngineResult.Failure(MoveErrorCode.CASTLING_NOT_ALLOWED, "Cannot castle while in check.")
        }
        val rookX = if (kingside) 7.0 else 0.0
        val rookTargetX = if (kingside) 5.0 else 3.0
        val rook = state.pieces.firstOrNull {
            it.type == PieceType.ROOK && it.color == actorColor && !it.hasMoved && samePosition(it.position, numericPosition(rookX, y))
        } ?: return EngineResult.Failure(MoveErrorCode.CASTLING_NOT_ALLOWED, "Required rook is not available for castling.")
        val clearSquares = if (kingside) listOf(5.0, 6.0) else listOf(1.0, 2.0, 3.0)
        if (clearSquares.any { x -> state.pieces.any { piece -> piece.id != king.id && piece.id != rook.id && samePosition(piece.position, numericPosition(x, y)) } }) {
            return EngineResult.Failure(MoveErrorCode.CASTLING_NOT_ALLOWED, "Castling path is blocked.")
        }
        val kingTravelSquares = if (kingside) listOf(5.0, 6.0) else listOf(3.0, 2.0)
        for (x in kingTravelSquares) {
            val trialPieces = state.pieces.filterNot { it.id == king.id }.plus(king.copy(position = numericPosition(x, y)))
            if (isKingInCheck(state.copy(pieces = trialPieces), actorColor)) {
                return EngineResult.Failure(MoveErrorCode.CASTLING_NOT_ALLOWED, "King would pass through check while castling.")
            }
        }
        val kingEnd = numericPosition(end.x, y)
        val rookEnd = numericPosition(rookTargetX, y)
        val movedKing = king.copy(position = kingEnd, hasMoved = true)
        val movedRook = rook.copy(position = rookEnd, hasMoved = true)
        val nextPieces = state.pieces.filterNot { it.id == king.id || it.id == rook.id }.plus(listOf(movedKing, movedRook)).sortedBy { it.id }
        val now = clock.instant()
        val move = MoveRecord(
            sequence = state.moveHistory.size + 1,
            pieceId = king.id,
            pieceType = king.type,
            color = king.color,
            start = king.position,
            end = kingEnd,
            xExpression = command.targetXExpression,
            yExpression = command.targetYExpression,
            capturedPieceId = null,
            specialMove = if (kingside) SpecialMoveType.CASTLE_KINGSIDE else SpecialMoveType.CASTLE_QUEENSIDE,
            createdAt = now,
        )
        val checked = applyCheckFlags(state.copy(pieces = nextPieces))
        return EngineResult.Success(
            checked.copy(
                turn = actorColor.opposite(),
                moveHistory = state.moveHistory + move,
                version = state.version + 1,
                clock = advanceClock(state, actorColor, now),
            ),
            move,
        )
    }

    private fun advanceClock(state: GameState, actorColor: PieceColor, movedAt: java.time.Instant): GameClock? {
        val current = state.clock ?: return null
        val elapsed = current.turnStartedAt?.let { java.time.Duration.between(it, movedAt).seconds.coerceAtLeast(0) } ?: 0
        val increment = current.timeControl.incrementSeconds
        return if (actorColor == PieceColor.WHITE) {
            current.copy(
                whiteRemainingSeconds = (current.whiteRemainingSeconds - elapsed + increment).coerceAtLeast(0),
                turnStartedAt = movedAt,
            )
        } else {
            current.copy(
                blackRemainingSeconds = (current.blackRemainingSeconds - elapsed + increment).coerceAtLeast(0),
                turnStartedAt = movedAt,
            )
        }
    }

    private fun MutableList<Piece>.addBackRank(color: PieceColor, y: Double) {
        val prefix = color.name.lowercase()
        val order = listOf(
            PieceType.ROOK,
            PieceType.KNIGHT,
            PieceType.BISHOP,
            PieceType.QUEEN,
            PieceType.KING,
            PieceType.BISHOP,
            PieceType.KNIGHT,
            PieceType.ROOK,
        )
        order.forEachIndexed { x, type ->
            val id = when (type) {
                PieceType.KING -> "$prefix-king"
                PieceType.QUEEN -> "$prefix-queen"
                PieceType.ROOK -> "$prefix-rook-${if (x == 0) "queen" else "king"}"
                PieceType.BISHOP -> "$prefix-bishop-${if (x == 2) "queen" else "king"}"
                PieceType.KNIGHT -> "$prefix-knight-${if (x == 1) "queen" else "king"}"
                PieceType.PAWN -> "$prefix-pawn-$x"
            }
            add(Piece(id, type, color, numericPosition(x.toDouble(), y)))
        }
    }
}
