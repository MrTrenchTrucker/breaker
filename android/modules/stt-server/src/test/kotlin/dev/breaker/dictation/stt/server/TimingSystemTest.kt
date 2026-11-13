package dev.breaker.dictation.stt.server

import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The real clock of Timing.SYSTEM moves forward. The test waits on no timer: it reads the clock again
 * until the value changes, which a clock that stands still never does.
 */
class TimingSystemTest {
    @Test
    fun `the real monotonic clock moves forward`() {
        val clock = Timing.SYSTEM.clock
        val before = clock.nowNanos()
        var after = before
        var reads = 0
        while (after == before && reads < 10_000_000) {
            after = clock.nowNanos()
            reads++
        }
        assertTrue("the clock did not move in $reads reads: $before then $after", after > before)
    }
}
