package dev.breaker.dictation.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The held-bodies refusal path of [ProbeExecutor].
 *
 * The admission contract the pool promises is two: at most [MAX_WEDGED_PROBES] bodies may be in
 * flight, and once every slot is taken by a body that has not answered, the next submission must be
 * refused AT ONCE, by name ([ProbeExecutor.Refusal.NO_FREE_SLOT]), never run on the caller's thread. This test
 * parks two bodies inside themselves - they have thrown and are waiting to be released - so the pool's
 * counters sit at their cap, then proves a further body is refused instead of silently starting. It
 * also pins the promise that once both held bodies are released, the pool admits a later body again:
 * both holding latches come down, the counters return, and a later throwing body runs one more time.
 *
 * **Why the third submission must be checked by name.** [execute] flattens the refusal to a `false`,
 * which cannot say WHY. The internal [ProbeExecutor.executeReporting] overload keeps the cause: null
 * when admitted, the [ProbeExecutor.Refusal] otherwise. This test asserts that refusal is exactly
 * [ProbeExecutor.Refusal.NO_FREE_SLOT], by name, rather than inferring it from a later latch that never fires.
 *
 * **Why both releases land in a finally on every path.** A body parked on a holding latch holds its
 * slot and its thread for the life of the JVM; handed to the next class, every later probe is refused
 * for capacity this test used. The base class's @After would fail that class by name too, but do not
 * rely on it: release both paths here so a failed assertion never leaves a body wedged behind.
 *
 * **The counters are read by the module helper, not polled here.** The second half waits for the pool
 * to return via [awaitProbePoolIdle], a single bounded signal read of the two counts; no loop is spun in
 * this file. See each test's own note on why that wait matters.
 *
 * No real DNS and no sockets: every wait is a bounded latch await or the module helper's bounded counter read,
 * and every bound fails with a message rather than deciding an outcome.
 */
class ProbeExecutorRefusalHeldBodiesTest : ProbePoolIsolation() {

    // --- while two slots are held the third body is refused AT ONCE, by name -----
    @Test
    fun `a full pool refuses the next body at once and the counters come back on release`() {
        val cap = ProbeExecutor.MAX_WEDGED_PROBES

        // Park two bodies inside themselves: each throws its own exception, counts down its own ended
        // latch in a finally (the one statement guaranteed to run), then parks on its holding latch so
        // the submitted wrapper's finally has not yet given either counter back. Both are admitted - the
        // cap is [MAX_WEDGED_PROBES] - and sit at the pool's ceiling.
        val firstBodyEnd = CountDownLatch(1)
        val secondBodyEnd = CountDownLatch(1)
        val firstHold = CountDownLatch(1)
        val secondHold = CountDownLatch(1)

        ProbeExecutor.execute {
            try {
                throw IllegalStateException("held body 1 of $cap fails on purpose")
            } finally {
                firstBodyEnd.countDown()
                firstHold.await(RELEASE_WAIT_MS, TimeUnit.MILLISECONDS)
            }
        }
        ProbeExecutor.execute {
            try {
                throw IllegalStateException("held body 2 of $cap fails on purpose")
            } finally {
                secondBodyEnd.countDown()
                secondHold.await(RELEASE_WAIT_MS, TimeUnit.MILLISECONDS)
            }
        }

        // Both bodies have thrown and are now parked. Await both ended latches, bounded, failing by name.
        assertTrue(
            cardFailure(
                "the first held body must count down its own ended latch while still parked on its holding latch, " +
                    "so the wait here is bounded rather than a hang; a body that never reached its finally was never admitted or started, " +
                    "which would leave its counters unclaimed for the rest of the pool",
            ),
            firstBodyEnd.await(ENDED_WAIT_MS, TimeUnit.MILLISECONDS),
        )
        assertTrue(
            cardFailure(
                "the second held body must count down its own ended latch while still parked on its holding latch, so the wait here is " +
                    "bounded rather than a hang; a body that never reached its finally was never admitted or started, which would leave its counters unclaimed",
            ),
            secondBodyEnd.await(ENDED_WAIT_MS, TimeUnit.MILLISECONDS),
        )

        // Submit the third throwing body and CHECK ITS ANSWER by name. On this shape the pool is full at its
        // cap, so executeReporting must return Refusal.NO_FREE_SLOT; a null here means a full pool admitted
        // a body it should never have started.
        // The pool is full (cap reached above), so executeReporting refuses; the thrown body never runs and
        // therefore never counts down its ended latch - which is exactly why we assert the refusal below, not an await.
        val refusal = ProbeExecutor.executeReporting {
            throw IllegalStateException("third (refused) body fails on purpose")
        }
        assertEquals(
            cardFailure(
                "the third body under a full pool must be refused, and the refusal must be Refusal.NO_FREE_SLOT by name; a null here means " +
                    "a fully held pool admitted a body it should never have started",
            ),
            ProbeExecutor.Refusal.NO_FREE_SLOT,
            refusal,
        )

        try {
            // Release both holding latches so the parked bodies un-park and their wrappers release their slot
            // and thread.
            firstHold.countDown()
            secondHold.countDown()
        } finally {
            // On every path: a failed assertion above must not leave either body wedged for the rest of the JVM.
            firstHold.countDown()
            secondHold.countDown()
        }

        // The counters come back as the parked bodies finish. This single call is the signal read: the pool
        // offers no release callback, so awaitProbePoolIdle converts a leak into a named failure rather than
        // deciding an outcome on a clock; it completes the moment both counts return to zero.
        awaitProbePoolIdle(context = "two held bodies were released; both counters must come back to zero")

        // Now the pool is confirmed clean, submit one more throwing body and assert it ADMITS (null), its own
        // ended latch fires bounded, and the pool returns clean again: a later admission still runs.
        // The later admitted body's own ended latch and holding latch, declared once before first use.
        val laterEnd = CountDownLatch(1)
        val admitted = ProbeExecutor.executeReporting {
            try {
                throw IllegalStateException("later body fails on purpose")
            } finally {
                laterEnd.countDown()
            }
        }
        assertNull(
            cardFailure(
                "a body submitted once the pool is confirmed idle must be ADMITTED (executeReporting returns null); a refusal here means " +
                    "the earlier release never returned, so a later admission still running would not prove the counters reset",
            ),
            admitted,
        )
        assertTrue(
            cardFailure(
                "the later admitted body must count down its own ended latch while it runs, so the wait here is bounded rather than a hang; " +
                    "a refusal or a never-started body would fail the promise that admissions still run once the pool is idle",
            ),
            laterEnd.await(ENDED_WAIT_MS, TimeUnit.MILLISECONDS),
        )
        awaitProbePoolIdle(context = "one admitted later body ran and ended; the pool must return to clean")

    }


    private companion object {
        /** Bound on releasing a parked holding latch before the test proceeds. Generous, bounded. */
        const val RELEASE_WAIT_MS: Long = 5_000L

        /** Bound shared by every ended-latch await in these tests. Bounded so a failed wait fails loudly. */
        const val ENDED_WAIT_MS: Long = 5_000L
    }
}
