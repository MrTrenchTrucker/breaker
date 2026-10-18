package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two decisions of the armed ring's pulse, as pure functions: whether a pulse is wanted at all,
 * and whether it runs under the five conditions the view can see. The timing constants are pinned too.
 */
class PulseRulesTest {

    private val conditions = listOf("wanted", "attached", "visible", "screen on", "animations on")

    /** A failure means the pulse runs with all five conditions held, or keeps running when one of them is false. */
    @Test
    fun `the pulse runs only when all five conditions hold`() {
        assertTrue("overlay: control: all five held must run", pulseShouldRun(true, true, true, true, true))
        for (index in conditions.indices) {
            val held = BooleanArray(5) { it != index }
            assertFalse(
                "overlay: a pulse with ${conditions[index]} false must not run",
                pulseShouldRun(held[0], held[1], held[2], held[3], held[4]),
            )
        }
    }

    /** A failure means one of the 32 combinations of the five conditions answers other than "all five held". */
    @Test
    fun `the pulse runs in exactly one of the thirty two combinations of the five conditions`() {
        for (bits in 0 until 32) {
            val expected = bits == 31
            assertEquals(
                "overlay: combination $bits of the five conditions",
                expected,
                pulseShouldRun(
                    (bits and 1) != 0,
                    (bits and 2) != 0,
                    (bits and 4) != 0,
                    (bits and 8) != 0,
                    (bits and 16) != 0,
                ),
            )
        }
    }

    /** A failure means a pulse is wanted for a state other than armed, or for an armed tile that is not shown. */
    @Test
    fun `a pulse is wanted only for an armed tile that is shown`() {
        for (state in TileState.values()) {
            assertEquals("overlay: a shown $state tile wants a pulse only when it is armed", state == TileState.ARMED, pulseWanted(state, true))
            assertFalse("overlay: a hidden $state tile must not want a pulse", pulseWanted(state, false))
        }
    }

    /** A failure means a timing constant moved, so the cycle no longer matches the approved period and phases. */
    @Test
    fun `the pulse constants are pinned`() {
        assertEquals("overlay: one cycle is 1600 milliseconds", 1600L, PULSE_PERIOD_MS)
        assertTrue("overlay: the pulse starts at phase 0.5, the peak", PULSE_PHASE_START == 0.5f)
        assertTrue("overlay: the pulse ends its first run at phase 1.5", PULSE_PHASE_END == 1.5f)
        assertTrue("overlay: one run is one whole cycle", PULSE_PHASE_END - PULSE_PHASE_START == 1f)
    }
}
