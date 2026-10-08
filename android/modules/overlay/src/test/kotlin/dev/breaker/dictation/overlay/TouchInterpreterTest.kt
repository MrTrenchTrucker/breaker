package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The tap-or-drag decision, on raw points and a slop given in pixels.
 *
 * Every expected report is worked out by hand in the comments next to it. Each test builds its own
 * interpreter, so no test depends on another.
 */
class TouchInterpreterTest {
    private fun assertEvent(what: String, expected: TouchEvent?, actual: TouchEvent?) {
        assertEquals("android_overlay: $what: expected $expected", expected, actual)
    }

    private fun assertDragging(what: String, expected: Boolean, touch: TouchInterpreter) {
        assertEquals("android_overlay: $what: expected isDragging $expected", expected, touch.isDragging)
    }

    /** A failure means a finger that stayed inside the slop is not reported as a tap when it lifts. */
    @Test
    fun `a release within the slop is a tap`() {
        val touch = TouchInterpreter(8)
        touch.down(100f, 100f)
        assertEvent("a move of 3 px inside a slop of 8 reports nothing", null, touch.move(103f, 100f))
        assertDragging("after a move inside the slop", false, touch)
        assertEvent("a release at 3 px away", TouchEvent.Tap, touch.up(103f, 100f))

        val still = TouchInterpreter(8)
        still.down(100f, 100f)
        assertEvent("a release on the down point", TouchEvent.Tap, still.up(100f, 100f))

        // Wobble in several directions, all inside 8 px of the down point:
        // (-5, 4) is 6.4 away and (4, -6) is 7.2 away.
        val wobble = TouchInterpreter(8)
        wobble.down(100f, 100f)
        assertEvent("a wobble to 95,104", null, wobble.move(95f, 104f))
        assertEvent("a wobble to 104,94", null, wobble.move(104f, 94f))
        assertDragging("after wobbling inside the slop", false, wobble)
        assertEvent("a release at 97,101 after a wobble", TouchEvent.Tap, wobble.up(97f, 101f))
    }

    /** A failure means the slop boundary is off by one: exactly the slop must be a tap and one more a drag. */
    @Test
    fun `a release at exactly the slop is still a tap`() {
        // 8 px away on one axis is exactly the slop.
        val direct = TouchInterpreter(8)
        direct.down(100f, 100f)
        assertEvent("a release 8 px to the right", TouchEvent.Tap, direct.up(108f, 100f))

        val vertical = TouchInterpreter(8)
        vertical.down(100f, 100f)
        assertEvent("a release 8 px above", TouchEvent.Tap, vertical.up(100f, 92f))

        val moved = TouchInterpreter(8)
        moved.down(100f, 100f)
        assertEvent("a move to exactly 8 px reports nothing", null, moved.move(108f, 100f))
        assertDragging("after a move to exactly the slop", false, moved)
        assertEvent("a release at exactly 8 px after that move", TouchEvent.Tap, moved.up(108f, 100f))

        // One pixel past: 9 px is a drag, both as a release and as a move.
        val past = TouchInterpreter(8)
        past.down(100f, 100f)
        assertEvent("a release 9 px to the right", TouchEvent.DragEnd, past.up(109f, 100f))

        val pastMove = TouchInterpreter(8)
        pastMove.down(100f, 100f)
        assertEvent("a move to 9 px starts a drag with the whole 9", TouchEvent.DragBy(9, 0), pastMove.move(109f, 100f))
        assertDragging("after a move to 9 px", true, pastMove)

        // Half a pixel past the slop is also past it: 8.5 > 8.
        val half = TouchInterpreter(8)
        half.down(100f, 100f)
        assertEvent("a release 8.5 px to the right", TouchEvent.DragEnd, half.up(108.5f, 100f))
    }

