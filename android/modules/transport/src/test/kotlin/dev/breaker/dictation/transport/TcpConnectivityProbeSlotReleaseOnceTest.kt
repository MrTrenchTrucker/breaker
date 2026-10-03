package dev.breaker.dictation.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/**
 * That ONE admission gives its SLOT back EXACTLY ONCE, though two paths are both
 * entitled to release it.
 *
 * [ProbeExecutor] releases a slot from two places by design:
 * [ProbeExecutor.answering]'s `finally`, at PUBLISH, and the submitted
 * wrapper's `finally`, the backstop for a body that never answers.
 * [ProbeExecutor.SlotRelease] is a compare-and-set precisely so that the second
 * of those is a no-op rather than a second decrement.
 *
 * **Why no existing test observes it.** The shape this needs is a body that
 * PUBLISHES and then ENDS, and nothing else in the module produces one:
 * [TcpConnectivityProbeSlotReleaseOnAnswerTest] asserts while its workers are
 * still PARKED inside their own task bodies, so the wrapper's `finally` has not
 * run; [TcpConnectivityProbeCapTest]'s tasks never call
 * [ProbeExecutor.answering] at all; and
 * [TcpConnectivityProbeThreadSaturationTest] parks ANSWERED bodies but never lets
 * one finish. A leak of this kind is per-JVM-lifetime and order-dependent, so no
 * existing test can be turned into an observer of it alone.
 * **Why a double release is not a small problem.** One admission decrementing
 * `occupied` twice drives that count BELOW ZERO within a couple of cycles, and
 * after that `occupied >= ProbeExecutor.MAX_WEDGED_PROBES` is satisfied by a
 * negative number - so the cap silently rises above the promise it documents,
 * permanently and with nothing reporting it, and a blackholed resolver can park
 * an UNBOUNDED number of lookups in a pool sized for
 * [ProbeExecutor.MAX_WEDGED_PROBES].
 *
 * **How the count is driven negative without depending on timing.** The cycles
 * run FIRST and the cap is read LAST, and between them sit two gates that make
 * the ordering a fact rather than a hope:
 *
 *  - each cycle publishes its answer and is then RETURNED, so its wrapper's
 *    `finally` really does offer that slot back a second time. Nothing parks in
 *    a cycle: a parked body would suppress the release under test;
 *  - [POOL_MAX_THREADS] answered-then-parked witnesses are then admitted, each
 *    awaited through its own published answer. The LAST is admissible only if
 *    `bodies` was below [POOL_MAX_THREADS] then, and the only bodies that can
 *    hold a thread are the witnesses already parked - so on every interleaving
 *    every cycle body has ENDED and released before the cap is read. That gate
 *    is what makes this deterministic rather than load-dependent, and why every
 *    admission below is a bounded RETRY: a cycle body can still be unwinding
 *    inside the wrapper's `finally` when the next submit arrives.
 *
 * **The observer.** With every counter back where it belongs, two bodies are
 * parked INSIDE [ProbeExecutor.answering]'s compute - admitted, not answered -
 * so each holds its SLOT, and those two fill the cap. A third never-looked-up,
 * never-dialled host must then be REFUSED with no lookup and no dial. Had each
 * finished admission double-decremented, `occupied` would be `2 - CYCLES` rather
 * than 2, the guard would not trip, the third probe would be ADMITTED, and the
 * assertions below fail.
 *
 * **The two wedges are raw [ProbeExecutor] bodies, NOT wedged probes, and that
 * is forced rather than chosen.** A lookup driven through [TcpConnectivityProbe]
 * cannot be held this long: the caller answers its own
 * [TcpConnectivityProbe.CONNECT_TIMEOUT_MS] budget by cancelling the
 * [FutureTask], the wrapper unwinds and the slot comes back - correctly, and
 * inside 1.5 s - so the wedge path carries no budgeted probe at all; see
 * [WedgeBody]. What the two hold is therefore ARITHMETIC, not a liveness
 * guess: 2 slots and 2 threads, so the third can only be refused
 * [ProbeExecutor.Refusal.NO_FREE_SLOT]. The third host is DISTINCT from both
 * wedges, so no in-flight mark is in its way.
 *
 * **No real DNS and no real sockets; nothing blocks longer than its bound.**
 * Every wait is a bounded latch await or a bounded park between
 * admission retries or drain retries; every latch is counted down in the
 * `finally`; and the process-wide pool is drained afterwards, because
 * [ProbeExecutor] is a singleton and a worker left parked here would answer
 * false for every later test in this JVM.
 */
