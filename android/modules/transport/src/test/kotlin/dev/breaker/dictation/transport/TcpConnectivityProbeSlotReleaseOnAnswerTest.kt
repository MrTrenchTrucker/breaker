package dev.breaker.dictation.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * A worker's SLOT must END WITH ITS ANSWER, exactly as its in-flight MARK does.
 *
 * The sibling G1 test
 * ([TcpConnectivityProbeMarkReleaseTest]) pins the MARK: the keyed
 * [ProbeExecutor.execute]'s wrapper `finally` runs only once the task body has
 * FULLY returned, and for a [java.util.concurrent.FutureTask] the caller's
 * waiter is released inside the callable, so a caller could hold the answer
 * while the host was still marked. That is why the mark's release lives in
 * [ProbeExecutor.answering].
 *
 * **The slot never got the same fix, and that is this test.** The SLOT is a
 * different piece of state from the mark, held by [ProbeExecutor.claim] and
 * given back by the UNKEYED [ProbeExecutor.execute]'s wrapper `finally` — the
 * one that waits for the task body to return. A lookup that has already
 * published its answer but has not yet returned from its body therefore still
 * counts against [ProbeExecutor.MAX_WEDGED_PROBES]. So the cap currently counts
 * LOOKUPS, when what the card promises it counts is lookups THAT HAVE NOT
 * ANSWERED YET.
 *
 * **What that costs, stated concretely.** Two distinct names are answered one
 * after another, both by workers that are then still inside their own task
 * bodies. Both slots are still held, the cap is full of workers that have
 * nothing left to find, and the next probe of a THIRD, perfectly healthy name
 * is refused: no lookup, no dial, and `isServerReachable()` answers
 * "not reachable" with no evidence any connection was ever attempted —
 * indistinguishable, to every caller, from a server that is down. Nothing is
 * wedged; nothing is stuck; the process simply refuses to dial because it is
 * counting answered workers as though they were still looking.
 *
 * **Why the refusal here can only be the CAP and not the mark.** The keyed
 * [ProbeExecutor.execute] marks the name first, on an absent-to-present
 * transition, and only then calls the unkeyed [ProbeExecutor.execute], whose
 * only gate is [ProbeExecutor.claim]: `occupied >= MAX_WEDGED_PROBES`. The
 * third name is one nothing has ever keyed, so its mark is inserted with no
 * contention; the two parked workers' marks were already removed by their own
 * [ProbeExecutor.answering] calls (that is G1, and their answers prove it ran).
 * Both `occupied` entries belong to the two bodies parked below. The mark is
 * free and the cap is full, which is the whole defect in one sentence.
 *
 * **Why this is deterministic and needs no load.** Each worker's state is
 * pinned by a [CountDownLatch] that ONLY this test's `finally` counts down, not
 * by the scheduler:
 *
 *  - each body runs its [FutureTask] — publishing the answer through
 *    [ProbeExecutor.answering] — and only THEN parks, so the answer is
 *    published strictly before the park;
 *  - the answer is observed with [FutureTask.get], a bounded await on an EVENT
 *    (the callable finished), never on a duration;
 *  - once [FutureTask.get] has returned for a body, that body has NOT returned
 *    from its task — it is at or heading for the park, and the park is the
 *    thing only the `finally` releases. So `occupied` is still 2 at the moment
 *    the third probe runs, on every interleaving, on an idle box or a loaded
 *    one. Nothing here can pass on a slow machine and fail on a fast one.
 *  - the third probe is a real [TcpConnectivityProbe] with a
 *    [RecordingConnector], so "admitted AND dialled" is two separate
 *    observations: the boolean, and the recorded dial. A refusal cannot borrow
 *    a dial from anywhere else, because this probe is the only thing that
 *    touches this connector.
 *
 * **The park is deliberately AFTER the answer, never inside
 * [ProbeExecutor.answering].** A hold inside `answering` would still be
 * holding the mark, and the test would then be asserting the correct refusal
 * for a genuinely un-answered lookup — which says nothing about where a slot
 * ends once the answer exists.
 *
 * **The pool is sized one thread above the cap**
 * ([ProbeExecutor.CAP_HEADROOM_THREADS]), so a third task that [claim] admitted
 * would be guaranteed a worker. The refusal below is therefore attributable to
 * this module's own accounting and not to the JDK's saturation path.
 *
 * **This parks a process-wide singleton on a pool of
 * [ProbeExecutor.MAX_WEDGED_PROBES] threads, so a leaked worker would DEADLOCK
 * every later test in this JVM rather than merely fail one.** Both latches are
 * counted down in a `finally`, always; every wait is bounded; and the shared
 * pool is then drained with a FRESH probe on a FRESH clock and a host nothing
 * has cached, so a `true` from the drain can only have come from a dial that
 * really ran on a worker this test let go of.
 *
 * No real DNS and no real sockets: every wait is a bounded latch await or a
 * bounded poll; nothing sleeps and nothing spins.
 */
