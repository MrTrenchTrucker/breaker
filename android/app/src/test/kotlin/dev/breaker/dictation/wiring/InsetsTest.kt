package dev.breaker.dictation.wiring

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the pure insets arithmetic in [paddingFor].
 *
 * Each side of the result is the max of the bar inset and the cutout inset
 * for that side, so a display cutout that reaches past the system bars is
 * still cleared.
 */
internal class InsetsTest {

    @Test
    fun `each side is the max of bar and cutout`() {
        val bars = Insets(left = 10, top = 20, right = 30, bottom = 40)
        val cutout = Insets(left = 50, top = 15, right = 60, bottom = 35)
        val padding = paddingFor(bars, cutout)
        assertEquals("left takes cutout", 50, padding.left)
        assertEquals("top takes bar", 20, padding.top)
        assertEquals("right takes cutout", 60, padding.right)
        assertEquals("bottom takes bar", 40, padding.bottom)
    }

    @Test
    fun `cutout larger than bar on every side`() {
        val bars = Insets(1, 2, 3, 4)
        val cutout = Insets(10, 20, 30, 40)
        val padding = paddingFor(bars, cutout)
        assertEquals(Padding(10, 20, 30, 40), padding)
    }

    @Test
    fun `bar larger than cutout on every side`() {
        val bars = Insets(10, 20, 30, 40)
        val cutout = Insets(1, 2, 3, 4)
        val padding = paddingFor(bars, cutout)
        assertEquals(Padding(10, 20, 30, 40), padding)
    }

    @Test
    fun `both zero gives zero padding`() {
        val padding = paddingFor(Insets(0, 0, 0, 0), Insets(0, 0, 0, 0))
        assertEquals(Padding(0, 0, 0, 0), padding)
    }

    @Test
    fun `a rotation-like change gives different padding per side`() {
        // Landscape: bars are 0 on left/right (rotated), cutout is on the right
        val bars = Insets(0, 24, 0, 48)
        val cutout = Insets(0, 0, 36, 0)
        val padding = paddingFor(bars, cutout)
        assertEquals(Padding(0, 24, 36, 48), padding)
    }

    @Test
    fun `equal bar and cutout gives that value`() {
        val padding = paddingFor(Insets(7, 7, 7, 7), Insets(7, 7, 7, 7))
        assertEquals(Padding(7, 7, 7, 7), padding)
    }

    @Test
    fun `one side zero while the other is not`() {
        val padding = paddingFor(Insets(0, 12, 0, 0), Insets(0, 0, 24, 0))
        assertEquals(Padding(0, 12, 24, 0), padding)
    }


}
