package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mic keeps its touch for the whole gesture. [TouchHold] is the pure seam of the view: while a finger is down
 * it asks the parent not to intercept, and it gives the touch back on a lift or a cancel. The view cannot be built
 * on a plain JVM, so a made-up scroll container stands in for the Home page: it takes the touch when the finger
 * drifts past the slop, unless it was asked to leave it. The wiring in ScreenMic.kt is pinned by ScreenMicHoldWiringGateTest.
 */
class TouchHoldTest {

    /** A scroll container that records what it was asked and takes a drifting touch unless told to leave it. */
    private class ScrollParent {
        var disallow = false
        val asked = ArrayList<Boolean>()

        fun request(flag: Boolean) {
            asked.add(flag)
            disallow = flag
        }

        /** True when a touch that has moved past the slop is taken from the child (the child gets a cancel). */
        fun takesDriftingTouch(): Boolean = !disallow
    }

    @Test
    fun `a press asks the parent to leave the touch alone`() {
        val parent = ScrollParent()
        TouchHold(parent::request).down()
        assertEquals("overlay: what the parent was asked", listOf(true), parent.asked)
    }

    @Test
    fun `a lift gives the touch back once`() {
        val parent = ScrollParent()
        val hold = TouchHold(parent::request)
        hold.down()
        hold.release()
        assertEquals("overlay: what the parent was asked", listOf(true, false), parent.asked)
    }

    @Test
    fun `a second release after one lift asks nothing more`() {
        val parent = ScrollParent()
        val hold = TouchHold(parent::request)
        hold.down()
        hold.release()
        hold.release()
        assertEquals("overlay: a second release must not ask again", listOf(true, false), parent.asked)
    }

    @Test
    fun `a release without a press asks the parent nothing`() {
        val parent = ScrollParent()
        TouchHold(parent::request).release()
        assertEquals("overlay: a stray cancel must not tell the parent anything", emptyList<Boolean>(), parent.asked)
    }

    @Test
    fun `a press after a lift asks again`() {
        val parent = ScrollParent()
        val hold = TouchHold(parent::request)
        hold.down()
        hold.release()
        hold.down()
        assertEquals("overlay: the next gesture asks again", listOf(true, false, true), parent.asked)
    }

    @Test
    fun `a long press that drifts past the slop is not taken by a scroll parent`() {
        val unguarded = ScrollParent()
        assertTrue("overlay: control: a scroll parent that was not asked takes a drifting touch", unguarded.takesDriftingTouch())
        val parent = ScrollParent()
        val hold = TouchHold(parent::request)
        hold.down()
        assertFalse("overlay: while the finger is down the parent must leave a drifting touch to the mic", parent.takesDriftingTouch())
        hold.release()
        assertTrue("overlay: after the lift the parent may scroll again", parent.takesDriftingTouch())
    }
}
