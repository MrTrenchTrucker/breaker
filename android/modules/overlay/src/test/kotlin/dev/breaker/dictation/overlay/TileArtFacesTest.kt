package dev.breaker.dictation.overlay

import dev.breaker.shared.tokens.ThemeMode
import dev.breaker.shared.tokens.TruckingTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The colour of each face of the mic in the light and the dark theme. Every expected value is a literal ARGB
 * worked out by hand from the palette and the shade rule, so a face that takes the wrong field or the wrong
 * factor fails in one of the two themes.
 */
class TileArtFacesTest {

    private val light = TileStyle.look(TileState.IDLE, TruckingTokens.palette(ThemeMode.LIGHT))
    private val dark = TileStyle.look(TileState.IDLE, TruckingTokens.palette(ThemeMode.DARK))

    /** A failure means the trigger body is not the body colour in either theme. */
    @Test
    fun `the trigger body is the body colour in both themes`() {
        assertEquals("overlay: light trigger body expected 0xFF111417", 0xFF111417.toInt(), faceColor(ArtPart.TRIGGER_BODY, light))
        assertEquals("overlay: dark trigger body expected 0xFF161B1E", 0xFF161B1E.toInt(), faceColor(ArtPart.TRIGGER_BODY, dark))
    }

    /** A failure means the trigger face is not the body colour moved a little toward white. */
    @Test
    fun `the trigger face is the body colour nudged toward white`() {
        assertEquals("overlay: light trigger face expected 0xFF2E3033", 0xFF2E3033.toInt(), faceColor(ArtPart.TRIGGER_FACE, light))
        assertEquals("overlay: dark trigger face expected 0xFF323639", 0xFF323639.toInt(), faceColor(ArtPart.TRIGGER_FACE, dark))
    }

    /** A failure means the trigger stripe is not the body colour lifted most of the way toward white. */
    @Test
    fun `the trigger stripe is the body colour lifted toward white`() {
        assertEquals("overlay: light trigger stripe expected 0xFFB8B9B9", 0xFFB8B9B9.toInt(), faceColor(ArtPart.TRIGGER_STRIPE, light))
        assertEquals("overlay: dark trigger stripe expected 0xFFB9BBBC", 0xFFB9BBBC.toInt(), faceColor(ArtPart.TRIGGER_STRIPE, dark))
    }

    /** A failure means the head body is not the body colour in either theme. */
    @Test
    fun `the head body is the body colour in both themes`() {
        assertEquals("overlay: light head body expected 0xFF111417", 0xFF111417.toInt(), faceColor(ArtPart.HEAD_BODY, light))
        assertEquals("overlay: dark head body expected 0xFF161B1E", 0xFF161B1E.toInt(), faceColor(ArtPart.HEAD_BODY, dark))
    }

    /** A failure means the cord body is not the body colour in either theme. */
    @Test
    fun `the cord body is the body colour in both themes`() {
        assertEquals("overlay: light cord body expected 0xFF111417", 0xFF111417.toInt(), faceColor(ArtPart.CORD_BODY, light))
        assertEquals("overlay: dark cord body expected 0xFF161B1E", 0xFF161B1E.toInt(), faceColor(ArtPart.CORD_BODY, dark))
    }

    /** A failure means the lower left shading is not the body colour moved a little toward white. */
    @Test
    fun `the lower left shading is the body colour nudged toward white`() {
        assertEquals("overlay: light lower left expected 0xFF24272A", 0xFF24272A.toInt(), faceColor(ArtPart.LOWER_LEFT, light))
        assertEquals("overlay: dark lower left expected 0xFF292D30", 0xFF292D30.toInt(), faceColor(ArtPart.LOWER_LEFT, dark))
    }

    /** A failure means the lower right shading is not the body colour darkened toward black. */
    @Test
    fun `the lower right shading is the body colour darkened toward black`() {
        assertEquals("overlay: light lower right expected 0xFF08090A", 0xFF08090A.toInt(), faceColor(ArtPart.LOWER_RIGHT, light))
        assertEquals("overlay: dark lower right expected 0xFF0A0C0D", 0xFF0A0C0D.toInt(), faceColor(ArtPart.LOWER_RIGHT, dark))
    }

