package com.yuhan8954.engine.geometry

import com.yuhan8954.engine.model.EPSILON
import com.yuhan8954.engine.model.NumericPosition
import kotlin.math.abs

/** Returns true if point lies on the open segment start-end. */
fun pointOnOpenSegment(
    point: NumericPosition,
    start: NumericPosition,
    end: NumericPosition,
    epsilon: Double = EPSILON,
): Boolean {
    val sx = end.x - start.x
    val sy = end.y - start.y
    val px = point.x - start.x
    val py = point.y - start.y
    val lengthSquared = sx * sx + sy * sy
    if (lengthSquared <= epsilon * epsilon) return false

    val cross = px * sy - py * sx
    if (abs(cross) > epsilon * kotlin.math.sqrt(lengthSquared)) return false

    val dot = px * sx + py * sy
    if (dot <= epsilon * kotlin.math.sqrt(lengthSquared)) return false
    if (dot >= lengthSquared - epsilon * kotlin.math.sqrt(lengthSquared)) return false
    return true
}
