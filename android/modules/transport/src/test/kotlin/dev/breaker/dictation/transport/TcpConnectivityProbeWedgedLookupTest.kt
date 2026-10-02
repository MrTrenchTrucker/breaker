package dev.breaker.dictation.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * What a hung name resolution does to every OTHER probe in the process.
 *
 * The promise under test is the one [ProbeExecutor] writes down: a wedged
 * lookup must be COUNTED AND REPLACED, never waited on, and the damage one hung
 * name can do must be bounded by [ProbeExecutor.MAX_WEDGED_PROBES] rather than
 * by whatever the platform feels like doing about the resolver. A resolution
 * that never returns is not a property of one address: an interrupt does not
 * stop a name lookup, so the worker inside it stays occupied, and the pool that
 * serves every probe in this JVM is what keeps that from becoming every
 * address's problem. From that moment a healthy host's probe can be answered by
 * a failed dial, or by no dial at all - and the domain routes every dictation
 * on-device, with no way back short of a process restart.
 *
 * **The design these tests describe.** [ProbeExecutor] is a process-wide
 * daemon worker pool, a slot at a time, with a bounded count of
 * [ProbeExecutor.MAX_WEDGED_PROBES] task bodies in flight. Three things follow
 * from that, and all three are exercised below: a wedged lookup is COUNTED so a
 * spare worker can serve a different address and the count comes back down on
 * the wedged worker's OWN thread when its lookup returns; a task with nowhere
 * to go is REFUSED and answered "not reachable" at once rather than queued
 * behind a wedged task, because there is no queue - a queued task is a task
 * nobody is going to run; and a later probe of a name already being looked up
 * starts no second lookup and takes no second slot.
 *
 * **The honest remaining limits, which no test here claims away.** Two
 * DIFFERENT hung names still fill the cap, and from there every further probe is
 * refused; a network that wedges two names at once is still described as
 * comprehensively down. And a re-probe of a PARKED host is answered not
 * reachable with no evidence any dial was attempted, so for as long as that
 * lookup is parked a host that is merely slow is indistinguishable from one
 * that is permanently wedged. That is the deliberate price of not spending a
 * second slot on it, and the alternative is a cap overrun that silences healthy
 * addresses too.
 *
 * So these tests assert through the PUBLIC behaviour only: what
 * `isServerReachable()` answers for a host that is fine, and how long the
 * caller was kept waiting. Nothing reaches into the executor, and nothing here
 * can tell a real DNS failure from a refusal - the assertion is always made
 * about a host whose dial is scripted CONNECTED, so a `true` is only possible
 * if a dial actually ran. No real DNS and no real sockets: the resolver is a
 * fake that blocks, and the connector is the shared [RecordingConnector].
 *
 * **Every test here wedges a process-wide singleton, so every one of them
 * cleans up in a `finally`: release the latch, then wait, bounded, until a
 * known-good probe answers true.** A worker still parked on a latch released
 * only by the test's own exit turns every LATER test in this JVM into a
 * refused task and a false "not reachable", and the suite goes flaky in
 * whichever order JUnit happens to pick. The drain uses a FRESH probe with a
 * FRESH clock on a host nothing has cached, so a `true` can only have come
 * from a dial that really ran on the shared worker.
 *
 * **The cap is read from [ProbeExecutor], never hardcoded**, so these tests
 * track [ProbeExecutor.MAX_WEDGED_PROBES] instead of pinning a second,
 * quietly-drifting copy of it.
 */
class TcpConnectivityProbeWedgedLookupTest {

    // --- T1: a wedged host must not answer for a healthy one ----------------------

