package dev.breaker.dictation.ui.controls

import dev.breaker.dictation.ui.theme.Theme
import dev.breaker.dictation.ui.theme.Themes
import dev.breaker.shared.tokens.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The compact button width, which the History entry rows use, and the 360 dp fit that is its reason.
 * The card's inner width is the phone width less the host gutter on each side and the card padding on
 * each side. The gutter is the token gutterDp, the card padding is the Space.L step (spaceLDp, the
 * step the card's padding is drawn with), and the gap between two buttons is the Space.S step
 * (spaceSDp). Every number here is read from the theme's metrics, none is typed in.
 */
class ControlsCompactWidthTest {

    private val light: Theme = Themes.of(ThemeMode.LIGHT)

    /** A failure means the compact floor is not the History mock-up width of 140 px at 1.5 px per dp. */
    @Test
    fun `the compact floor is the History mock-up width`() {
        assertEquals("controls: compact floor expected 93.33 dp", 93.33f, ControlsGeometry.BUTTON_COMPACT_MIN_WIDTH_DP, 0.01f)
    }

    /** A failure means the default floor moved from the art's 170 px. */
    @Test
    fun `the default floor is unchanged at 113 point 33 dp`() {
        assertEquals("controls: default floor expected 113.33 dp", 113.33f, ControlsGeometry.buttonWidth(0f), 0.01f)
        assertEquals("controls: default floor expected 113.33 dp when named", 113.33f, ControlsGeometry.buttonWidth(0f, compact = false), 0.01f)
    }

    /** A failure means a short label in a compact button does not get the compact floor. */
    @Test
    fun `a short label gets the compact floor`() {
        assertEquals("controls: a short compact label expected the compact floor", 93.33f, ControlsGeometry.buttonWidth(10f, compact = true), 0.01f)
    }

    /** A failure means a long label does not grow past its floor in either mode, or is cut short. */
    @Test
    fun `a long label grows past the floor by its width and two side paddings in both modes`() {
        assertEquals("controls: compact label of 100 dp expected 132 dp", 132f, ControlsGeometry.buttonWidth(100f, compact = true), 0.0001f)
        assertEquals("controls: default label of 100 dp expected 132 dp", 132f, ControlsGeometry.buttonWidth(100f, compact = false), 0.0001f)
        assertEquals("controls: compact label of 200 dp expected 232 dp", 232f, ControlsGeometry.buttonWidth(200f, compact = true), 0.0001f)
        assertEquals("controls: default label of 200 dp expected 232 dp", 232f, ControlsGeometry.buttonWidth(200f, compact = false), 0.0001f)
    }

    /** A failure means a compact button is wider than the default button for the same label. */
    @Test
    fun `a compact button is never wider than the default for the same label`() {
        for (label in listOf(0f, 10f, 50f, 61f, 62f, 81f, 82f, 100f, 200f)) {
            assertTrue(
                "controls: compact wider than the default for a label of $label dp",
                ControlsGeometry.buttonWidth(label, compact = true) <= ControlsGeometry.buttonWidth(label, compact = false),
            )
        }
    }

    /** A failure means the boundary label does not switch from the floor to the label plus its side paddings. */
    @Test
    fun `the boundary label sits on each side of the compact floor`() {
        assertEquals("controls: label of 61 dp expected the compact floor", 93.33f, ControlsGeometry.buttonWidth(61f, compact = true), 0.01f)
        assertEquals("controls: label of 62 dp expected 94 dp", 94f, ControlsGeometry.buttonWidth(62f, compact = true), 0.0001f)
    }

    /** A failure means the three compact buttons and their two gaps no longer fill the card of a 360 dp phone. */
    @Test
    fun `three compact buttons and two gaps fill the card of a 360 dp phone exactly`() {
        val metrics = light.metrics
        val inner = 360f - 2f * metrics.gutterDp - 2f * metrics.spaceLDp
        assertEquals("controls: the card inner width expected 296 dp", 296f, inner, 0.0001f)
        val buttons = 3f * ControlsGeometry.buttonWidth(0f, compact = true)
        val gaps = 2f * metrics.spaceSDp
        assertEquals("controls: three compact buttons and two gaps expected the card inner width", inner, buttons + gaps, 0.001f)
    }

    /** A failure means the default floors would fit the card, which is the reason the compact floor exists. */
    @Test
    fun `three default buttons do not fit the same card`() {
        val metrics = light.metrics
        val inner = 360f - 2f * metrics.gutterDp - 2f * metrics.spaceLDp
        val buttons = 3f * ControlsGeometry.buttonWidth(0f, compact = false)
        assertTrue("controls: three default buttons expected wider than the card", buttons > inner)
        assertEquals("controls: three default buttons expected 44 dp past the card", 44f, buttons - inner, 0.01f)
    }
}
