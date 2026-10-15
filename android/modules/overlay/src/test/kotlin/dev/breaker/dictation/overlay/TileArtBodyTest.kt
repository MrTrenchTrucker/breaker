package dev.breaker.dictation.overlay

import dev.breaker.shared.tokens.ThemeMode
import dev.breaker.shared.tokens.TruckingTokens
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The body colour of the mic: the darker of the text and the surface colours in each theme, chosen by the
 * relative luminance, with a tie going to the background. The luminance values are worked out by awk and
 * written as literals to six decimals.
 */
class TileArtBodyTest {

    private val light = TileStyle.look(TileState.IDLE, TruckingTokens.palette(ThemeMode.LIGHT))
    private val dark = TileStyle.look(TileState.IDLE, TruckingTokens.palette(ThemeMode.DARK))

    /** A failure means the light body is not the text colour, so the body would be the light surface. */
    @Test
    fun `the body is dark in the light theme and comes from text`() {
        assertEquals("overlay: light body expected 0xFF111417", 0xFF111417.toInt(), bodyColor(light))
        assertEquals("overlay: light body expected the text colour", TruckingTokens.palette(ThemeMode.LIGHT).text.argb, bodyColor(light))
    }

    /** A failure means the dark body is not the surface colour, so the body would be the light text. */
    @Test
    fun `the body is dark in the dark theme and comes from surface`() {
        assertEquals("overlay: dark body expected 0xFF161B1E", 0xFF161B1E.toInt(), bodyColor(dark))
        assertEquals("overlay: dark body expected the surface colour", TruckingTokens.palette(ThemeMode.DARK).surface.argb, bodyColor(dark))
    }

    /** A failure means black does not have the luminance of zero. */
    @Test
    fun `the relative luminance of black is exactly zero`() {
        assertEquals("overlay: black expected luminance 0", 0.0, relativeLuminance(0xFF000000.toInt()), 0.0)
    }

    /** A failure means white does not have the luminance of one. */
    @Test
    fun `the relative luminance of white is exactly one`() {
        assertEquals("overlay: white expected luminance 1", 1.0, relativeLuminance(0xFFFFFFFF.toInt()), 0.0)
    }

    /** A failure means the light text colour does not have its worked luminance, 0.006813 to six decimals. */
    @Test
    fun `the relative luminance of the light text colour matches its worked value`() {
        assertEquals("overlay: light text expected 0.006813", 0.006813, relativeLuminance(0xFF111417.toInt()), 0.0000005)
    }

    /** A failure means the light surface colour does not have its worked luminance, 0.916763 to six decimals. */
    @Test
    fun `the relative luminance of the light surface colour matches its worked value`() {
        assertEquals("overlay: light surface expected 0.916763", 0.916763, relativeLuminance(0xFFF4F6F4.toInt()), 0.0000005)
    }

    /** A failure means the dark surface colour does not have its worked luminance, 0.010482 to six decimals. */
    @Test
    fun `the relative luminance of the dark surface colour matches its worked value`() {
        assertEquals("overlay: dark surface expected 0.010482", 0.010482, relativeLuminance(0xFF161B1E.toInt()), 0.0000005)
    }

    /** A failure means the dark text colour does not have its worked luminance, 0.905929 to six decimals. */
    @Test
    fun `the relative luminance of the dark text colour matches its worked value`() {
        assertEquals("overlay: dark text expected 0.905929", 0.905929, relativeLuminance(0xFFF2F5F2.toInt()), 0.0000005)
    }

    /** A failure means the alpha byte changes the luminance, which the rule does not allow. */
    @Test
    fun `the alpha byte does not change the relative luminance`() {
        assertEquals("overlay: half alpha expected the luminance of the opaque colour", relativeLuminance(0xFF111417.toInt()), relativeLuminance(0x80111417.toInt()), 0.0)
    }

    /** A failure means a body whose two colours tie takes the text instead of the background. The colours differ only in alpha, so their luminance is exactly equal. */
    @Test
    fun `a body whose colours tie in luminance takes the background, alpha and all`() {
        val tie = lookWith(background = 0x80111417.toInt(), control = 0xFF111417.toInt())
        assertEquals("overlay: a tie expected the background colour", 0x80111417.toInt(), bodyColor(tie))
    }

    /** A failure means a body whose background is the darker colour does not take the background. */
    @Test
    fun `a body whose background is darker than its text takes the background`() {
        val look = lookWith(background = 0xFF000000.toInt(), control = 0xFFFFFFFF.toInt())
        assertEquals("overlay: darker background expected the background", 0xFF000000.toInt(), bodyColor(look))
    }

    /** A failure means a body whose text is the darker colour does not take the text. */
    @Test
    fun `a body whose text is darker than its background takes the text`() {
        val look = lookWith(background = 0xFFFFFFFF.toInt(), control = 0xFF000000.toInt())
        assertEquals("overlay: darker text expected the text", 0xFF000000.toInt(), bodyColor(look))
    }

    private fun lookWith(background: Int, control: Int): TileLook = TileLook(
        background = background,
        glyph = 0xFF000000.toInt(),
        glyphOutline = 0xFF000000.toInt(),
        ring = 0xFF000000.toInt(),
        litSegment = 0xFF000000.toInt(),
        unlitSegment = 0xFF000000.toInt(),
        control = control,
    )
}
