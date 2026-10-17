package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

/** The window frame of the close target: the box is centred on the target point and keeps the size it is given. */
class CloseTargetWindowFrameTest {

    /** A failure here means an even sized box is not centred on its point. */
    @Test
    fun `frameFor centres an even sized box on its point`() {
        val frame = CloseTargetWindow.frameFor(100, 200, 40, 20)
        assertEquals("overlay: the left edge of an even box", 80, frame.left)
        assertEquals("overlay: the top edge of an even box", 190, frame.top)
        assertEquals("overlay: the centre x of an even box", 100, frame.left + frame.widthPx / 2)
        assertEquals("overlay: the centre y of an even box", 200, frame.top + frame.heightPx / 2)
    }

    /** A failure here means an odd sized box is not placed from the integer halves of its size. */
    @Test
    fun `frameFor places an odd sized box from the integer halves of its size`() {
        val frame = CloseTargetWindow.frameFor(101, 201, 41, 21)
        assertEquals("overlay: the left edge of an odd box", 81, frame.left)
        assertEquals("overlay: the top edge of an odd box", 191, frame.top)
    }

    /** A failure here means the frame does not keep the width and the height it was given. */
    @Test
    fun `frameFor keeps the width and height it was given`() {
        val even = CloseTargetWindow.frameFor(100, 200, 40, 20)
        assertEquals("overlay: the width of an even box is the given width", 40, even.widthPx)
        assertEquals("overlay: the height of an even box is the given height", 20, even.heightPx)
        val odd = CloseTargetWindow.frameFor(101, 201, 41, 21)
        assertEquals("overlay: the width of an odd box is the given width", 41, odd.widthPx)
        assertEquals("overlay: the height of an odd box is the given height", 21, odd.heightPx)
    }

    /** A failure here means a second centre is not placed the same way as the first. */
    @Test
    fun `frameFor works for a second centre`() {
        val frame = CloseTargetWindow.frameFor(300, 700, 150, 52)
        assertEquals("overlay: the left edge at the second centre", 225, frame.left)
        assertEquals("overlay: the top edge at the second centre", 674, frame.top)
        assertEquals("overlay: the width at the second centre", 150, frame.widthPx)
        assertEquals("overlay: the height at the second centre", 52, frame.heightPx)
    }

    /** A failure here means a centre near the corner does not give the negative edges that centring gives. */
    @Test
    fun `frameFor gives negative edges for a centre near the corner`() {
        val frame = CloseTargetWindow.frameFor(10, 5, 80, 40)
        assertEquals("overlay: the left edge near the corner is negative", -30, frame.left)
        assertEquals("overlay: the top edge near the corner is negative", -15, frame.top)
    }
}