    /** A failure means the frame top is not the trim colour itself, since its factor is zero. */
    @Test
    fun `the frame top is the trim colour itself`() {
        assertEquals("overlay: light frame top expected 0xFF000000", 0xFF000000.toInt(), faceColor(ArtPart.FRAME_TOP, light))
        assertEquals("overlay: dark frame top expected 0xFFFFFFFF", 0xFFFFFFFF.toInt(), faceColor(ArtPart.FRAME_TOP, dark))
    }

    /** A failure means the frame left is not the trim colour moved toward black; in the light theme black has no darker step, so it stays black. */
    @Test
    fun `the frame left is the trim colour nudged toward black`() {
        assertEquals("overlay: light frame left expected 0xFF000000", 0xFF000000.toInt(), faceColor(ArtPart.FRAME_LEFT, light))
        assertEquals("overlay: dark frame left expected 0xFFEBEBEB", 0xFFEBEBEB.toInt(), faceColor(ArtPart.FRAME_LEFT, dark))
    }

    /** A failure means the frame bottom is not the trim colour darkened by its larger factor. */
    @Test
    fun `the frame bottom is the trim colour darkened toward black`() {
        assertEquals("overlay: light frame bottom expected 0xFF000000", 0xFF000000.toInt(), faceColor(ArtPart.FRAME_BOTTOM, light))
        assertEquals("overlay: dark frame bottom expected 0xFFA1A1A1", 0xFFA1A1A1.toInt(), faceColor(ArtPart.FRAME_BOTTOM, dark))
    }

    /** A failure means the frame right is not the trim colour darkened by its own factor. */
    @Test
    fun `the frame right is the trim colour darkened toward black`() {
        assertEquals("overlay: light frame right expected 0xFF000000", 0xFF000000.toInt(), faceColor(ArtPart.FRAME_RIGHT, light))
        assertEquals("overlay: dark frame right expected 0xFFBDBDBD", 0xFFBDBDBD.toInt(), faceColor(ArtPart.FRAME_RIGHT, dark))
    }

    /** A failure means the well is not the body colour in either theme. */
    @Test
    fun `the well is the body colour in both themes`() {
        assertEquals("overlay: light well expected 0xFF111417", 0xFF111417.toInt(), faceColor(ArtPart.WELL, light))
        assertEquals("overlay: dark well expected 0xFF161B1E", 0xFF161B1E.toInt(), faceColor(ArtPart.WELL, dark))
    }

    /** A failure means the ledge edge is not the body colour lifted toward white. */
    @Test
    fun `the ledge edge is the body colour lifted toward white`() {
        assertEquals("overlay: light ledge edge expected 0xFF292C2E", 0xFF292C2E.toInt(), faceColor(ArtPart.LEDGE_EDGE, light))
        assertEquals("overlay: dark ledge edge expected 0xFF2D3235", 0xFF2D3235.toInt(), faceColor(ArtPart.LEDGE_EDGE, dark))
    }

    /** A failure means the ledge face is not the trim colour darkened toward black. */
    @Test
    fun `the ledge face is the trim colour darkened toward black`() {
        assertEquals("overlay: light ledge face expected 0xFF000000", 0xFF000000.toInt(), faceColor(ArtPart.LEDGE_FACE, light))
        assertEquals("overlay: dark ledge face expected 0xFFB2B2B2", 0xFFB2B2B2.toInt(), faceColor(ArtPart.LEDGE_FACE, dark))
    }

    /** A failure means the left ledge end is not the trim colour darkened toward black by its factor. */
    @Test
    fun `the left ledge end is the trim colour darkened toward black`() {
        assertEquals("overlay: light left ledge end expected 0xFF000000", 0xFF000000.toInt(), faceColor(ArtPart.LEDGE_END_LEFT, light))
        assertEquals("overlay: dark left ledge end expected 0xFF7F7F7F", 0xFF7F7F7F.toInt(), faceColor(ArtPart.LEDGE_END_LEFT, dark))
    }

