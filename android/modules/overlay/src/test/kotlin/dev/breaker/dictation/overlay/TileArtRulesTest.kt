package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules of the mic art: the shade arithmetic, which slots are lit, the glow alphas, and the range of
 * the named shade factors.
 */
class TileArtRulesTest {

    /** A failure means a zero factor changes the colour, or drops the alpha byte. */
    @Test
    fun `a zero factor keeps the colour, alpha included`() {
        assertEquals("overlay: a zero factor expected the colour unchanged", 0x80123456.toInt(), shade(0x80123456.toInt(), 0f))
    }

    /** A failure means a factor of one does not mix to white, or the alpha byte changes. */
    @Test
    fun `a factor of one mixes to white and keeps the alpha byte`() {
        assertEquals("overlay: factor 1 expected 0x80FFFFFF", 0x80FFFFFF.toInt(), shade(0x80123456.toInt(), 1f))
    }

    /** A failure means a factor of minus one does not mix to black, or the alpha byte changes. */
    @Test
    fun `a factor of minus one mixes to black and keeps the alpha byte`() {
        assertEquals("overlay: factor -1 expected 0x80000000", 0x80000000.toInt(), shade(0x80123456.toInt(), -1f))
    }

    /** A failure means half of black is not mid grey, so a half step does not go up. */
    @Test
    fun `half of black is mid grey, a half step going up`() {
        assertEquals("overlay: factor 0.5 on black expected 0xFF808080", 0xFF808080.toInt(), shade(0xFF000000.toInt(), 0.5f))
    }

    /** A failure means a channel at 30 does not reach 188 at a factor of 0.70, so the half step is not taken upward. */
    @Test
    fun `a factor of seven tenths on a channel at 30 lifts it by exactly 158`() {
        assertEquals("overlay: blue 30 at factor 0.70 expected 188", 188, shade(0xFF161B1E.toInt(), 0.70f) and 0xFF)
    }

    /** A failure means a part shade loses the alpha byte, or moves a channel by the wrong share. */
    @Test
    fun `a part shade keeps the alpha byte and moves each channel by its share`() {
        assertEquals("overlay: factor 0.5 on 0x80123456 expected 0x80899AAB", 0x80899AAB.toInt(), shade(0x80123456.toInt(), 0.5f))
        assertEquals("overlay: the alpha byte expected 0x80", 0x80, shade(0x80123456.toInt(), 0.5f) ushr 24)
    }

    /** A failure means a factor that is not a number changes the colour, or throws. */
    @Test
    fun `a factor that is not a number returns the colour`() {
        assertEquals("overlay: NaN expected the colour unchanged", 0xFF123456.toInt(), shade(0xFF123456.toInt(), Float.NaN))
    }

    /** A failure means a factor above one is not held to one, so the colour goes past white, or below minus one goes past black. */
    @Test
    fun `a factor above one is held to one and below minus one to minus one`() {
        assertEquals("overlay: factor 2 expected white", 0xFFFFFFFF.toInt(), shade(0xFF000000.toInt(), 2f))
        assertEquals("overlay: factor -2 expected black", 0xFF000000.toInt(), shade(0xFFFFFFFF.toInt(), -2f))
    }

    /** A failure means in the recording state the lit slots are not the leftmost ones, one for each step of the level. */
    @Test
    fun `in the recording state the slots light from the left, one for each step of the level`() {
        for (lit in 0..6) {
            val litSlots = (0..5).filter { slotLit(TileState.RECORDING, it, lit) }
            assertEquals("overlay: a level of $lit expected the leftmost $lit slots lit", (0 until lit).toList(), litSlots)
        }
    }

    /** A failure means a state other than recording dims a slot, or the level changes a slot outside recording. */
    @Test
    fun `every other state lights all six slots whatever the level`() {
        for (state in TileState.values().filter { it != TileState.RECORDING }) {
            for (lit in listOf(-3, 0, 3, 6, 9)) {
                assertEquals(
                    "overlay: $state at level $lit expected six lit slots",
                    (0..5).toList(),
                    (0..5).filter { slotLit(state, it, lit) },
                )
            }
        }
    }

    /** A failure means a recording level above six does not light all six, or a level below zero lights a slot. */
    @Test
    fun `a recording level above six lights all six slots and below zero lights none`() {
        assertEquals("overlay: level 9 expected all six lit", (0..5).toList(), (0..5).filter { slotLit(TileState.RECORDING, it, 9) })
        assertEquals("overlay: level -1 expected none lit", emptyList<Int>(), (0..5).filter { slotLit(TileState.RECORDING, it, -1) })
    }

    /** A failure means a glow layer has the wrong alpha, or the layers do not fade from the inside out. */
    @Test
    fun `the glow layers fade from the inside out with the alpha each layer is given`() {
        assertTrue("overlay: layer 0 expected alpha 0.45", glowAlpha(0) == 0.45f)
        assertTrue("overlay: layer 1 expected alpha 0.25", glowAlpha(1) == 0.25f)
        assertTrue("overlay: layer 2 expected alpha 0.12", glowAlpha(2) == 0.12f)
        assertTrue("overlay: the layers expected to fall from the inside out", glowAlpha(0) > glowAlpha(1) && glowAlpha(1) > glowAlpha(2))
    }

    /** A failure means a layer outside zero to two draws, which would add an unplanned halo. */
    @Test
    fun `a glow layer outside zero to two has no alpha`() {
        assertTrue("overlay: layer -1 expected alpha 0", glowAlpha(-1) == 0f)
        assertTrue("overlay: layer 3 expected alpha 0", glowAlpha(3) == 0f)
    }

    /** A failure means a named shade factor lies outside minus one to one, where the shade would hold it silently. */
    @Test
    fun `every named shade factor lies between minus one and one`() {
        val factors = listOf(
            ArtFactors.TRIGGER_FACE, ArtFactors.TRIGGER_STRIPE, ArtFactors.LOWER_LEFT, ArtFactors.LOWER_RIGHT,
            ArtFactors.FRAME_TOP, ArtFactors.FRAME_LEFT, ArtFactors.FRAME_BOTTOM, ArtFactors.FRAME_RIGHT,
            ArtFactors.LEDGE_EDGE, ArtFactors.LEDGE_FACE, ArtFactors.LEDGE_END, ArtFactors.SLOT_TOP,
        )
        for (f in factors) {
            assertTrue("overlay: factor $f expected between -1 and 1", f >= -1f && f <= 1f)
        }
    }
}