    /** A failure means the first drag report is the part beyond the slop, or only the last step, instead of the whole delta from the down point. */
    @Test
    fun `the first move past the slop starts a drag with the whole delta from the down point`() {
        val direct = TouchInterpreter(8)
        direct.down(100f, 100f)
        assertEvent("a move 10 px right", TouchEvent.DragBy(10, 0), direct.move(110f, 100f))
        assertDragging("after the first move past the slop", true, direct)

        // (6, 7) is 9.2 away, past 8, though each axis alone is inside 8: the whole (6, 7) is reported.
        val diagonal = TouchInterpreter(8)
        diagonal.down(100f, 100f)
        assertEvent("a diagonal move to 106,107", TouchEvent.DragBy(6, 7), diagonal.move(106f, 107f))

        // A move inside the slop first, then one past: the report is from the down point (10), not from the previous move (6).
        val staged = TouchInterpreter(8)
        staged.down(100f, 100f)
        assertEvent("a first move to 104 inside the slop", null, staged.move(104f, 100f))
        assertEvent("a second move to 110 past the slop", TouchEvent.DragBy(10, 0), staged.move(110f, 100f))
        assertEvent("a third move to 112", TouchEvent.DragBy(2, 0), staged.move(112f, 100f))

        // The first report is rounded to whole pixels: (10.4, 0.6) -> (10, 1).
        val fractional = TouchInterpreter(8)
        fractional.down(100f, 100f)
        assertEvent("a first move to 110.4,100.6", TouchEvent.DragBy(10, 1), fractional.move(110.4f, 100.6f))
    }

    /** A failure means later moves report from the down point instead of the previous report, or a sub-pixel remainder is lost. */
    @Test
    fun `later moves report the delta since the previous report`() {
        // Slop 8: 100 -> 110 is (10, 0); 110,100 -> 125,90 is (15, -10), not (25, -10); a repeat is (0, 0).
        val steps = TouchInterpreter(8)
        steps.down(100f, 100f)
        assertEvent("the first move to 110,100", TouchEvent.DragBy(10, 0), steps.move(110f, 100f))
        assertEvent("the second move to 125,90", TouchEvent.DragBy(15, -10), steps.move(125f, 90f))
        assertEvent("a move to the same point", TouchEvent.DragBy(0, 0), steps.move(125f, 90f))

        // Slop 2: down 0,0 then 3,0 reports (3, 0) and the reference becomes 3.
        // 3.4 - 3 = 0.4 -> 0 (reference stays 3); 3.8 - 3 = 0.8 -> 1 (reference becomes 4); 4.2 - 4 = 0.2 -> 0.
        val carry = TouchInterpreter(2)
        carry.down(0f, 0f)
        assertEvent("the first move to 3,0", TouchEvent.DragBy(3, 0), carry.move(3f, 0f))
        assertEvent("a move to 3.4 is 0.4 px", TouchEvent.DragBy(0, 0), carry.move(3.4f, 0f))
        assertEvent("a move to 3.8 carries the remainder to 0.8 px", TouchEvent.DragBy(1, 0), carry.move(3.8f, 0f))
        assertEvent("a move to 4.2 is 0.2 px from the new reference", TouchEvent.DragBy(0, 0), carry.move(4.2f, 0f))

        // The same carry on the vertical axis.
        val vertical = TouchInterpreter(2)
        vertical.down(0f, 0f)
        assertEvent("the first move to 0,3", TouchEvent.DragBy(0, 3), vertical.move(0f, 3f))
        assertEvent("a move to y 3.4", TouchEvent.DragBy(0, 0), vertical.move(0f, 3.4f))
        assertEvent("a move to y 3.8", TouchEvent.DragBy(0, 1), vertical.move(0f, 3.8f))
        assertEvent("a move to y 4.2", TouchEvent.DragBy(0, 0), vertical.move(0f, 4.2f))
    }

    /** A failure means lifting a finger after a drag reports a tap, or does not end the drag. */
    @Test
    fun `a release after a drag ends the drag and is not a tap`() {
        val touch = TouchInterpreter(8)
        touch.down(100f, 100f)
        assertEvent("the move to 120,100", TouchEvent.DragBy(20, 0), touch.move(120f, 100f))
        assertEvent("the release at 120,100", TouchEvent.DragEnd, touch.up(120f, 100f))
        assertDragging("after the release", false, touch)

        // A release away from the last reported point still just ends the drag.
        val away = TouchInterpreter(8)
        away.down(100f, 100f)
        assertEvent("the move to 120,100", TouchEvent.DragBy(20, 0), away.move(120f, 100f))
        assertEvent("the release at 125,100", TouchEvent.DragEnd, away.up(125f, 100f))

        // A release already past the slop with no move before it is a drag end, not a tap.
        val direct = TouchInterpreter(8)
        direct.down(100f, 100f)
        assertEvent("a release 20 px away with no move", TouchEvent.DragEnd, direct.up(120f, 100f))
        assertDragging("after a release with no move", false, direct)
    }

