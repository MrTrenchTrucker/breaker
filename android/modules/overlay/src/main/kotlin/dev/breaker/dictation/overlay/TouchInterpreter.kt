package dev.breaker.dictation.overlay

import kotlin.math.roundToInt

/**
 * What a finger gesture on the tile has come to, as [TouchInterpreter] reports it.
 *
 * A gesture is either a tap (a press and release that stayed within the touch
 * slop) or a drag (a press that moved past the slop). A drag is reported as a
 * run of [DragBy] steps and then one [DragEnd]; a tap is reported once.
 */
internal sealed interface TouchEvent {
    /** The finger went down and came up without leaving the touch slop. */
    data object Tap : TouchEvent

    /**
     * The finger moved the tile: [dx] and [dy] are whole pixels, positive to the right and down.
     *
     * The first step of a drag carries the whole distance from the point where the
     * finger went down. Every later step carries the distance since the previous step.
     */
    data class DragBy(val dx: Int, val dy: Int) : TouchEvent

    /** A drag is over, because the finger was released or the gesture was cancelled. */
    data object DragEnd : TouchEvent
}

/**
 * Turns raw down, move and up points into taps and drags.
 *
 * The one rule is the touch slop, [slopPx], measured as a straight-line
 * distance from the point where the finger went down. A move that takes the
 * finger strictly farther than the slop starts a drag. A finger that stays
 * within the slop, exactly at the slop included, is a tap when released.
 *
 * Once a drag has started it stays a drag until the gesture ends, even when the
 * finger comes back inside the slop, so a tile dragged back to where it started
 * is still saved and never taps.
 *
 * The interpreter holds only the gesture in progress: where the finger went
 * down, whether it is dragging, and a reference point, kept as exact floats.
 * Each step is the whole-pixel difference between the new point and the
 * reference point, and the reference point then moves forward by exactly the
 * whole pixels that were reported, not to the finger. The remainder is carried
 * to the next move, so a step smaller than half a pixel is never lost: moves of
 * +0.4, +0.4 and +0.4 px report 0, 1 and 0. It has no clock and no Android
 * types, so a long press is not a thing it knows about.
 */
internal class TouchInterpreter(private val slopPx: Int) {

    private var active = false
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f

    /** True from the move that passed the slop until the gesture ends. */
    var isDragging: Boolean = false
        private set

    /** The finger went down at ([x], [y]). Always starts a fresh gesture and forgets any earlier one. */
    fun down(x: Float, y: Float) {
        active = true
        isDragging = false
        downX = x
        downY = y
        lastX = x
        lastY = y
    }

    /**
     * The finger moved to ([x], [y]).
     *
     * Returns null when no gesture is active (no [down] yet) or when the finger
     * has not yet passed the slop. The move that passes it, and every move after
     * it, returns a [TouchEvent.DragBy]. The first one carries the whole delta
     * from the down point; later ones carry the delta from the reference point,
     * each taken to the nearest whole pixel. The reference point then advances by
     * exactly the whole pixels reported, so the remainder is carried to the next
     * move: moves of +0.4, +0.4 and +0.4 px report 0, 1 and 0.
     */
    fun move(x: Float, y: Float): TouchEvent? {
        if (!active) return null
        if (!isDragging) {
            if (!pastSlop(x, y)) return null
            isDragging = true
        }
        val dx = (x - lastX).roundToInt()
        val dy = (y - lastY).roundToInt()
        lastX += dx
        lastY += dy
        return TouchEvent.DragBy(dx, dy)
    }

    /**
     * The finger came up at ([x], [y]), which ends the gesture.
     *
     * Returns null when no gesture is active. Returns [TouchEvent.DragEnd] after
     * a drag, or when the release point itself is past the slop. Otherwise the
     * finger stayed within the slop and the answer is [TouchEvent.Tap].
     */
    fun up(x: Float, y: Float): TouchEvent? {
        if (!active) return null
        val drag = isDragging || pastSlop(x, y)
        reset()
        return if (drag) TouchEvent.DragEnd else TouchEvent.Tap
    }

    /**
     * The system took the gesture away.
     *
     * Ends a drag in progress like a release and returns [TouchEvent.DragEnd].
     * Without a drag it returns null: a cancelled press is not a tap.
     */
    fun cancel(): TouchEvent? {
        val drag = active && isDragging
        reset()
        return if (drag) TouchEvent.DragEnd else null
    }

    /** True when ([x], [y]) is strictly farther than the slop from the down point, in a straight line. */
    private fun pastSlop(x: Float, y: Float): Boolean {
        val dx = (x - downX).toDouble()
        val dy = (y - downY).toDouble()
        val slop = slopPx.toDouble()
        return dx * dx + dy * dy > slop * slop
    }

    /** Forgets the gesture: nothing is active and nothing is being dragged. */
    private fun reset() {
        active = false
        isDragging = false
    }
}
