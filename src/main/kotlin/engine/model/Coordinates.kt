package com.yuhan8954.engine.model

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.round

/** Global floating-point tolerance used by the rules engine. */
const val EPSILON: Double = 1e-7

/** A user-entered coordinate expression and its normalized numeric value. */
@Serializable
data class Coordinate(
    val expression: String,
    val normalizedExpression: String,
    val numericValue: Double,
)

/** A display-preserving board position. */
@Serializable
data class Position(
    val x: Coordinate,
    val y: Coordinate,
)

/** A numeric board position used by geometry and movement checks. */
@Serializable
data class NumericPosition(
    val x: Double,
    val y: Double,
)

/** Compares two Doubles using the engine tolerance. */
fun approximatelyEqual(a: Double, b: Double): Boolean = abs(a - b) <= EPSILON

/** Returns true when a coordinate is within EPSILON of an integer. */
fun isIntegerCoordinate(value: Double): Boolean = approximatelyEqual(value, round(value))

/** Snaps a near-integer coordinate to that integer; otherwise returns the original value. */
fun snapToIntegerIfClose(value: Double): Double = if (isIntegerCoordinate(value)) round(value) else value

/** Compares two board positions as point locations. */
fun samePosition(a: Position, b: Position): Boolean = samePosition(a.numeric(), b.numeric())

/** Compares two numeric positions using squared distance. */
fun samePosition(a: NumericPosition, b: NumericPosition): Boolean {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return dx * dx + dy * dy <= EPSILON * EPSILON
}

/** Converts a display position to its numeric form. */
fun Position.numeric(): NumericPosition = NumericPosition(x.numericValue, y.numericValue)

/** Builds a position from raw numeric values. */
fun numericPosition(x: Double, y: Double): Position = Position(
    Coordinate(formatNumber(x), formatNumber(x), x),
    Coordinate(formatNumber(y), formatNumber(y), y),
)

/** Builds a display-preserving position from expressions and numeric values. */
fun expressedPosition(xExpression: String, xValue: Double, yExpression: String, yValue: Double): Position = Position(
    Coordinate(xExpression, normalizeExpressionText(xExpression), xValue),
    Coordinate(yExpression, normalizeExpressionText(yExpression), yValue),
)

/** Normalizes expression text for stable storage without changing its meaning. */
fun normalizeExpressionText(expression: String): String = expression.trim().replace(Regex("\\s+"), "")

/** Formats an engine-generated coordinate compactly. */
fun formatNumber(value: Double): String = if (isIntegerCoordinate(value)) round(value).toLong().toString() else value.toString()

/** Returns true when a numeric position is inside the logical board. */
fun isInsideBoard(position: NumericPosition): Boolean =
    position.x >= -EPSILON && position.x <= 7.0 + EPSILON && position.y >= -EPSILON && position.y <= 7.0 + EPSILON

/** Snaps both axes of an integer-constrained target. */
fun snapIntegerPosition(position: Position): Position = numericPosition(
    snapToIntegerIfClose(position.x.numericValue),
    snapToIntegerIfClose(position.y.numericValue),
).copy(
    x = Coordinate(position.x.expression, position.x.normalizedExpression, snapToIntegerIfClose(position.x.numericValue)),
    y = Coordinate(position.y.expression, position.y.normalizedExpression, snapToIntegerIfClose(position.y.numericValue)),
)
