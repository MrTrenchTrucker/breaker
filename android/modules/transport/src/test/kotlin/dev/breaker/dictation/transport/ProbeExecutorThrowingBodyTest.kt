package dev.breaker.dictation.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * What a probe body that THROWS leaves behind on [ProbeExecutor].
 *
 * The counter that makes the pool's bound honest is given back in the submitted
 * wrapper's `finally`, not on the way out of the body through a `catch`: a body
 * that returns takes that path, and a body that throws takes it too, and the
 * second case is the one nobody exercises, because everything the pool runs
 * today is written to end quietly. That asymmetry is invisible from a
 * happy-path test and expensive when it is wrong: the two counters are
 * process-wide, so a single body that threw and failed to give them back would
 * leave the pool permanently smaller than its own promise, and every later
 * probe in the JVM - from any feature, any screen, for the life of the
 * process - would be refused as "no free slot" for a slot nobody was using.
 *
 * **The shape that detects it without reading the counters.** [ProbeExecutor]
 * exposes no accessor for occupancy, deliberately: production has no caller
 * that needs one, and adding one for a test would put an API on the seam whose
 * only user is the test. So this test observes the accounting through its
 * consequence instead. It submits MORE throwing bodies than the cap allows
 * ([ProbeExecutor.MAX_WEDGED_PROBES] of them, one at a time, each awaited to
 * completion), waits for the pool to come back, and then requires a FULL cap of
 * BLOCKING bodies to be admitted and to run. A counter the throws failed to
 * return would refuse one of those two - and a refusal here is unambiguous
 * because the order is fixed: every throw is finished before the first blocking
 * body is submitted, so there is no way to blame a refusal on a body that still
 * holds a counter for a legitimate reason. Interleaving them would leave two
 * suspects for one failure. The wait between the halves is what makes that
 * separation exact: a body's OWN `finally` counts down its `ended` latch, so a
 * body can be over while the submitted wrapper has not yet returned the pool's
 * counters, and reading the pool in that gap would report a hand-back still in
 * progress as a leak.
 *
 * **Why three throws and not one.** One throw is not more than the cap, so it
 * cannot be distinguished from a body that had legitimately released its
 * counters; the arithmetic has to be driven past the number the pool promises
 * to hold before "the pool still works afterwards" means anything. The count is
 * read from the production constant and derived from it rather than written as
 * a second copy of a literal that could drift.
 *
 * **How a body that threw can still signal that it ended.** The obvious
 * `countDown()` at the end of a body never runs when the body throws, which
 * would make the wait for it unbounded in the worst case - a hang rather than a
 * failure. So the signal is a latch counted down in a `finally` INSIDE the body:
 * the one statement guaranteed to run on both the return path and the throw
 * path. Each throw gets its own latch, awaited on its own, so "the third throw
 * finished" is a fact rather than an inference from the second having finished.
 *
 * **What this test does not claim, and why the absence is deliberate.** It does
 * not assert that anything logs or surfaces the throw on a thread: [ProbeExecutor]
 * contains the throw inside the launch block (see the object KDoc), so the body's
 * failure never reaches a thread's uncaught-exception handler - it is carried in
 * the result the body's own caller already saw, and the launched coroutine ends
 * normally. That is the module's promise, pinned by
 * [TcpConnectivityProbeUncaughtHandlerTest]; it is not what this test measures,
 * and an `UncaughtExceptionHandler` installed here would REPLACE exactly that
 * promise rather than observe it, so none is installed. What this test pins is
 * the accounting: that a body that throws still gives both counters back, so a
 * throwing body is the same to the pool as a returning one.
 *
 * **And it makes no claim about the caller.** `execute` answers one boolean for
 * the admission, and that boolean is produced before the body has run, so it
 * cannot and does not report anything about how the body ended. Nothing here
 * asserts an outcome for the submitter, because there is no outcome to assert.
 *
 * **This parks bodies on a process-wide singleton, so it inherits an idle
 * check on both sides of the test.** The one pool in [ProbeExecutor] serves
 * every probe in this JVM; a worker left parked on a latch only this test can
 * release turns every later test into a discarded task and a false "not
 * reachable", and the suite goes flaky in whatever order JUnit happens to pick.
 * [ProbePoolIsolation] supplies the hooks, and they fail this class by name,
 * with all four counters read out, before the test begins and again once it
 * ends - so a leak lands on the class that made it instead of on whichever
 * class happens to run next. The hooks can only find the pool idle if the gates
 * this test parks its later bodies on come down first, which is why the
 * `finally` in the test body counts them down on the passing and the failing
 * path alike.
 *
 * No real DNS and no real sockets: every wait is a bounded latch await, and
 * nothing sleeps or polls in a loop to decide an assertion.
 */
