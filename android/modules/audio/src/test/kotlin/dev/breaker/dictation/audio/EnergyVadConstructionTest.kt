package dev.breaker.dictation.audio

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arguments the detector refuses to be constructed with.
 */
class EnergyVadConstructionTest {

    // ── construction ────────────────────────────────────────────────────

    @Test
    fun `a frame size of zero is refused`() {
        try {
            EnergyVad(frameSizeSamples = 0)
            org.junit.Assert.fail("expected a zero frame size to be refused")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("frameSizeSamples"))
        }
    }

    @Test
    fun `a negative pad is refused`() {
        try {
            EnergyVad(padMs = -1)
            org.junit.Assert.fail("expected a negative pad to be refused")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("padMs"))
        }
    }

    @Test
    fun `a negative hangover is refused`() {
        try {
            EnergyVad(hangoverFrames = -1)
            org.junit.Assert.fail("expected a negative hangover to be refused")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("hangoverFrames"))
        }
    }
}
