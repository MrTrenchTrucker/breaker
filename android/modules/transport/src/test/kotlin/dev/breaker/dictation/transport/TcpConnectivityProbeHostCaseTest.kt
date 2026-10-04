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
 * That the single-flight name a host is filed under is a NAME, not a spelling.
 *
 * [ProbeExecutor] keeps a map of the names whose lookup is in flight, and a
 * later probe of a name already in that map is answered "not reachable" at once
 * instead of starting a second lookup. Keying that map on the raw text of the
 * host as the configuration wrote it WOULD be wrong, and DNS names are
 * case-insensitive: "Box.local" and "box.local" are the same name to every
 * resolver that has ever existed, and to `InetAddress.getAllByName`, which
 * folds case before it asks the platform. So [ProbeExecutor] case-folds the key
 * (see its `keyFor`) and this test pins that it does.
 *
 * So keying on the raw text splits one wedged name in two. Both spellings take
 * a slot of [ProbeExecutor.MAX_WEDGED_PROBES] and each starts its own lookup
 * into the same blackholed resolver, which is the very cap overrun the map
 * exists to prevent: a host that is unreachable for one reason is now
 * unreachable for two, and the other addresses in the process lose half the
 * pool. And the joining never happens either - the second spelling is a
 * stranger to the map, so instead of being answered at once it is resolved and
 * dialled, and the caller is answered from a fresh connection attempt against a
 * name that was already known to be stuck.
 *
 * **The clock does NOT move between the two probes, and that is load bearing
 * in the other direction.** A cached false is served without touching the pool,
 * so moving the clock would be the usual way to guarantee the second probe is a
 * real question - but there is nothing to expire here. The first attempt ended
 * at the budget with the lookup still parked, so it is UNANSWERED and cached
 * nothing. A jump would therefore expire nothing, while hiding any mutant that
 * DID cache such a false.
 *
 * **The resolver parks ONE spelling and only one.** That is what makes the
 * defect sharp rather than merely wasteful: a second lookup started for the
 * other spelling finds no latch, resolves at once and dials, so a probe that
 * should have been answered from the parked lookup is instead answered from a
 * fresh connection. Asserting the answer, the recorded lookups and the
 * recorded dials together therefore separates "joined the parked lookup" from
 * "did the work twice" without reaching into the pool.
 *
 * **This test wedges a process-wide singleton, so it cleans up in a
 * `finally`:** every latch is released and then the test waits, bounded, until a
 * known-good probe answers true. A worker still parked on a latch released only
 * by this test's exit turns every LATER test in this JVM into a refused task
 * and a false "not reachable", and the suite goes flaky in whichever order JUnit
 * happens to pick. The drain uses a FRESH probe with a FRESH clock on a host
 * nothing has cached, so a `true` can only have come from a dial that really
 * ran on the shared worker.
 *
 * No real DNS and no real sockets: the resolver is a fake that parks, the
 * connector is the shared [RecordingConnector], and every wait is a bounded
 * latch await or a bounded poll.
 */
class TcpConnectivityProbeHostCaseTest : ProbePoolIsolation() {

