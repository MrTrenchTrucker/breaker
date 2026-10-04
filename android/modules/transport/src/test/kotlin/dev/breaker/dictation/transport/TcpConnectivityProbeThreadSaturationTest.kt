package dev.breaker.dictation.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * What happens when every THREAD is held by a body that has already ANSWERED.
 *
 * [ProbeExecutor] keeps two counters and gives them back at two different
 * moments, and the gap between those moments is the whole subject here. The
 * SLOT is released by [ProbeExecutor.answering], as the answer is published, so
 * answered workers stop counting against
 * [ProbeExecutor.MAX_WEDGED_PROBES]. The THREAD is not: it comes back only in
 * the submitted wrapper's `finally`, when the body ENDS. So a worker that has
 * published its answer and is still inside its own task holds a thread and
 * nothing else - and at [POOL_MAX_THREADS] such workers the next probe has
 * nowhere to go.
 *
 * **What the caller is owed, and what it is not.** The refusal is
 * [ProbeExecutor.Refusal.NO_FREE_THREAD]: nothing was looked up, nothing was
 * dialled, so nothing was learned about the server. It is NOT a reachability
 * verdict, it is not a measurement, and storing it would serve it for the whole
 * [TcpConnectivityProbe.CACHE_TTL_MS] window - which is the false that answers
 * "the server is down" about a server that is very much up, for half a minute,
 * to a caller that has no way to tell the two apart.
 *
 * **The refusal is answered AT ONCE, and the JDK's own rejection never fires
 * here.** [ProbeExecutor.CAP_HEADROOM_THREADS] is what makes [ProbeExecutor.claim]
 * the only thing that can refuse: the pool's `maximumPoolSize` IS
 * [POOL_MAX_THREADS], and a body that `claim` admits is always given a worker.
 * So a submit at saturation is turned into a named refusal by
 * [ProbeExecutor.executeReporting] rather than by the pool's rejection handler,
 * and the [RejectedExecutionException] catch behind it never sees anything.
 * **The catch is nevertheless reachable, and the way to reach it is a mutant
 * rather than a test:** drop the `bodies` check from `claim` and the fourth
 * body is admitted with all three threads busy, the pool's handler throws, and
 * that catch is the only thing standing between the caller and an exception.
 * Both paths are held to the same contract here - refused, named, not measured,
 * cached never, and nothing thrown at the caller.
 *
 * **The bodies are parked, one at a time, each on a latch this test owns.**
 * Every worker's state is a fact before the next assertion turns on it:
 *
 *  - a body runs its [FutureTask] - publishing the answer through
 *    [ProbeExecutor.answering], which is what releases the SLOT - and only THEN
 *    parks, so the slot is provably free while the thread is provably held;
 *  - the test does not admit the next body until it has OBSERVED the previous
 *    one's answer through [FutureTask.get], a bounded await on the event of
 *    the callable finishing. So at most one slot is ever taken, and the third
 *    admission is refused for the THREAD count and for nothing else - which is
 *    the only reason the refusal below is attributable to thread saturation;
 *  - the park is on a latch whose only `countDown` is in this test's `finally`,
 *    so no scheduler, no load and no timing decides how many threads are held.
 *
 * The pool in [ProbeExecutor] is a process-wide singleton, so a worker left
 * parked past this test's `finally` would answer false for every later test in
 * this JVM. Every latch is released there, always, and the pool is then drained
 * with a FRESH probe on a FRESH clock against a host nothing has cached, so a
 * `true` from the drain can only have come from a dial that really ran.
 *
 * No real DNS and no real sockets: the resolver is the shared fake, the
 * connector is the shared [RecordingConnector], and every wait is a bounded
 * latch await or a bounded poll over real probes.
 */
class TcpConnectivityProbeThreadSaturationTest : ProbePoolIsolation() {

    // --- the saturation contract -------------------------------------------------

