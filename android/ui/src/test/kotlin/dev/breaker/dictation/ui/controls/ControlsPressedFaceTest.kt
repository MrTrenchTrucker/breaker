package dev.breaker.dictation.ui.controls

import dev.breaker.dictation.ui.theme.Theme
import dev.breaker.dictation.ui.theme.Themes
import dev.breaker.shared.tokens.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pressed face of a raised button: its colour in both themes, the on label over it, and where the
 * face and its label sit once the button is pushed down. Every expected number is a literal the art
 * gives (its px over 1.5 px per dp), so a changed constant cannot move its own expectation.
 */
class ControlsPressedFaceTest {

    private val light: Theme = Themes.of(ThemeMode.LIGHT)
    private val dark: Theme = Themes.of(ThemeMode.DARK)

    /** A failure means the pressed face is not the primary hover fill in one of the two themes. */
    @Test
    fun `the pressed face is the primary hover fill in both themes`() {
        assertEquals("controls: light pressed face expected 0xFF16603A", 0xFF16603A.toInt(), ControlsPalette.partColor(ControlPart.FACE_PRESSED, light))
        assertEquals("controls: dark pressed face expected 0xFF3BBE6E", 0xFF3BBE6E.toInt(), ControlsPalette.partColor(ControlPart.FACE_PRESSED, dark))
    }

    /** A failure means the on label over the pressed face is not the measured ratio, or it falls to 4.5 or below. */
    @Test
    fun `the on label keeps its contrast over the pressed face in both themes`() {
        val lightRatio = contrast(ControlsPalette.partColor(ControlPart.LABEL, light), ControlsPalette.partColor(ControlPart.FACE_PRESSED, light))
        val darkRatio = contrast(ControlsPalette.partColor(ControlPart.LABEL, dark), ControlsPalette.partColor(ControlPart.FACE_PRESSED, dark))
        assertEquals("controls: light label over the pressed face expected 7.5857", 7.5857, lightRatio, 0.001)
        assertEquals("controls: dark label over the pressed face expected 7.9192", 7.9192, darkRatio, 0.001)
        assertTrue("controls: light label over the pressed face expected above 4.5", lightRatio > 4.5)
        assertTrue("controls: dark label over the pressed face expected above 4.5", darkRatio > 4.5)
    }

    /** A failure means the raised label is not at 46 thirds of a dp, or the pressed label is not 20 dp down. */
    @Test
    fun `the raised label sits at 46 thirds and the pressed label at 20 dp`() {
        assertEquals("controls: raised label expected 46 thirds of a dp", 46f / 3f, ControlsGeometry.button(170f, 48f, pressed = false).labelTop, 0.0001f)
        assertEquals("controls: pressed label expected 20 dp", 20f, ControlsGeometry.button(170f, 48f, pressed = true).labelTop, 0.0001f)
    }

    /** A failure means the pressed face is not the raised face pushed down 4 dp, or the pressed button still draws a lip. */
    @Test
    fun `the pressed face is the raised face pushed down four dp`() {
        val pressed = ControlsGeometry.button(170f, 48f, pressed = true)
        assertEquals("controls: pressed face expected 170 by 44 dp at 4 dp down", Rect(0f, 4f, 170f, 44f), pressed.face)
        assertNull("controls: pressed button expected no lip", pressed.lip)
    }
}
