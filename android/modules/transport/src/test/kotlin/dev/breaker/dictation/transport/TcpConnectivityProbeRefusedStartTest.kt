package dev.breaker.dictation.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * That a probe REFUSED before it ever ran leaves nothing behind.
 *
 * [ProbeExecutor] answers a task that has nowhere to go with a single `false`,
 * which the caller turns into "not reachable". That refusal has two
 * consequences outside the pool, and this file pins both:
 *
 *  - **the name's in-flight mark.** [ProbeExecutor.execute]'s keyed overload
 *    marks the host before it submits, because a name that is already being
 *    looked up must not be looked up again. A submit that is then REFUSED - the
 *    cap is full, so the task body never runs and no wrapper `finally` will
 *    ever clear that mark - has to unmark the host itself. A mark that outlives
 *    a refused start is invisible and permanent: nothing about the host is
 *    still parked, yet every later probe of it is refused by the single-flight
 *    check, answers "not reachable" without a dial, and is indistinguishable
 *    from a server that is down. That is the false the domain turns into a
 *    permanent on-device routing decision, and there is no way back short of a
 *    process restart. See `T1`.
 *
 *  - **the answer cache.** A refusal is not a measurement: no connection was
 *    attempted, so there is nothing that was learned. Storing it is the same
 *    class of error, one layer up - the false is then served for the whole
 *    [TcpConnectivityProbe.CACHE_TTL_MS] window, and the first question asked
 *    once the pool can serve the host again is answered out of that cache
 *    instead of dialling a server that has just come back. See `T2`.
 *
 * **The clock is never moved to make a cache assertion pass.** Neither test
 * advances [FakeClock] between a refused probe and the question that must
 * dial: a stale-looking entry and a fresh-looking one are the same entry from
 * the caller's side, and the whole question is whether an entry that recorded
 * no dial is there at all. A refusal is UNDIALED - it learned nothing, so it
 * stores nothing - and there is no entry to expire, so a jump would not make
 * either question more real. It would only hide a mutant that cached one: the
 * poll after a release would then expire that entry on its first attempt and
 * look like a clean recovery.
 *
 * **Every test here wedges a process-wide singleton, so every one of them
 * cleans up in a `finally`: every latch is released and then the test waits,
 * bounded, until a known-good probe answers true.** A worker still parked on a
 * latch released only by this test's exit turns every LATER test in this JVM
 * into a refused task and a false "not reachable", and the suite goes flaky in
 * whichever order JUnit happens to pick. The drain uses a FRESH probe with a
 * FRESH clock on a host nothing has cached, so a `true` can only have come
 * from a dial that really ran on the shared worker.
 *
 * **On the one observation in [T2].** A refusal taken at the cap never reaches
 * a worker, so unlike a task still parked in a resolver there is nothing for it
 * to unwind: [ProbeExecutor.execute] removes the mark it added, on the calling
 * thread, before it hands back its `false`. That is why [T2] can ask its
 * question exactly once. It first makes both of its preconditions facts rather
 * than hopes - the pool is provably at the cap because it AWAITED each parked
 * lookup arriving inside the resolver, and a slot is provably back because
 * [drainProbeExecutor] got a real dial - and only then asks. One question, no
 * poll loop, no pause, and nothing a later attempt could have overwritten: an
 * entry that recorded no dial is served instantly and a host with nothing
 * cached really dials, and those two cannot be confused with each other.
 *
 * No real DNS and no real sockets: the resolver is a fake that parks, the
 * connector is the shared [RecordingConnector], and every wait is a bounded
 * latch await or a bounded poll.
 */
class TcpConnectivityProbeRefusedStartTest {

    // --- T1: a refused start must not leave the host marked in flight -----------