    @Test
    fun `a host whose lookup never returns does not stop a healthy host from being probed`() {
        val latches = WedgeResolver().apply { wedge(WEDGED_HOST) }
        val connector = RecordingConnector(script = listOf(ProbeOutcome.CONNECTED))
        var address = "https://$WEDGED_HOST:8443"
        val probe = TcpConnectivityProbe({ address }, FakeClock(1_000L), latches.asResolver(), connector)

        var secondAnswer = true
        var secondElapsedMs = 0L
        try {
            assertFalse(
                cardFailure("the resolution of $WEDGED_HOST is parked on a latch this test does not release, so the resolve-and-connect pair cannot finish inside the ${TcpConnectivityProbe.CONNECT_TIMEOUT_MS} ms budget and the caller must be released by the budget and answer not reachable - a true here means the caller waited on the lookup instead of bounding it"),
                probe.isServerReachable(),
            )
            assertTrue(
                cardFailure("the wedged lookup must still be parked when the first caller was released, otherwise the worker was never occupied and this test proves nothing about a wedged worker"),
                latches.parked(WEDGED_HOST),
            )

            // The same provider, re-pointed. Nothing about the operator's edit
            // changes the worker: this is a fresh question about a different
            // address, and the cache is keyed on the address.
            address = "https://$HEALTHY_HOST:8443"
            val startedAt = System.nanoTime()
            secondAnswer = probe.isServerReachable()
            secondElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        } finally {
            latches.releaseAll()
            drainProbeExecutor()
        }

        assertTrue(
            cardFailure("a lookup that never returns is a property of $WEDGED_HOST alone, so it cannot make $HEALTHY_HOST answer not reachable - but that host's dial is scripted CONNECTED, and it was never even made: the shared worker still held by the wedged lookup had nowhere to put a second task, so the healthy probe was queued or discarded without a single connection attempt. Recorded dials: ${connector.hosts}. Answered after ${secondElapsedMs} ms. the domain reads this false as \"the server is down\" and routes every dictation on-device, permanently"),
            secondAnswer,
        )
    }

    // --- T2: a released lookup must not leave its host permanently broken ---------

    @Test
    fun `a lookup that is released answers reachable again without a restart`() {
        val latches = WedgeResolver().apply { wedge(WEDGED_HOST) }
        val connector = RecordingConnector(script = listOf(ProbeOutcome.CONNECTED))
        val clock = FakeClock(1_000L)
        val probe = TcpConnectivityProbe({ "https://$WEDGED_HOST:8443" }, clock, latches.asResolver(), connector)

        var recovered = false
        try {
            assertFalse(
                cardFailure("with the resolution parked there is nothing to answer, so the first question must be not reachable - a true here means the lookup was never actually wedged"),
                probe.isServerReachable(),
            )

            latches.release(WEDGED_HOST)

            // Bounded poll rather than a single retry: the worker may still be
            // finishing the wedged attempt when the latch is released, so the
            // first poll can legitimately race it, and it is UNDIALED - not
            // run, not dialled, cached nothing - while this host's own lookup
            // is still marked in flight.
            // The clock is NOT advanced here, and that is the point rather than
            // an omission. The attempt above ended at the budget with the lookup
            // parked, so it never obtained an address and never dialled: it is
            // UNANSWERED, and UNANSWERED caches NOTHING (the attempt learned nothing
            // about the server). There is therefore no cached false for the window
            // to expire, and moving the clock past [TcpConnectivityProbe.CACHE_TTL_MS]
            // on every attempt would only hide a regression that did cache one: it makes a cache hit
            // impossible, which is exactly what a wrongly-cached false needs to
            // stay unobserved. Leaving the clock still keeps the poll measuring
            // the recovery.
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RECOVERY_BOUND_MS)
            var attempts = 0
            while (System.nanoTime() < deadline) {
                attempts++
                if (probe.isServerReachable()) {
                    recovered = true
                    break
                }
            }
            if (!recovered) {
                throw AssertionError(
                    cardFailure("the lookup was released and the dial is scripted CONNECTED, yet $WEDGED_HOST was still answered not reachable after $RECOVERY_BOUND_MS ms and $attempts probes - a name resolution that returns eventually must leave the probe answering from what it just measured, and not from a worker still stuck on the lookup that preceded it"),
                )
            }
        } finally {
            latches.releaseAll()
            drainProbeExecutor()
        }