    /** A failure means a drag that comes back to its start point is downgraded to a tap. */
    @Test
    fun `returning to the start point after a drag still ends as a drag`() {
        val touch = TouchInterpreter(8)
        touch.down(100f, 100f)
        assertEvent("the move out to 120,100", TouchEvent.DragBy(20, 0), touch.move(120f, 100f))
        assertEvent("the move back to 100,100", TouchEvent.DragBy(-20, 0), touch.move(100f, 100f))
        assertDragging("back at the start point", true, touch)
        // Still a drag: a small step is reported, not dropped as being inside the slop.
        assertEvent("a 3 px step after returning", TouchEvent.DragBy(3, 0), touch.move(103f, 100f))
        assertEvent("the release at 103,100", TouchEvent.DragEnd, touch.up(103f, 100f))
    }

    /** A failure means the slop is measured per axis or as a sum instead of a straight-line distance. */
    @Test
    fun `the slop is a straight-line distance`() {
        // Slop 5: (3, 4) is exactly 5 away, a tap; (4, 4) is 5.66 away, a drag.
        val three = TouchInterpreter(5)
        three.down(100f, 100f)
        assertEvent("a release at 3,4 away", TouchEvent.Tap, three.up(103f, 104f))

        val four = TouchInterpreter(5)
        four.down(100f, 100f)
        assertEvent("a release at 4,4 away", TouchEvent.DragEnd, four.up(104f, 104f))

        val move = TouchInterpreter(5)
        move.down(100f, 100f)
        assertEvent("a move to 3,4 away reports nothing", null, move.move(103f, 104f))
        assertEvent("a move on to 4,4 away reports the whole delta", TouchEvent.DragBy(4, 4), move.move(104f, 104f))

        // The other quadrant: (-3, -4) is exactly 5 away.
        val negative = TouchInterpreter(5)
        negative.down(100f, 100f)
        assertEvent("a release at -3,-4 away", TouchEvent.Tap, negative.up(97f, 96f))

        // On one axis: 5 is a tap, 6 is a drag.
        val five = TouchInterpreter(5)
        five.down(100f, 100f)
        assertEvent("a release 5 px below", TouchEvent.Tap, five.up(100f, 105f))

        val six = TouchInterpreter(5)
        six.down(100f, 100f)
        assertEvent("a release 6 px below", TouchEvent.DragEnd, six.up(100f, 106f))
    }

    /** A failure means a cancel produces an event when no drag was going, or does not end a drag that was. */
    @Test
    fun `a cancel without a drag gives nothing and a cancel in a drag ends it`() {
        // Nothing active: a release and a cancel both report nothing.
        val idle = TouchInterpreter(8)
        assertEvent("a release with no gesture", null, idle.up(100f, 100f))
        assertEvent("a cancel with no gesture", null, idle.cancel())

        // A cancel after a move inside the slop gives nothing, and the gesture is over: a later release is no tap.
        val inside = TouchInterpreter(8)
        inside.down(100f, 100f)
        assertEvent("a move to 103,100 reports nothing", null, inside.move(103f, 100f))
        assertEvent("a cancel inside the slop", null, inside.cancel())
        assertEvent("a release after a cancel", null, inside.up(103f, 100f))

        // A cancel in a drag ends it, once.
        val dragging = TouchInterpreter(8)
        dragging.down(100f, 100f)
        assertEvent("the move to 120,100", TouchEvent.DragBy(20, 0), dragging.move(120f, 100f))
        assertDragging("before the cancel", true, dragging)
        assertEvent("a cancel in a drag", TouchEvent.DragEnd, dragging.cancel())
        assertDragging("after the cancel", false, dragging)
        assertEvent("a release after the cancelled drag", null, dragging.up(120f, 100f))
        assertEvent("a second cancel", null, dragging.cancel())
    }

