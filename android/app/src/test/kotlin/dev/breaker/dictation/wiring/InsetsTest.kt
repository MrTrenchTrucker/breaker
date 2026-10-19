package dev.breaker.dictation.wiring

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the pure insets arithmetic in [paddingFor] and the gutter conversion in [gutterPx].
 *
 * Each side of the padding result is the max of the bar inset and the cutout inset
 * for that side, so a display cutout that reaches past the system bars is
 * still cleared. The gutter is the outer margin added to every side.
 */
internal class InsetsTest {

    // ── layoutFor ──────────────────────────────────────────────────────────────

    @Test
    fun `layoutFor returns clear as margins and gutter as padding on all sides`() {
        val clear = Padding(left = 10, top = 20, right = 30, bottom = 40)
        val gutter = 16
        val (margins, padding) = layoutFor(clear, gutter)
        assertEquals("margins are the clear insets", clear, margins)
        assertEquals("padding is gutter on all sides", Padding(16, 16, 16, 16), padding)
    }

    @Test
    fun `layoutFor with zero gutter gives zero padding`() {
        val clear = Padding(5, 10, 15, 20)
        val (margins, padding) = layoutFor(clear, gutter = 0)
        assertEquals("margins are the clear insets", clear, margins)
        assertEquals("padding is zero", Padding(0, 0, 0, 0), padding)
    }

    @Test
    fun `layoutFor with zero clear gives zero margins`() {
        val clear = Padding(0, 0, 0, 0)
        val (margins, padding) = layoutFor(clear, gutter = 8)
        assertEquals("margins are zero", Padding(0, 0, 0, 0), margins)
        assertEquals("padding is gutter on all sides", Padding(8, 8, 8, 8), padding)
    }

    @Test
    fun `layoutFor with asymmetric clear preserves each side`() {
        val clear = Padding(left = 0, top = 24, right = 0, bottom = 48)
        val (margins, padding) = layoutFor(clear, gutter = 16)
        assertEquals("margins preserve the asymmetric clear", clear, margins)
        assertEquals("padding is gutter on every side regardless", Padding(16, 16, 16, 16), padding)
    }

    @Test
    fun `layoutFor does not mutate the input clear`() {
        val clear = Padding(1, 2, 3, 4)
        layoutFor(clear, gutter = 8)
        assertEquals("clear is unchanged after layoutFor", Padding(1, 2, 3, 4), clear)
    }

    // ── paddingFor ─────────────────────────────────────────────────────────────

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

    @Test
    fun `layoutFor puts the clear amount in the margins and the gutter in the padding`() {
        val clear = Padding(left = 10, top = 20, right = 30, bottom = 40)
        val (margins, padding) = layoutFor(clear, 16)
        assertEquals("margins are the clear amount", Padding(10, 20, 30, 40), margins)
        assertEquals("padding is the gutter on every side", Padding(16, 16, 16, 16), padding)
    }

    @Test
    fun `layoutFor keeps a zero gutter as zero padding`() {
        val (margins, padding) = layoutFor(Padding(4, 5, 6, 7), 0)
        assertEquals(Padding(4, 5, 6, 7), margins)
        assertEquals(Padding(0, 0, 0, 0), padding)
    }

    @Test
    fun `layoutFor hands back the same clear amount it was given`() {
        val clear = Padding(1, 2, 3, 4)
        assertEquals("the clear amount is not copied or rebuilt", clear, layoutFor(clear, 48).first)
    }

    @Test
    fun `layoutFor gives a view without margins the whole amount as padding`() {
        val clear = Padding(10, 20, 30, 40)
        val gutter = 16
        val (margins, padding) = layoutFor(clear, gutter)
        // The fallback adds the two halves of each side, so it is never below either.
        assertEquals(margins.left + padding.left, clear.left + gutter)
        assertEquals(margins.top + padding.top, clear.top + gutter)
        assertEquals(margins.right + padding.right, clear.right + gutter)
        assertEquals(margins.bottom + padding.bottom, clear.bottom + gutter)
    }

    @Test
    fun `gutter in px on a 3x phone is 48`() {
        assertEquals(48, gutterPx(16, 3.0f))
    }

    @Test
    fun `gutter in px on a 2x phone is 32`() {
        assertEquals(32, gutterPx(16, 2.0f))
    }

    @Test
    fun `gutter in px on a 1x phone is 16`() {
        assertEquals(16, gutterPx(16, 1.0f))
    }

    @Test
    fun `gutter in px rounds up so it is never below its dp`() {
        // 16 dp at 1.1x density = 17.6 px, rounded up to 18
        assertEquals(18, gutterPx(16, 1.1f))
    }

}