    /** A failure means the right ledge end is not the trim colour darkened toward black by its factor. */
    @Test
    fun `the right ledge end is the trim colour darkened toward black`() {
        assertEquals("overlay: light right ledge end expected 0xFF000000", 0xFF000000.toInt(), faceColor(ArtPart.LEDGE_END_RIGHT, light))
        assertEquals("overlay: dark right ledge end expected 0xFF7F7F7F", 0xFF7F7F7F.toInt(), faceColor(ArtPart.LEDGE_END_RIGHT, dark))
    }

    /** A failure means the slot top strip is not the lit colour darkened by the slot top factor. */
    @Test
    fun `the slot top strip is the lit colour darkened toward black`() {
        assertEquals("overlay: light slot top expected 0xFF175D35", 0xFF175D35.toInt(), faceColor(ArtPart.SLOT_TOP, light))
        assertEquals("overlay: dark slot top expected 0xFF237845", 0xFF237845.toInt(), faceColor(ArtPart.SLOT_TOP, dark))
    }

    /** A failure means a lit slot body is not the lit colour, or the lit flag is ignored. */
    @Test
    fun `the lit slot body is the lit colour in both themes`() {
        assertEquals("overlay: light lit slot body expected 0xFF1E7A46", 0xFF1E7A46.toInt(), slotColor(false, true, light))
        assertEquals("overlay: dark lit slot body expected 0xFF2E9E5B", 0xFF2E9E5B.toInt(), slotColor(false, true, dark))
    }

    /** A failure means a lit slot strip is not the lit colour darkened toward black, or the strip is not shaded. */
    @Test
    fun `the lit slot top strip is the lit colour darkened toward black`() {
        assertEquals("overlay: light lit slot top expected 0xFF175D35", 0xFF175D35.toInt(), slotColor(true, true, light))
        assertEquals("overlay: dark lit slot top expected 0xFF237845", 0xFF237845.toInt(), slotColor(true, true, dark))
    }

    /** A failure means an unlit slot body is not the page background, so the unlit segment is not used. */
    @Test
    fun `the unlit slot body is the page background in both themes`() {
        assertEquals("overlay: light unlit slot body expected 0xFFFFFFFF", 0xFFFFFFFF.toInt(), slotColor(false, false, light))
        assertEquals("overlay: dark unlit slot body expected 0xFF0E1113", 0xFF0E1113.toInt(), slotColor(false, false, dark))
    }

    /** A failure means an unlit slot strip is not the page background darkened toward black, or the strip takes the lit colour. */
    @Test
    fun `the unlit slot top strip is the page background darkened toward black`() {
        assertEquals("overlay: light unlit slot top expected 0xFFC2C2C2", 0xFFC2C2C2.toInt(), slotColor(true, false, light))
        assertEquals("overlay: dark unlit slot top expected 0xFF0B0D0E", 0xFF0B0D0E.toInt(), slotColor(true, false, dark))
    }

    /** A failure means the lettering is not the glyph colour in one of the two themes, or it is the body colour. */
    @Test
    fun `the lettering is the glyph colour in both themes`() {
        assertEquals("overlay: light lettering expected 0xFF1E7A46", 0xFF1E7A46.toInt(), faceColor(ArtPart.LETTERS, light))
        assertEquals("overlay: dark lettering expected 0xFF2E9E5B", 0xFF2E9E5B.toInt(), faceColor(ArtPart.LETTERS, dark))
        assertNotEquals("overlay: light lettering expected a colour other than the body", bodyColor(light), faceColor(ArtPart.LETTERS, light))
        assertNotEquals("overlay: dark lettering expected a colour other than the body", bodyColor(dark), faceColor(ArtPart.LETTERS, dark))
    }
}
