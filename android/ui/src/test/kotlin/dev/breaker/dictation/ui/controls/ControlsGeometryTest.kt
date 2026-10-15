package dev.breaker.dictation.ui.controls

import dev.breaker.dictation.ui.theme.Theme
import dev.breaker.dictation.ui.theme.Themes
import dev.breaker.shared.tokens.ThemeMode
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The geometry and the colours of the controls, one test per invariant. Every expected colour is a
 * literal ARGB worked out by hand from the palette and the shade rule, so a part that takes the wrong
 * slot or the wrong factor fails in one of the two themes. The literals live here in the test tree:
 * the main tree holds no colour of its own, and NoColourGateTest already checks that over the whole
 * module, so this file notes the rule rather than repeating it.
 */
class ControlsGeometryTest {

    private val light: Theme = Themes.of(ThemeMode.LIGHT)
    private val dark: Theme = Themes.of(ThemeMode.DARK)

    /** A failure means the raised face is not the primary fill, or the lip and the lit line do not shade it by their factors. */
    @Test
    fun `the main button is the primary face with its lip and lit line shading it`() {
        val parts = ControlsGeometry.button(170f, 48f, pressed = false)
        assertEquals("controls: the face expected the top of the button above the lip", Rect(0f, 0f, 170f, 44f), parts.face)
        assertEquals("controls: the lip expected the bottom 4 dp of the button", Rect(0f, 44f, 170f, 4f), parts.lip)
        val lit = parts.litLine
        assertNotNull("controls: the lit line expected a rectangle", lit)
        assertEquals("controls: the lit line expected 2 px in from each side", 4f / 3f, lit!!.x, 0.0001f)
        assertEquals("controls: the lit line expected 1 px down from the top", 2f / 3f, lit.y, 0.0001f)
        assertEquals("controls: the lit line expected the face less 2 px each side", 170f - 8f / 3f, lit.width, 0.0001f)
        assertEquals("controls: the lit line expected 3 px thick", 2f, lit.height, 0.0001f)
        assertEquals("controls: light face expected 0xFF1E7A46", 0xFF1E7A46.toInt(), ControlsPalette.partColor(ControlPart.FACE, light))
        assertEquals("controls: dark face expected 0xFF2E9E5B", 0xFF2E9E5B.toInt(), ControlsPalette.partColor(ControlPart.FACE, dark))
        assertEquals("controls: light lip expected 0xFF134F2D", 0xFF134F2D.toInt(), ControlsPalette.partColor(ControlPart.LIP, light))
        assertEquals("controls: dark lip expected 0xFF1E673B", 0xFF1E673B.toInt(), ControlsPalette.partColor(ControlPart.LIP, dark))
        assertEquals("controls: light lit line expected 0xFF62A27E", 0xFF62A27E.toInt(), ControlsPalette.partColor(ControlPart.LIT_LINE, light))
        assertEquals("controls: dark lit line expected 0xFF6DBB8C", 0xFF6DBB8C.toInt(), ControlsPalette.partColor(ControlPart.LIT_LINE, dark))
    }

    /** A failure means the pressed face is not pushed down by the lip, or the pressed button still draws a lip. */
    @Test
    fun `a pressed button is pushed down by the lip and draws no lip`() {
        val parts = ControlsGeometry.button(170f, 48f, pressed = true)
        assertEquals("controls: the pressed face expected 4 dp down", 4f, parts.face.y, 0f)
        assertNull("controls: the pressed button expected no lip", parts.lip)
        val lit = parts.litLine
        assertNotNull("controls: the pressed top line expected a rectangle", lit)
        assertEquals("controls: the pressed top line expected 2 px in from each side", 4f / 3f, lit!!.x, 0.0001f)
        assertEquals("controls: the pressed top line expected 4 dp plus 1 px down", 14f / 3f, lit.y, 0.0001f)
        assertEquals("controls: the pressed top line expected the face less 2 px each side", 170f - 8f / 3f, lit.width, 0.0001f)
        assertEquals("controls: the pressed top line expected 3 px thick", 2f, lit.height, 0.0001f)
        assertEquals("controls: light shadow line expected 0xFF0E3E26", 0xFF0E3E26.toInt(), ControlsPalette.partColor(ControlPart.SHADOW_LINE, light))
        assertEquals("controls: dark shadow line expected 0xFF267B47", 0xFF267B47.toInt(), ControlsPalette.partColor(ControlPart.SHADOW_LINE, dark))
    }

