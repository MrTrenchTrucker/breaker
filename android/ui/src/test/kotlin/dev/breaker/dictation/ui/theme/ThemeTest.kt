package dev.breaker.dictation.ui.theme

import dev.breaker.shared.tokens.ThemeMode
import dev.breaker.shared.tokens.TokenColor
import dev.breaker.shared.tokens.TruckingPalette
import dev.breaker.dictation.ui.controls.contrast
import dev.breaker.shared.tokens.TruckingTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The theme maps each palette slot to one field of the token palette of the mode in force.
 * The table below is written here, apart from the theme, so a slot wired to the wrong
 * field is a difference between two independent statements and not a test that repeats
 * the code it checks.
 */
class ThemeTest {
    private val fieldOf: Map<PaletteSlot, (TruckingPalette) -> TokenColor> = mapOf(
        PaletteSlot.BACKGROUND to { p -> p.bg },
        PaletteSlot.SURFACE to { p -> p.surface },
        PaletteSlot.TEXT to { p -> p.text },
        PaletteSlot.TEXT_MUTED to { p -> p.textMuted },
        PaletteSlot.PRIMARY to { p -> p.primary },
        PaletteSlot.PRIMARY_HOVER to { p -> p.primaryHover },
        PaletteSlot.ON to { p -> p.bg },
        PaletteSlot.ACCENT to { p -> p.accent },
        PaletteSlot.DANGER to { p -> p.danger },
        PaletteSlot.TRIM to { p -> p.trim },
        PaletteSlot.STATE_SENT to { p -> p.sent },
        PaletteSlot.STATE_WARNING to { p -> p.warning },
        PaletteSlot.STATE_DANGER to { p -> p.danger },
    )

    private fun assertEverySlotMaps(mode: ThemeMode, tokens: TruckingPalette) {
        val theme = Themes.of(mode)
        for ((slot, field) in fieldOf) {
            assertEquals("$slot in $mode", field(tokens), theme.color(slot))
        }
    }

    @Test
    fun `the test table names every palette slot`() {
        assertEquals(PaletteSlot.entries.toSet(), fieldOf.keys)
    }

    @Test
    fun `every slot resolves to its own token field in light mode`() {
        assertEverySlotMaps(ThemeMode.LIGHT, TruckingTokens.LIGHT)
    }

    @Test
    fun `every slot resolves to its own token field in dark mode`() {
        assertEverySlotMaps(ThemeMode.DARK, TruckingTokens.DARK)
    }

    @Test
    fun `a theme carries the mode it is built for`() {
        for (mode in ThemeMode.entries) {
            assertEquals(mode, Themes.of(mode).mode)
        }
        assertEquals(setOf(ThemeMode.LIGHT, ThemeMode.DARK), ThemeMode.entries.toSet())
    }

    @Test
    fun `the palette of a theme is the token palette of its mode`() {
        assertSame(TruckingTokens.LIGHT, Themes.of(ThemeMode.LIGHT).palette)
        assertSame(TruckingTokens.DARK, Themes.of(ThemeMode.DARK).palette)
    }

    @Test
    fun `light and dark differ for background surface text and primary`() {
        val light = Themes.of(ThemeMode.LIGHT)
        val dark = Themes.of(ThemeMode.DARK)

        for (slot in listOf(PaletteSlot.BACKGROUND, PaletteSlot.SURFACE, PaletteSlot.TEXT, PaletteSlot.PRIMARY)) {
            assertNotEquals("$slot must differ between light and dark", light.color(slot), dark.color(slot))
        }
    }

    @Test
    fun `the metrics of a theme are the token metrics`() {
        for (mode in ThemeMode.entries) {
            val metrics = Themes.of(mode).metrics
            assertSame(TruckingTokens.metrics, metrics)
        }
    }

    @Test
    fun `the type of a theme is the token type`() {
        for (mode in ThemeMode.entries) {
            assertSame(TruckingTokens.type, Themes.of(mode).type)
        }
    }

    @Test
    fun `colors by slot lists every slot with the color the slot resolves to`() {
        for (mode in ThemeMode.entries) {
            val theme = Themes.of(mode)
            val all = theme.colorsBySlot()

            assertEquals(PaletteSlot.entries.toSet(), all.keys)
            for (slot in PaletteSlot.entries) {
                assertEquals("$slot in $mode", theme.color(slot), all.getValue(slot))
            }
        }
    }

    @Test
    fun `the on slot reads the palette background value in both themes`() {
        for (mode in ThemeMode.entries) {
            val theme = Themes.of(mode)
            assertEquals("$mode on", theme.color(PaletteSlot.BACKGROUND), theme.color(PaletteSlot.ON))
        }
    }

    @Test
    fun `a label on the primary face clears the contrast floor in both themes`() {
        for (mode in ThemeMode.entries) {
            val theme = Themes.of(mode)
            val label = theme.color(PaletteSlot.ON).argb
            val face = theme.color(PaletteSlot.PRIMARY).argb
            val ratio = contrast(label, face)
            assertTrue("$mode on over primary must clear 4.5, found $ratio", ratio >= 4.5)
        }
    }

    /**
     * A failure here means the contrast is not the WCAG ratio of the two colours: white over black
     * is exactly 21.0 (white luminance 1.0, black 0.0), and 0xFF777777 over white is about 4.478
     * (the grey's luminance is 0.184475, so (1.0 + 0.05) / (0.184475 + 0.05)). The floor test above
     * cannot see a ratio that answers 21.0 to everything, so the exact pair is pinned here.
     */
    @Test
    fun `contrast answers the exact ratio of white over black and of grey over white`() {
        assertEquals("white over black must be exactly 21.0", 21.0, contrast(0xFFFFFFFF.toInt(), 0xFF000000.toInt()), 0.01)
        assertEquals("0xFF777777 over white must be about 4.478", 4.478, contrast(0xFF777777.toInt(), 0xFFFFFFFF.toInt()), 0.01)
    }
}