    @Test
    fun `a probe refused because every thread is held by an answered body is named, not measured, and never cached`() {
        val bodies = List(POOL_MAX_THREADS) { index -> ParkedAnsweredBody("answered-then-parked-$index.invalid") }
        val refusedHost = "thread-saturated.invalid"
        val connector = RecordingConnector(script = listOf(ProbeOutcome.CONNECTED))
        // Deliberately never moved: an entry whose age is inside the window IS
        // served, so holding the clock still is what makes a wrongly-cached
        // false observable at all. A jump would expire a cached false on the
        // first attempt and make the recovery below look clean.
        val probe = TcpConnectivityProbe({ "https://$refusedHost" }, FakeClock(1_000L), FakeHostResolver(), connector)
        val escaped = CopyOnWriteArrayList<Throwable>()

        var refusal: ProbeExecutor.Refusal? = null
        var bodyRan = false
        var firstAnswer = true
        var secondAnswer = true
        var refusedElapsedMs = -1L
        var dialsWhileRefused = -1
        var recoveryAttempts = 0
        var recovered = false
        try {
            // POOL_MAX_THREADS answered bodies, admitted and observed one at a
            // time: each publishes before it parks, so the SLOT cap is never
            // what is full - only the THREAD count is.
            for ((index, body) in bodies.withIndex()) {
                assertTrue(
                    cardFailure("the ${index + 1}th of the $POOL_MAX_THREADS workers this test parks must be admitted and must start its task: the slot each previous worker released at publish is free, so a refusal here means the thread cap was reached before this test had parked enough bodies, and the refusal asserted below would be measuring the wrong occupancy"),
                    body.admitAndAwaitPark(),
                )
                assertEquals(
                    cardFailure("worker ${index + 1} of $POOL_MAX_THREADS must have PUBLISHED an answer before it parked: it ran its task and only then waited, so a value here can only exist if the callable finished and [ProbeExecutor.answering]'s finally - the SLOT release - has already run"),
                    true,
                    body.awaitPublishedAnswer(),
                )
            }

            // The refusal, at the face that NAMES it. The task below is
            // instrumented so "refused" is observable in two independent ways:
            // a non-null refusal, and a body that never ran at all.
            refusal = recording(escaped) {
                ProbeExecutor.executeReporting("$refusedHost-executor", Runnable { bodyRan = true })
            }

            // The same refusal as the caller sees it: a real probe of a fresh
            // host, whose dial is scripted CONNECTED, so a `true` is only
            // possible if a dial really ran.
            val startedAt = System.nanoTime()
            firstAnswer = recording(escaped) { probe.isServerReachable() } ?: true
            refusedElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            dialsWhileRefused = connector.callCount

            // Still refused, and STILL nothing dialled: a second question about
            // the same host must not be answered differently from the first.
            secondAnswer = recording(escaped) { probe.isServerReachable() } ?: true

            // Release ONE body. `bodies` comes back in the wrapper's `finally`,
            // which no statement inside the body can observe, so "this thread is
            // back" has no in-band event; the only external evidence of it is
            // that a submit is admitted again, and that is the thing being
            // measured. Hence a bounded poll over REAL probes: each iteration
            // either dials or is refused, a refusal is UNDIALED and caches
            // nothing, and a dial to this host is recorded.
            bodies.first().release()
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RECOVERY_BOUND_MS)
            while (System.nanoTime() < deadline) {
                recoveryAttempts++
                if (recording(escaped) { probe.isServerReachable() } == true) {
                    recovered = true
                    break
                }
            }
        } finally {
            bodies.forEach { it.release() }
            drainProbeExecutor()
        }

        // --- the refusal is named as thread saturation ---------------------------