    /** A failure means state from a finished gesture leaks into the next one: a stuck drag flag, a stale reference point or a carried remainder. */
    @Test
    fun `a new gesture after a finished one starts clean`() {
        val touch = TouchInterpreter(8)

        // First gesture: a drag with a 0.4 px remainder left over.
        touch.down(100f, 100f)
        assertEvent("the first drag, move to 110.4", TouchEvent.DragBy(10, 0), touch.move(110.4f, 100f))
        assertEvent("the first drag ends", TouchEvent.DragEnd, touch.up(110.4f, 100f))

        // Second gesture, far away: a small move is inside the slop and the release is a tap.
        touch.down(200f, 200f)
        assertDragging("right after the second down", false, touch)
        assertEvent("a 3 px move in the second gesture", null, touch.move(203f, 200f))
        assertEvent("the second gesture is a tap", TouchEvent.Tap, touch.up(203f, 200f))

        // Third gesture: a drag measured from its own down point, not from the earlier ones.
        touch.down(50f, 50f)
        assertEvent("the third gesture move to 70,50", TouchEvent.DragBy(20, 0), touch.move(70f, 50f))
        assertEvent("the third gesture is cancelled", TouchEvent.DragEnd, touch.cancel())

        // Fourth gesture after a cancel: starts clean, and the release is a tap.
        touch.down(300f, 300f)
        assertDragging("right after the fourth down", false, touch)
        assertEvent("the fourth gesture is a tap", TouchEvent.Tap, touch.up(300f, 300f))

        // Nothing left over: a release after the finished tap reports nothing.
        assertEvent("a release after the finished tap", null, touch.up(300f, 300f))
    }

    /** A failure means a drag delta lost its sign, or a negative sub-pixel remainder is carried the wrong way. */
    @Test
    fun `drag deltas keep their sign in all four directions`() {
        // Each case is a fresh gesture from 100,100 to the given point; the expected delta is the point minus 100,100.
        val cases = listOf(
            Triple("20 px right", 120f to 100f, TouchEvent.DragBy(20, 0)),
            Triple("20 px left", 80f to 100f, TouchEvent.DragBy(-20, 0)),
            Triple("20 px down", 100f to 120f, TouchEvent.DragBy(0, 20)),
            Triple("20 px up", 100f to 80f, TouchEvent.DragBy(0, -20)),
        )
        for ((what, point, expected) in cases) {
            val single = TouchInterpreter(8)
            single.down(100f, 100f)
            assertEvent(what, expected, single.move(point.first, point.second))
        }

        val upLeft = TouchInterpreter(8)
        upLeft.down(100f, 100f)
        assertEvent("20 px up and left", TouchEvent.DragBy(-20, -20), upLeft.move(80f, 80f))

        val upRight = TouchInterpreter(8)
        upRight.down(100f, 100f)
        assertEvent("30 px right and 30 px up", TouchEvent.DragBy(30, -30), upRight.move(130f, 70f))

        // Later negative steps: 120 -> 110 is -10; y 100 -> 85 is -15.
        val later = TouchInterpreter(8)
        later.down(100f, 100f)
        assertEvent("the first move to 120,100", TouchEvent.DragBy(20, 0), later.move(120f, 100f))
        assertEvent("a step back to 110,100", TouchEvent.DragBy(-10, 0), later.move(110f, 100f))
        assertEvent("a step up to 110,85", TouchEvent.DragBy(0, -15), later.move(110f, 85f))

        // Negative carry: after 120 is reported, 119.6 is -0.4 -> 0 (reference stays 120);
        // 119.2 is -0.8 -> -1 (reference becomes 119); 118.9 is -0.1 -> 0.
        val carry = TouchInterpreter(8)
        carry.down(100f, 100f)
        assertEvent("the first move to 120,100", TouchEvent.DragBy(20, 0), carry.move(120f, 100f))
        assertEvent("a move to 119.6 is -0.4 px", TouchEvent.DragBy(0, 0), carry.move(119.6f, 100f))
        assertEvent("a move to 119.2 is -0.8 px", TouchEvent.DragBy(-1, 0), carry.move(119.2f, 100f))
        assertEvent("a move to 118.9 is -0.1 px from the new reference", TouchEvent.DragBy(0, 0), carry.move(118.9f, 100f))
    }
}