class TcpConnectivityProbeSlotReleaseOnceTest {

    @Test
    fun `a slot is returned exactly once by a body that answered and then finished`() {
        val cycles = List(CYCLES) { index -> AnsweringThenEndingBody("cycle-$index.invalid") }
        val witnesses = List(POOL_MAX_THREADS) { index -> ParkedAnsweredBody("witness-$index.invalid") }
        val wedges = listOf(WedgeBody(FIRST_WEDGED_HOST), WedgeBody(SECOND_WEDGED_HOST))
        val thirdConnector = RecordingConnector(script = listOf(ProbeOutcome.CONNECTED))

        var thirdAnswer = true
        var thirdElapsedMs = -1L
        var namedRefusal: ProbeExecutor.Refusal? = null
        // Sampled while the wedges still hold: the `finally` below releases them.
        var wedgesStillParked: Boolean = false
        try {
            // The cycles. Each publishes AND is then returned, so the wrapper's
            // finally runs and this admission's slot is offered back a second
            // time.
            for ((index, cycle) in cycles.withIndex()) {
                assertTrue(
                    cardFailure("the ${index + 1}th cycle body must be admitted and started: a refusal that outlived its retry bound means a slot or a thread was still held by something this JVM did not clean up, and the count read below would be measuring that instead of $CYCLES completed cycles"),
                    admitWithin(ADMIT_BOUND_MS) { cycle.admit() },
                )
                assertEquals(
                    cardFailure("cycle body ${index + 1} of $CYCLES must have PUBLISHED an answer: it ran its FutureTask and was then returned, so a value here can only exist if [ProbeExecutor.answering]'s finally - the FIRST of the two releases - has already run"),
                    true,
                    cycle.awaitPublishedAnswer(),
                )
            }

            // The gate. Admitting the last of these witnesses is what proves
            // every cycle body above had ENDED and released: only the witnesses
            // already parked can be holding a thread at that point.
            for ((index, witness) in witnesses.withIndex()) {
                assertTrue(
                    cardFailure("witness ${index + 1} of $POOL_MAX_THREADS must be admitted. Only $index witnesses can be ahead of it, so its admission is what proves every cycle body above had ENDED and released before the cap is read; a refusal that outlived its retry bound means a cycle body was still holding a thread, and the releases this test counts had not all happened yet"),
                    admitWithin(ADMIT_BOUND_MS) { witness.admit() },
                )
                assertEquals(
                    cardFailure("witness ${index + 1} must have published its answer before it parked, so it holds a thread and NO slot - which is what keeps these admissions from being refused by the very cap whose arithmetic the cycles just disturbed"),
                    true,
                    witness.awaitPublishedAnswer(),
                )
            }
            witnesses.forEach { it.release() }

            // The two SLOT-holding bodies, admitted LAST and as raw bodies: there is NO
            // budgeted probe on this path. Each is a keyed
            // [ProbeExecutor.executeReporting] submit whose body blocks on a latch
            // only this file's `finally` opens, so it can neither answer nor END
            // until then. Nothing runs between an admission and the cap read below
            // except the admissions themselves and the capture.
            for ((index, wedge) in wedges.withIndex()) {
                assertTrue(
                    cardFailure("wedged body ${index + 1} of ${wedges.size} must be ADMITTED before the cap is read - a keyed submit returns true only when [ProbeExecutor.claim] granted both the slot and the thread, so a false here means the cap was already full before the wedges were in place and the refusals below would be measuring that instead of the ${ProbeExecutor.MAX_WEDGED_PROBES} answered-and-ended cycles above. A refusal that outlived its retry bound means a cycle or witness body was still holding a slot or a thread this JVM did not clean up"),
                    admitWithin(ADMIT_BOUND_MS) { wedge.admit() },
                )
            }

            // The capture. Taken with the minimum work in between: the admissions above
            // are the last thing that can change it, because the only thing that
            // ends a wedge is the `finally` that opens its latch - which has not
            // run - and the third probe is not even built. It reads the ADMISSION,
            // not a liveness guess: the slot is taken inside
            // [ProbeExecutor.claim], synchronously, so an admitted body holds it
            // whether or not its worker thread has reached the latch yet.
            wedgesStillParked = wedges.all { it.admitted }

            // The cap, read at last. **WHICH cap is the whole question here.** The two
            // wedges hold 2 of [ProbeExecutor.MAX_WEDGED_PROBES] SLOTS and 2 of
            // [POOL_MAX_THREADS] THREADS - not the same number, the thread ceiling
            // being the cap plus one headroom thread - so one thread is still FREE
            // and a refusal can ONLY be [Refusal.NO_FREE_SLOT]. Were it
            // [Refusal.NO_FREE_THREAD] this test would have passed for the wrong
            // reason, blaming a loosened cap on a pool out of workers. The host is
            // DISTINCT from both wedges, so no mark is in its way, and the two
            // faces are asked in the order below so the naming face cannot be what
            // caused the real probe's refusal.
            val third = TcpConnectivityProbe(
                { "https://$THIRD_HOST" },
                FakeClock(1_000L),
                FakeHostResolver(),
                thirdConnector,
            )
            val startedAt = System.nanoTime()
            thirdAnswer = third.isServerReachable()
            thirdElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

            // The same refusal at the face that NAMES it, asked AFTER the real
            // probe on purpose. An empty task that is admitted runs and ends at
            // once, so asking it first would put a transient third slot in
            // flight and could make the third probe refused for the wrong reason -
            // a false PASS for the very defect under test. Its host is suffixed so
            // it is a THIRD distinct name: reusing either wedge's would answer
            // [Refusal.HOST_IN_FLIGHT] and name the wrong cause. The cause is
            // ASSERTED against the exact constant below, never inferred from the
            // boolean face: a boolean `false` is what a refusal and a dead server
            // both look like.
            namedRefusal = ProbeExecutor.executeReporting("$THIRD_HOST-executor", Runnable { })
        } finally {
            wedges.forEach { it.release() }
            witnesses.forEach { it.release() }
            drainProbeExecutor()
        }

        assertTrue(
            cardFailure("both wedged bodies must still be HOLDING their slots when the cap is read, or their slots came back and the refusals below are measuring nothing"),
            wedgesStillParked,
        )
        assertFalse(
            cardFailure("$THIRD_HOST has never been looked up and its dial is scripted CONNECTED, so only a real dial can make this probe answer reachable - the boolean and the recorded dials are two independent observations, and only this probe touches this connector. It answered reachable with ${thirdConnector.callCount} dials to ${thirdConnector.hosts}: a cap that has been silently loosened lets a THIRD lookup start while two others are still wedged, which is the unbounded pile of parked lookups the cap exists to prevent, and the caller has just been handed a positive answer derived from work it never asked for"),
            thirdAnswer,
        )
        assertEquals(
            cardFailure("a probe refused at the cap must not have dialled anything: ${thirdConnector.callCount} dials were recorded, to ${thirdConnector.hosts}. A refusal costs no connection attempt at all, and a caller cannot tell that refusal from a server that is down - which is the whole damage a falsely-loosened cap creates"),
            0,
            thirdConnector.callCount,
        )
        assertSame(
            cardFailure("one admission releases its slot ONCE, however many paths are entitled to release it. $CYCLES bodies each published an answer and then ENDED, so [ProbeExecutor.answering]'s release and the submitted wrapper's backstop both ran for each, and every one of those slots must be back - a body that has finished is holding nothing worth counting. The cap is ${ProbeExecutor.MAX_WEDGED_PROBES} and $FIRST_WEDGED_HOST plus $SECOND_WEDGED_HOST are two un-answered bodies still inside it, so the very next distinct probe must be refused - and refused by the SLOT, which is the exact constant asserted here: those two hold 2 slots of ${ProbeExecutor.MAX_WEDGED_PROBES} but only 2 of the $POOL_MAX_THREADS threads [ProbeExecutor] allows, so a thread is still FREE and ${ProbeExecutor.Refusal.NO_FREE_THREAD} could not be the answer. It came back ${namedRefusal?.name}, with the reason \"${namedRefusal?.reason()}\". A refusal that did not come back means `occupied` no longer counts what this file says it counts: the second release decremented it a second time, drove it below zero, and the cap now reads as anything but the bound it promises - so one blackholed resolver can park an unbounded number of lookups in a pool sized for ${ProbeExecutor.MAX_WEDGED_PROBES}, and nothing reports it"),
            ProbeExecutor.Refusal.NO_FREE_SLOT,
            namedRefusal,
        )
        assertTrue(
            cardFailure("a task with nowhere to go is refused AT ONCE: this probe was held ${thirdElapsedMs} ms against a ${TcpConnectivityProbe.CONNECT_TIMEOUT_MS} ms budget, so a caller was held rather than answered"),
            thirdElapsedMs < RETURN_BOUND_MS,
        )
    }

