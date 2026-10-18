package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The colour of one glow layer, as literal ARGB values. Each expected value is traced by hand in the
 * notes: the alpha byte of the colour times the pulse alpha times the layer alpha, rounded half up.
 */
class GlowDrawColorTest {

    private val teal = 0xFF1E7A46.toInt()

    /** A full pulse and the innermost layer: 255 times 0.45 is 114.75, which rounds up to 115, the byte 0x73. */
    @Test
    fun `a full pulse and the innermost layer give alpha 115`() {
        assertEquals(0x731E7A46, glowDrawColor(teal, 1f, 0.45f))
    }

    /** A pulse of a quarter: 0.25 times 0.45 is 0.1125, 255 times it is 28.6875, which rounds up to 29, the byte 0x1D. */
    @Test
    fun `a quarter pulse and the innermost layer give alpha 29`() {
        assertEquals(0x1D1E7A46, glowDrawColor(teal, 0.25f, 0.45f))
    }

    /** Without the rule, a NaN or infinite pulse makes the product not finite and the colour is returned unchanged, alpha 0xFF. */
    @Test
    fun `a pulse that is not finite counts as full strength`() {
        assertEquals(0x731E7A46, glowDrawColor(teal, Float.NaN, 0.45f))
        assertEquals(0x731E7A46, glowDrawColor(teal, Float.POSITIVE_INFINITY, 0.45f))
        assertEquals(0x731E7A46, glowDrawColor(teal, Float.NEGATIVE_INFINITY, 0.45f))
    }

    /** A layer alpha that is not finite gives alpha 0 for every pulse, the low bits kept. */
    @Test
    fun `a layer alpha that is not finite gives no alpha`() {
        assertEquals(0x001E7A46, glowDrawColor(teal, 1f, Float.NaN))
        assertEquals(0x001E7A46, glowDrawColor(teal, 1f, Float.POSITIVE_INFINITY))
        assertEquals(0x001E7A46, glowDrawColor(teal, 1f, Float.NEGATIVE_INFINITY))
    }

    /** Held to 1.0 the product is 0.5, 255 times it is 127.5, which rounds up to 128, the byte 0x80; unheld it would be 0.75 and 0xBF. */
    @Test
    fun `a layer alpha above one is held to one`() {
        assertEquals(0x801E7A46.toInt(), glowDrawColor(teal, 0.5f, 1.5f))
    }

    /** A layer alpha below zero gives no alpha at full pulse; a rule that took the size instead would give 0.5 and 0x80. */
    @Test
    fun `a layer alpha below zero is held to zero`() {
        assertEquals(0x001E7A46, glowDrawColor(teal, 1f, -0.5f))
    }

    /** The alpha byte 0x80 is 128; times 0.5 it is 64, plus half is 64.5, which gives 64, the byte 0x40. The low 24 bits stay ABCDEF. */
    @Test
    fun `the colour bits under the alpha are kept`() {
        assertEquals(0x40ABCDEF, glowDrawColor(0x80ABCDEF.toInt(), 1f, 0.5f))
    }

    /** A layer of one is the ring colour at the same pulse, for each pulse tried. */
    @Test
    fun `a layer of one is the ring colour at the same pulse`() {
        for (pulse in listOf(1f, 0.6f, 0.25f)) {
            assertEquals(ringDrawColor(teal, pulse), glowDrawColor(teal, pulse, 1f))
        }
    }
}
