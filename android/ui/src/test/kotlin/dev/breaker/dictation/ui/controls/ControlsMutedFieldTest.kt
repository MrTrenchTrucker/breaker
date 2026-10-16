package dev.breaker.dictation.ui.controls

import dev.breaker.dictation.ui.theme.Theme
import dev.breaker.dictation.ui.theme.Themes
import dev.breaker.shared.tokens.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The muted field words, the one colour the kit adds for a field. The art does not draw a muted
 * field, so the words take the theme's muted text colour while the fill and the outline stay as
 * they are. These tests pin that choice in both modes.
 */
class ControlsMutedFieldTest {

    private val light: Theme = Themes.of(ThemeMode.LIGHT)
    private val dark: Theme = Themes.of(ThemeMode.DARK)

    /** A failure means the muted words are not the theme's muted text colour in one of the two modes. */
    @Test
    fun `the muted field words take the muted text colour in both modes`() {
        assertEquals("controls: light muted words expected 0xFF5A6368", 0xFF5A6368.toInt(), ControlsPalette.partColor(ControlPart.FIELD_TEXT_MUTED, light))
        assertEquals("controls: dark muted words expected 0xFF9AA5A0", 0xFF9AA5A0.toInt(), ControlsPalette.partColor(ControlPart.FIELD_TEXT_MUTED, dark))
    }

    /** A failure means a muted value reads the same as a typed one, so the muted look is not told apart. */
    @Test
    fun `the muted field words differ from the normal words in both modes`() {
        val lightMuted = ControlsPalette.partColor(ControlPart.FIELD_TEXT_MUTED, light)
        val lightNormal = ControlsPalette.partColor(ControlPart.LABEL_NEUTRAL, light)
        val darkMuted = ControlsPalette.partColor(ControlPart.FIELD_TEXT_MUTED, dark)
        val darkNormal = ControlsPalette.partColor(ControlPart.LABEL_NEUTRAL, dark)
        assertTrue("controls: light muted words expected a colour of their own", lightMuted != lightNormal)
        assertTrue("controls: dark muted words expected a colour of their own", darkMuted != darkNormal)
    }

    /** A failure means the field fill or the outline has moved off the surface and the accent. */
    @Test
    fun `the field fill and outline keep the surface and the accent`() {
        assertEquals("controls: light fill expected 0xFFF4F6F4", 0xFFF4F6F4.toInt(), ControlsPalette.partColor(ControlPart.FIELD, light))
        assertEquals("controls: dark fill expected 0xFF161B1E", 0xFF161B1E.toInt(), ControlsPalette.partColor(ControlPart.FIELD, dark))
        assertEquals("controls: light outline expected 0xFF257F49", 0xFF257F49.toInt(), ControlsPalette.partColor(ControlPart.FIELD_OUTLINE, light))
        assertEquals("controls: dark outline expected 0xFF34A853", 0xFF34A853.toInt(), ControlsPalette.partColor(ControlPart.FIELD_OUTLINE, dark))
    }

    /** A failure means the muted field part is missing from the enum, or is declared more than once. */
    @Test
    fun `the muted field part is declared exactly once`() {
        val rows = enumValues<ControlPart>().count { it.name == "FIELD_TEXT_MUTED" }
        assertEquals("controls: the muted field part expected one enum row", 1, rows)
    }
}
