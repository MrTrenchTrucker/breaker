package dev.breaker.dictation.probe

import dev.breaker.dictation.overlay.GlowShape
import dev.breaker.dictation.overlay.GlowShapes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The debug-only glow shape switch: [glowShapeFromWord] reads a stored word as a shape, and
 * [applyGlowShape] selects one. A missing, empty or unknown word selects nothing, and a null shape
 * leaves the current selection alone.
 */
internal class GlowShapeSwitchTest {

    @Test
    fun `a missing empty or unknown word selects no shape`() {
        assertNull("probe: a null word must select no shape", glowShapeFromWord(null))
        assertNull("probe: an empty word must select no shape", glowShapeFromWord(""))
        assertNull("probe: an unknown word must select no shape", glowShapeFromWord("NOPE"))
    }

    @Test
    fun `the words V1 V2 and V3 read as their shapes`() {
        assertEquals("probe: the word V1 must read as V1", GlowShape.V1, glowShapeFromWord("V1"))
        assertEquals("probe: the word V2 must read as V2", GlowShape.V2, glowShapeFromWord("V2"))
        assertEquals("probe: the word V3 must read as V3", GlowShape.V3, glowShapeFromWord("V3"))
    }

    @Test
    fun `a null shape returns the shape already selected and does not change it`() {
        val before: GlowShape = GlowShapes.screen
        assertEquals("probe: a null shape must return the shape already selected", before, applyGlowShape(null))
        assertEquals("probe: a null shape must not change the selection", before, GlowShapes.screen)
    }

    @Test
    fun `applying V2 selects V2 and applying V1 restores V1`() {
        assertEquals("probe: applying V2 must select V2", GlowShape.V2, applyGlowShape(GlowShape.V2))
        assertEquals("probe: the halo must draw V2", GlowShape.V2, GlowShapes.screen)
        assertEquals("probe: applying V1 must restore V1", GlowShape.V1, applyGlowShape(GlowShape.V1))
        assertEquals("probe: the halo must draw V1 again", GlowShape.V1, GlowShapes.screen)
    }

    @Test
    fun `the test leaves the halo shape at V1 so no state leaks into another test in this JVM`() {
        // This test selects V2 and V3 and then puts the selector back; the final line asserts it is V1.
        assertEquals(GlowShape.V2, applyGlowShape(GlowShape.V2))
        assertEquals(GlowShape.V3, applyGlowShape(GlowShape.V3))
        assertEquals(
            "probe: the selector must be V1 at the end so it cannot leak into another test in this JVM",
            GlowShape.V1,
            applyGlowShape(GlowShape.V1),
        )
        assertEquals("probe: the halo must end on V1", GlowShape.V1, GlowShapes.screen)
    }
}
