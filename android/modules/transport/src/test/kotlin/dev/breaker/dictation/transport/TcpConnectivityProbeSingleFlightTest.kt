package dev.breaker.dictation.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * That ONE wedged lookup may occupy ONE slot, however often its host is asked again.
 *
 * [ProbeExecutor] bounds how many task bodies the process ever runs at once -
 * [ProbeExecutor.MAX_WEDGED_PROBES], two - and that bound is what keeps a hung
 * name resolution from becoming an unbounded pile of threads. But a bound with
 * no *sharing* is not the same promise: two probes of the SAME host, seconds
 * apart, each start their own lookup, and two parked lookups for one
 * unreachable name consume the whole cap. From there nothing else in the
 * process can be probed: a perfectly healthy server is answered "not reachable"
 * without a dial, and the domain routes every dictation on-device with no way
 * back short of a restart.
 *
 * So the missing piece is per-host single-flight: while a lookup for a host is
 * in flight, a later probe of that host must not start a second one. These
 * tests pin it from the outside, through the public answer only.
 *
 * **Every probe of a parked host below moves the fake clock past
 * [TcpConnectivityProbe.CACHE_TTL_MS] first.** A cached `false` is served
 * without touching the pool at all, so a re-probe that never reaches the pool is
 * indistinguishable from a re-probe that was refused - and the defect would be
 * invisible. Expiring the entry is what makes the second probe a real one.
 *
 * **These tests wedge a process-wide singleton, so every one of them cleans up
 * in a `finally`:** every latch is released and then the test waits, bounded,
 * until a known-good probe answers true. A worker still parked on a latch this
 * test alone releases turns every LATER test in this JVM into a refused task
 * and a false "not reachable", and the suite goes flaky in whichever order
 * JUnit happens to pick. The drain uses a FRESH probe with a FRESH clock on a
 * host nothing has cached, so a `true` can only have come from a dial that
 * really ran on the shared worker.
 *
 * **On reading the pool's occupancy.** [ProbeExecutor] exposes no accessor for
 * the number of slots in use - `occupied` is private and nothing reads it - so
 * these tests do not assert a count they cannot see. They assert the two things
 * that count is only a proxy for: whether a lookup for a host was STARTED (this
 * file's resolver records every host it was asked about) and whether a slot was
 * still free afterwards (a healthy host must still be dialled). A second worker
 * can only ever be occupied by a second task, and every task resolves before it
 * dials, so "the resolver was asked twice" and "a second slot is gone" are the
 * same statement seen from two sides.
 *
 * No real DNS and no real sockets: the resolver is a fake that parks, the
 * connector is the shared [RecordingConnector], and every wait is a bounded
 * latch await or a bounded poll. Nothing sleeps, and no test starts more than
 * two of anything.
 */
class TcpConnectivityProbeSingleFlightTest {

    // --- R1: the cap must survive repeated probes of one wedged host -------------

    @Test
    fun `two probes of one wedged host still leave a healthy host dialable`() {
        val latches = ParkingResolver().apply { park(WEDGED_HOST) }
        val connector = RecordingConnector(script = listOf(ProbeOutcome.CONNECTED))
        val clock = FakeClock(1_000L)
        var address = "https://$WEDGED_HOST:8443"
        val probe = TcpConnectivityProbe({ address }, clock, latches.asResolver(), connector)

        var firstAnswer = true
        var secondAnswer = true
        var healthyAnswer = true
        var healthyElapsedMs = -1L
        try {
            firstAnswer = probe.isServerReachable()
            assertFalse(
                cardFailure("the resolution of $WEDGED_HOST is parked on a latch this test never releases, so the resolve-and-connect pair cannot finish inside the ${TcpConnectivityProbe.CONNECT_TIMEOUT_MS} ms budget; the caller must be released by the budget and answer not reachable - a true here means the caller waited on the lookup instead of bounding it"),
                firstAnswer,
            )
            assertEquals(
                cardFailure("the first probe of $WEDGED_HOST must have started exactly one lookup, so that the re-probe below can be told apart from it and the count in the next assertion means what it says; it started ${latches.lookupsOf(WEDGED_HOST)}"),
                1,
                latches.lookupsOf(WEDGED_HOST),
            )

            // Expire the answer the timed-out probe stored, so the second probe
            // of the SAME host is a real one and reaches the pool.
            clock.instant += TcpConnectivityProbe.CACHE_TTL_MS + 1L
            val second = measured { probe.isServerReachable() }
            secondAnswer = second.answer
            assertFalse(
                cardFailure("the lookup of $WEDGED_HOST is still parked on a latch this test never releases, so the re-probe must also be released by the budget and answer not reachable - a true here means a cache entry answered rather than a probe measuring a wedged lookup"),
                secondAnswer,
            )

            // The whole point. A different host, a dial scripted CONNECTED, and
            // two slots held by two lookups for ONE name.
            address = "https://$HEALTHY_HOST:8443"
            val healthy = measured { probe.isServerReachable() }
            healthyAnswer = healthy.answer
            healthyElapsedMs = healthy.elapsedMs
        } finally {
            latches.releaseAll()
            drainProbeExecutor()
        }

        assertTrue(
            cardFailure("the resolution of $WEDGED_HOST is wedged and must be SHARED, not repeated: ${latches.lookupsOf(WEDGED_HOST)} lookups for that one name ran, and the second consumed the second and last slot in [ProbeExecutor] for a name already known to be hung. $HEALTHY_HOST has nothing wrong with it and its dial is scripted CONNECTED, so a true is only possible if that dial really ran - and it did not. Recorded dials: ${connector.hosts}. Answered after ${healthyElapsedMs} ms. the domain reads this false as \"the server is down\" and routes every dictation on-device, permanently"),
            healthyAnswer,
        )
        assertTrue(
            cardFailure("a healthy host that is refused must be refused AT ONCE, not queued behind a wedged lookup; this call took ${healthyElapsedMs} ms against a ${TcpConnectivityProbe.CONNECT_TIMEOUT_MS} ms budget, so a caller was held rather than answered"),
            healthyElapsedMs < RETURN_BOUND_MS,
        )
    }

    // --- R2: a re-probe of a parked host must add nothing ------------------------

    @Test
    fun `re-probing a host whose lookup is already parked adds no worker and answers at once`() {
        val latches = ParkingResolver().apply { park(WEDGED_HOST) }
        val connector = RecordingConnector(script = listOf(ProbeOutcome.CONNECTED))
        val clock = FakeClock(1_000L)
        var address = "https://$WEDGED_HOST:8443"
        val probe = TcpConnectivityProbe({ address }, clock, latches.asResolver(), connector)

        var lookupsAfterFirst = 0
        var reAnswer = true
        var reElapsedMs = -1L
        var healthyAnswer = true
        try {
            probe.isServerReachable()
            lookupsAfterFirst = latches.lookupsOf(WEDGED_HOST)
            assertEquals(
                cardFailure("the first probe of $WEDGED_HOST must have started exactly one lookup, so that the re-probe below can be told apart from it; it started $lookupsAfterFirst"),
                1,
                lookupsAfterFirst,
            )

            // Same host, same wedged lookup, and a cache entry that cannot be
            // served: this call is a fresh question about a name that is already
            // being looked up.
            clock.instant += TcpConnectivityProbe.CACHE_TTL_MS + 1L
            val re = measured { probe.isServerReachable() }
            reAnswer = re.answer
            reElapsedMs = re.elapsedMs

            // The observable half of "no worker was added": the pool still has a
            // slot for a host that has never been looked up at all.
            address = "https://$HEALTHY_HOST:8443"
            healthyAnswer = probe.isServerReachable()
        } finally {
            latches.releaseAll()
            drainProbeExecutor()
        }

        assertFalse(
            cardFailure("the lookup of $WEDGED_HOST is parked on a latch this test never releases, so a re-probe of it has nothing to answer from and must say not reachable - a true here means a cache entry answered, not the lookup"),
            reAnswer,
        )
        assertEquals(
            cardFailure("a lookup that is already in flight for $WEDGED_HOST must be JOINED, not duplicated: the re-probe started a second lookup for a name whose first lookup is still parked, and a second worker is a second slot out of the ${ProbeExecutor.MAX_WEDGED_PROBES} this process allows itself. Lookups of $WEDGED_HOST in total: ${latches.lookupsOf(WEDGED_HOST)}, of which $lookupsAfterFirst belonged to the first probe"),
            lookupsAfterFirst,
            latches.lookupsOf(WEDGED_HOST),
        )
        assertTrue(
            cardFailure("a re-probe of a host whose lookup is already parked must be answered AT ONCE and cost nothing: it took ${reElapsedMs} ms, which is a second slot handed to a second lookup of one wedged name rather than an answer from the lookup already running. The whole budget this class allows a caller is ${TcpConnectivityProbe.CONNECT_TIMEOUT_MS} ms"),
            reElapsedMs < REPROBE_BOUND_MS,
        )
        assertTrue(
            cardFailure("the re-probe must have consumed no slot, so a healthy host with a scripted CONNECTED dial can still be dialled afterwards - but it answered not reachable, with these dials recorded: ${connector.hosts}. Both slots were held by two lookups for $WEDGED_HOST"),
            healthyAnswer,
        )
    }

    // --- R3: a parked host must recover once its lookup returns ------------------

    @Test
    fun `a host whose lookup was released dials afresh on the next probe`() {
        val latches = ParkingResolver().apply { park(WEDGED_HOST) }
        val connector = RecordingConnector(script = listOf(ProbeOutcome.CONNECTED))
        val clock = FakeClock(1_000L)
        val probe = TcpConnectivityProbe({ "https://$WEDGED_HOST:8443" }, clock, latches.asResolver(), connector)

        var recovered = false
        var attempts = 0
        var dialsToRecoveredHost = 0
        try {
            assertFalse(
                cardFailure("the resolution of $WEDGED_HOST is parked, so the first question has nothing to answer and must say not reachable - a true here means the lookup was never actually wedged and nothing below is testing a release"),
                probe.isServerReachable(),
            )

            latches.release(WEDGED_HOST)

            // Bounded poll rather than a single retry: the worker may still be
            // finishing the wedged attempt when the latch is released, so the
            // first poll can legitimately race it. The clock moves past the cache
            // window on every attempt, because the parked attempt STORED its own
            // not-reachable answer and a poll served from that entry would be
            // measuring the cache rather than the recovery.
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RECOVERY_BOUND_MS)
            while (System.nanoTime() < deadline) {
                attempts++
                clock.instant += TcpConnectivityProbe.CACHE_TTL_MS + 1L
                if (probe.isServerReachable()) {
                    recovered = true
                    break
                }
            }
            dialsToRecoveredHost = connector.hosts.count { it == WEDGED_HOST }
        } finally {
            latches.releaseAll()
            drainProbeExecutor()
        }

        assertTrue(
            cardFailure("the lookup of $WEDGED_HOST was released and its dial is scripted CONNECTED, yet $WEDGED_HOST was still answered not reachable after $RECOVERY_BOUND_MS ms and $attempts probes - a name resolution that returns eventually must leave the probe answering from what it just measured, and not from a worker still stuck on the lookup that preceded it"),
            recovered,
        )
        assertTrue(
            cardFailure("the recovery must come from a DIAL that ran after the release, not from the not-reachable answer the parked attempt stored: no dial to $WEDGED_HOST was recorded at all, over $attempts probes, so ${connector.hosts} cannot have produced the true above"),
            dialsToRecoveredHost >= 1,
        )
    }

    // --- shared plumbing ---------------------------------------------------------

    /** One probe's answer and how long the caller was held for it. */
    private class Measured(val answer: Boolean, val elapsedMs: Long)

    /** Runs one probe and measures how long the caller was held for it. */
    private fun measured(probe: () -> Boolean): Measured {
        val startedAt = System.nanoTime()
        val answer = probe()
        return Measured(answer, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt))
    }

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
                { "https://single-flight-drain-$attempts.invalid" },
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
     * does - it **ignores the interrupt** the budget's `task.cancel(true)` sends
     * and keeps waiting. A double that honoured the interrupt would let the
     * worker free itself and the defect under test would not reproduce at all.
     *
     * Every host it is asked about is recorded, and that recording is load
     * bearing: it is the only seam through which a test can see that a lookup
     * was STARTED, which is what decides whether a slot is now held. Once
     * released, a host resolves to a loopback address carrying its name, so the
     * connector is offered a real (never dialled) address and a scripted
     * CONNECTED answer means a genuine success path.
     */
    private class ParkingResolver {
        private val latches = ConcurrentHashMap<String, CountDownLatch>()
        private val asked = CopyOnWriteArrayList<String>()

        /** Parks [host] until [release] is called, or until the await times out. */
        fun park(host: String) {
            latches.putIfAbsent(host, CountDownLatch(1))
        }

        /** Releases [host], so its next lookup answers instead of parking. */
        fun release(host: String) {
            latches[host]?.countDown()
        }

        /** How many lookups of [host] this resolver was actually asked for. */
        fun lookupsOf(host: String): Int = asked.count { it == host }

        fun releaseAll() {
            latches.values.forEach { it.countDown() }
        }

        /** The resolver itself: parks the scripted hosts, answers the rest. */
        fun asResolver(): HostResolver = HostResolver { host ->
            asked.add(host)
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
        /** Documentation address whose resolution is parked. */
        const val WEDGED_HOST = "wedged.invalid"

        /** Documentation address with nothing wrong with it at all. */
        const val HEALTHY_HOST = "healthy.invalid"

        /** Ceiling on a parked lookup if nothing releases it; a safety valve. */
        const val WEDGE_AWAIT_MS = 30_000L

        /**
         * Ceiling on a refused or joined probe. Half the budget on purpose: such
         * a probe spends a CAS and returns, so anything approaching the budget
         * means a worker was handed to a lookup that was already in flight.
         */
        const val REPROBE_BOUND_MS = 750L

        /** Ceiling on what a refused caller may be held. */
        const val RETURN_BOUND_MS = 3_000L

        /** Ceiling on a bounded recovery poll; generous for several budgets. */
        const val RECOVERY_BOUND_MS = 10_000L

        /** Ceiling on the drain, generous because each attempt can cost one budget. */
        const val DRAIN_BOUND_MS = 10_000L
    }
}