    /** A failure means the disabled button draws a lip or a lit line, or its outline and label are not muted. */
    @Test
    fun `a disabled button is flat with a muted outline and label`() {
        val parts = ControlsGeometry.disabled(170f, 48f)
        assertNull("controls: the disabled button expected no lip", parts.lip)
        assertNull("controls: the disabled button expected no lit line", parts.litLine)
        assertEquals("controls: light fill expected 0xFFF4F6F4", 0xFFF4F6F4.toInt(), ControlsPalette.partColor(ControlPart.FACE_SURFACE, light))
        assertEquals("controls: dark fill expected 0xFF161B1E", 0xFF161B1E.toInt(), ControlsPalette.partColor(ControlPart.FACE_SURFACE, dark))
        assertEquals("controls: light outline expected 0xFF5A6368", 0xFF5A6368.toInt(), ControlsPalette.partColor(ControlPart.OUTLINE, light))
        assertEquals("controls: dark outline expected 0xFF9AA5A0", 0xFF9AA5A0.toInt(), ControlsPalette.partColor(ControlPart.OUTLINE, dark))
        assertEquals("controls: light label expected 0xFF5A6368", 0xFF5A6368.toInt(), ControlsPalette.partColor(ControlPart.LABEL_DISABLED, light))
        assertEquals("controls: dark label expected 0xFF9AA5A0", 0xFF9AA5A0.toInt(), ControlsPalette.partColor(ControlPart.LABEL_DISABLED, dark))
    }

    /** A failure means the neutral button is not the surface fill with the muted outline, lip and label. */
    @Test
    fun `the neutral button is the surface fill with a muted outline and lip`() {
        val parts = ControlsGeometry.neutral(170f, 48f)
        assertNotNull("controls: the neutral button expected a lip", parts.lip)
        assertNull("controls: the neutral button expected no lit line", parts.litLine)
        assertEquals("controls: light fill expected 0xFFF4F6F4", 0xFFF4F6F4.toInt(), ControlsPalette.partColor(ControlPart.FACE_SURFACE, light))
        assertEquals("controls: dark fill expected 0xFF161B1E", 0xFF161B1E.toInt(), ControlsPalette.partColor(ControlPart.FACE_SURFACE, dark))
        assertEquals("controls: light lip expected 0xFF434A4E", 0xFF434A4E.toInt(), ControlsPalette.partColor(ControlPart.LIP_NEUTRAL, light))
        assertEquals("controls: dark lip expected 0xFF737C78", 0xFF737C78.toInt(), ControlsPalette.partColor(ControlPart.LIP_NEUTRAL, dark))
        assertEquals("controls: light label expected 0xFF111417", 0xFF111417.toInt(), ControlsPalette.partColor(ControlPart.LABEL_NEUTRAL, light))
        assertEquals("controls: dark label expected 0xFFF2F5F2", 0xFFF2F5F2.toInt(), ControlsPalette.partColor(ControlPart.LABEL_NEUTRAL, dark))
    }

    /** A failure means the outlined button is not the page fill with the accent outline, lip and label. */
    @Test
    fun `the outlined button is the page fill with the accent outline and lip`() {
        val parts = ControlsGeometry.outlined(170f, 48f)
        assertNotNull("controls: the outlined button expected a lip", parts.lip)
        assertNull("controls: the outlined button expected no lit line", parts.litLine)
        assertEquals("controls: light fill expected 0xFFFFFFFF", 0xFFFFFFFF.toInt(), ControlsPalette.partColor(ControlPart.FACE_BACKGROUND, light))
        assertEquals("controls: dark fill expected 0xFF0E1113", 0xFF0E1113.toInt(), ControlsPalette.partColor(ControlPart.FACE_BACKGROUND, dark))
        assertEquals("controls: light lip expected 0xFF18532F", 0xFF18532F.toInt(), ControlsPalette.partColor(ControlPart.LIP_ACCENT, light))
        assertEquals("controls: dark lip expected 0xFF226D36", 0xFF226D36.toInt(), ControlsPalette.partColor(ControlPart.LIP_ACCENT, dark))
        assertEquals("controls: light label expected 0xFF257F49", 0xFF257F49.toInt(), ControlsPalette.partColor(ControlPart.LABEL_ACCENT, light))
        assertEquals("controls: dark label expected 0xFF34A853", 0xFF34A853.toInt(), ControlsPalette.partColor(ControlPart.LABEL_ACCENT, dark))
    }

