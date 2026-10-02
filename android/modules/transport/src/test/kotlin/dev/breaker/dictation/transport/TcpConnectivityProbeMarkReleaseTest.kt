package dev.breaker.dictation.transport

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * The single-flight mark must END WITH THE ANSWER, not with the worker's
 * bookkeeping (G1).
 *
 * [ProbeExecutor.execute] holds one in-flight mark per name, and that mark is
 * what stops a second probe of the same host from starting a second lookup
 * against a resolver that is already known to be struggling. Its lifetime has
 * to be the ANSWER's: once a caller can observe a result, that caller already
 * knows what the host looked like, so a probe arriving a second later has a
 * question the mark must not be answering on its behalf.
 *
 * **The window this test exists to close.** [ProbeExecutor.answering]'s `finally`
 * runs BEFORE the value it produced reaches the caller's waiter, so by the time
 * a caller holds an answer the mark is gone. That is the mark's real end. If the
 * end were left to the keyed `execute`'s wrapper `finally` instead, the mark
 * would survive until the task body had FULLY returned - and for a
 * [java.util.concurrent.FutureTask] the caller's waiter is released inside the
 * callable, so there is a real window in which the caller holds the answer and
 * the host is still marked. A back-to-back probe landing in that window is
 * refused by the single-flight check and answered "not reachable" WITHOUT A
 * DIAL, which is indistinguishable from a server that is down.
 *
 * **So this test parks the worker INSIDE that window and looks.** The mark's
 * removal must be observable before the wrapper's backstop has run, and the
 * backstop is the thing being ruled out as the release point. The park is
 * deliberately placed AFTER `answerA.run()` and never inside [answering]: a
 * hold inside [answering] would still be holding the mark, and the assertion
 * below would then be testing the opposite property - a refusal - which is the
 * correct behaviour for a genuinely parked lookup and says nothing about where
 * the mark ends once the answer exists.
 *
 * [ProbeExecutor] is `internal` to this module and is called DIRECTLY here,
 * deliberately: through a probe, a single-flight refusal and a failed dial both
 * arrive as `isServerReachable() == false`, and nothing in the public surface
 * can tell "refused because the mark outlived the answer" apart from "refused
 * because the server is down". The claim under test is about the mark's LIFETIME,
 * so it is asserted on the lifetime's own boolean.
 *
 * **The last test in here (the locale one) goes THROUGH a probe instead.** It
 * is not about a lifetime at all: it is about the KEY, and the only way a key
 * is observable is two spellings of one name competing for one entry. It
 * therefore builds a real probe over a wedging resolver, parks the lookup of
 * "IIS.local" and asks about "iis.local", and asserts the resolver was asked
 * for the second spelling ZERO times. That is the join [keyFor]'s locale-
 * independent fold exists to make, and it is invisible from the boolean alone:
 * a wedged name answers "not reachable" either way.
 *
 * **This parks a process-wide singleton on a pool capped at
 * [ProbeExecutor.MAX_WEDGED_PROBES] plus one headroom thread, so a leaked worker
 * would DEADLOCK the suite rather than merely fail one test.** Every latch is
 * counted down in a `finally`, always; every wait is bounded; and the test then
 * drains the shared pool - the same bounded-poll drain the sibling tests use,
 * with a FRESH probe on a FRESH clock and a host nothing has cached, so a `true`
 * from it can only have come from a dial that really ran on the shared worker.
 *
 * No real DNS and no real sockets: every wait is a bounded latch await or a
 * bounded poll; nothing sleeps and nothing spins.
 *
 * **The locale test also restores what it installs.** It sets
 * `Locale.setDefault(Locale("tr", "TR"))`, which is process-wide, so its saved
 * default goes back in the test's own `finally` AND through an [@After]
 * backstop - a leaked Turkish default would silently re-key every later test in
 * this JVM, in a way that only shows up as an unrelated flake.
 */
class TcpConnectivityProbeMarkReleaseTest {

    // --- G1: the mark ends with the answer, not with the wrapper ---------------