        assertNotNull(
            cardFailure("a submit while every one of the $POOL_MAX_THREADS threads is held by a body that has answered but not ended has nowhere to go, so [ProbeExecutor.executeReporting] must hand back the refusal that says so; it handed back null, meaning a body was admitted on a pool of $POOL_MAX_THREADS threads. Escaped: $escaped"),
            refusal,
        )
        assertSame(
            cardFailure("the refusal must be [ProbeExecutor.Refusal.NO_FREE_THREAD], because nothing about the server was measured: every thread of this pool is held by a task body that has published its answer and not yet ended, and this host's dial is scripted CONNECTED. A different case here means the refusal is being attributed to the wrong cause - ${refusal?.name} was returned, with the reason \"${refusal?.reason()}\", and it is the CAUSE, not the word, that tells a caller whether anything was learned"),
            ProbeExecutor.Refusal.NO_FREE_THREAD,
            refusal,
        )
        assertTrue(
            cardFailure("a refusal a caller may log has to say what refused it, in words: the reason returned was \"${refusal?.reason()}\", which does not name thread saturation. A non-null refusal that cannot be told from a refused start tells a reader nothing, and a reachability verdict is what this is NOT"),
            refusal?.reason()?.contains(THREAD_SATURATION_PHRASE) == true,
        )
        assertFalse(
            cardFailure("the task was refused before it ran, so its body must never have executed: it did, on a pool that had no thread to give it. A refusal that runs its task anyway is not a refusal, and it is the body - not the answer - that a caller can be harmed by here"),
            bodyRan,
        )

        // --- nothing escapes to the caller --------------------------------------

        assertTrue(
            cardFailure("a refused probe must ANSWER, not throw: the caller is the dictation path and has no handler for a failure it cannot see through a boolean. Thrown: $escaped"),
            escaped.isEmpty(),
        )
        assertFalse(
            cardFailure("no [RejectedExecutionException] may reach the caller: [ProbeExecutor.CAP_HEADROOM_THREADS] exists so that [ProbeExecutor.claim] is the only thing that can refuse a task and the pool's own rejection handler never fires. One escaped: $escaped"),
            escaped.any { it is RejectedExecutionException },
        )

        // --- it is not measured, and it is not cached ---------------------------

        assertFalse(
            cardFailure("$refusedHost's dial is scripted CONNECTED, so only a dial can make this probe answer reachable: it was answered not reachable with every thread held by a body that had already answered. Nothing was looked up and nothing was dialled, so nothing was learned about the server - and the domain reads this false as \"the server is down\" and routes every dictation on-device"),
            firstAnswer,
        )
        assertEquals(
            cardFailure("a probe refused before it ran must not have dialled anything: $dialsWhileRefused dials were recorded. Recorded: ${connector.hosts}. The not-cached assertion below is only meaningful if the refusal really was UNDIALED"),
            0,
            dialsWhileRefused,
        )
        assertTrue(
            cardFailure("a task with nowhere to go is refused AT ONCE: this probe waited ${refusedElapsedMs} ms against a ${TcpConnectivityProbe.CONNECT_TIMEOUT_MS} ms budget, so a caller was held rather than answered, and the false it was finally given describes a wait rather than a refusal"),
            refusedElapsedMs < RETURN_BOUND_MS,
        )
        assertFalse(
            cardFailure("a refusal is a moment, not a verdict: the pool is still saturated here, so the same host must be refused the same way. It answered reachable, which can only have come from an entry recording a connection attempt that was never made"),
            secondAnswer,
        )

        // --- and it is still there to be served when the thread comes back -------

