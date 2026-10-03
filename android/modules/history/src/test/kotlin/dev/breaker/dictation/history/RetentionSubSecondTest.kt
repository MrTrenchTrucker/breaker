package dev.breaker.dictation.history

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The policy at a `now` that has a sub-second part.
 *
 * Every other test in the module uses a whole-second `now`, so a cutoff cut to whole seconds would look
 * the same as the real one. Here `now` is 12:00:00.500, the cutoff is 12:00:00.500 ninety days earlier,
 * and the instants tried are at the cutoff and 1 ms and 100 ms either side of it.
 */
class RetentionSubSecondTest {

    private val policy = RetentionPolicy()

    private val now = Instant.parse("2026-06-30T12:00:00.500Z")

    /** Worked out by hand: noon on 1 April 2026 (the whole-second cutoff used elsewhere) plus the 500 ms. */
    private val expectedCutoff = Instant.parse("2026-04-01T12:00:00.500Z")

    private val cutoff: Instant = policy.cutoff(now)

    @Test
    fun `the cutoff keeps the sub-second part of now`() {
        assertEquals(expectedCutoff, policy.cutoff(now))
        assertEquals(1_775_044_800_500L, policy.cutoff(now).toEpochMilli())
    }

    @Test
    fun `the tombstone cutoff keeps the sub-second part of now`() {
        assertEquals(expectedCutoff, policy.tombstoneCutoff(now))
    }

    @Test
    fun `an instant at the cutoff is kept`() {
        assertFalse(policy.isExpired(expectedCutoff, now))
    }

    @Test
    fun `an instant one millisecond after the cutoff is kept`() {
        assertFalse(policy.isExpired(expectedCutoff.plusMillis(1), now))
    }

    @Test
    fun `an instant one millisecond before the cutoff is expired`() {
        assertTrue(policy.isExpired(expectedCutoff.minusMillis(1), now))
    }

    @Test
    fun `an instant 100 milliseconds before the cutoff is expired`() {
        assertTrue(policy.isExpired(expectedCutoff.minusMillis(100), now))
    }
}