    @Test
    fun `the in-flight mark is released before the answer is observable`() {
        val host = "Box.local"
        val otherSpelling = "box.local"
        val hold = CountDownLatch(1)

        // The value-producing task. `answering` is what releases the mark, and
        // its `finally` has finished by the time `get` returns - which is
        // precisely the claim: the release is tied to the ANSWER, and the
        // wrapper's backstop has not run yet, because the body is parked below.
        val answerA = FutureTask<Any?> { ProbeExecutor.answering(host) { true } }

        var secondAdmitted = false
        try {
            assertTrue(
                cardFailure("the first probe of $host must be admitted and start its task; a refusal here means the mark was already held by something this JVM did not clean up, and nothing below would be testing the mark's end"),
                ProbeExecutor.execute(host, Runnable {
                    // Publish first, park second. Parking here instead would
                    // hold the mark open itself and turn the assertion below
                    // into a test of the correct refusal for a still-parked
                    // lookup rather than of where the mark ends.
                    answerA.run()
                    hold.await(PUBLISH_AWAIT_MS, TimeUnit.MILLISECONDS)
                }),
            )

            assertEquals(
                cardFailure("`answering` must have produced its answer: the task body ran it and then parked, so the value below can only be there if the callable finished - and a callable that has finished is a mark whose `finally` has already run"),
                true,
                answerA.get(PUBLISH_AWAIT_MS, TimeUnit.MILLISECONDS),
            )

            // The worker is now parked BETWEEN the publish and the wrapper's
            // backstop. Anything still holding this name here is holding it
            // past the point where its own answer existed.
            secondAdmitted = ProbeExecutor.execute(otherSpelling, Runnable {})
        } finally {
            hold.countDown()
            drainProbeExecutor()
        }

        assertTrue(
            cardFailure(
                "the mark must be released before the answer is observable, or a back-to-back probe of the same name is refused without a dial. $host had ALREADY answered (the callable above returned true), so the refusal here came from a name that was still marked with nothing left to find: the mark outlived the answer, and the caller would have been told \"not reachable\" about a server it had just measured. The refusal is not the mark doing its job - while $host's lookup is genuinely outstanding the refusal is right, and this window is precisely where it stops being",
            ),
            secondAdmitted,
        )
    }

    // --- G2: the backstop clears only ITS OWN mark -----------------------------

    @Test
    fun `the backstop cannot clear a later probe's mark`() {
        val host = "Box.local"
        val otherSpelling = "box.local"
        val upperSpelling = "BOX.LOCAL"
        val holdA = CountDownLatch(1)
        val holdB = CountDownLatch(1)
        val bEnteredLookup = CountDownLatch(1)

        // A answers, so `answering` releases A's mark, and then A's worker parks
        // with the wrapper's backstop still to run. Same shape as G1.
        val answerA = FutureTask<Any?> { ProbeExecutor.answering(host) { true } }

        // B is a DIFFERENT probe of the same name whose lookup parks INSIDE
        // `answering`: its mark was claimed by `execute` and is still held by
        // the body that is parked here, which is exactly what a genuinely
        // wedged lookup looks like from the outside.
        val answerB = FutureTask<Any?> {
            ProbeExecutor.answering(otherSpelling) {
                bEnteredLookup.countDown()
                holdB.await(PUBLISH_AWAIT_MS, TimeUnit.MILLISECONDS)
                true
            }
        }

        try {
            assertTrue(
                cardFailure("the first probe of $host must be admitted and start its task; a refusal here means the mark was already held by something this JVM did not clean up, and nothing below would be testing the backstop"),
                ProbeExecutor.execute(host, Runnable {
                    answerA.run()
                    holdA.await(PUBLISH_AWAIT_MS, TimeUnit.MILLISECONDS)
                }),
            )

            assertEquals(
                cardFailure("`answering` must have produced A's answer: the task body ran it and then parked, so the value below can only be there if the callable finished - and a finished callable is a mark whose `finally` has already run, which is what frees the name for B"),
                true,
                answerA.get(PUBLISH_AWAIT_MS, TimeUnit.MILLISECONDS),
            )

            assertTrue(
                cardFailure("B must be ADMITTED: A's mark was released by `answering` when A answered, and B arrives after that, so the name is free and refusing B here would mean the mark outlived the answer rather than testing the backstop at all"),
                ProbeExecutor.execute(otherSpelling, answerB),
            )
            assertTrue(
                cardFailure("B's lookup must reach its own body and park there; if B never started there is no in-flight lookup for A's backstop to clear, and the refusal asserted below would prove nothing"),
                bEnteredLookup.await(PUBLISH_AWAIT_MS, TimeUnit.MILLISECONDS),
            )

            // Release A. Its body returns, so its wrapper's backstop runs on
            // A's own thread - now while B holds the name.
            holdA.countDown()

            // A's worker is now released and its wrapper's backstop runs.
            // Wait, BOUNDED, for the pool to give a slot back by admitting an
            // UNRELATED healthy name - an event, not a duration.
            awaitUnrelatedAdmission()

            assertTrue(
                cardFailure(
                    "the in-flight mark for $host must still be held: B's lookup of the same name is parked inside `answering` right now, so a probe of $upperSpelling MUST be refused rather than starting a second lookup into the same name. It was admitted, which means A's wrapper backstop ran `remove` on the name AFTER B had already claimed it - the backstop removed that name UNCONDITIONALLY, so it cleared B's mark while B's lookup was still outstanding, and only removing the key AND the mark it wrote distinguishes one attempt from a later probe's attempt for the same name, and a wedged name got two lookups and two slots. That is the single-flight defect this whole mechanism exists to prevent",
                ),
                !ProbeExecutor.execute(upperSpelling, Runnable {}),
            )
        } finally {
            holdA.countDown()
            holdB.countDown()
            drainProbeExecutor()
        }
    }