    /** A failure means the destructive button is not the danger face, or its label is not the on colour. */
    @Test
    fun `the destructive button is the danger face with the on label`() {
        assertEquals("controls: light face expected 0xFFC0392B", 0xFFC0392B.toInt(), ControlsPalette.partColor(ControlPart.FACE_DANGER, light))
        assertEquals("controls: dark face expected 0xFFE74C3C", 0xFFE74C3C.toInt(), ControlsPalette.partColor(ControlPart.FACE_DANGER, dark))
        assertEquals("controls: light lip expected 0xFF7D251C", 0xFF7D251C.toInt(), ControlsPalette.partColor(ControlPart.LIP_DANGER, light))
        assertEquals("controls: dark lip expected 0xFF963127", 0xFF963127.toInt(), ControlsPalette.partColor(ControlPart.LIP_DANGER, dark))
        assertEquals("controls: light lit line expected 0xFFD3746B", 0xFFD3746B.toInt(), ControlsPalette.partColor(ControlPart.LIT_LINE_DANGER, light))
        assertEquals("controls: dark lit line expected 0xFFEE8277", 0xFFEE8277.toInt(), ControlsPalette.partColor(ControlPart.LIT_LINE_DANGER, dark))
        assertEquals("controls: light label expected 0xFFFFFFFF", 0xFFFFFFFF.toInt(), ControlsPalette.partColor(ControlPart.LABEL, light))
        assertEquals("controls: dark label expected 0xFF0E1113", 0xFF0E1113.toInt(), ControlsPalette.partColor(ControlPart.LABEL, dark))
    }

    /** A failure means the full width button does not span its row, or stands below the touch floor. */
    @Test
    fun `a full width button spans its row and stands at the touch floor`() {
        val row = 560f / 1.5f
        val parts = ControlsGeometry.button(row, 72f / 1.5f, pressed = false)
        assertEquals("controls: the face expected the row's width", row, parts.face.width, 0.001f)
        val lip = parts.lip
        assertNotNull("controls: the raised button expected a lip", lip)
        assertEquals("controls: the button expected the art's 48 dp", 48f, parts.face.height + lip!!.height, 0.001f)
    }

    /** A failure means the switch is not the primary track with the on thumb while on. */
    @Test
    fun `the switch is the primary track and on thumb while on`() {
        val parts = ControlsGeometry.switch(on = true, w = 48f, h = 80f / 3f)
        assertEquals("controls: the track expected the switch's box", Rect(0f, 0f, 48f, 80f / 3f), parts.track)
        val thumb = parts.thumb
        assertEquals("controls: the on thumb expected 36 px from the left", 24f, thumb.x, 0.0001f)
        assertEquals("controls: the on thumb expected 4 px from the top", 8f / 3f, thumb.y, 0.0001f)
        assertEquals("controls: the on thumb expected 32 px across", 64f / 3f, thumb.width, 0.0001f)
        assertEquals("controls: the on thumb expected 32 px high", 64f / 3f, thumb.height, 0.0001f)
        assertEquals("controls: light track expected 0xFF1E7A46", 0xFF1E7A46.toInt(), ControlsPalette.partColor(ControlPart.TRACK, light))
        assertEquals("controls: dark track expected 0xFF2E9E5B", 0xFF2E9E5B.toInt(), ControlsPalette.partColor(ControlPart.TRACK, dark))
        assertEquals("controls: light thumb expected 0xFFFFFFFF", 0xFFFFFFFF.toInt(), ControlsPalette.partColor(ControlPart.THUMB, light))
        assertEquals("controls: dark thumb expected 0xFF0E1113", 0xFF0E1113.toInt(), ControlsPalette.partColor(ControlPart.THUMB, dark))
    }

    /** A failure means the switch is not the surface track with the muted outline and thumb while off. */
    @Test
    fun `the switch is the surface track and muted thumb while off`() {
        val parts = ControlsGeometry.switch(on = false, w = 48f, h = 80f / 3f)
        val thumb = parts.thumb
        assertEquals("controls: the off thumb expected 5 px from the left", 10f / 3f, thumb.x, 0.0001f)
        assertEquals("controls: the off thumb expected 5 px from the top", 10f / 3f, thumb.y, 0.0001f)
        assertEquals("controls: the off thumb expected 30 px across", 20f, thumb.width, 0.0001f)
        assertEquals("controls: the off thumb expected 30 px high", 20f, thumb.height, 0.0001f)
        assertEquals("controls: light track expected 0xFFF4F6F4", 0xFFF4F6F4.toInt(), ControlsPalette.partColor(ControlPart.TRACK_OFF, light))
        assertEquals("controls: dark track expected 0xFF161B1E", 0xFF161B1E.toInt(), ControlsPalette.partColor(ControlPart.TRACK_OFF, dark))
        assertEquals("controls: light thumb expected 0xFF5A6368", 0xFF5A6368.toInt(), ControlsPalette.partColor(ControlPart.THUMB_OFF, light))
        assertEquals("controls: dark thumb expected 0xFF9AA5A0", 0xFF9AA5A0.toInt(), ControlsPalette.partColor(ControlPart.THUMB_OFF, dark))
    }