class TcpConnectivityProbeSlotReleaseOnAnswerTest : ProbePoolIsolation() {

    @Test
    fun `a slot is released when the answer is published, not when the body returns`() {
        val firstHost = "answered-alpha.invalid"
        val secondHost = "answered-beta.invalid"
        val thirdHost = "still-dialable.invalid"
        val firstHold = CountDownLatch(1)
        val secondHold = CountDownLatch(1)

        // The value-producing task, in the G1 shape: `answering` is what
        // publishes the answer and releases the mark, and its `finally` has
        // finished by the time `get` returns.
        val answerAlpha = FutureTask<Any?> { ProbeExecutor.answering(firstHost) { true } }
        val answerBeta = FutureTask<Any?> { ProbeExecutor.answering(secondHost) { true } }

        val connector = RecordingConnector(script = listOf(ProbeOutcome.CONNECTED))
        var thirdProbeAnswered = false
        try {
            // Two DIFFERENT keys, so neither is refused by the other: this is
            // not single-flight refusing a repeat, it is the cap refusing a
            // third name. A single worker would not do — the cap is 2, and a
            // test that fills only one slot cannot tell "the cap counted a
            // finished worker" from "the cap was never reached".
            assertTrue(
                cardFailure("the first worker must be admitted and start its task: a refusal here means the pool was already saturated by something this JVM did not clean up, and the cap arithmetic below would be measuring the wrong occupancy"),
                ProbeExecutor.execute(firstHost, Runnable {
                    // Publish first, park second. Parking before the publish
                    // would hold an UN-ANSWERED lookup, where the cap is right
                    // to count it and the assertion below would be testing the
                    // correct behaviour instead of the defect.
                    answerAlpha.run()
                    firstHold.await(PUBLISH_AWAIT_MS, TimeUnit.MILLISECONDS)
                }),
            )
            assertTrue(
                cardFailure("the second worker must be admitted too, on a DIFFERENT key: the cap is ${ProbeExecutor.MAX_WEDGED_PROBES} and this test is only meaningful if it fills every one of those slots with a worker that has already answered - one answered worker leaves a slot free and the third probe below would be admitted for the right reason"),
                ProbeExecutor.execute(secondHost, Runnable {
                    answerBeta.run()
                    secondHold.await(PUBLISH_AWAIT_MS, TimeUnit.MILLISECONDS)
                }),
            )

            // Both answers are now OBSERVABLE, and both bodies are still
            // inside their own tasks. `get` returning proves the callable
            // finished; the park proves the body did not.
            assertEquals(
                cardFailure("the first worker must have PUBLISHED an answer: the body ran its task and then parked, so a value here can only exist if the callable finished"),
                true,
                answerAlpha.get(PUBLISH_AWAIT_MS, TimeUnit.MILLISECONDS),
            )
            assertEquals(
                cardFailure("the second worker must have PUBLISHED an answer, for the same reason as the first: the whole claim under test is that a worker which has answered holds nothing worth counting"),
                true,
                answerBeta.get(PUBLISH_AWAIT_MS, TimeUnit.MILLISECONDS),
            )

            // A third name, one nothing has ever keyed and nothing has cached.
            // Its mark is inserted with no contention, so the only gate it can
            // meet is the cap - and both of the cap's slots are held by
            // workers that answered above and are parked only by this test.
            val third = TcpConnectivityProbe(
                { "https://$thirdHost" },
                FakeClock(1_000L),
                FakeHostResolver(),
                connector,
            )
            thirdProbeAnswered = third.isServerReachable()
        } finally {
            firstHold.countDown()
            secondHold.countDown()
            drainProbeExecutor()
        }

        assertTrue(
            cardFailure(
                "a worker's slot must be released when it PUBLISHES its answer, not when its task body returns. $firstHost and $secondHost both answered (both callables finished, above) and both workers are parked on latches only this test's finally can release, so every slot of the ${ProbeExecutor.MAX_WEDGED_PROBES} this process allows itself is held by a worker with nothing left to find - and $thirdHost, a name that has never been looked up and has never been dialled, was refused for want of one. The caller above was told \"not reachable\" about a server nobody had tried to reach, and with the recording below there is no dial behind that answer: a healthy server reported down, which is exactly the failure the whole bounded pool exists to prevent, arrived at not because anything is wedged but because the cap counts ANSWERED workers. A worker is still inside its task body here, so the count is being taken from the task's bookkeeping rather than from the answer - the same mistake the mark was fixed for, one layer down",
            ),
            thirdProbeAnswered,
        )
        assertEquals(
            cardFailure(
                "\"admitted\" is not enough on its own: the third probe must have actually DIALLED $thirdHost. It made ${connector.callCount} dials, to ${connector.hosts} - a refusal costs no connection attempt at all, and a caller cannot tell that refusal from a server that is down, which is the whole damage here",
            ),
            1,
            connector.callCount,
        )
        assertEquals(
            cardFailure("the one dial that did happen must have gone to the configured third host and nowhere else, so the answer above is about $thirdHost and not about some name reached on the way"),
            listOf(thirdHost),
            connector.hosts,
        )
    }