    // --- G3: the key's case fold is locale-independent ------------------------

    /**
     * The default locale this test found, restored in the test's own `finally`
     * and here as a backstop.
     *
     * `Locale.setDefault` is GLOBAL state, so Turkish set by the locale test
     * and not restored - by an early return, an assertion, or a throw out of a
     * helper - would leave every LATER test in this JVM folding case under a
     * locale that maps "I" to a dotless "ı". JUnit builds a fresh instance per
     * test method, so this field starts `null` on every other test and this
     * cleanup is a no-op for them; it is `null`ed on restore so it cannot
     * restore twice.
     */
    private var localeToRestore: Locale? = null

    /**
     * G3: the in-flight key is folded under the Turkish locale, so the two
     * spellings of one name are still ONE name there.
     *
     * [ProbeExecutor]'s `keyFor` folds case with `Locale.ROOT`, and the reason
     * is that a fold driven by the DEFAULT locale is a different fold per
     * device: under a `tr-TR` default "IIS.local" becomes "ııs.local" while
     * "iis.local" becomes "iis.local" - two different keys for one DNS name,
     * which is exactly the split the fold exists to remove, and one that would
     * appear only on devices whose owners would be least likely to explain it.
     *
     * Note what is NOT being claimed here: Kotlin's no-argument `lowercase()`
     * compiles to `toLowerCase(Locale.ROOT)`, so swapping the argument for
     * nothing does not change the key. What this test rules out is a fold that
     * consults the default locale at all - `Locale.getDefault()`,
     * `toLowerCase()`, or a rewrite that reintroduces them.
     *
     * So this parks the lookup of "IIS.local" under a Turkish default and then
     * asks about "iis.local". Nothing about the network, the resolver or the
     * configuration has changed: it is the same name written the other way. The
     * second probe must JOIN the parked lookup - no second lookup, no second
     * slot out of [ProbeExecutor.MAX_WEDGED_PROBES] - which is only observable
     * if the key is folded the same way both times.
     *
     * **The resolver parks ONE spelling only**, so a key that failed to fold
     * takes the other branch visibly: the second lookup finds no latch,
     * resolves at once and dials, so the recorded lookup count below is
     * non-zero instead of zero. That is the decisive assertion; the answers are
     * asserted too, so a mutant cannot hide behind a coincidental boolean.
     *
     * **Locale restoration is the load-bearing hygiene here, not a nicety.**
     * `Locale.setDefault` is process-wide, so Turkish leaking out of this test
     * would silently re-key every later test in this JVM. The saved default is
     * captured BEFORE anything is set and restored in a `finally` that also
     * releases the latch and drains the shared pool, plus an [@After] as a
     * backstop for a path the `finally` cannot cover.
     */
    @Test
    fun `the in-flight key is folded under the Turkish locale`() {
        val savedLocale = Locale.getDefault()
        localeToRestore = savedLocale
        Locale.setDefault(TURKISH)
        val latches = ParkingResolver().apply { park(PARKED_SPELLING) }
        val connector = RecordingConnector(script = listOf(ProbeOutcome.CONNECTED))
        var address = "https://$PARKED_SPELLING:8443"
        val probe = TcpConnectivityProbe({ address }, FakeClock(1_000L), latches.asResolver(), connector)

        var joinedAnswer = true
        var joinedLookups = -1
        try {
            assertFalse(
                cardFailure("the resolution of $PARKED_SPELLING is parked on a latch this test does not release until its `finally`, so its probe must be released by the ${TcpConnectivityProbe.CONNECT_TIMEOUT_MS} ms budget and answer not reachable - a true here means the lookup was never actually wedged and there is no in-flight key for the probe below to join"),
                probe.isServerReachable(),
            )

            // The clock does NOT move here, and that is deliberate. The attempt
            // above ended at the budget with the lookup still parked, so it
            // never obtained an address and never dialled: it is UNANSWERED and
            // caches nothing, so there is no entry for the window to expire and
            // the probe below reaches the pool - and therefore the in-flight
            // key - on its own. Advancing the clock "to be safe against the
            // cache" would instead hide a mutant that caches such a false.
            //
            // The same DNS name, written the other way round.
            address = "https://$OTHER_SPELLING:8443"
            joinedAnswer = probe.isServerReachable()
            joinedLookups = latches.lookupsOf(OTHER_SPELLING)
        } finally {
            latches.releaseAll()
            drainProbeExecutor()
            Locale.setDefault(savedLocale)
            localeToRestore = null
        }

        assertEquals(
            cardFailure(
                "a name already in flight must be ONE key however it was spelled, and the fold must be locale-independent: \"IIS.local\" under a $TURKISH default lowercases its \"I\" to a DOTLESS \"ı\", so \"$PARKED_SPELLING\" and \"$OTHER_SPELLING\" became two different keys - the second spelling was a stranger to the in-flight map, so a wedged name got a second lookup and a second slot out of the ${ProbeExecutor.MAX_WEDGED_PROBES} this process allows itself, and the join that exists to prevent exactly that never happened. Lookups of $OTHER_SPELLING: $joinedLookups (expected 0); lookups of $PARKED_SPELLING: ${latches.lookupsOf(PARKED_SPELLING)}. The key must be folded with Locale.ROOT, because the DEFAULT locale is what maps \"I\" to \"ı\" here",
            ),
            0,
            joinedLookups,
        )
        assertFalse(
            cardFailure("the lookup of $PARKED_SPELLING is still parked on a latch only this test releases, so a probe of that same name has nothing to answer and must say not reachable. It answered reachable, with these dials recorded: ${connector.hosts} - a fresh connection attempt against a name already known to be stuck, which is what taking a second key costs"),
            joinedAnswer,
        )
    }

