package dev.breaker.dictation.ui.render

import dev.breaker.dictation.ui.screen.Emphasis
import dev.breaker.dictation.ui.theme.PaletteSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two rules the renderer takes from RenderMath.kt: how many pixels a touch
 * target needs, and which colour an action's text takes. Expected values are
 * written here as literals, apart from the code, so a wrong rule is a difference
 * between two independent statements.
 */
class RenderMathTest {
    private val densities = listOf(0.75f, 1.0f, 1.5f, 2.0f, 2.6f, 2.61f, 3.0f, 4.0f)

    @Test
    fun `a touch target is never smaller than its dp size and less than a pixel larger`() {
        for (dp in listOf(4f, 48f)) {
            for (density in densities) {
                val exact = dp.toDouble() * density.toDouble()
                val px = touchTargetPx(dp, density)
                assertTrue("$dp dp at density $density: $px px is below $exact", px >= exact)
                assertTrue("$dp dp at density $density: $px px is a pixel or more above $exact", px < exact + 1.0)
            }
        }
    }

    @Test
    fun `the touch target of 48 dp comes out at the pixel counts worked out by hand`() {
        val expected = listOf(
            0.75f to 36,
            1.0f to 48,
            1.5f to 72,
            2.0f to 96,
            2.6f to 125,
            2.61f to 126,
            3.0f to 144,
            4.0f to 192,
        )
        for ((density, px) in expected) {
            assertEquals("48 dp at density $density", px, touchTargetPx(48f, density))
        }
    }

    @Test
    fun `an action takes the text colour of its emphasis and a disabled one the muted colour`() {
        val table = listOf(
            Triple(Emphasis.PRIMARY, true, PaletteSlot.PRIMARY),
            Triple(Emphasis.SECONDARY, true, PaletteSlot.TEXT),
            Triple(Emphasis.DESTRUCTIVE, true, PaletteSlot.DANGER),
            Triple(Emphasis.PRIMARY, false, PaletteSlot.TEXT_MUTED),
            Triple(Emphasis.SECONDARY, false, PaletteSlot.TEXT_MUTED),
            Triple(Emphasis.DESTRUCTIVE, false, PaletteSlot.TEXT_MUTED),
        )
        assertEquals("the table covers every emphasis twice", Emphasis.entries.size * 2, table.size)
        for ((emphasis, enabled, slot) in table) {
            assertEquals("$emphasis, enabled=$enabled", slot, actionTextSlot(emphasis, enabled))
        }
    }
}