    /**
     * Waits, bounded, for the shared probe pool to come back.
     *
     * [ProbeExecutor] is a process-wide singleton on a pool of
     * [ProbeExecutor.MAX_WEDGED_PROBES] plus one headroom thread, so a worker
     * still parked past this test's `finally` turns every LATER test in this
     * JVM into a discarded task and a false "not reachable". Each drain attempt
     * costs at most one budget while the pool is still busy, so the bound is
     * generous for the several attempts it can take and still fails rather than
     * hanging when a worker never comes back. The drain runs a FRESH probe on a
     * FRESH clock against a host nothing has cached, so a `true` from it can
     * only have come from a dial that really ran on a shared worker.
     */
    private fun drainProbeExecutor() {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DRAIN_BOUND_MS)
        var attempts = 0
        var dialled = false
        while (System.nanoTime() < deadline) {
            attempts++
            val drain = TcpConnectivityProbe(
                { "https://drain-$attempts.invalid" },
                FakeClock(1_000L),
                FakeHostResolver(),
                RecordingConnector(script = listOf(ProbeOutcome.CONNECTED)),
            )
            if (drain.isServerReachable()) {
                dialled = true
                break
            }
        }
        if (!dialled) {
            throw AssertionError(
                cardFailure("the shared probe worker was still busy ${DRAIN_BOUND_MS} ms after this test released its latches, over $attempts attempts - the one pool in [ProbeExecutor] serves every probe in this JVM, so a worker left parked past this test answers false for everything that follows it"),
            )
        }
        // The dial above answered true, which means a worker really ran it; now
        // wait for that worker's slot and thread to be handed back so the pool
        // is idle - not merely dial-able - before this class hands it on.
        // (Same shape as [TcpConnectivityProbeWedgedLookupTest]'s drain: a
        // first green dial alone does not prove the pool is idle, and a cold
        // build is exactly where the hand-off is slowest.)
        awaitProbePoolIdle(
            context = "after this test's drain dial, before the next class reads the shared pool",
        )
    }

    private companion object {
        /**
         * Ceiling on one bounded wait: the park inside a task body and the wait
         * for its published answer. Generous enough that a slow CI box cannot
         * turn a correct implementation red, and short enough that a broken one
         * fails instead of hanging the JVM.
         */
        const val PUBLISH_AWAIT_MS = 10_000L

        /** Ceiling on the drain, generous because each attempt can cost one budget. */
        const val DRAIN_BOUND_MS = 10_000L
    }
}