    @Test
    fun `a host refused at the cap is dialled on a later probe once the slots are free`() {
        // One wedged host per slot the pool allows, so the cap really is full
        // and the third name below is refused for that reason and no other.
        val wedgedHosts = List(ProbeExecutor.MAX_WEDGED_PROBES) { "refused-start-wedged-$it.invalid" }
        val latches = ParkingResolver().apply { wedgedHosts.forEach { park(it) } }
        val connector = RecordingConnector(script = listOf(ProbeOutcome.CONNECTED))
        val clock = FakeClock(1_000L)
        var address = "https://$REFUSED_HOST:8443"
        val probe = TcpConnectivityProbe({ address }, clock, latches.asResolver(), connector)

        var dialsWhileRefused = -1
        var refusedElapsedMs = -1L
        var recovered = false
        var attempts = 0
        try {
            for (wedged in wedgedHosts) {
                address = "https://$wedged:8443"
                assertFalse(
                    cardFailure("the resolution of $wedged is parked on a latch this test does not release, so its probe must be released by the ${TcpConnectivityProbe.CONNECT_TIMEOUT_MS} ms budget and answer not reachable - a true here means the caller waited on the lookup instead of bounding it"),
                    probe.isServerReachable(),
                )
            }

            // The name the cap refuses. Nothing about it is wrong and nothing
            // about it has been looked up, so the only thing that can stop it is
            // the cap - and a stopped task must not leave it marked.
            address = "https://$REFUSED_HOST:8443"
            val startedAt = System.nanoTime()
            val refusedAnswer = probe.isServerReachable()
            refusedElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            dialsWhileRefused = connector.hosts.count { it == REFUSED_HOST }
            assertFalse(
                cardFailure("every slot is held by a lookup that has not returned, so no task for $REFUSED_HOST can run and its probe must answer not reachable - a true here means the answer came from somewhere other than the dial it never made"),
                refusedAnswer,
            )
            assertTrue(
                cardFailure("a task with nowhere to go must be refused AT ONCE: this probe waited ${refusedElapsedMs} ms against a ${TcpConnectivityProbe.CONNECT_TIMEOUT_MS} ms budget, so a caller was held rather than answered"),
                refusedElapsedMs < RETURN_BOUND_MS,
            )

            latches.releaseAll()
            drainProbeExecutor()

            // The question the card has to answer: a refusal is a moment, not a
            // verdict on the name. Once the slots are back, this host must be
            // probed and dialled like any other. The clock does NOT move: the
            // refusal was UNDIALED, so it cached nothing and there is no entry
            // for a jump to expire. A jump would only hide a mutant that
            // cached it, by expiring that entry on the poll's first attempt and
            // making the recovery look clean.
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RECOVERY_BOUND_MS)
            while (System.nanoTime() < deadline) {
                attempts++
                if (probe.isServerReachable()) {
                    recovered = true
                    break
                }
            }
        } finally {
            latches.releaseAll()
            drainProbeExecutor()
        }