        assertTrue(
            cardFailure("one body was released and ${connector.hosts.size} dials were recorded, yet $refusedHost was still answered not reachable after $RECOVERY_BOUND_MS ms and $recoveryAttempts probes. A refusal that is not measured must not be cached, so the first question asked once a thread is free again has to be a real probe and really dial; a true here cannot be reached by dialling at all, since the only dials made are those listed"),
            recovered,
        )
        assertEquals(
            cardFailure("the recovery must come from a DIAL that ran after the thread came back, and from nothing else: no dial to $refusedHost was recorded at all over $recoveryAttempts probes. An answer that recorded no connection attempt is not a measurement, and serving one for ${TcpConnectivityProbe.CACHE_TTL_MS} ms hides a server that has just come back from the very question meant to find it"),
            listOf(refusedHost),
            connector.hosts,
        )
    }

    // --- what a refused admission leaves behind ----------------------------------

    /**
     * Two refusals in a row, at saturation, and then the same host admitted.
     *
     * The forced-rejection path needs no production seam, because a version of
     * `claim` without the thread check exercises it; this is the test that holds
     * such a version to the same
     * contract the correct code is held to: a refused submit is refused with a
     * named cause, its task never runs, and it leaves NOTHING pinned - no slot,
     * no thread, and no in-flight mark - so the very same host is admitted and
     * its body really executes as soon as one thread is free.
     *
     * Two refusals, not one, because the second is the one that can catch a
     * mark that outlived its refusal: [ProbeSingleFlight.submit] inserts the
     * host's mark before it submits and removes it only when the submit is
     * refused, so a second probe of a host whose first probe was refused is
     * refused with [ProbeExecutor.Refusal.HOST_IN_FLIGHT] instead of the thread
     * cap if that removal is missing. A mark left behind is invisible and
     * permanent - nothing about the host is still parked, yet every later probe
     * of it is refused and answers "not reachable" with no dial, for the life of
     * the process.
     *
     * The bodies are parked and answered exactly as in the test above, and
     * "the task really ran" is observed as an EVENT - a latch counted down
     * inside the body - rather than as a sleep or a poll.
     */
    @Test
    fun `a refused submit at saturation runs no body and leaves nothing pinned against its host`() {
        val bodies = List(POOL_MAX_THREADS) { index -> ParkedAnsweredBody("saturated-no-residue-$index.invalid") }
        val host = "saturated-then-admitted.invalid"
        val escaped = CopyOnWriteArrayList<Throwable>()

        var firstRefusal: ProbeExecutor.Refusal? = null
        var secondRefusal: ProbeExecutor.Refusal? = null
        // Every body a REFUSED submit was given, so "refused" is observable as
        // "nothing ran" and not only as a non-null return.
        val bodiesRunByRefusal = CopyOnWriteArrayList<Boolean>()
        var admittedAfterRelease = false
        var admissionAttempts = 0
        try {
            for ((index, body) in bodies.withIndex()) {
                assertTrue(
                    cardFailure("the ${index + 1}th parked body must be admitted: each earlier body published its answer before parking, so every slot is free and only the thread count is full. A refusal here means this test never reached saturation"),
                    body.admitAndAwaitPark(),
                )
                assertEquals(
                    cardFailure("worker ${index + 1} of $POOL_MAX_THREADS must have published its answer before it parked, so the slot it was holding is already back and the refusals below are the THREAD cap and not the SLOT cap"),
                    true,
                    body.awaitPublishedAnswer(),
                )
            }

            firstRefusal = recording(escaped) {
                ProbeExecutor.executeReporting(host, Runnable { bodiesRunByRefusal.add(true) })
            }
            secondRefusal = recording(escaped) {
                ProbeExecutor.executeReporting(host, Runnable { bodiesRunByRefusal.add(true) })
            }

            // One thread free: the very same host, whose two probes above were
            // both refused, must now be admitted and must really run.
            //
            // Bounded poll, not one submit. The released body's thread comes
            // back in the submitted wrapper's `finally`, which runs on THAT
            // worker and not on this thread, so "the counter is down again" is
            // an event this test can only wait for and never assert directly -
            // the only visible evidence of it is the next submit being
            // admitted. Every iteration is a real submit: a refused one runs no
            // body and is recorded as such, so a poll cannot hide a refusal
            // that never clears.
            bodies.first().release()
            val ranAfterRelease = CountDownLatch(1)
            var admission: ProbeExecutor.Refusal? = ProbeExecutor.Refusal.NO_FREE_THREAD
            val admissionDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RECOVERY_BOUND_MS)
            while (admission != null && System.nanoTime() < admissionDeadline) {
                admissionAttempts++
                admission = recording(escaped) {
                    ProbeExecutor.executeReporting(host, Runnable { ranAfterRelease.countDown() })
                }
            }
            assertNull(
                cardFailure("with one body ended, the thread it was holding is back and this host - which nothing is still looking up - must be admitted; over $admissionAttempts submits the last returned $admission, with the reason \"${admission?.reason()}\". A refusal that pinned its slot, its thread or its mark would show up here and nowhere else. Escaped: $escaped"),
                admission,
            )
            assertTrue(
                cardFailure("an admitted task must really run its body: the latch its body counts down never did, over $ADMITTED_AWAIT_MS ms. A submit reported as admitted whose body never runs is a silent loss of every probe behind it"),
                ranAfterRelease.await(ADMITTED_AWAIT_MS, TimeUnit.MILLISECONDS),
            )
            admittedAfterRelease = true
        } finally {
            bodies.forEach { it.release() }
            drainProbeExecutor()
        }

        assertSame(
            cardFailure("the first submit at saturation must be refused for the thread count, and named as such: it came back $firstRefusal, with the reason \"${firstRefusal?.reason()}\""),
            ProbeExecutor.Refusal.NO_FREE_THREAD,
            firstRefusal,
        )
        assertSame(
            cardFailure("the second probe of a host whose first probe was REFUSED must be refused for the same reason, not by single-flight: it came back $secondRefusal, with the reason \"${secondRefusal?.reason()}\". [ProbeSingleFlight.submit] removes the mark it inserted when the submit is refused, so nothing about this host is still parked - a mark that outlives its refusal is invisible, permanent, and makes every later probe of a healthy host answer not reachable with no dial"),
            ProbeExecutor.Refusal.NO_FREE_THREAD,
            secondRefusal,
        )
        assertTrue(
            cardFailure("neither refused submit may have run its body, and ${bodiesRunByRefusal.size} did. A refused admission holds no slot and no thread, so a body that ran anyway is running on a pool this process told itself was full - exactly the over-subscription the two counters exist to prevent, and the over-subscription the pool's own rejection handler exists to stop"),
            bodiesRunByRefusal.isEmpty(),
        )
        assertTrue(
            cardFailure("the body must have run after the thread came back; it did not. A refused submit that pinned its slot, its thread or its mark would show up here and nowhere else"),
            admittedAfterRelease,
        )
        assertTrue(
            cardFailure("a refused submit must ANSWER, not throw, and must not throw [RejectedExecutionException] at the caller: thrown $escaped"),
            escaped.isEmpty(),
        )
    }

    // --- shared plumbing -----------------------------------------------------------

    /**
     * One body that publishes an answer and then parks, holding its thread and
     * nothing else.
     *
     * The two halves are separate on purpose. [answer] is a [FutureTask] whose
     * callable is [ProbeExecutor.answering], so running it publishes a value
     * AND releases the SLOT, in that order, before the callable returns. The
     * park comes after, on a latch only this test's `finally` (or an explicit
     * [release]) can open - which is what holds the THREAD.
     *
     * Both waits are bounded, so a body whose latch is never opened ends by
     * itself rather than poisoning every later test in this JVM.
     */
    private class ParkedAnsweredBody(private val host: String) {

        /** Releases the SLOT at publish, and gives the test an observable answer. */
        private val answer: FutureTask<Any?> = FutureTask<Any?> { ProbeExecutor.answering(host) { true } }

        /** The only thing this test's `finally` - or [release] - counts down. */
        private val hold = CountDownLatch(1)

        /** Admits the body, which publishes its answer and then parks. */
        fun admitAndAwaitPark(): Boolean = ProbeExecutor.execute(host, Runnable {
            answer.run()
            hold.await(PUBLISH_AWAIT_MS, TimeUnit.MILLISECONDS)
        })

        /**
         * The published answer, awaited as an EVENT.
         *
         * Returning here proves the callable finished, which is what proves the
         * slot is already back. It proves nothing about the body, which is by
         * then at or heading for its park.
         */
        fun awaitPublishedAnswer(): Any? = answer.get(PUBLISH_AWAIT_MS, TimeUnit.MILLISECONDS)

        /** Opens the park, so this body ends and gives its thread back. */
        fun release() {
            hold.countDown()
        }
    }

    /**
     * Runs [block], recording anything it throws instead of letting it escape.
     *
     * "No exception reaches the caller" is the claim, so it cannot be asserted
     * by a `try` that only wraps its own assertion - every observation in these
     * tests goes through here and the recorded list is asserted empty.
     */
    private fun <T> recording(escaped: MutableList<Throwable>, block: () -> T): T? =
        try {
            block()
        } catch (thrown: Throwable) {
            escaped.add(thrown)
            null
        }

    /**
     * Waits, bounded, for the shared probe pool to come back.
     *
     * [ProbeExecutor] is a process-wide singleton on a pool of
     * [ProbeExecutor.MAX_WEDGED_PROBES] plus one headroom thread, so a worker
     * still parked on a latch released only by this test turns every LATER test
     * in this JVM into a refused task and a false "not reachable". Each drain
     * attempt costs at most one budget while the pool is still busy, so the
     * bound is generous for the several attempts it can take and still fails
     * rather than hanging the JVM. The drain runs a FRESH probe with a FRESH
     * clock against a host nothing has cached, so a `true` from it can only
     * have come from a dial that really ran on a shared worker.
     */
    private fun drainProbeExecutor() {
        val startedAt = System.nanoTime()
        val deadline = startedAt + TimeUnit.MILLISECONDS.toNanos(DRAIN_BOUND_MS)
        var attempts = 0
        while (System.nanoTime() < deadline) {
            attempts++
            val drain = TcpConnectivityProbe(
                { "https://thread-saturation-drain-$attempts.invalid" },
                FakeClock(1_000L),
                FakeHostResolver(),
                RecordingConnector(script = listOf(ProbeOutcome.CONNECTED)),
            )
            if (drain.isServerReachable()) return
        }
        val waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        throw AssertionError(
            cardFailure("the shared probe worker was still busy after the drain MEASURED $waitedMs ms of real waiting, over $attempts attempts really made, against a $DRAIN_BOUND_MS ms bound - the one pool in [ProbeExecutor] serves every probe in this JVM, so a worker left parked past this test answers false for everything that follows it. The measured figures are reported, not the bound's, so a mis-converted unit can never make this message agree with the code it is describing"),
        )
    }

    private companion object {
        /**
         * [ProbeExecutor.CAP_HEADROOM_THREADS]; private in production.
         * Declared before [POOL_MAX_THREADS] because a `const val` initialiser
         * may not name a constant declared later in the file.
         */
        const val CAP_HEADROOM_THREADS = 1

        /**
         * The thread ceiling these tests fill, read as production reads it:
         * [ProbeExecutor.MAX_WEDGED_PROBES] plus the one headroom thread, which
         * is the pool's own `maximumPoolSize` and the number `bodies` is
         * checked against at admission.
         *
         * Mirrored here because production keeps both halves private
         * (`CAP_HEADROOM_THREADS`, `POOL_MAX_THREADS`) and exposing them only for a
         * test would widen the production surface.
         *
         * The mirror is loud, not silent, in the other direction too: these
         * tests admit exactly this many bodies and no more, so if production's
         * real ceiling were LOWER, one of these admissions is refused and the
         * loop's own assertTrue fails naming the index it reached - before any
         * saturation claim is made. And if it were HIGHER, the refusals below
         * are not refusals, which the `assertSame` on [ProbeExecutor.Refusal]
         * catches outright.
         */
        const val POOL_MAX_THREADS = ProbeExecutor.MAX_WEDGED_PROBES + CAP_HEADROOM_THREADS

        /**
         * The words a refusal about threads must contain, from
         * [ProbeExecutor.Refusal.NO_FREE_THREAD]'s explanation. Asserted as a
         * phrase rather than as the whole sentence, because the sentence is
         * prose and the promise is the cause being named.
         */
        const val THREAD_SATURATION_PHRASE = "thread saturation"

        /**
         * Ceiling on one bounded wait: the park inside a task body and the wait
         * for its published answer. Generous enough that a slow CI box cannot
         * turn a correct implementation red, short enough that a broken one
         * fails instead of hanging the JVM.
         */
        const val PUBLISH_AWAIT_MS = 10_000L

        /** Ceiling on the wait for an admitted body's own event. */
        const val ADMITTED_AWAIT_MS = 10_000L

        /** Ceiling on what a refused or recovering caller may be held. */
        const val RETURN_BOUND_MS = 3_000L

        /** Ceiling on the bounded recovery poll, 10 s: generous for several budgets. */
        const val RECOVERY_BOUND_MS = 10_000L

        /** Ceiling on the drain, 10 s: generous because each attempt can cost a budget. */
        const val DRAIN_BOUND_MS = 10_000L
    }
}
