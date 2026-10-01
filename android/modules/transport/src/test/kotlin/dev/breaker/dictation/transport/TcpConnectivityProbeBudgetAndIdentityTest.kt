package dev.breaker.dictation.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The three promises that keep a probe from costing more than it promises: the
 * caller comes back inside the budget, the cache is keyed on the whole
 * configured address, and an address carrying credentials never becomes a
 * lookup.
 *
 * Each of these is a promise about what the probe DID - the dial it made, the
 * lookup it did or did not make, the time it kept the caller waiting - rather
 * than about the boolean that came back. "Not reachable" is only honest if the
 * probe really tried, and "not looked up" is only honest if the resolver's
 * recorded names are empty, which is why the resolver's own record is asserted
 * and not merely the absence of a dial: a name resolution is a contact with the
 * outside world as much as a socket is.
 *
 * Note on the 1.5 s budget: production bounds the resolve-and-connect work with
 * `System.nanoTime()`, not with the injected clock, so a frozen fake clock
 * cannot make a stalled probe look instant. The connector below is parked on a
 * latch the test owns, which is what makes the bound observable: the caller has
 * to come back while the dial is still running.
 */
class TcpConnectivityProbeBudgetAndIdentityTest {

    // --- The caller is bounded by the budget, not by the dial -----------------------

    @Test
    fun `a dial that outlives the budget answers not reachable without waiting for it`() {
        // The dial parks here for far longer than the budget and only ends when
        // this test says so. That is what separates "the caller returned at the
        // budget" from "the dial happened to finish quickly": a probe with no
        // bound at all cannot return until the latch is released, which this
        // test does not do until after the answer is in hand.
        val release = CountDownLatch(1)
        val connector = RecordingConnector(
            script = listOf(ProbeOutcome.CONNECTED),
            beforeAnswer = { release.await(PARKED_DIAL_AWAIT_MS, TimeUnit.MILLISECONDS) },
        )
        val probe = TcpConnectivityProbe(
            { "https://box.local" },
            FakeClock(1_000L),
            FakeHostResolver(),
            connector,
        )

        var reachable = true
        var stillParkedWhenAnswered = false
        var elapsedMs = 0L
        val startedAt = System.nanoTime()
        try {
            reachable = probe.isServerReachable()
            // Read before the finally releases the latch: after the release this
            // is true by construction and would prove nothing.
            stillParkedWhenAnswered = release.count > 0L
        } finally {
            release.countDown()
            elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            drainProbeExecutor()
        }

        assertTrue(
            cardFailure("the dial was parked on a latch for up to ${PARKED_DIAL_AWAIT_MS} ms, far past the ${TcpConnectivityProbe.CONNECT_TIMEOUT_MS} ms budget, and was still parked when the caller came back - so the caller was released by the budget rather than by the dial finishing, which is the guarantee this class exists to keep"),
            stillParkedWhenAnswered,
        )
        assertFalse(
            cardFailure("a dial that had not finished when the budget ran out did not reach the server, so this must answer not reachable - and the connector's scripted answer was CONNECTED, so any true here means the probe waited for the dial rather than bounding it"),
            reachable,
        )
        assertTrue(
            cardFailure("the caller waited ${elapsedMs} ms for a dial parked for up to ${PARKED_DIAL_AWAIT_MS} ms; a probe that waits on the connect instead of bounding it holds the dictation thread for the whole dial - android/modules/transport promises a call that comes back inside ${TcpConnectivityProbe.CONNECT_TIMEOUT_MS} ms"),
            elapsedMs < RETURN_BOUND_MS,
        )
        assertEquals(
            cardFailure("the budget must be spent on a real dial, so exactly one attempt must have been recorded before the caller was released"),
            1,
            connector.callCount,
        )
        assertEquals(
            cardFailure("the parked dial was the only attempt and it went to the configured host"),
            listOf("box.local"),
            connector.hosts,
        )
    }

    // --- The cache is keyed on the whole configured address ------------------------