    /** A failure means a badge is not the state's fill with the on label, or its size is not the art's. */
    @Test
    fun `the badges are the state fills with the on label`() {
        val ready = ControlsGeometry.badge("READY")
        assertEquals("controls: READY expected 40 px plus 5 characters at 13 px", 70f, ready.width, 0.001f)
        assertEquals("controls: the badge expected the art's 40 px height", 80f / 3f, ready.height, 0.001f)
        val phoneOnly = ControlsGeometry.badge("PHONE ONLY")
        assertEquals("controls: PHONE ONLY expected 40 px plus 10 characters at 13 px", 170f / 1.5f, phoneOnly.width, 0.001f)
        val failed = ControlsGeometry.badge("FAILED")
        assertEquals("controls: FAILED expected 40 px plus 6 characters at 13 px", 118f / 1.5f, failed.width, 0.001f)
        assertEquals("controls: light READY expected 0xFF1E7A46", 0xFF1E7A46.toInt(), ControlsPalette.partColor(ControlPart.FACE, light))
        assertEquals("controls: dark READY expected 0xFF2E9E5B", 0xFF2E9E5B.toInt(), ControlsPalette.partColor(ControlPart.FACE, dark))
        assertEquals("controls: light PHONE ONLY expected 0xFFA55713", 0xFFA55713.toInt(), ControlsPalette.partColor(ControlPart.FACE_WARNING, light))
        assertEquals("controls: dark PHONE ONLY expected 0xFFF39C12", 0xFFF39C12.toInt(), ControlsPalette.partColor(ControlPart.FACE_WARNING, dark))
        assertEquals("controls: light FAILED expected 0xFFC0392B", 0xFFC0392B.toInt(), ControlsPalette.partColor(ControlPart.FACE_DANGER, light))
        assertEquals("controls: dark FAILED expected 0xFFE74C3C", 0xFFE74C3C.toInt(), ControlsPalette.partColor(ControlPart.FACE_DANGER, dark))
        assertEquals("controls: light label expected 0xFFFFFFFF", 0xFFFFFFFF.toInt(), ControlsPalette.partColor(ControlPart.LABEL, light))
        assertEquals("controls: dark label expected 0xFF0E1113", 0xFF0E1113.toInt(), ControlsPalette.partColor(ControlPart.LABEL, dark))
    }

    /** A failure means the field is not the surface fill with the accent outline. */
    @Test
    fun `the text field is the surface fill with the accent outline`() {
        val field = ControlsGeometry.textField(560f / 1.5f, 72f / 1.5f)
        assertEquals("controls: the field expected the art's 48 dp", 48f, field.height, 0.001f)
        assertEquals("controls: light fill expected 0xFFF4F6F4", 0xFFF4F6F4.toInt(), ControlsPalette.partColor(ControlPart.FIELD, light))
        assertEquals("controls: dark fill expected 0xFF161B1E", 0xFF161B1E.toInt(), ControlsPalette.partColor(ControlPart.FIELD, dark))
        assertEquals("controls: light outline expected 0xFF257F49", 0xFF257F49.toInt(), ControlsPalette.partColor(ControlPart.FIELD_OUTLINE, light))
        assertEquals("controls: dark outline expected 0xFF34A853", 0xFF34A853.toInt(), ControlsPalette.partColor(ControlPart.FIELD_OUTLINE, dark))
    }

    /**
     * A failure means a step mark is not slanted by the art's twelve degrees, or the done and todo
     * marks take the wrong colours. The expected slant is the LITERAL a 12-degree slant on an 8 dp
     * mark is worth — tan 12 deg = 0.212557, so 1.70045 dp — never a formula over
     * ControlsFactors.SLANT_DEG, which would move its expectation with the constant and let a
     * changed slant survive this test.
     */
    @Test
    fun `the step marks are slanted twelve degrees, the first accent and the rest muted`() {
        val marks = ControlsGeometry.stepMarks(4, 100f / 3f, 8f)
        assertEquals("controls: the art expected four marks", 4, marks.size)
        for (mark in marks) {
            assertEquals("controls: the mark expected the art's 12-degree slant on an 8 dp mark: 1.70045 dp", 1.70045f, mark.slant, 0.0005f)
        }
        assertEquals("controls: the first mark expected the art's 30 px inset", 20f, marks.first().x, 0f)
        assertEquals("controls: the second mark expected one mark and one gap further", 20f + (100f / 3f + 8f), marks[1].x, 0.001f)
        assertEquals("controls: light done expected 0xFF257F49", 0xFF257F49.toInt(), ControlsPalette.partColor(ControlPart.STEP_DONE, light))
        assertEquals("controls: dark done expected 0xFF34A853", 0xFF34A853.toInt(), ControlsPalette.partColor(ControlPart.STEP_DONE, dark))
        assertEquals("controls: light todo expected 0xFF5A6368", 0xFF5A6368.toInt(), ControlsPalette.partColor(ControlPart.STEP_TODO, light))
        assertEquals("controls: dark todo expected 0xFF9AA5A0", 0xFF9AA5A0.toInt(), ControlsPalette.partColor(ControlPart.STEP_TODO, dark))
    }

