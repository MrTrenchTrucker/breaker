package dev.breaker.dictation.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * What [ProbeExecutor.MAX_WEDGED_PROBES] is actually WORTH.
 *
 * The cap is the promise that bounds the damage when every lookup hangs forever -
 * "however many hang, this process creates at most two daemon threads, never an
 * unbounded pile" (see [ProbeExecutor]). A test that only ever saturates the pool
 * does not pin that number: a cap of 1 and a cap of 100 both answer "something
 * returned false" once the pool is full. So this test claims the slots DIRECTLY
 * through [ProbeExecutor.execute] instead of through a probe, one task at a time,
 * and checks the arithmetic the cap exists to guarantee: exactly the cap is
 * accepted, every accepted task really starts and is still in flight, and the one
 * past it is refused without ever running.
 *
 * **The constant is read from the production code, never hardcoded**, so this test
 * tracks [ProbeExecutor.MAX_WEDGED_PROBES] instead of pinning a second,
 * quietly-drifting copy of 2.
 *
 * **Why the slots are claimed DIRECTLY.** Through a probe, an overflow refusal
 * and the refusals the other saturation tests observe are the same event: a host
 * whose lookup wedges, one whose connect is refused by the fake connector, and a
 * pool that is simply full all arrive as `isServerReachable() == false`. Holding
 * the slots with tasks that block on their own latches removes the probe from the
 * picture entirely, so the only thing the assertion below can be about is the
 * pool's own accounting.
 *
 * **And the honest limit of this test, stated here because a reader will ask.**
 * `execute` answers ONE boolean for TWO refusals: the `claim()` guard at
 * [ProbeExecutor.MAX_WEDGED_PROBES], and the pool's `RejectedExecutionException`
 * backstop. With `maximumPoolSize` set to that same constant, the two refusals
 * coincide - at the moment `occupied` reaches the cap, all the workers the pool
 * can ever grow are busy - so the overflow call answers `false` either way and
 * the task never runs either way. There is therefore NO boolean this test can
 * assert that separates "claim() declined at the cap" from "the pool's handler
 * refused it", and a mutant that deletes the `claim()` guard while leaving the
 * backstop in place survives it. What that costs in coverage is exactly the
 * backstop's presence: the guard is currently redundant with the pool's ceiling,
 * so the mutant is behaviour-preserving through this seam. Pinning the guard
 * rather than the resulting number needs a production change, not a test - see the
 * note on the assertion below for the two shapes that would do it. What this test
 * does pin is the number itself, which is the part a reader of the card is
 * promised.
 *
 * **This wedges a process-wide singleton, so it cleans up in a `finally`:** every
 * latch it made is released and then it waits, bounded, until a known-good probe
 * answers true. A worker still parked on a latch that only this test's exit
 * releases turns every LATER test in this JVM into a discarded task and a false
 * "not reachable", and the suite goes flaky in whichever order JUnit picks. The
 * drain uses a FRESH probe with a FRESH clock on a host nothing has cached, so a
 * `true` can only have come from a dial that really ran on the shared worker.
 *
 * No real DNS and no real sockets: every wait is a bounded latch await or a
 * bounded poll; nothing sleeps.
 */
class TcpConnectivityProbeCapTest : ProbePoolIsolation() {

    // --- the cap is the number, not just "there is a number" --------------------