    /**
     * Restores the default locale G3 saved, whatever path that test left by.
     *
     * G3's own `finally` normally gets there first and nulls the field; this
     * only does work when it did not, so a failure inside the drain cannot
     * strand a Turkish default in front of every later test in this JVM.
     */
    @After
    fun restoreLocaleAfterEveryTest() {
        val saved = localeToRestore ?: return
        localeToRestore = null
        Locale.setDefault(saved)
    }

    /**
     * A resolver that parks ONE spelling of a name and answers every other host
     * at once, standing in for a name lookup the platform has given up on
     * handing back.
     *
     * Same shape as the sibling host-case test's double, and for the same
     * reason: a real `InetAddress.getAllByName` cannot be interrupted, so it
     * must ignore the `cancel(true)` the caller's budget sends. Only the exact
     * text given to [park] is parked, so a lookup under a key that failed to
     * fold finds no latch, resolves immediately and dials - which is what makes
     * the recorded lookup count the decisive observation here rather than a
     * boolean that could be right by accident.
     */
    private class ParkingResolver {
        private val latches = ConcurrentHashMap<String, CountDownLatch>()
        private val asked = CopyOnWriteArrayList<String>()

        /** Parks [host] until [releaseAll] is called, or until the await times out. */
        fun park(host: String) {
            latches.putIfAbsent(host, CountDownLatch(1))
        }