    /**
     * A failure means a touch target stands below the touch floor. The buttons and the field are the
     * touch targets and the art draws them at 48 dp; the badge is display-only and the switch's track
     * is the art's own 40 px, so neither is held to the floor here.
     */
    @Test
    fun `every touch target stands at the touch floor`() {
        val raised = ControlsGeometry.button(170f, 48f, pressed = false)
        val raisedLip = raised.lip
        assertNotNull("controls: the raised button expected a lip", raisedLip)
        assertEquals(48f, raised.face.height + raisedLip!!.height, 0.001f)
        val pressed = ControlsGeometry.button(170f, 48f, pressed = true)
        assertEquals(48f, pressed.face.y + pressed.face.height, 0.001f)
        for (parts in listOf(ControlsGeometry.neutral(170f, 48f), ControlsGeometry.outlined(170f, 48f))) {
            val lip = parts.lip
            assertNotNull("controls: the lipped button expected a lip", lip)
            assertEquals(48f, parts.face.height + lip!!.height, 0.001f)
        }
        assertEquals(48f, ControlsGeometry.disabled(170f, 48f).face.height, 0.001f)
        assertEquals(48f, ControlsGeometry.textField(560f, 48f).height, 0.001f)
    }

    /** A failure means the shade arithmetic loses the alpha byte, or moves a channel by the wrong share. */
    @Test
    fun `the shade arithmetic keeps the alpha and moves each channel by its share`() {
        assertEquals("controls: a zero factor expected the colour unchanged", 0x80123456.toInt(), shade(0x80123456.toInt(), 0f))
        assertEquals("controls: half of black expected mid grey", 0xFF808080.toInt(), shade(0xFF000000.toInt(), 0.5f))
        assertEquals("controls: factor 1 expected white with the alpha kept", 0x80FFFFFF.toInt(), shade(0x80123456.toInt(), 1f))
        assertEquals("controls: factor -1 expected black with the alpha kept", 0x80000000.toInt(), shade(0x80123456.toInt(), -1f))
        assertEquals("controls: a factor above 1 expected held to 1", 0xFFFFFFFF.toInt(), shade(0xFF000000.toInt(), 2f))
        assertEquals("controls: NaN expected the colour unchanged", 0xFF123456.toInt(), shade(0xFF123456.toInt(), Float.NaN))
    }

    /** A failure means the drawn form of a label is not the capitals the art draws, or the locale is the phone's, which turns a stored "i" into a dotted capital. */
    @Test
    fun `a stored label is drawn in capitals in the root locale`() {
        assertEquals("DELETE", drawnLabel("Delete"))
        assertEquals("CANCEL", drawnLabel("Cancel"))
        assertEquals("OPEN SETTINGS", drawnLabel("Open settings"))
        assertEquals("READY", drawnLabel("Ready"))
        assertEquals("PHONE ONLY", drawnLabel("phone only"))
        assertEquals("FAILED", drawnLabel("Failed"))
    }

    /**
     * A failure means the drawn form of a label follows the phone's own locale instead of the root
     * one: under a Turkish default the capital of a stored "i" is the dotted İ, so a label drawn in
     * the phone's locale reads "FAİLED" and "OPEN SETTİNGS" on a Turkish phone. The default is put
     * back in a finally, so the test leaves the phone's locale as it found it.
     */
    @Test
    fun `a stored label is drawn in capitals whatever locale the phone carries`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"))
            assertEquals("FAILED", drawnLabel("Failed"))
            assertEquals("PHONE ONLY", drawnLabel("phone only"))
            assertEquals("OPEN SETTINGS", drawnLabel("Open settings"))
        } finally {
            Locale.setDefault(saved)
        }
    }
}
