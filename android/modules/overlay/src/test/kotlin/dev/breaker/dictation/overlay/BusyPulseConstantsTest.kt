package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two busy-ring pulse constants, pinned to the approved values. The other busy tests compare these
 * constants with each other or with themselves, so a move of the period, or a move of both alpha floors
 * together, stays unseen there. These tests run on a plain JVM.
 */
class BusyPulseConstantsTest {

    /** A failure means the busy cycle moved, so the busy ring no longer pulses at the approved period. */
    @Test
    fun `the busy pulse period is pinned`() {
        assertEquals("overlay: one busy cycle is 3200 milliseconds", 3200L, BUSY_PULSE_PERIOD_MS)
    }

    /** A failure means the busy alpha floor moved, so the busy ring no longer fades to the approved depth. */
    @Test
    fun `the busy pulse alpha floor is pinned`() {
        assertTrue("overlay: the busy pulse alpha minimum is 0.6", BUSY_PULSE_ALPHA_MIN == 0.6f)
    }
}
