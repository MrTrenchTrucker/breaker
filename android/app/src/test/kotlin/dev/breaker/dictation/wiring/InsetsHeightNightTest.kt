package dev.breaker.dictation.wiring

import dev.breaker.shared.tokens.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the pure height-arithmetic helper [usableHeightDp] and the night-to-theme mapping
 * [themeModeForNight]. Each assertion is a single, readable fact about one behaviour.
 */
internal class InsetsHeightNightTest {

    // ── usableHeightDp ─────────────────────────────────────────────────────────

    @Test
    fun `usable height divides the barless pixels by density`() {
        assertEquals(781.8182f, usableHeightDp(2400, 100, 150, 2.75f), 0.001f)
    }

    @Test
    fun `usable height with zero bars divides pixels by density`() {
        assertEquals(960f, usableHeightDp(1920, 0, 0, 2f), 0.001f)
    }

    @Test
    fun `a bar lowers the usable height below a bare division`() {
        // A single bottom bar leaves less than the full window divided by density.
        val withBar = usableHeightDp(2400, 0, 150, 2.75f)
        val noBar = 2400 / 2.75f
        assertTrue("a bar lowers the usable height", withBar < noBar)
    }

    @Test
    fun `usable height keeps a fractional pixel rather than truncating`() {
        // (2400 - 100 - 150) / 2.75 is not a whole number; an Int-truncated value would be 781f.
        assertEquals(781.8182f, usableHeightDp(2400, 100, 150, 2.75f), 0.001f)
        assertNotEquals("a truncated float is not the stored result", 781f,
            usableHeightDp(2400, 100, 150, 2.75f))
    }

    @Test
    fun `usable height subtracts both a top and a bottom bar`() {
        // (2400 - 100 - 200) / 2 = 1050; only the full pair of bars yields this value.
        val withBoth = usableHeightDp(2400, 100, 200, 2f)
        assertEquals("both bars subtracted together give 1050dp", 1050f, withBoth, 0.001f)

        // A top-only bar and a bottom-only bar differ, so each side is genuinely removed.
        val topOnly = usableHeightDp(2400, 100, 0, 2f)
        val bottomOnly = usableHeightDp(2400, 0, 150, 2f)
        assertNotEquals("top and bottom bars are not interchangeable", topOnly, bottomOnly)

        // Subtracting both is strictly less than subtracting either one alone.
        assertTrue("subtracting both is below the top-only result", withBoth < topOnly)
        assertTrue("subtracting both is below the bottom-only result", withBoth < bottomOnly)
    }

    // ── themeModeForNight ──────────────────────────────────────────────────────

    @Test
    fun `night maps to DARK`() {
        assertEquals(ThemeMode.DARK, themeModeForNight(true))
    }

    @Test
    fun `not night maps to LIGHT`() {
        assertEquals(ThemeMode.LIGHT, themeModeForNight(false))
    }
}