    @Test
    fun `the other spelling of a name already being looked up is that same name`() {
        val latches = ParkingResolver().apply { park(PARKED_SPELLING) }
        val connector = RecordingConnector(script = listOf(ProbeOutcome.CONNECTED))
        val clock = FakeClock(1_000L)
        var address = "https://$PARKED_SPELLING:8443"
        val probe = TcpConnectivityProbe({ address }, clock, latches.asResolver(), connector)

        var joinedAnswer = true
        var joinedElapsedMs = -1L
        var dialsAfterJoined = -1
        var healthyAnswer = true
        try {
            assertFalse(
                cardFailure("the resolution of $PARKED_SPELLING is parked on a latch this test does not release, so its probe must be released by the ${TcpConnectivityProbe.CONNECT_TIMEOUT_MS} ms budget and answer not reachable - a true here means the lookup was never actually wedged and nothing below is testing a join"),
                probe.isServerReachable(),
            )

            // The clock does NOT move here, and that is the point: the attempt
            // above ended at the budget with the lookup still parked, so it
            // never obtained an address and never dialled. It is UNANSWERED,
            // and UNANSWERED caches NOTHING, so there is no entry for the
            // window to expire and the probe below reaches the pool on its own.
            // Advancing it "to be safe against the cache" would instead hide a
            // mutant that caches such a false.

            // The same name, written the other way round. Nothing about the
            // network, the resolver or the configuration has changed.
            address = "https://$OTHER_SPELLING:8443"
            val startedAt = System.nanoTime()
            joinedAnswer = probe.isServerReachable()
            joinedElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            dialsAfterJoined = connector.callCount

            // The observable half of "no second slot was taken": a host nothing
            // has looked up still has a worker to be dialled on.
            address = "https://$HEALTHY_HOST:8443"
            healthyAnswer = probe.isServerReachable()
        } finally {
            latches.releaseAll()
            drainProbeExecutor()
        }

        assertFalse(
            cardFailure("the lookup of $PARKED_SPELLING is still parked on a latch this test does not release, so a probe of that name in any spelling has nothing to answer and must say not reachable - a true here means the answer came from a connection attempt this test should never have made, against a name already known to be stuck. Recorded dials: ${connector.hosts}"),
            joinedAnswer,
        )
        assertEquals(
            cardFailure("a name already in flight must be JOINED, not looked up again: the probe of $OTHER_SPELLING started a second lookup for the same DNS name whose first lookup is still parked, and a second lookup is a second slot out of the ${ProbeExecutor.MAX_WEDGED_PROBES} this process allows itself. Lookups of $PARKED_SPELLING: ${latches.lookupsOf(PARKED_SPELLING)}, of $OTHER_SPELLING: ${latches.lookupsOf(OTHER_SPELLING)}"),
            0,
            latches.lookupsOf(OTHER_SPELLING),
        )
        assertEquals(
            cardFailure("a probe that joins a lookup already in flight must not dial: the in-flight name is unresolved, so there is nothing to connect to. $dialsAfterJoined dials were recorded, so this probe did the work a second time instead of answering at once. Recorded dials: ${connector.hosts}"),
            0,
            dialsAfterJoined,
        )
        assertTrue(
            cardFailure("a probe that joins a lookup already in flight must be answered AT ONCE and cost nothing: it took ${joinedElapsedMs} ms, which is a second slot handed to a second lookup of one wedged name rather than an answer from the lookup already running. The whole budget this class allows a caller is ${TcpConnectivityProbe.CONNECT_TIMEOUT_MS} ms"),
            joinedElapsedMs < JOIN_BOUND_MS,
        )
        assertTrue(
            cardFailure("the joined probe must have consumed no slot, so a host nothing has looked up can still be dialled afterwards - but it answered not reachable, with these dials recorded: ${connector.hosts}. Two spellings of one unreachable name had taken the whole cap"),
            healthyAnswer,
        )
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
                { "https://host-case-drain-$attempts.invalid" },
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
     * A resolver that can park ONE spelling of a name and answers every other
     * host at once, standing in for a name lookup the platform has given up on
     * handing back.
     *
     * A real `InetAddress.getAllByName` cannot be interrupted: the JDK hands the
     * name to the OS resolver and waits on it, so a lookup against a blackholed
     * resolver, a captive portal or a VPN mid-handshake parks that thread until
     * the platform gives up on its own. So this double does what the real thing
     * does - it **ignores the interrupt** the budget's `task.cancel(true)` sends
     * and keeps waiting. A double that honoured the interrupt would let the
     * worker free itself and the defect under test would not reproduce at all.
     *
     * Only the exact text [park] was given is parked, which is the point: a
     * lookup of the name in any other spelling is not parked, so it resolves
     * immediately and its dial is recorded - that is what tells a joined probe
     * from a duplicated one. Every host asked about is recorded, because a name
     * resolution is as much a contact with the outside world as a dial is.
     */
    private class ParkingResolver {
        private val latches = ConcurrentHashMap<String, CountDownLatch>()
        private val asked = CopyOnWriteArrayList<String>()

        /** Parks [host] until [release] is called, or until the await times out. */
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
        /** One spelling of a documentation name whose resolution is wedged. */
        const val PARKED_SPELLING = "Box.local"

        /** The same DNS name, written the other way round. */
        const val OTHER_SPELLING = "box.local"

        /** Documentation address with nothing wrong with it at all. */
        const val HEALTHY_HOST = "healthy.invalid"

        /** Ceiling on a parked lookup if nothing releases it; a safety valve. */
        const val WEDGE_AWAIT_MS = 30_000L

        /**
         * Ceiling on a probe that joins a lookup already in flight. Half the
         * budget on purpose: such a probe spends a CAS and returns, so anything
         * approaching the budget means a worker was handed to a lookup that was
         * already running.
         */
        const val JOIN_BOUND_MS = 750L

        /** Ceiling on the drain, generous because each attempt can cost one budget. */
        const val DRAIN_BOUND_MS = 10_000L
    }
}