class ProbeExecutorThrowingBodyTest : ProbePoolIsolation() {

    // --- a throwing body must not cost the pool a single later admission -----

    @Test
    fun `bodies that throw give both counters back and later admissions still run`() {
        val cap = ProbeExecutor.MAX_WEDGED_PROBES
        // One more throw than the cap can hold at once, so the sequence cannot
        // be explained by every throw simply having fitted inside the promise.
        val throwCount = cap + 1
        val gates = List(cap) { CountDownLatch(1) }
        val blockingStarted = CountDownLatch(cap)
        val blockingRan = AtomicInteger(0)

        for (throwNumber in 1..throwCount) {
            // Counted down in a `finally` inside the body, so the throw itself
            // cannot skip it. This is the only statement in the body that is
            // guaranteed to run.
            val ended = CountDownLatch(1)
            ProbeExecutor.execute {
                try {
                    throw IllegalStateException("probe body $throwNumber of $throwCount fails on purpose")
                } finally {
                    ended.countDown()
                }
            }
            assertTrue(
                cardFailure(
                    "probe body $throwNumber of $throwCount threw and must still reach the end of its own body, which is what returns its counters; " +
                        "the latch waited on here is counted down in a `finally` inside that body, so it cannot have been skipped by the throw. " +
                        "It never counted down, which means the body was never admitted, never started, or is still in flight - and the wait is bounded, " +
                        "so this is a failure rather than a hang. A body that never started would leave its counters unclaimed, and this test's later " +
                        "admissions are what would show it",
                ),
                ended.await(THROW_END_BOUND_MS, TimeUnit.MILLISECONDS),
            )
        }

        // Every throw above ENDED, which is not the same fact as every counter
        // coming back: `ended` is counted down on the last statement inside the
        // body, and the submitted wrapper only returns both counters in its own
        // `finally`, which runs after that. Reading the pool here rather than
        // after it would measure a legitimate hand-back as a leak. So the pool
        // is asked, once, to come back - and that is also the only reading that
        // makes the admission count below mean what its message claims: an
        // admission refused once the pool is confirmed idle can only have been
        // refused by a counter a throw held on to.
        awaitProbePoolIdle(
            context = "$throwCount bodies threw before this point, and every one of them reached the end of itself",
        )

        try {
            admitAndMeasureBlockingBodies(cap, throwCount, gates, blockingStarted, blockingRan)
        } finally {
            // On both paths. A failed assertion between here and the end of the
            // method would otherwise leave both bodies parked on their gates,
            // holding a slot and a thread for the rest of the JVM, and every
            // later probe would be refused for capacity this test is using.
            gates.forEach { it.countDown() }
        }
    }

