package dev.breaker.dictation.overlay

/*
 * The path commands of the close target art: a move, a straight line, a cubic curve, and the end of a sub-path.
 * The coordinates are art units of the close target canvas, 740 wide and 200 high.
 */

/** One command of a close target path. */
internal sealed interface PathCmd

/** Starts a new sub-path at the point ([x], [y]). */
internal data class MoveTo(val x: Float, val y: Float) : PathCmd

/** A straight line from the current point to ([x], [y]). */
internal data class LineTo(val x: Float, val y: Float) : PathCmd

/** A cubic curve from the current point through the control points to ([x], [y]). */
internal data class CurveTo(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val x: Float,
    val y: Float,
) : PathCmd

/** Closes the current sub-path. */
internal object ClosePath : PathCmd