    @Test
    fun `the pool holds exactly the cap in flight and refuses the one past it`() {
        val cap = ProbeExecutor.MAX_WEDGED_PROBES
        val gates = List(cap) { CountDownLatch(1) }
        val starts = CountDownLatch(cap)
        val overflowRan = AtomicInteger(0)
        var accepted = 0
        var overflowAccepted = true
        try {
            // One task per slot, each blocking on its OWN latch so none of them
            // can finish and hand a slot back while the cap is being measured. A
            // task that returned immediately would let the count fall between
            // submissions and the number below would be measuring nothing.
            for (slot in 0 until cap) {
                val gate = gates[slot]
                val took = ProbeExecutor.execute {
                    starts.countDown()
                    gate.await(SLOT_AWAIT_MS, TimeUnit.MILLISECONDS)
                }
                if (!took) break
                accepted++
            }

            assertEquals(
                cardFailure("a pool whose cap is $cap must accept $cap tasks that are all still in flight; it accepted $accepted, so the ceiling this test is measuring is not ProbeExecutor.MAX_WEDGED_PROBES"),
                cap,
                accepted,
            )
            assertTrue(
                cardFailure("all $cap accepted tasks must actually have started before the overflow is submitted, otherwise a slot was still free when the cap was measured and the refusal below came from nothing being occupied. Started: ${cap - starts.count}L of $cap"),
                starts.await(START_BOUND_MS, TimeUnit.MILLISECONDS),
            )

            overflowAccepted = ProbeExecutor.execute { overflowRan.incrementAndGet() }
        } finally {
            gates.forEach { it.countDown() }
            drainProbeExecutor()
        }

        assertEquals(
            cardFailure("the task past the cap was refused, so its body must never run; it ran $overflowRan times. Every task handed to the pool and not yet finished is counted, and the count can never exceed the cap, so there is nowhere for one more to go"),
            0,
            overflowRan.get(),
        )
        assertFalse(
            cardFailure(
                "with all $cap slots held by tasks that have not finished, ProbeExecutor.execute must report that there is nowhere to put another task - " +
                    "not throw, and not run it on this thread, which is the caller the pool exists to keep inside its own budget. " +
                    "NOTE ON WHAT THIS DOES AND DOES NOT PIN: this false cannot on its own tell claim() declining at MAX_WEDGED_PROBES from the pool's " +
                    "RejectedExecutionException backstop - with maximumPoolSize equal to the cap, both refusals land at the same occupancy and the overflow task " +
                    "never runs under either, so a mutant that removes the claim() guard alone still answers this false. That is why the number is pinned here and " +
                    "the guard is not: to pin the guard, production has to separate the two refusals, either by giving the pool a maximumPoolSize ABOVE the cap so " +
                    "claim() is the only thing that can refuse, or by returning a refusal a caller can tell apart from a rejection",
            ),
            overflowAccepted,
        )
    }

    /**
     * Waits, bounded, for the shared probe pool to come back.
     *
     * [ProbeExecutor] is a process-wide singleton, so a worker still parked on a
     * latch this test released turns every LATER test in this JVM into a discarded
     * task and a false "not reachable". Failing loudly here beats letting the next
     * test fail for a reason that has nothing to do with what it is testing. Each
     * attempt costs at most one budget while the pool is still busy, so the bound
     * is generous for the several attempts it can take and still fails rather than
     * hanging when a worker never comes back.
     */
    private fun drainProbeExecutor() {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DRAIN_BOUND_MS)
        var attempts = 0
        while (System.nanoTime() < deadline) {
            attempts++
            val drain = TcpConnectivityProbe(
                { "https://drain-$attempts.invalid" },
                FakeClock(1_000L),
                FakeHostResolver(),
                RecordingConnector(script = listOf(ProbeOutcome.CONNECTED)),
            )
            if (drain.isServerReachable()) return
        }
        throw AssertionError(
            cardFailure("the shared probe worker was still busy ${DRAIN_BOUND_MS} ms after this test released its latches, over $attempts attempts - the one pool in [ProbeExecutor] serves every probe in this JVM, so a worker left parked past this test answers false for everything that follows it"),
        )
    }

    private companion object {
        /**
         * Ceiling on one occupying task's own await. It bounds the damage a
         * forgotten `finally` does: a task left blocked forever would park a
         * worker for the life of the JVM rather than until this test fails.
         */
        const val SLOT_AWAIT_MS = 30_000L

        /** Ceiling on waiting for every accepted task to reach its own body. */
        const val START_BOUND_MS = 10_000L

        /** Ceiling on the drain, generous because each attempt can cost one budget. */
        const val DRAIN_BOUND_MS = 10_000L
    }
}