        fun releaseAll() {
            latches.values.forEach { it.countDown() }
        }

        /** How many lookups of [host] this resolver was actually asked for. */
        fun lookupsOf(host: String): Int = asked.count { it == host }

        /** The resolver itself: parks the scripted hosts, answers the rest. */
        fun asResolver(): HostResolver = HostResolver { host ->
            asked.add(host)
            latches[host]?.let { latch -> awaitIgnoringInterrupts(latch) }
            listOf(FakeHostResolver.loopbackFor(host))
        }

        /**
         * Awaits [latch] to completion however often the thread is interrupted,
         * which is what a real uninterruptible name lookup does. Bounded, so a
         * test that forgets its `finally` fails with a real answer rather than
         * poisoning the rest of the JVM.
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
     * Blocks, bounded, until the shared pool admits a task for an unrelated
     * host, which is the observable event that a parked worker came back.
     *
     * Each attempt uses a FRESH name, so a refusal can only ever mean "the cap
     * is still full" and never "that attempt's own mark is still held". This is
     * the wait G2 needs to know A's backstop has already run before it looks
     * at the name - the backstop runs on A's own thread, inside A's task, so
     * A's slot is the one thing that orders the two.
     */
    private fun awaitUnrelatedAdmission() {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DRAIN_BOUND_MS)
        var attempts = 0
        while (System.nanoTime() < deadline) {
            attempts++
            if (ProbeExecutor.execute("unrelated-$attempts.invalid", Runnable {})) return
        }
        throw AssertionError(
            cardFailure("no unrelated probe was admitted within ${DRAIN_BOUND_MS} ms over $attempts attempts, so the parked worker never came back and the backstop's timing could not be established"),
        )
    }

    /**
     * Waits, bounded, for the shared probe pool to come back.
     *
     * [ProbeExecutor] is a process-wide singleton on a pool of
     * [ProbeExecutor.MAX_WEDGED_PROBES] plus one headroom thread, so a worker
     * still parked on this test's latch turns every LATER test in this JVM into
     * a discarded task and a false "not reachable" - and, once two of them are
     * parked, into a suite that cannot proceed at all. Each drain attempt costs
     * at most one budget while the pool is still busy, so the bound is generous
     * for the several attempts it can take and still fails rather than hanging
     * when a worker never comes back.
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
            cardFailure("the shared probe worker was still busy ${DRAIN_BOUND_MS} ms after this test released its latch, over $attempts attempts - the one pool in [ProbeExecutor] serves every probe in this JVM, so a worker left parked past this test answers false for everything that follows it"),
        )
    }

    private companion object {
        /**
         * The default locale G3 installs for its own duration.
         *
         * Turkish because it is the locale whose case rules actually differ:
         * its "I" lowercases to a dotless "ı" while every other locale's maps
         * to a dotted "i". That single difference is what splits one DNS name
         * into two in-flight keys, so it is the one locale worth testing under.
         */
        val TURKISH: Locale = Locale("tr", "TR")

        /**
         * The spelling whose lookup G3 parks: dotted "I" "I" "S", which is
         * exactly the text a dotless-"ı" fold mangles.
         */
        const val PARKED_SPELLING = "IIS.local"

        /** The same DNS name, written the other way round. */
        const val OTHER_SPELLING = "iis.local"

        /** Ceiling on a parked lookup if nothing releases it; a safety valve. */
        const val WEDGE_AWAIT_MS = 30_000L

        /**
         * Ceiling on one bounded wait, used for both the park inside the task
         * body and the wait for its answer. Generous enough that a slow CI box
         * cannot turn a correct implementation red, and short enough that a
         * broken one fails instead of hanging the JVM.
         */
        const val PUBLISH_AWAIT_MS = 10_000L

        /** Ceiling on the drain, generous because each attempt can cost one budget. */
        const val DRAIN_BOUND_MS = 10_000L
    }
}