    /** Retries an admission until it is accepted or [boundMs] is spent.
     *
     * A refused submit leaves NOTHING pinned - no slot, no thread, no mark - so
     * retrying is free of consequence and the loop converges as soon as the thing
     * that was holding a body finishes. That is the one transient this test cannot
     * assert away: a body whose answer has been published is still inside the
     * wrapper's `finally` for a moment, and a single submit can land in that
     * window. Bounded, so a body that never ends fails here instead of hanging the
     * JVM. The retry PARKS for [ADMIT_RETRY_PARK_MS] between attempts rather than
     * re-submitting in a tight loop: a tight loop burns a core flat for the whole
     * bound in a JVM shared with other suites, and a waiter that starves the pool
     * it is waiting on is how a wait becomes the scheduling flake it was written
     * to prevent. First attempt immediate.
     */
    private fun admitWithin(boundMs: Long, admit: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(boundMs)
        if (admit()) return true
        while (true) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(ADMIT_RETRY_PARK_MS))
            if (admit()) return true
            if (System.nanoTime() >= deadline) return false
        }
    }

    /** One body that publishes an answer and then ENDS - the cycle this test needs
     * and no other shape here provides.
     *
     * The whole difference from [ParkedAnsweredBody] is the missing park. The
     * body runs its [FutureTask], whose callable is [ProbeExecutor.answering] (so
     * the SLOT comes back at publish), then simply returns, so the submitted
     * wrapper's `finally` runs and offers that same slot back a SECOND time.
     * [awaitPublishedAnswer] observes the first release; the second has no
     * in-band event, which is why the test gates on the witnesses.
     */
    private class AnsweringThenEndingBody(private val host: String) {

        private val answer: FutureTask<Any?> = FutureTask<Any?> { ProbeExecutor.answering(host) { true } }

        /** Admits the body, which publishes its answer and then returns. */
        fun admit(): Boolean = ProbeExecutor.execute(host, Runnable { answer.run() })

        /** The published answer, awaited as an EVENT: it proves the callable finished. */
        fun awaitPublishedAnswer(): Any? = answer.get(PUBLISH_AWAIT_MS, TimeUnit.MILLISECONDS)
    }

    /** One body that publishes an answer and then parks, holding its thread and
     * nothing else - the gate that proves the cycle bodies have ended.
     *
     * The two halves are separate on purpose: [answer] releases the SLOT at
     * publish, and only the park afterwards holds the THREAD. The park is on a
     * latch only this test's `finally` (or [release]) can open, and both waits
     * are bounded, so a body whose latch is never opened ends by itself rather
     * than poisoning every later test in this JVM.
     */
    private class ParkedAnsweredBody(private val host: String) {

        private val answer: FutureTask<Any?> = FutureTask<Any?> { ProbeExecutor.answering(host) { true } }

        private val hold = CountDownLatch(1)

        fun admit(): Boolean = ProbeExecutor.execute(host, Runnable {
            answer.run()
            hold.await(PUBLISH_AWAIT_MS, TimeUnit.MILLISECONDS)
        })

        fun awaitPublishedAnswer(): Any? = answer.get(PUBLISH_AWAIT_MS, TimeUnit.MILLISECONDS)

        fun release() {
            hold.countDown()
        }
    }

    /** One raw task body that HOLDS ITS SLOT for as long as this test says so.
     *
     * **Why it is not a wedged probe.** A lookup driven through
     * [TcpConnectivityProbe] cannot be held for the length this test needs: the
     * caller answers its own [TcpConnectivityProbe.CONNECT_TIMEOUT_MS] budget by
     * cancelling its [FutureTask], the wrapper unwinds, and the slot comes back -
     * correctly, and inside 1.5 s. So the wedge path carries NO budgeted probe,
     * and this body is what replaces one.
     *
     * **What it is doing instead.** It blocks on a test-owned latch BEFORE it
     * calls [ProbeExecutor.answering], so for the whole window this test cares
     * about it has admitted and not answered: the SLOT is held (given back only
     * in `answering`'s `finally`, which has not run) and the THREAD is held
     * (given back only in the wrapper's `finally`, which has not run either).
     * That is the state a genuinely unresolved lookup occupies, reached without
     * the budget.
     *
     * **The keyed submit is deliberate.** It puts [host]'s in-flight mark up too,
     * as a real lookup would, and it names the refusal - so [admit] records
     * WHICH cap stopped it rather than folding a `false` into a boolean.
     */
    private class WedgeBody(private val host: String) {

        /** Only this file's `finally` opens it; see the class KDoc. */
        private val hold = CountDownLatch(1)

        /** True once [ProbeExecutor.claim] GRANTED this body a slot and a thread. */
        var admitted: Boolean = false
            private set

        /** What refused it, when [admit] did not succeed; for the failure report. */
        var refusal: ProbeExecutor.Refusal? = null
            private set

        /**
         * Admits the body, which then parks on [hold] holding its slot.
         *
         * Returns whether it was admitted. The slot is taken inside
         * [ProbeExecutor.claim] synchronously, before this returns, so a true
         * here means the slot is held - it does not matter whether the worker
         * thread has reached [hold] yet.
         */
        fun admit(): Boolean {
            val stopped = ProbeExecutor.executeReporting(host, Runnable {
                awaitIgnoringInterrupts(hold)
                // Answers only once released, so the slot's real end and the
                // wrapper's backstop both still run on the normal path.
                ProbeExecutor.answering(host) { true }
            })
            refusal = stopped
            admitted = stopped == null
            return admitted
        }

        /** Opens [hold]; the body answers and ends normally from here. */
        fun release() {
            hold.countDown()
        }

        /** Awaits [latch] to completion however often the thread is interrupted.
         *
         * An `await` throws [InterruptedException] and CLEARS the interrupt status,
         * so swallowing it and awaiting again is what "interrupts do not stop this
         * body" means in code. The status is restored afterwards so the park is not
         * silently swallowing a shutdown request either, and the wait is bounded so
         * a test that forgets its `finally` fails rather than poisoning the rest of
         * the JVM - [ProbeExecutor] is a singleton.
         */
        private fun awaitIgnoringInterrupts(latch: CountDownLatch) {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WEDGE_AWAIT_MS)
            var interrupted = false
            while (latch.count > 0L && System.nanoTime() < deadline) {
                try {
                    latch.await(WEDGE_AWAIT_MS, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    /**
     * Waits, bounded, for the shared probe pool to come back.
     *
     * [ProbeExecutor] is a process-wide singleton on a pool of
     * [ProbeExecutor.MAX_WEDGED_PROBES] plus one headroom thread, so a worker
     * still parked past this test's `finally` turns every LATER test in this JVM
     * into a discarded task and a false "not reachable". Each attempt costs at
     * most one budget while the pool is still busy, so [DRAIN_BOUND_MS] is
     * generous for the several attempts it can take. The drain runs a FRESH probe
     * with a FRESH clock against a host nothing has cached, so a `true` can only
     * have come from a real dial.
     */
    private fun drainProbeExecutor() {
        val startedAt = System.nanoTime()
        val deadline = startedAt + TimeUnit.MILLISECONDS.toNanos(DRAIN_BOUND_MS)
        var attempts = 0
        while (System.nanoTime() < deadline) {
            attempts++
            val drain = TcpConnectivityProbe(
                { "https://slot-release-once-drain-$attempts.invalid" },
                FakeClock(1_000L),
                FakeHostResolver(),
                RecordingConnector(script = listOf(ProbeOutcome.CONNECTED)),
            )
            if (attempts > 1) LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(DRAIN_RETRY_PARK_MS))
            if (drain.isServerReachable()) return
        }
        val waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        throw AssertionError(
            cardFailure("the shared probe worker was still busy after the drain MEASURED $waitedMs ms of real waiting, over $attempts attempts really made, against a $DRAIN_BOUND_MS ms bound - the one pool in [ProbeExecutor] serves every probe in this JVM, so a worker left parked past this test answers false for everything that follows it. The measured figures are reported, not the bound's, so a mis-converted unit can never make this message agree with the code it is describing"),
        )
    }

    private companion object {
        /**
         * How many bodies go through the answer-then-END cycle before the cap is
         * read.
         *
         * Two is the smallest count that proves the point - two double decrements
         * drive `occupied` to 0, still a legal reading of the cap - so this is
         * comfortably past it: every extra cycle drives the count lower, and a
         * guard satisfied by a negative number cannot tell one leak from many.
         */
        const val CYCLES = 4

        /**
         * [ProbeExecutor.CAP_HEADROOM_THREADS]; private in production.
         *
         * DECLARED BEFORE [POOL_MAX_THREADS] ON PURPOSE: a `const val`
         * initialiser may not name a constant declared LATER in the file, and
         * [POOL_MAX_THREADS] names this one. Swapping them reintroduces
         * "Variable CAP_HEADROOM_THREADS must be initialized." at compile time.
         */
        const val CAP_HEADROOM_THREADS = 1

        /**
         * The thread ceiling, read as production reads it:
         * [ProbeExecutor.MAX_WEDGED_PROBES] plus the one headroom thread that is
         * the pool's own `maximumPoolSize`. Mirrored because production keeps
         * both halves private.
         *
         * **This is also what makes the refusal attributable.** The two wedges
         * hold 2 slots of [ProbeExecutor.MAX_WEDGED_PROBES] but 2 threads of
         * THIS - one short of the ceiling - so the third probe's refusal can only
         * be [ProbeExecutor.Refusal.NO_FREE_SLOT]. A ceiling one lower would make
         * it [ProbeExecutor.Refusal.NO_FREE_THREAD] and this test would pass for
         * the wrong reason; a real production ceiling below the mirror fails the
         * gate's own assertion naming the index it reached.
         */
        const val POOL_MAX_THREADS = ProbeExecutor.MAX_WEDGED_PROBES + CAP_HEADROOM_THREADS

        /** Two names whose bodies are admitted and never answered, filling the cap. */
        const val FIRST_WEDGED_HOST = "wedged-one.invalid"
        const val SECOND_WEDGED_HOST = "wedged-two.invalid"

        /** A third, healthy name: never looked up, never dialled, and refused. */
        const val THIRD_HOST = "still-dialable.invalid"

        /** Ceiling on one bounded wait: the park inside a body, and a published answer. */
        const val PUBLISH_AWAIT_MS = 10_000L

        /**
         * Ceiling on the retry of one admission.
         *
         * Far more than the transient needs - the thing being waited on is a
         * `finally` block, not a timed budget - and the bound's job is only to
         * stop an INFINITE wait from hanging the JVM.
         */
        const val ADMIT_BOUND_MS = 2_000L

        /**
         * How long a refused admission parks before it is retried.
         *
         * The transient being waited on - a body still unwinding out of the
         * wrapper's `finally` - resolves in microseconds, so a short park is
         * ample, and its job is to make the retry cost no CPU while it waits.
         */
        const val ADMIT_RETRY_PARK_MS = 5L

        /**
         * How long a drain retry parks before it is re-issued. Same rationale
         * and same idiom as [ADMIT_RETRY_PARK_MS]: the drain re-issues a real
         * probe every attempt, so a tight loop of those is the scheduling
         * pressure this drain avoids. First attempt immediate.
         */
        const val DRAIN_RETRY_PARK_MS = 5L

        /** Ceiling on a wedge's park if nothing releases it; a safety valve. */
        const val WEDGE_AWAIT_MS = 30_000L

        /** Ceiling on what a refused caller may be held. */
        const val RETURN_BOUND_MS = 3_000L

        /**
         * Ceiling on the drain, 5 s: generous because each attempt can cost one
         * budget. The drain's only job is to notice a worker that has come back,
         * and each retry parks [DRAIN_RETRY_PARK_MS], so a longer bound buys a
         * busy pool no extra patience - it only extends how long a genuinely
         * wedged worker stalls the rest of this JVM's suite.
         */
        const val DRAIN_BOUND_MS = 5_000L
    }
}