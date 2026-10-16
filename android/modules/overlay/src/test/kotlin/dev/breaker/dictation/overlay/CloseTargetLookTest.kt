package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

/** The named look constants of the close target box, and the alpha byte of its fill opacity. */
class CloseTargetLookTest {

    /** A failure here means one of the five named look constants differs from the design value. */
    @Test
    fun `the five look constants hold the design values`() {
        assertEquals("overlay: the fill lightening", 0.085f, CloseTargetLook.FILL_LIGHTEN, 0f)
        assertEquals("overlay: the truck shade", -0.05f, CloseTargetLook.TRUCK_SHADE, 0f)
        assertEquals("overlay: the active load lightening", 0.07f, CloseTargetLook.LOAD_ACTIVE_LIGHTEN, 0f)
        assertEquals("overlay: the idle fill opacity", 0.68f, CloseTargetLook.FILL_OPACITY_IDLE, 0f)
        assertEquals("overlay: the active fill opacity", 0.82f, CloseTargetLook.FILL_OPACITY_ACTIVE, 0f)
    }

    /** A failure here means the idle or the active opacity does not become the alpha byte of the design. */
    @Test
    fun `alphaOf turns the idle and active opacities into 173 and 209`() {
        assertEquals("overlay: opacity 0.68 must be alpha 173", 173, CloseTargetLook.alphaOf(0.68f))
        assertEquals("overlay: opacity 0.82 must be alpha 209", 209, CloseTargetLook.alphaOf(0.82f))
    }

    /** A failure here means an opacity outside 0 to 1 is not held to the alpha range. */
    @Test
    fun `alphaOf holds the ends and the values outside 0 to 1 to the alpha range`() {
        assertEquals("overlay: opacity 0 must be alpha 0", 0, CloseTargetLook.alphaOf(0f))
        assertEquals("overlay: opacity 1 must be alpha 255", 255, CloseTargetLook.alphaOf(1f))
        assertEquals("overlay: a negative opacity must be alpha 0", 0, CloseTargetLook.alphaOf(-0.5f))
        assertEquals("overlay: an opacity above 1 must be alpha 255", 255, CloseTargetLook.alphaOf(2f))
    }
}
