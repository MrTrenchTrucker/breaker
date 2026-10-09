package dev.breaker.dictation.overlay

import dev.breaker.shared.tokens.ThemeMode
import dev.breaker.shared.tokens.TruckingTokens
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The colour the armed ring is drawn with at a pulse alpha, through [ringDrawColor]: the palette colour with its
 * own alpha byte multiplied by the pulse alpha, rounded half up, and its colour bits kept. The values the pulse
 * never sends (NaN and the two infinities) keep the colour, and the finite range is held to zero to one.
 */
class PulseAlphaTest {

    private val modes = listOf(ThemeMode.LIGHT, ThemeMode.DARK)
    private val nonFinite = listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
    private val opaque = 0xFF1E7A46.toInt()
    private val halfAlpha = 0x80FFFFFF.toInt()

    private fun alphaByte(argb: Int): Int = argb ushr 24

    private fun rgb(argb: Int): Int = argb and 0xFFFFFF

    /** A failure means a non-finite alpha on the armed ring is drawn other than at full strength, or it changes the colour. */
    @Test
    fun `a non finite alpha on the armed ring is drawn at full strength with the colour kept`() {
        for (mode in modes) {
            val palette = TruckingTokens.palette(mode)
            for (alpha in nonFinite) {
                val drawn = ringDrawColor(palette.primary.argb, alpha)
                assertEquals("overlay: $mode armed ring at alpha $alpha expected an alpha byte of 255", 255, alphaByte(drawn))
                assertEquals("overlay: $mode armed ring at alpha $alpha expected the primary colour", palette.primary.argb, drawn)
            }
        }
    }

    /** A failure means a non-finite pulse alpha does not leave the colour unchanged. */
    @Test
    fun `ringDrawColor takes a non finite alpha as the colour unchanged`() {
        for (alpha in nonFinite) {
            assertEquals("overlay: opaque colour at alpha $alpha expected the colour unchanged", opaque, ringDrawColor(opaque, alpha))
            assertEquals("overlay: half alpha colour at alpha $alpha expected the colour unchanged", halfAlpha, ringDrawColor(halfAlpha, alpha))
        }
    }

    /** A failure means an opaque colour under a non-finite pulse alpha is not drawn at full strength. */
    @Test
    fun `a non finite pulse alpha keeps an opaque colour at full strength`() {
        for (alpha in nonFinite) {
            assertEquals("overlay: opaque colour at alpha $alpha expected an alpha byte of 255", 255, alphaByte(ringDrawColor(opaque, alpha)))
        }
    }

    /** A failure means a finite pulse alpha is not held to zero to one, or is not rounded to the nearest step. */
    @Test
    fun `ringDrawColor holds finite pulse alphas to zero to one and rounds them`() {
        assertEquals("overlay: pulse 0 expected an alpha byte of 0", 0, alphaByte(ringDrawColor(opaque, 0f)))
        assertEquals("overlay: pulse 1 expected an alpha byte of 255", 255, alphaByte(ringDrawColor(opaque, 1f)))
        assertEquals("overlay: pulse 0.25 expected an alpha byte of 64", 64, alphaByte(ringDrawColor(opaque, 0.25f)))
        assertEquals("overlay: pulse -0.2 expected an alpha byte of 0", 0, alphaByte(ringDrawColor(opaque, -0.2f)))
        assertEquals("overlay: pulse 1.7 expected an alpha byte of 255", 255, alphaByte(ringDrawColor(opaque, 1.7f)))
    }

    /** A failure means the pulse alpha replaces the colour's own alpha byte instead of being multiplied into it. */
    @Test
    fun `the pulse alpha is applied to the colour's own alpha byte`() {
        assertEquals("overlay: a half alpha colour at pulse 0.5 expected an alpha byte of 64", 64, alphaByte(ringDrawColor(halfAlpha, 0.5f)))
        assertEquals("overlay: a half alpha colour at pulse 0.5 expected the colour bits kept", 0xFFFFFF, rgb(ringDrawColor(halfAlpha, 0.5f)))
        assertEquals("overlay: an opaque colour at pulse 0.5 expected an alpha byte of 128", 128, alphaByte(ringDrawColor(opaque, 0.5f)))
    }

    /** A failure means the pulse alpha is not rounded half up: an opaque colour at one half must give 128, not the truncated 127. */
    @Test
    fun `finite pulse alphas are held to zero to one and rounded half up`() {
        assertEquals("overlay: an opaque colour at pulse 0.5 expected an alpha byte of 128", 128, alphaByte(ringDrawColor(opaque, 0.5f)))
        assertEquals("overlay: pulse 0.5 expected the colour bits kept", 0x1E7A46, rgb(ringDrawColor(opaque, 0.5f)))
    }

    /** A failure means a non-finite alpha changes the ring of a state other than armed. */
    @Test
    fun `a non finite alpha changes no ring other than the armed ring`() {
        for (mode in modes) {
            val palette = TruckingTokens.palette(mode)
            for (state in TileState.values()) {
                if (state == TileState.ARMED) continue
                val ring = TileStyle.look(state, palette).ring
                assertEquals("overlay: $mode $state ring expected the palette ring", ring, ringColor(state, palette))
                for (alpha in nonFinite) {
                    assertEquals("overlay: $mode $state ring at alpha $alpha expected the palette ring", ring, ringDrawColor(ring, alpha))
                }
            }
        }
    }

    /** A failure means a half alpha colour at one half is not drawn at a quarter alpha, with its colour bits kept bit for bit. */
    @Test
    fun `a half alpha colour at one half gives a quarter alpha with the colour bits kept`() {
        assertEquals("overlay: 0x80FFFFFF at pulse 0.5 expected 0x40FFFFFF", 0x40FFFFFF, ringDrawColor(halfAlpha, 0.5f))
    }

    /** A failure means the colour bits change under a pulse alpha, for the armed ring or for a literal colour. */
    @Test
    fun `the colour bits are kept bit for bit under every tested pulse alpha`() {
        for (mode in modes) {
            val primary = TruckingTokens.palette(mode).primary.argb
            for (alpha in listOf(0.25f, 0.5f, 1f)) {
                assertEquals("overlay: $mode primary colour at pulse $alpha expected the colour bits kept", rgb(primary), rgb(ringDrawColor(primary, alpha)))
            }
        }
        for (colour in listOf(opaque, 0x00123456, halfAlpha)) {
            for (alpha in listOf(0.25f, 0.5f, 1f)) {
                assertEquals("overlay: colour $colour at pulse $alpha expected the colour bits kept", rgb(colour), rgb(ringDrawColor(colour, alpha)))
            }
        }
    }

    /** A failure means a pulse alpha of zero does not give an alpha byte of zero for an opaque colour. */
    @Test
    fun `a pulse alpha of zero gives an alpha byte of zero`() {
        assertEquals("overlay: opaque colour at pulse 0 expected 0x001E7A46", 0x001E7A46, ringDrawColor(opaque, 0f))
    }
}