    /**
     * Admits [cap] bodies that stay in flight for the whole measurement, so
     * neither can hand its own counter back and make the count look right.
     *
     * The gates are NOT released here. The caller owns them and releases them in
     * a `finally`, so a failed assertion in this method still lets the workers
     * go instead of parking them for the rest of the JVM; this method's own
     * contract is the measurement and nothing else.
     */
    private fun admitAndMeasureBlockingBodies(
        cap: Int,
        throwCount: Int,
        gates: List<CountDownLatch>,
        blockingStarted: CountDownLatch,
        blockingRan: AtomicInteger,
    ) {
        var admittedBlocking = 0
        for (index in 0 until cap) {
            val gate = gates[index]
            if (!ProbeExecutor.execute {
                    blockingStarted.countDown()
                    blockingRan.incrementAndGet()
                    gate.await(BLOCKING_AWAIT_MS, TimeUnit.MILLISECONDS)
                }
            ) {
                break
            }
            admittedBlocking++
        }

        assertEquals(
            cardFailure(
                "a full cap of $cap later bodies must be admitted once the pool has come back from $throwCount bodies that threw, because the " +
                    "wrapper's `finally` gives back both counters whether the body returns or throws; the pool is confirmed idle immediately " +
                    "before this loop, so a refusal here cannot be blamed on a counter that is merely on its way back - only $admittedBlocking were " +
                    "admitted, so a throwing body kept a slot and a thread for the life of the process and every probe after this one is refused " +
                    "for capacity nobody is using",
            ),
            cap,
            admittedBlocking,
        )

        // Order is the proof: every admitted body must have STARTED before the
        // count of what ran is read, or an admitted body that never began would
        // be counted as a leak-free success. It sits AFTER the admission
        // assertion on purpose: a leaked counter is refused at admission, so
        // that assertion is the fast one, and putting this wait in front of it
        // would burn its whole bound waiting for a body that was deliberately
        // never admitted.
        assertTrue(
            cardFailure(
                "all $cap admitted later bodies must have STARTED, and not merely been accepted for admission, before the count below is taken; " +
                    "the latch is counted down on the first statement of each body, so it times out only when a body that was admitted never began " +
                    "to run. Without this wait, a body that was admitted and then never started - an executor that accepts work and drops it, which is " +
                    "the same capacity leak seen from the other side - would leave the run count below short of $cap only by luck of scheduling, and on " +
                    "a loaded machine the assertion could pass with a body that never executed. The wait is bounded, so an unstarted body is a failure " +
                    "here rather than a hang",
            ),
            blockingStarted.await(ALL_BODIES_STARTED_BOUND_MS, TimeUnit.MILLISECONDS),
        )

        assertEquals(
            cardFailure(
                "all $cap admitted later bodies must really have run, not merely been admitted; $blockingRan of $cap reached their body. An admitted " +
                    "task that never runs would hide the same leak, because the admission counter would already have been restored by the time this " +
                    "test looked at it",
            ),
            cap,
            blockingRan.get(),
        )
    }

    private companion object {
        /**
         * Ceiling on waiting for one throwing body to reach the end of itself.
         * Generous because it must also cover the worker having to be started
         * for it; bounded because an unbounded wait here is a hang in a suite
         * that other tests share a JVM with.
         */
        const val THROW_END_BOUND_MS = 10_000L

        /**
         * Ceiling on one blocking body's own await. It bounds the damage a
         * forgotten `finally` does: a body left blocked forever would park a
         * worker for the life of the JVM rather than until this test fails.
         */
        const val BLOCKING_AWAIT_MS = 30_000L

        /**
         * Ceiling on waiting for every admitted later body to have STARTED.
         *
         * Not [BLOCKING_AWAIT_MS], deliberately: that one is a bound on a body
         * deliberately parked on a latch this test owns, so it has to outlast
         * the whole test body. This one covers only the hand-off from an
         * accepted task to a worker picking it up - pool scheduling latency for
         * at most [ProbeExecutor.MAX_WEDGED_PROBES] already-admitted tasks -
         * and by the time the wait runs, every worker this pool needs has
         * already run a throwing body to completion, so nothing here is waiting
         * on a cold start.
         *
         * Ten seconds, and the same number as [THROW_END_BOUND_MS], because this
         * is a wait for SOMETHING TO HAPPEN and a bound like that costs time
         * only on a failure: on the passing path the latch trips in
         * microseconds and the whole ceiling is never spent, while on the
         * failing path a few seconds of patience buys a decision that is right
         * rather than a failure manufactured by a loaded machine - every
         * worker this pool needs has just finished a body, the run queue is
         * short by construction, and a heavily loaded host is exactly where
         * scheduling latency is unbounded from the test's point of view. A
         * tight ceiling here would therefore report a healthy pool as a leak
         * whenever the machine is busy, which is the one thing this assertion
         * must never do. Ten seconds is still an order of magnitude above the
         * real hand-off cost, so it separates "slow" from "never begun":
         * anything beyond it is a body that was accepted and never begun, which
         * is a failure to report rather than scheduling to wait out.
         */
        const val ALL_BODIES_STARTED_BOUND_MS = 10_000L
    }
}