        assertEquals(
            cardFailure("a task refused at the cap never runs, so no dial to $REFUSED_HOST may have been recorded while it was refused; $dialsWhileRefused were. A refused probe that dialled anyway is not refused"),
            0,
            dialsWhileRefused,
        )
        assertTrue(
            cardFailure("the slots are free again and $REFUSED_HOST has a dial scripted CONNECTED, yet it was still answered not reachable after $RECOVERY_BOUND_MS ms and $attempts probes - a host whose start was REFUSED has nothing still parked against it, and a name that stays marked after a refused submit is never probed again for the life of the process, so every answer about it looks like a legitimate \"not reachable\""),
            recovered,
        )
        assertTrue(
            cardFailure("the recovery must come from a DIAL that ran after the slots came back, and not from any answer that was merely re-served: no dial to $REFUSED_HOST was recorded at all over $attempts probes, so the true above cannot have come from one. An answer that recorded no connection attempt is not a measurement, and serving one for ${TcpConnectivityProbe.CACHE_TTL_MS} ms would hide a server that has just come back from the very question meant to find it. Recorded dials: ${connector.hosts}"),
            connector.hosts.count { it == REFUSED_HOST } >= 1,
        )
    }

    // --- T2: a refusal that dialled nothing must not be cached ------------------

    /**
     * A cap refusal taken on a host whose answer cache starts empty, and the
     * one question that proves nothing was left in it.
     *
     * Every precondition is made a fact before the single observation, and the
     * reason that is possible at all is a property of the refusal path rather
     * than of anything waited for:
     *
     *  - **The cap is full, and that is CONFIRMED rather than assumed.** Each
     *    wedged host is probed and its lookup is then confirmed to have
     *    REACHED the resolver, on a latch the resolver trips on its way in.
     *    A probe of a host whose resolution had not been entered returns at
     *    once with a dial; one that parks cannot. So two parked lookups against
     *    a cap of two means the next host really has nowhere to go.
     *
     *  - **The refused host is not in flight when the refusal returns.**
     *    [ProbeExecutor.execute] hands the slot straight back and removes the
     *    in-flight mark ON THE CALLING THREAD before it returns `false`. A cap
     *    refusal therefore leaves nothing to unwind, no worker to wait for and
     *    no window in which the question below could be refused by a stale
     *    mark. There is no race here to lose, which is why this test asks
     *    exactly once instead of polling for a favourable moment.
     *
     * The only wait is [drainProbeExecutor], and it waits for the two wedged
     * slots this test itself took, not for anything the assertion turns on. A
     * question that answers `false` here has exactly one possible cause: the
     * refusal above stored an entry, and it stored it without dialling.
     */
    @Test
    fun `a refresh refused without a dial leaves no answer cached in its place`() {
        val resolver = AwaitedParkingResolver()
        val wedgedHosts = List(ProbeExecutor.MAX_WEDGED_PROBES) { "undialed-wedged-$it.invalid" }
        val connector = RecordingConnector(script = listOf(ProbeOutcome.CONNECTED))
        val clock = FakeClock(1_000L)
        var address = ""
        val probe = TcpConnectivityProbe({ address }, clock, resolver.asResolver(), connector)

        var dialsAfterRefresh = -1
        var refusedElapsedMs = -1L
        var normalAnswer = true
        try {
            // One slot per wedged lookup, and each one PROVEN to hold its slot
            // for the rest of this test by being parked inside the resolver.
            for (wedged in wedgedHosts) {
                resolver.park(wedged)
                address = "https://$wedged:8443"
                assertFalse(
                    cardFailure("the resolution of $wedged is parked on a latch this test does not release, so its probe must be released by the ${TcpConnectivityProbe.CONNECT_TIMEOUT_MS} ms budget and answer not reachable - a true here means the lookup was never actually wedged"),
                    probe.isServerReachable(),
                )
                assertTrue(
                    cardFailure("the probe of $wedged answered not reachable without dialling it, which is only possible if its lookup reached the resolver and parked there; the latch that resolver trips on its way in never did, so a full cap cannot be claimed below and the refresh after it would not be a refusal at all"),
                    resolver.awaitEntered(wedged, ENTERED_BOUND_MS),
                )
            }

            // The cap is full and this name has never been looked up, so the
            // only thing that can stop it is the cap - and being stopped is
            // UNDIALED: no task ran, so no lookup, no dial, nothing learned.
            address = "https://$REFUSED_HOST:8443"
            val startedAt = System.nanoTime()
            assertFalse(
                cardFailure("every slot is held by a lookup that has not returned, so no task for $REFUSED_HOST can run and this refresh must answer not reachable - a true here means the answer came from somewhere other than the dial it never made"),
                probe.refresh(),
            )
            refusedElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            assertTrue(
                cardFailure("a task with nowhere to go is refused AT ONCE: this refresh waited $refusedElapsedMs ms against a ${TcpConnectivityProbe.CONNECT_TIMEOUT_MS} ms budget, so a caller was held rather than answered. A refresh that spent the budget was not refused at the cap, and the cache assertion below would then be about an expired lookup rather than about a refused start"),
                refusedElapsedMs < RETURN_BOUND_MS,
            )
            dialsAfterRefresh = connector.hosts.count { it == REFUSED_HOST }
            assertEquals(
                cardFailure("a refresh refused before it ran must not have dialled anything; $dialsAfterRefresh dials to $REFUSED_HOST were recorded. The question below is only meaningful if the refusal really was undialed"),
                0,
                dialsAfterRefresh,
            )

            resolver.releaseAll()
            drainProbeExecutor()

            // THE observation, asked once. Two facts make its answer
            // unambiguous and neither of them can be raced: the cap is empty
            // again, so this host can be given a worker, and the refusal above
            // left no mark in flight, so nothing can refuse this question but
            // its own cache. A `false` here therefore means exactly one thing.
            normalAnswer = probe.isServerReachable()
        } finally {
            resolver.releaseAll()
            drainProbeExecutor()
        }

        assertTrue(
            cardFailure("a refresh that was refused without a dial cached a not-reachable answer for $REFUSED_HOST, so the first question asked once the cap came back was answered out of that cache instead of dialling. An answer that recorded no connection attempt is not a measurement, and serving it for ${TcpConnectivityProbe.CACHE_TTL_MS} ms hides a server that has just come back from the very question meant to find it. Recorded dials: ${connector.hosts}"),
            connector.hosts.count { it == REFUSED_HOST } >= 1,
        )
        assertTrue(
            cardFailure("$REFUSED_HOST's dial is scripted CONNECTED and the cap is empty again, so this question must answer from a dial that really ran - it answered not reachable. A refusal is a moment, not a verdict on the name: nothing about $REFUSED_HOST is still parked and nothing is left in flight to refuse it, so the only thing that can answer it is an entry that recorded no dial"),
            normalAnswer,
        )
    }

    // --- shared plumbing ---------------------------------------------------------

    /**
     * Waits, bounded, for the shared probe pool to come back.
     *
     * [ProbeExecutor] is a process-wide singleton, so a worker still parked on a
     * latch this test released turns every LATER test in this JVM into a refused
     * task and a false "not reachable". Failing loudly here beats letting the
     * next test fail for a reason that has nothing to do with what it is
     * testing. Each attempt costs at most one budget while the pool is still
     * busy, so the bound is generous for the several attempts it can take and
     * still fails rather than hanging when the pool never comes back.
     */
    private fun drainProbeExecutor() {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DRAIN_BOUND_MS)
        var attempts = 0
        while (System.nanoTime() < deadline) {
            attempts++
            val drain = TcpConnectivityProbe(
                { "https://refused-start-drain-$attempts.invalid" },
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
     * A resolver that parks a lookup AND reports when the lookup ARRIVED.
     *
     * [ParkingResolver] can only be asked "is it still parked", which is a
     * question about a moment in time. [T2] needs the stronger statement: the
     * lookup is parked **right now**, on this thread's clock, because the test
     * has already been told it got there. So this one trips a latch as the
     * lookup enters, which turns "the cap is full" from an assumption about
     * worker scheduling into a fact the test has observed.
     *
     * A refusal at the cap depends entirely on how many slots are held. Without
     * that latch the test can only hope the two lookups reached the resolver
     * before the third probe was submitted; if either were still queued, the
     * refusal would not have happened at all and the assertion after it would
     * be measuring something else. It is used only where a cap refusal is the
     * subject; [ParkingResolver] stays the one [T1] uses, unchanged.
     *
     * A parked lookup ignores the interrupt exactly as [ParkingResolver]'s
     * does - an `InetAddress` lookup cannot be stopped - and only then can the
     * caller observe, bounded, that a name is genuinely still in flight.
     */
    private class AwaitedParkingResolver {
        private val parked = ConcurrentHashMap<String, CountDownLatch>()
        private val entered = ConcurrentHashMap<String, CountDownLatch>()

        /** Parks [host]'s lookup, and arms the latch that reports its arrival. */
        fun park(host: String) {
            parked.putIfAbsent(host, CountDownLatch(1))
            entered.putIfAbsent(host, CountDownLatch(1))
        }

        /**
         * Blocks, bounded, until [host]'s lookup is really inside the resolver.
         *
         * False means the lookup never arrived within [boundMillis], and the
         * caller must not treat the cap as full: it failed loudly for exactly
         * that reason rather than letting a later assertion blame the cache.
         */
        fun awaitEntered(host: String, boundMillis: Long): Boolean =
            entered[host]?.await(boundMillis, TimeUnit.MILLISECONDS) ?: false

        fun releaseAll() {
            parked.values.forEach { it.countDown() }
        }

        /** The resolver itself: parks the scripted hosts, answers the rest. */
        fun asResolver(): HostResolver = HostResolver { host ->
            entered[host]?.countDown()
            parked[host]?.let { latch -> awaitIgnoringInterrupts(latch) }
            listOf(FakeHostResolver.loopbackFor(host))
        }

        /**
         * Awaits [latch] to completion however often the thread is interrupted.
         *
         * The same reason [ParkingResolver] does this, and the same reason it
         * cannot be shared: that one is private to it, and duplicating four
         * lines beats giving a shared helper a second caller whose failure
         * modes are not the ones its own documentation describes. The interrupt
         * status is restored afterwards so the park is not silently swallowing
         * a shutdown request, and the wait is bounded so a test that forgets
         * its `finally` fails with a real answer rather than poisoning the
         * rest of the JVM.
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
     * A resolver whose hosts can be made to hang, standing in for a name lookup
     * the platform has given up on handing back.
     *
     * A real `InetAddress.getAllByName` cannot be interrupted: the JDK hands the
     * name to the OS resolver and waits on it, so a lookup against a blackholed
     * resolver, a captive portal or a VPN mid-handshake parks that thread until
     * the platform gives up on its own. So this double does what the real thing
     * does - it **ignores the interrupt** the budget's `task.cancel(true)` sends
     * and keeps waiting. A double that honoured the interrupt would let the
     * worker free itself and the defects under test would not reproduce at all.
     *
     * Once released, a host resolves to a loopback address carrying its name,
     * so the connector is offered a real (never dialled) address and a scripted
     * CONNECTED answer means a genuine success path.
     */
    private class ParkingResolver {
        private val latches = ConcurrentHashMap<String, CountDownLatch>()

        /** Parks [host] until [release] is called, or until the await times out. */
        fun park(host: String) {
            latches.putIfAbsent(host, CountDownLatch(1))
        }

        /** Releases [host], so its next lookup answers instead of parking. */
        fun release(host: String) {
            latches[host]?.countDown()
        }

        fun releaseAll() {
            latches.values.forEach { it.countDown() }
        }

        /** The resolver itself: parks the scripted hosts, answers the rest. */
        fun asResolver(): HostResolver = HostResolver { host ->
            latches[host]?.let { latch -> awaitIgnoringInterrupts(latch) }
            listOf(FakeHostResolver.loopbackFor(host))
        }

        /**
         * Awaits [latch] to completion however often the thread is interrupted.
         *
         * An `await` throws [InterruptedException] and CLEARS the interrupt
         * status, so swallowing it and awaiting again is what "interrupts do not
         * stop this lookup" means in code. The status is restored afterwards so
         * the park is not silently swallowing a shutdown request either, and the
         * wait is bounded so a test that forgets its `finally` fails with a real
         * answer rather than poisoning the rest of the JVM.
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
        /**
         * Documentation address refused at the cap, with nothing wrong with it.
         *
         * Shared by both tests on purpose: each builds its OWN probe over a
         * fresh clock, and neither reaches this name while the other is
         * running, so the two can never answer each other's question out of a
         * cache or a single-flight mark.
         */
        const val REFUSED_HOST = "refused-start-refused.invalid"

        /** Ceiling on a parked lookup if nothing releases it; a safety valve. */
        const val WEDGE_AWAIT_MS = 30_000L

        /** Ceiling on a refused caller. Half the budget: a refusal costs a CAS. */
        const val RETURN_BOUND_MS = 750L

        /** Ceiling on a bounded recovery poll; generous for several budgets. */
        const val RECOVERY_BOUND_MS = 10_000L

        /**
         * Ceiling on confirming a wedged lookup really reached the resolver.
         *
         * A lookup that has been submitted is on its way to the resolver within
         * microseconds, so this is not a wait for work - it is a bound on how
         * long the test will claim to believe its own premise. Half the budget:
         * generous for a hand-off, and short enough that a lookup which never
         * arrives fails as "the cap is not full" rather than as a cache
         * accusation several assertions later.
         */
        const val ENTERED_BOUND_MS = 750L

        /** Ceiling on the drain, generous because each attempt can cost one budget. */
        const val DRAIN_BOUND_MS = 10_000L
    }
}
