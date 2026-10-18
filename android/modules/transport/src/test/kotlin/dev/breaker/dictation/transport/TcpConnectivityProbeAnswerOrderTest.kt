package dev.breaker.dictation.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-host mark (and the probe slot) must END WITH THE ANSWER, not with the
 * worker's bookkeeping - a release-at-publish contract this test asserts on the
 * PRODUCTION [TcpConnectivityProbe], where the ordering is a property of body.
 *
 * WHY PRODUCTION, NOT A REPLICA. The promise lives in [TcpConnectivityProbe]
 * `runProbe`'s body: the answer is published to its caller INSIDE the
 * [ProbeExecutor.answering] block that also releases the mark, so a same-host
 * re-probe can reach the single-flight check only between publish and release. A
 * body-shape copy in a test could not see a change that restored the old order in
 * production - it would stay green over the very regression it exists to catch.
 * Driving the real probe is what lets this class observe the ordering for itself.
 *
 * WHY REFRESH. [TcpConnectivityProbe.isServerReachable] would serve a cached
 * answer for a re-probe and never touch the mark; `refresh` bypasses that cache,
 * so it is the same-host probe that must dial again. The test issues refresh at
 * the exact statement after reach returns, with nothing between them - that
 * immediacy IS the isolation: it is the guard's back-to-back re-probe stripped of
 * its clock bookkeeping.
 *
 * THE HONESTY PARAGRAPH. On the corrected order (mark released strictly before the
 * answer becomes observable) no iteration can refuse, because the host is
 * already unmarked by the time a same-host re-probe arrives; every assertion here
 * is therefore deterministic and green on every machine - the green is not an
 * accident of timing. On the uncorrected order a re-probe is refused only when its
 * single-flight putIfAbsent lands between the worker's `complete()` and its
 * `releaseMark` - a few-instruction window - so any red here is interleaving-
 * dependent, never a proof that one machine reads differently from another. The 200
 * iterations are the amplification: each iteration is one fresh chance at that
 * window where the flake test had only one.
 *
 * THE DRAIN'S PLACE runs AFTER every assertion and only so the next iteration's
 * probe meets an idle pool rather than a prior worker's tail; it waits on the
 * pool's own counters, a bounded event, which neither stretches nor hides the
 * window that the assertions close before it runs.
 *
 * No real DNS and no real sockets: every call reaches the pool through one
 * bounded idle-wait via [TcpConnectivityProbeAnswerOrderTest.awaitProbePoolIdle];
 * no call here parks a worker or waits on a clock as a mechanism. The only duration
 * bounds how long that single wait may run. Bounded by count, not any duration.
 */
class TcpConnectivityProbeAnswerOrderTest : ProbePoolIsolation() {

    private companion object {
        /**
         * The number of same-host re-probe iterations this class runs: bounded by
         * count alone - 200 fresh chances at the publish-before-release window, one
         * per iteration - never any clock. Each iteration is one fresh host, so none
         * collides with another's cache or mark; on the corrected order every iteration
         * passes by construction, and on the wrong order each iteration is a chance to
         * fail where the flake test had exactly one.
         */
        const val RACE_ITERS = 200
    }

    @Test
    fun `the mark does not outlive an observable answer`() {
        repeat(RACE_ITERS) { i ->
            val host = "answer-order-$i.invalid"
            val connector = RecordingConnector(script = listOf(ProbeOutcome.CONNECTED))
            val probe = TcpConnectivityProbe({ "https://$host" }, FakeClock(1_000L), FakeHostResolver(), connector)

            val first = probe.isServerReachable()
            assertTrue(
                cardFailure("iteration $i: the first probe of $host, a name nothing has cached on an idle pool, must be admitted and dial - a refusal here means the pool was not idle when this test began"),
                first,
            )

            val second = probe.refresh()
            assertTrue(
                cardFailure("iteration $i: a same-host re-probe issued at the moment $host's answer became observable must be ADMITTED; a 'not reachable' here is the single-flight refusal that says the in-flight mark outlived the answer, so the caller was told $host was down with no dial and nothing cached - indistinguishable from a server that is down"),
                second,
            )
            assertEquals(
                cardFailure("iteration $i: the re-probe above must have actually DIALLED; a refusal costs no attempt at all, so a call count staying at 1 is exactly what the refusal shows from the connector's side"),
                2,
                connector.callCount,
            )
            assertEquals(
                cardFailure("iteration $i: both dials of this iteration must be to $host - one from isServerReachable and one from refresh, no other host reached"),
                listOf(host, host),
                connector.hosts,
            )

            awaitProbePoolIdle(context = "after answer-order iteration $i, before the next iteration reads the shared pool")
        }
    }

    @Test
    fun `a back-to-back same-host sequence keeps its dial count`() {
        var address = "https://order-a.invalid"
        val connector = RecordingConnector(script = listOf(ProbeOutcome.CONNECTED))
        val resolver = FakeHostResolver()
        val clock = FakeClock(1_000L)
        val probe = TcpConnectivityProbe({ address }, clock, resolver, connector)

        probe.isServerReachable()
        clock.instant += 30_001
        probe.isServerReachable()
        clock.instant += 30_001
        probe.refresh()

        address = "https://order-b.invalid"
        probe.isServerReachable()
        clock.instant += 30_001
        probe.refresh()

        assertTrue(
            cardFailure("these cases reach, advance the window, refresh twice and change address, so dials must have been made - an empty recording would let every host check below pass without testing anything"),
            connector.allAttempts.isNotEmpty(),
        )
        assertEquals(
            cardFailure("every dial on this probe must be to order-a.invalid or order-b.invalid; a probe that reached any other name would be telling the caller it contacted the configured server when it did not"),
            setOf("order-a.invalid", "order-b.invalid"),
            connector.hosts.toSet(),
        )
        assertEquals(
            cardFailure("the reachable case, its cache hit past the window, both refreshes and the address change all dial, so this probe makes exactly five dials - a case that quietly skipped its own dial leaves the count below the five the guard covers"),
            5,
            connector.callCount,
        )
        assertEquals(
            cardFailure("a name resolution contacts the outside world as much as a dial does, so only order-a.invalid and order-b.invalid may ever be looked up on this probe"),
            setOf("order-a.invalid", "order-b.invalid"),
            resolver.hostsAskedFor.toSet(),
        )

        awaitProbePoolIdle(context = "after the back-to-back same-host sequence, before the next class reads the shared pool")
    }
}
