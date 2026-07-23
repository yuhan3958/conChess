package com.yuhan8954.engine.movement

import com.yuhan8954.engine.model.GameState
import com.yuhan8954.engine.model.MoveValidationResult
import com.yuhan8954.engine.model.Piece
import com.yuhan8954.engine.model.Position

/** Piece-specific movement and attack contract. */
interface MovementRule {
    fun validateMove(state: GameState, piece: Piece, target: Position): MoveValidationResult
    fun attacks(state: GameState, piece: Piece, target: Position): Boolean
}