    @Test
    fun `a changed port dials again at the same instant`() {
        var address = "https://box.local:8443"
        val connector = RecordingConnector(script = listOf(ProbeOutcome.CONNECTED))
        val clock = FakeClock(1_000L)
        val probe = TcpConnectivityProbe({ address }, clock, FakeHostResolver(), connector)

        assertTrue(
            cardFailure("the first address is the configured one and its dial is accepted, so the answer is reachable and the cache has something in it"),
            probe.isServerReachable(),
        )
        assertEquals(
            cardFailure("the first question must dial exactly once before the port is changed"),
            1,
            connector.callCount,
        )

        // The clock is NOT moved. The port alone is the whole difference, so any
        // answer served here came out of the cache rather than off the network.
        address = "https://box.local:9443"
        assertTrue(
            cardFailure("the new port's dial is accepted, so the question about it must answer reachable"),
            probe.isServerReachable(),
        )
        assertEquals(
            cardFailure("a cached answer is an answer about the address it was dialled on, port included - 8443 is not 9443, so serving the first answer for the second port reports the old port's answer as the new port's, and the operator's edit has no effect until the 30 s window closes"),
            2,
            connector.callCount,
        )
        assertEquals(
            cardFailure("the question after the port change must dial the port that is configured now"),
            listOf(8443, 9443),
            connector.ports,
        )
        assertEquals(
            cardFailure("the two dials are to one host, so the port is the only thing that changed"),
            listOf("box.local", "box.local"),
            connector.hosts,
        )
    }

    // --- Credentials are refused before any lookup ----------------------------------

    @Test
    fun `credentials in the address are refused and never looked up`() {
        var address = "https://user@box.local"
        val connector = RecordingConnector(script = listOf(ProbeOutcome.CONNECTED))
        val resolver = FakeHostResolver()
        val probe = TcpConnectivityProbe({ address }, FakeClock(1_000L), resolver, connector)

        assertFalse(
            cardFailure("an address carrying credentials names a host this probe cannot strip, so it must answer not reachable rather than dial something"),
            probe.isServerReachable(),
        )

        address = "https://user:pw@box.local:8443"
        assertFalse(
            cardFailure("a user:password pair before the host is the same unusable authority with more text on it, and an explicit port does not make it usable"),
            probe.isServerReachable(),
        )

        assertEquals(
            cardFailure("an authority containing credentials is not a host name this probe may dial - 'user@box.local' read as a host would send the lookup and the dial to a name the operator never configured"),
            0,
            connector.callCount,
        )
        assertEquals(
            cardFailure("a name resolution is a contact with the outside world as much as a dial is, and this address must not produce one - the resolver's own record is asserted because no dial is not the same as no lookup"),
            emptyList<String>(),
            resolver.hostsAskedFor,
        )
    }

    /**
     * Waits, bounded, for the one shared probe worker to come back.
     *
     * The worker in [TcpConnectivityProbe] is a process-wide singleton with a
     * queue of one, so a worker still parked on a released latch turns every
     * LATER test in this JVM into a discarded task and a false "not reachable".
     * Proving it is drained here means the parking test cannot poison the rest
     * of the suite: each attempt uses a FRESH probe with a fresh clock, so a
     * cache hit is impossible and a `true` can only come from a dial that
     * actually ran on the shared worker.
     *
     * Each attempt costs at most one budget while the worker is still busy, so
     * the bound is generous enough for the several attempts it can take and
     * still fails loudly rather than hanging when the worker never comes back.
     */
    private fun drainProbeExecutor() {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DRAIN_BOUND_MS)
        var attempts = 0
        while (System.nanoTime() < deadline) {
            attempts++
            val drain = TcpConnectivityProbe(
                { "https://drain-$attempts.local" },
                FakeClock(1_000L),
                FakeHostResolver(),
                RecordingConnector(script = listOf(ProbeOutcome.CONNECTED)),
            )
            if (drain.isServerReachable()) return
        }
        throw AssertionError(
            cardFailure("the shared probe worker was still busy ${DRAIN_BOUND_MS} ms after the parked dial was released, over $attempts attempts - the one thread in [TcpConnectivityProbe] serves every probe in this JVM, so a worker parked past this test answers false for everything that follows it"),
        )
    }

    private companion object {
        /** How long the parked dial is willing to stay parked if nothing releases it. */
        const val PARKED_DIAL_AWAIT_MS = 30_000L

        /** Ceiling on what the caller is allowed to wait, against a 1500 ms budget. */
        const val RETURN_BOUND_MS = 3_000L

        /** Ceiling on the drain, generous because each attempt can cost one budget. */
        const val DRAIN_BOUND_MS = 10_000L
    }
}
