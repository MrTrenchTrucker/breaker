package dev.breaker.dictation.ui.render

import dev.breaker.dictation.ui.controls.scaledAlpha
import dev.breaker.dictation.ui.controls.shade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The pure functions of the full-width layers, the capitals helper and the soft stripe colour, run
 * on the JVM. The stripe colour is checked per mode and against the two colours a broken factor
 * would give: the primary itself and the full shade.
 */
class RenderFoundationMathTest {
    private val lightPrimary = 0xFF1E7A46.toInt()
    private val darkPrimary = 0xFF2E9E5B.toInt()

    @Test
    fun `a lower-case word is drawn in capitals and an upper-case word is left as it is`() {
        assertEquals("TAP TO TALK", displayText("Tap to talk", true))
        assertEquals("HISTORY", displayText("HISTORY", true))
    }

    @Test
    fun `a non-letter is left alone and the root locale is used for the capitals`() {
        assertEquals("06:42", displayText("06:42", true))
        assertEquals("ISTANBUL", displayText("istanbul", true))
        val saved = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("tr"))
        try {
            assertEquals("the capitals ignore the default locale", "ISTANBUL", displayText("istanbul", true))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `without the flag the words come back exactly as written`() {
        assertEquals("Last transcript", displayText("Last transcript", false))
    }

    @Test
    fun `the bleed width adds the host's padding on both sides to the parent width`() {
        assertEquals(392, bleedWidth(360, 16, 16))
        assertEquals(100, bleedWidth(100, 0, 0))
        assertEquals(116, bleedWidth(100, 16, 0))
        assertEquals(116, bleedWidth(100, 0, 16))
    }

    @Test
    fun `the bleed shift moves a layer left by the host's left padding`() {
        assertEquals(-16, bleedShift(16))
        assertEquals(0, bleedShift(0))
    }

    @Test
    fun `the bleed takes the nearest padded scroll view and the outermost when none has`() {
        assertEquals("a single padded scroll view", 0, bleedScrollIndex(listOf(32)))
        assertEquals("an unpadded scroll view inside a padded one is passed over", 1, bleedScrollIndex(listOf(0, 32)))
        assertEquals("the nearest padded one wins", 0, bleedScrollIndex(listOf(16, 32)))
        assertEquals("with none padded, the outermost", 1, bleedScrollIndex(listOf(0, 0)))
        assertEquals("a single unpadded scroll view", 0, bleedScrollIndex(listOf(0)))
        assertEquals("no scroll view above the layer", null, bleedScrollIndex(emptyList()))
    }

    @Test
    fun `the stripes alternate between the primary and the soft colour, starting with the primary`() {
        assertTrue(isPrimaryStripe(0))
        assertFalse(isPrimaryStripe(1))
        assertTrue(isPrimaryStripe(2))
        assertFalse(isPrimaryStripe(3))
    }

    /**
     * The light soft stripe is the primary lightened part of the way. A factor of 1.0 gives the full
     * white, which differs from the primary and is lighter, so those two asserts pass on it; the
     * assert that the channels are not all full is the one that a full lightening breaks. This test
     * is the one that fails when the light factor is 1.0.
     */
    @Test
    fun `in light mode the soft stripe is lighter than the primary, not the primary, and not the full white`() {
        val soft = softStripeColor(lightPrimary, dark = false)

        assertTrue("the soft stripe must differ from the primary", soft != lightPrimary)
        assertTrue("the light soft stripe must be lighter", brightness(soft) > brightness(lightPrimary))
        assertTrue("the light soft stripe must keep some of the primary, not the full white", (soft and 0xFFFFFF) != 0xFFFFFF)
        assertEquals("the alpha is kept", 0xFF, soft ushr 24)
    }

    @Test
    fun `the drawable alpha scales the alpha byte of a colour and keeps its channels`() {
        assertEquals(0xFF1E7A46.toInt(), scaledAlpha(0xFF1E7A46.toInt(), 255))
        assertEquals(0x801E7A46.toInt(), scaledAlpha(0xFF1E7A46.toInt(), 128))
        assertEquals(0x001E7A46, scaledAlpha(0xFF1E7A46.toInt(), 0))
    }

    @Test
    fun `in dark mode the soft stripe is darker than the primary and is not the primary`() {
        val soft = softStripeColor(darkPrimary, dark = true)

        assertTrue("the soft stripe must differ from the primary", soft != darkPrimary)
        assertTrue("the dark soft stripe must be darker", brightness(soft) < brightness(darkPrimary))
        assertEquals("the alpha is kept", 0xFF, soft ushr 24)
    }

    @Test
    fun `the soft stripe is a named partial shade, not the full white or the full black`() {
        assertTrue("a full lightening must not be used", softStripeColor(lightPrimary, false) != shade(lightPrimary, 1f))
        assertTrue("a full darkening must not be used", softStripeColor(darkPrimary, true) != shade(darkPrimary, -1f))
        assertTrue("the light soft stripe must not be pure white", softStripeColor(lightPrimary, false) != 0xFFFFFFFF.toInt())
    }

    private fun brightness(color: Int): Double {
        val redPart = (color ushr 16) and 0xFF
        val green = (color ushr 8) and 0xFF
        val blue = color and 0xFF
        return 0.2126 * redPart + 0.7152 * green + 0.0722 * blue
    }
}
