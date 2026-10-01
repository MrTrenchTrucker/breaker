package dev.breaker.dictation.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * What a hung name resolution does to every OTHER probe in the process.
 *
 * The promise under test is the KDoc's own: "Until then, probes for **that
 * address** keep answering from this rule, not from a stale success" (see
 * [TcpConnectivityProbe] lines 31-35). A resolution that never returns is not
 * a property of one address. The worker in [TcpConnectivityProbe] is a
 * companion-object singleton with a queue of one, and an interrupt does not
 * stop a name lookup, so one wedged host parks the ONE thread that serves
 * every probe in the JVM. From that moment a healthy host's probe is not
 * answered by a failed dial - it is never dialled at all, and the domain
 * routes every dictation on-device with no way back.
 *
 * So these tests assert through the PUBLIC behaviour only: what
 * `isServerReachable()` answers for a host that is fine, and how long the
 * caller was kept waiting. Nothing reaches into the executor, and nothing here
 * can tell a real DNS failure from a refusal - the assertion is always made
 * about a host whose dial is scripted CONNECTED, so a `true` is only possible
 * if a dial actually ran.
 *
 * **Every test here wedges a process-wide singleton, so every one of them
 * cleans up in a `finally`: release the latch, then wait, bounded, until a
 * known-good probe answers true.** A worker still parked on a latch released
 * only by the test's own exit turns every LATER test in this JVM into a
 * discarded task and a false "not reachable", and the suite goes flaky in
 * whichever order JUnit happens to pick. The drain uses a FRESH probe with a
 * FRESH clock on a host nothing has cached, so a `true` can only have come
 * from a dial that really ran on the shared worker.
 *
 * No real DNS and no real sockets: the resolver is a fake that blocks, and the
 * connector is the shared [RecordingConnector]. The only waits here are latch
 * awaits and bounded polling loops; nothing sleeps.
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
            // address, and the cache is keyed on the address, so nothing can be
            // served from the previous answer.
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
            // first poll can legitimately race it. The clock is moved past the
            // cache window on every attempt, because the parked attempt STORED
            // its own not-reachable answer and a poll served from that entry
            // would be measuring the cache rather than the recovery.
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
        val latches = WedgeResolver().apply { wedge(WEDGED_HOST); wedge(WEDGED_HOST_TWO) }
        val clock = FakeClock(1_000L)
        val connector = RecordingConnector(script = listOf(ProbeOutcome.CONNECTED))
        var address = "https://$WEDGED_HOST:8443"
        val probe = TcpConnectivityProbe({ address }, clock, latches.asResolver(), connector)

        // Two wedged hosts is enough to exhaust the singleton and no more: one
        // occupies the single thread and one fills the single queue slot, so the
        // next probe has nowhere to go. The list is fixed, not looped, and each
        // entry is released in the finally - see the class KDoc.
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

            // Releasing the RUNNING host does not by itself free the singleton:
            // the worker simply moves on to the queued one and parks there
            // again, because the queue is FIFO. So the recovery arm is two
            // steps, and each is asserted for what it shows - the singleton is
            // handed from one lookup to the next, and then handed back.
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

    /**
     * Waits, bounded, for the one shared probe worker to come back.
     *
     * The worker in [TcpConnectivityProbe] is a process-wide singleton with a
     * queue of one, so a worker still parked on a released latch turns every
     * LATER test in this JVM into a discarded task and a false "not reachable".
     * Failing loudly here beats letting the next test fail for a reason that
     * has nothing to do with what it is testing.
     *
     * Each attempt costs at most one budget while the worker is still busy, so
     * the bound is generous for the several attempts it can take and still
     * fails rather than hanging when the worker never comes back.
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
            cardFailure("the shared probe worker was still busy ${DRAIN_BOUND_MS} ms after this test released its latches, over $attempts attempts - the one thread in [TcpConnectivityProbe] serves every probe in this JVM, so a worker left parked past this test answers false for everything that follows it"),
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
     * worker free itself and the defect under test would not reproduce at all.
     *
     * Once released, the host resolves to a loopback address carrying its name,
     * so the connector is offered a real (never dialled) address and a scripted
     * CONNECTED answer means a genuine success path.
     */
    private class WedgeResolver {
        private val latches = LinkedHashMap<String, CountDownLatch>()

        /** Wedges [host] until [release] is called, or until the await times out. */
        fun wedge(host: String): CountDownLatch = latches.getOrPut(host) { CountDownLatch(1) }

        /** True while [host]'s resolution is still inside its await. */
        fun parked(host: String): Boolean = latches[host]?.let { it.count > 0L } ?: false

        fun release(host: String) {
            latches[host]?.countDown()
        }

        fun releaseAll() {
            latches.values.forEach { it.countDown() }
        }

        /** The resolver itself: wedges the scripted hosts, answers the rest. */
        fun asResolver(): HostResolver = HostResolver { host ->
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
