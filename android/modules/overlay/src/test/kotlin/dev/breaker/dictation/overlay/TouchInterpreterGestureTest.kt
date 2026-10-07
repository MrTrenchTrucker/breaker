package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How the interpreter treats a press that arrives in the middle of another gesture, and moves that
 * arrive with no press at all.
 *
 * Every expected report is worked out by hand in the comments next to it. Each test builds its own
 * interpreter, so no test depends on another.
 */
class TouchInterpreterGestureTest {
    private fun assertEvent(what: String, expected: TouchEvent?, actual: TouchEvent?) {
        assertEquals("android_overlay: $what: expected $expected", expected, actual)
    }

    private fun assertDragging(what: String, expected: Boolean, touch: TouchInterpreter) {
        assertEquals("android_overlay: $what: expected isDragging $expected", expected, touch.isDragging)
    }

    /** A failure means a press that arrives during a drag keeps the drag state, so the new touch reports a drag instead of a tap. */
    @Test
    fun `a second down inside a drag starts a fresh gesture`() {
        val touch = TouchInterpreter(8)
        touch.down(100f, 100f)
        // 20 px to the right is far past the slop of 8, so the first move starts a drag with the whole delta.
        assertEvent("a move of 20 px to the right", TouchEvent.DragBy(20, 0), touch.move(120f, 100f))
        assertDragging("after a move past the slop", true, touch)

        // A new press at 200,200 with no release in between: the drag is forgotten.
        touch.down(200f, 200f)
        assertDragging("after a second press", false, touch)

        // 3 px from the NEW press point is inside the slop of 8, so nothing is reported.
        assertEvent("a move of 3 px from the new press point", null, touch.move(203f, 200f))
        assertEvent("a release 3 px from the new press point", TouchEvent.Tap, touch.up(203f, 200f))
    }

    /** A failure means a move or a release with no press in front of it is reported as a drag or a tap. */
    @Test
    fun `a move before any down gives nothing`() {
        // A fresh interpreter has seen no press. (10, 10) is about 14 px from the origin, past a slop of 8,
        // so a move that ignored the missing press would start a drag.
        val fresh = TouchInterpreter(8)
        assertEvent("a move before any press", null, fresh.move(10f, 10f))
        assertDragging("after a move before any press", false, fresh)
        assertEvent("a release before any press", null, fresh.up(10f, 10f))

        // After a finished tap the gesture is over again: a later move is ignored once more.
        val finished = TouchInterpreter(8)
        finished.down(0f, 0f)
        assertEvent("a release on the press point", TouchEvent.Tap, finished.up(0f, 0f))
        assertEvent("a move after the finished tap", null, finished.move(50f, 50f))
        assertDragging("after a move that follows a finished tap", false, finished)
    }
}