        assertTrue(
            cardFailure("the released lookup must answer reachable on a later probe, without the caller having to restart anything"),
            recovered,
        )
    }

    // --- T3: exhaustion must be fast, contained, and reversible -------------------

    @Test
    fun `probes past the worker and its queue answer not reachable at once and the worker comes back`() {
        val wedgedHosts = listOf(WEDGED_HOST, WEDGED_HOST_TWO) +
            (2 until ProbeExecutor.MAX_WEDGED_PROBES).map { index -> "wedged-extra-$index.invalid" }
        val latches = WedgeResolver().apply { wedgedHosts.forEach { wedge(it) } }
        val clock = FakeClock(1_000L)
        val connector = RecordingConnector(script = listOf(ProbeOutcome.CONNECTED))
        var address = "https://$WEDGED_HOST:8443"
        val probe = TcpConnectivityProbe({ address }, clock, latches.asResolver(), connector)

        // Enough wedged hosts to fill [ProbeExecutor.MAX_WEDGED_PROBES] and no
        // more, so the next probe has nowhere to go. The cap is read from
        // production so a change to it is tracked here rather than pinned a
        // second time; every entry is released in the finally.
        var exhaustedAnswer = true
        var exhaustedElapsedMs = -1L
        var escaped: Throwable? = null
        var recovered = false
        try {
            assertFalse(
                cardFailure("the first wedged lookup occupies the single probe thread, so its probe must answer not reachable at the budget"),
                probe.isServerReachable(),
            )
            address = "https://$WEDGED_HOST_TWO:8443"
            assertFalse(
                cardFailure("the second wedged lookup fills the single queue slot behind the first, so its probe must answer not reachable at the budget too - it is queued, not dialled"),
                probe.isServerReachable(),
            )
            assertTrue(
                cardFailure("both wedged lookups must still be parked, otherwise the worker was not saturated and nothing below is testing exhaustion"),
                latches.parked(WEDGED_HOST) && latches.parked(WEDGED_HOST_TWO),
            )

            // The question the card has to answer: an exhausted worker is a
            // degraded network, not a broken probe. It must answer the SAFE value
            // promptly, and it must not throw at the caller.
            address = "https://$EXHAUSTED_HOST:8443"
            val startedAt = System.nanoTime()
            try {
                exhaustedAnswer = probe.isServerReachable()
            } catch (thrown: Throwable) {
                escaped = thrown
            }
            exhaustedElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            assertTrue(
                cardFailure("a refused probe must answer, not throw: the caller is the dictation path and has no handler for a failure it cannot see through a boolean. Escaped: $escaped"),
                escaped == null,
            )
            assertFalse(
                cardFailure("a probe with nowhere to run cannot reach a server, so it must answer not reachable - a true here would mean the answer is coming from somewhere other than the dial it never made"),
                exhaustedAnswer,
            )
            assertTrue(
                cardFailure("a saturated worker must be reported at once: this probe waited ${exhaustedElapsedMs} ms, and the class exists so a caller is never held longer than the ${TcpConnectivityProbe.CONNECT_TIMEOUT_MS} ms budget. A refused task is the answer the card promises; a caller stuck behind a wedged lookup is the bug"),
                exhaustedElapsedMs < RETURN_BOUND_MS,
            )

            // Releasing one parked lookup does not by itself hand the whole cap
            // back: the count comes down when THAT worker's lookup returns, so
            // a slot is only free once its own name has been released, so the
            // recovery arm releases every wedged name.
            latches.release(WEDGED_HOST)
            address = "https://$WEDGED_HOST_TWO:8443"
            latches.release(WEDGED_HOST_TWO)
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RECOVERY_BOUND_MS)
            var attempts = 0
            while (System.nanoTime() < deadline) {
                attempts++
                clock.instant += TcpConnectivityProbe.CACHE_TTL_MS + 1L
                if (probe.isServerReachable()) {
                    recovered = true
                    break
                }
            }
            if (!recovered) {
                throw AssertionError(
                    cardFailure("both wedged lookups were released and the dial is scripted CONNECTED, yet $WEDGED_HOST_TWO was still answered not reachable after $RECOVERY_BOUND_MS ms and $attempts probes - a saturated worker that only comes back on a process restart is a permanent on-device routing decision for the operator, with no way out but reinstalling the app"),
                )
            }
        } finally {
            latches.releaseAll()
            drainProbeExecutor()
        }

        assertTrue(
            cardFailure("once every parked lookup is released the saturated worker must hand the singleton back, so a later probe of a released host answers reachable from a dial that really ran"),
            recovered,
        )
    }

    // --- T4: neither an unanswered nor an undialled attempt may cache its false --

    /**
     * That a false learned from nothing is not left behind
     *
     * "Not reachable" is one word for three facts, and only one of them is a
     * measurement. A task refused before it ran - because [ProbeExecutor] had
     * nowhere to put it, or because this host's own lookup is still parked -
     * obtained no address and dialled nothing: it is UNDIALED. A task whose
     * budget ran out while its lookup was still parked learned nothing for the
     * same reason: it is UNANSWERED. Caching either serves it for the whole
     * [TcpConnectivityProbe.CACHE_TTL_MS] window, and the first question asked
     * once the host is genuinely there again is answered out of that cache
     * instead of by a dial: the caller sees a server that has come back as
     * still down, and the domain routes every dictation on-device for a whole
     * window.
     *
     * So this is ONE sequence through both, and it is the only observation that
     * can tell them apart, because both are the same host and both are answered
     * "not reachable": the lookup is parked and the first question is answered
     * false AT THE BUDGET (UNANSWERED); while it is STILL parked a
     * [TcpConnectivityProbe.refresh] - which never serves a cached answer, so
     * it can hide nothing - is answered false by the single-flight refusal and
     * must start no second lookup (UNDIALED); the latch is released; and with
     * the clock untouched a released host must still DIAL.
     *
     * **The clock is deliberately NOT moved, and this is the whole point
     * rather than an omission.** A cached entry whose age is inside
     * `0 until CACHE_TTL_MS` is FRESH and WILL be served, so holding the clock
     * still is precisely what makes a wrongly-cached false observable.
     * Advancing it by [TcpConnectivityProbe.CACHE_TTL_MS] or more - the "be
     * safe against the cache" instinct - would make a cache hit impossible, and
     * a wrongly-cached false needs exactly that to stay unobserved: both
     * observations here vanish behind such a jump, step 1's false under an
     * implementation that caches UNANSWERED and step 2's under one caching
     * UNDIALED.
     *
     * The poll is bounded in wall-clock time by [RECOVERY_BOUND_MS] instead,
     * and that is enough: a released lookup resolves on its next poll, and a
     * poll that loses the race to the still-parked lookup is itself UNDIALED.
     */
    @Test
    fun `a false answered at the budget for a parked lookup is not cached and a released host still dials`() {
        val latches = WedgeResolver().apply { wedge(WEDGED_HOST) }
        val connector = RecordingConnector(script = listOf(ProbeOutcome.CONNECTED))
        val clock = FakeClock(1_000L)
        val probe = TcpConnectivityProbe({ "https://$WEDGED_HOST:8443" }, clock, latches.asResolver(), connector)

        var refreshElapsedMs = -1L
        var recovered = false
        try {
            // Step 1: the first question, answered at the budget.
            assertFalse(
                cardFailure("with the resolution parked there is nothing to answer, so the first question must be not reachable - a true here means the lookup was never actually wedged"),
                probe.isServerReachable(),
            )
            assertTrue(
                cardFailure("the lookup must still be parked when the first caller was released at the budget, otherwise that false was measured rather than merely unanswered and this test would prove nothing about the budget path"),
                latches.parked(WEDGED_HOST),
            )

            // Step 2: the same host, still parked, asked again by a caller that
            // ignores the cache entirely - so its false can only have come
            // from the single-flight refusal, and it is UNDIALED.
            val startedAt = System.nanoTime()
            assertFalse(
                cardFailure("the lookup of $WEDGED_HOST is still parked, so a refresh of it has nothing to answer and must say not reachable - a true here means the answer came from a connection attempt against a name known to be stuck"),
                probe.refresh(),
            )
            refreshElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            assertTrue(
                cardFailure("a refresh of a host whose lookup is already parked must be answered AT ONCE and cost nothing: it took ${refreshElapsedMs} ms against a ${TcpConnectivityProbe.CONNECT_TIMEOUT_MS} ms budget, so a second slot was handed to a second lookup of one wedged name rather than the in-flight lookup answering"),
                refreshElapsedMs < RETURN_BOUND_MS,
            )
            assertEquals(
                cardFailure("a lookup that is already in flight for $WEDGED_HOST must be JOINED, not duplicated: the refresh started a second lookup for a name whose first lookup is still parked, and a second worker is a second slot out of the ${ProbeExecutor.MAX_WEDGED_PROBES} this process allows itself. Lookups of $WEDGED_HOST in total: ${latches.lookupsOf(WEDGED_HOST)}"),
                1,
                latches.lookupsOf(WEDGED_HOST),
            )

            // Step 3: the name comes back.
            latches.release(WEDGED_HOST)

            // Step 4: the clock stays exactly where it is - see this test's
            // KDoc. The poll is bounded the way T2's is, so a lookup that
            // never comes back fails here instead of hanging.
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RECOVERY_BOUND_MS)
            var attempts = 0
            while (System.nanoTime() < deadline) {
                attempts++
                if (probe.isServerReachable()) {
                    recovered = true
                    break
                }
            }
            if (!recovered) {
                throw AssertionError(
                    cardFailure("the lookup was released and the dial is scripted CONNECTED, yet $WEDGED_HOST was still answered not reachable after $RECOVERY_BOUND_MS ms and $attempts probes - with the clock held still inside the cache window, so the only thing that could keep answering false is one of the two steps above having cached a not-reachable answer it never measured: the budget-expired attempt in step 1, or the refused refresh in step 2. A false that was never measured, served for the whole window, is a server that has come back being reported down; recorded dials: ${connector.hosts}"),
                )
            }
        } finally {
            latches.releaseAll()
            drainProbeExecutor()
        }

        assertTrue(
            cardFailure("the released lookup must be re-probed rather than answered from the cache: the host answered false only because its lookup had not returned yet, and it has now, so the dial that really ran is the only honest source of the answer. Recorded dials: ${connector.hosts}"),
            connector.hosts.contains(WEDGED_HOST),
        )
    }

    /**
     * Waits, bounded, for the one shared probe pool to come back.
     *
     * The pool in [ProbeExecutor] is a process-wide singleton, so a worker still
     * parked on a released latch turns every LATER test in this JVM into a
     * refused task and a false "not reachable". Each attempt costs at most one
     * budget while the pool is still busy, so the bound is generous for the
     * several attempts it can take and still fails rather than hanging when the
     * pool never comes back.
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
            cardFailure("the shared probe pool was still busy ${DRAIN_BOUND_MS} ms after this test released its latches, over $attempts attempts - the one pool in [ProbeExecutor] serves every probe in this JVM, so a worker left parked past this test answers false for everything that follows it"),
        )
    }

    /**
     * A resolver whose hosts can be made to hang, standing in for a name lookup
     * the platform has given up on handing back.
     *
     * A real `InetAddress.getAllByName` cannot be interrupted: the JDK hands the
     * name to the OS resolver and waits on it, so a lookup against a blackholed
     * resolver, a captive portal or a VPN mid-handshake parks that thread until
     * the platform gives up on its own. So this double does what the real thing
     * does — it **ignores the interrupt** the budget's `task.cancel(true)` sends
     * and keeps waiting. A double that honoured the interrupt would let the
     * worker free itself and the defects under test would not reproduce at all.
     *
     * Every host asked about is recorded on the way IN, which is what lets a
     * test see that a lookup was STARTED - and therefore see that a lookup
     * refused before it started is absent. Once released, a host resolves to a
     * loopback address carrying its name, so a scripted CONNECTED answer means
     * a genuine success path.
     */
    private class WedgeResolver {
        private val latches = LinkedHashMap<String, CountDownLatch>()
        private val asked = CopyOnWriteArrayList<String>()

        /** Wedges [host] until [release] is called, or until the await times out. */
        fun wedge(host: String): CountDownLatch = latches.getOrPut(host) { CountDownLatch(1) }

        /** True while [host]'s resolution is still inside its await. */
        fun parked(host: String): Boolean = latches[host]?.let { it.count > 0L } ?: false

        /**
         * How many lookups of [host] this resolver was actually asked for.
         *
         * Recorded on the way in, so a lookup that is refused before it is
         * started is visibly absent: the only way to start a second lookup is to
         * reach this resolver at all.
         */
        fun lookupsOf(host: String): Int = asked.count { it == host }

        fun release(host: String) {
            latches[host]?.countDown()
        }

        fun releaseAll() {
            latches.values.forEach { it.countDown() }
        }

        /** The resolver itself: wedges the scripted hosts, answers the rest. */
        fun asResolver(): HostResolver = HostResolver { host ->
            asked.add(host)
            latches[host]?.let { latch ->
                awaitIgnoringInterrupts(latch)
            }
            listOf(FakeHostResolver.loopbackFor(host))
        }

        /**
         * Awaits [latch] to completion however often the thread is interrupted.
         *
         * An `await` throws [InterruptedException] and CLEARS the interrupt
         * status, so swallowing it and awaiting again is what "interrupts do not
         * stop this lookup" means in code. The status is restored afterwards so
         * the park is not silently swallowing a shutdown request either, and
         * the wait is bounded so a test that forgets its `finally` fails with a
         * real answer rather than poisoning the rest of the JVM.
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

    private companion object {
        /** Documentation address whose resolution is wedged. */
        const val WEDGED_HOST = "wedged.invalid"

        /** A second documentation address, wedged to fill the queue slot. */
        const val WEDGED_HOST_TWO = "wedged-two.invalid"

        /** Documentation address with nothing wrong with it at all. */
        const val HEALTHY_HOST = "healthy.invalid"

        /** Documentation address probed once the worker has nowhere to put a task. */
        const val EXHAUSTED_HOST = "exhausted.invalid"

        /** Ceiling on a wedged lookup if nothing releases it; a safety valve. */
        const val WEDGE_AWAIT_MS = 30_000L

        /** Ceiling on what an exhausted or recovering caller may be held. */
        const val RETURN_BOUND_MS = 3_000L

        /** Ceiling on a bounded recovery poll; generous for several budgets. */
        const val RECOVERY_BOUND_MS = 10_000L

        /** Ceiling on the drain, generous because each attempt can cost one budget. */
        const val DRAIN_BOUND_MS = 10_000L
    }
}
