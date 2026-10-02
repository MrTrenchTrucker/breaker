package dev.breaker.dictation.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether the Local Server is reachable: the four answers, the 30 s cache and
 * its identity, and the port the configured address resolves to.
 *
 * The connector is scripted and records what it was asked, so every assertion
 * here can say what the probe DID and not merely what it returned. That
 * distinction matters for the promises this module makes: "not reachable" is
 * only honest if something was actually dialled, and "reachable" is only
 * trustworthy if it was the configured server that answered.
 *
 * Note on the 1.5 s connect budget: production bounds the DNS and connect work
 * with `System.nanoTime()`, not with the injected clock. The injected clock is
 * here for the 30 s cache window and for nothing else. A frozen fake clock
 * therefore CANNOT make a stalled probe look instant, and no test below tries to
 * prove it does - a test that waited on the clock to time out a stalled dial
 * would pass against code with no timeout at all.
 */
class TcpConnectivityProbeTest {

    // --- What each dial outcome means -------------------------------------------------

    @Test
    fun `a server that accepts the connect is reachable`() {
        val probe = probeOn("https://box.local", scripted = listOf(ProbeOutcome.CONNECTED))
        assertTrue(
            cardFailure("a connect the server accepted means the server is there, so this must answer reachable"),
            probe.isServerReachable(),
        )
        assertEquals(
            cardFailure("one cold question must dial exactly once, not once per candidate address"),
            1,
            connector.callCount,
        )
    }

    @Test
    fun `a refused connect is not reachable and the dial really happened`() {
        val probe = probeOn("https://box.local", scripted = listOf(ProbeOutcome.REFUSED))
        assertFalse(
            cardFailure("a refused connect means nothing is listening, so this must answer not reachable"),
            probe.isServerReachable(),
        )
        assertEquals(
            cardFailure("not reachable because the dial was refused means the dial was made - an unrecorded refusal is indistinguishable from never asking"),
            1,
            connector.callCount,
        )
        assertEquals(
            cardFailure("this test is about a refused connect, so the dial must have been the one that was refused"),
            listOf(ProbeOutcome.REFUSED),
            connector.outcomes,
        )
    }

    @Test
    fun `a connect that times out is not reachable`() {
        val probe = probeOn("https://box.local", scripted = listOf(ProbeOutcome.TIMED_OUT))
        assertFalse(
            cardFailure("a connect that timed out did not reach the server, so this must answer not reachable"),
            probe.isServerReachable(),
        )
        assertEquals(
            cardFailure("the 1.5 s budget must be spent on a real dial, so the attempt has to be recorded"),
            1,
            connector.callCount,
        )
        assertEquals(
            cardFailure("this test is about a dial that timed out, so the dial must have been the one that timed out"),
            listOf(ProbeOutcome.TIMED_OUT),
            connector.outcomes,
        )
    }

    @Test
    fun `no network is not reachable`() {
        val probe = probeOn("https://box.local", scripted = listOf(ProbeOutcome.NO_NETWORK))
        assertFalse(
            cardFailure("with no network there is no route to the server, so this must answer not reachable"),
            probe.isServerReachable(),
        )
        assertEquals(
            cardFailure("no network is only known because something asked and got nowhere, so the dial must be recorded"),
            1,
            connector.callCount,
        )
        assertEquals(
            cardFailure("this test is about there being no route, so the dial must have been the unroutable one"),
            listOf(ProbeOutcome.NO_NETWORK),
            connector.outcomes,
        )
    }

    @Test
    fun `the connect timeout never exceeds the 1_500 ms budget`() {
        // The budget is exactly 1500 ms, and that number is asserted here as a
        // constant rather than as the argument the connector is handed. It is
        // NOT asserted there, and the reason is worth writing down: production
        // gives each attempt whatever is LEFT of one shared deadline, measured
        // with the platform's monotonic timer, so the number arriving at the
        // connector is the budget minus however long resolution took. That is
        // the correct design - a second address must not be able to double the
        // wait - and it means a test demanding the exact Int 1500 at this seam
        // would be measuring scheduler latency, not the promise.
        //
        // What is promised is the ceiling: one attempt never gets more than the
        // whole budget, and never a fresh full budget per address.
        assertEquals(
            cardFailure("the connect budget is the 1.5 s the card promises - a dictation waits on this, so a longer budget holds the UI thread longer and a shorter one fails on a slow but healthy server"),
            1_500L,
            TcpConnectivityProbe.CONNECT_TIMEOUT_MS,
        )

        val probe = probeOn("https://box.local", scripted = listOf(ProbeOutcome.CONNECTED))
        probe.isServerReachable()
        assertEquals(
            cardFailure("the connect must be attempted before the number it was given can be read"),
            1,
            connector.callCount,
        )
        assertTrue(
            cardFailure("the connect timeout must be a real budget, not zero - a zero timeout gives up before the socket is even handed to the network stack, so a healthy server reads as unreachable"),
            connector.timeouts.single() > 0,
        )
        assertTrue(
            cardFailure("an attempt was given ${connector.timeouts.single()} ms of a ${TcpConnectivityProbe.CONNECT_TIMEOUT_MS} ms budget, so one attempt is being handed more time than the whole budget it is allowed to spend - a per-address budget that resets would let a second address double the wait"),
            connector.timeouts.single() <= TcpConnectivityProbe.CONNECT_TIMEOUT_MS,
        )
    }

    @Test
    fun `the connector's answer is passed through and not second-guessed`() {
        val refused = probeOn("https://box.local", scripted = listOf(ProbeOutcome.REFUSED))
        val accepted = probeOn("https://box.local", scripted = listOf(ProbeOutcome.CONNECTED))
        assertFalse(
            cardFailure("a refused dial must reach the caller as not reachable, never smoothed into reachable"),
            refused.isServerReachable(),
        )
        assertTrue(
            cardFailure("an accepted dial must reach the caller as reachable, never damped into not reachable by a probe that wants a different answer"),
            accepted.isServerReachable(),
        )
    }

    @Test
    fun `a second resolved address does not get a fresh connect budget`() {
        // The companion to the ceiling asserted above. A ceiling of 1500 ms is
        // satisfied just as well by a per-address budget that RESETS to the
        // full 1500 on every attempt as by one that shrinks - so with only one
        // candidate address the two are indistinguishable and the promise the
        // card makes ("a second address must not be able to double the time the
        // caller waits") is untested. Two addresses and a first attempt that
        // spends real time are what tell them apart.
        val first = FakeHostResolver.loopbackFor("box.local")
        val second = FakeHostResolver.loopbackFor("box.local-alt")
        val connector = RecordingConnector(
            script = listOf(ProbeOutcome.REFUSED, ProbeOutcome.CONNECTED),
            beforeAnswer = { index -> if (index == 0) Thread.sleep(FIRST_ATTEMPT_SPENT_MS) },
        )
        val probe = TcpConnectivityProbe(
            { "https://box.local" },
            FakeClock(1_000L),
            FakeHostResolver(addressesFor = { listOf(first, second) }),
            connector,
        )

        // The second address is the one that accepted, so the loop has to have
        // run both iterations to get here - which is what makes the timeout
        // below an observation about a second attempt rather than a comment.
        assertTrue(
            cardFailure("the first address refused and the second accepted, so this probe must have run both attempts and answered reachable"),
            probe.isServerReachable(),
        )
        assertEquals(
            cardFailure("a host that resolves to two addresses is dialled twice - a probe that stopped after the refusal would answer not reachable for a server that is there"),
            2,
            connector.callCount,
        )

        val secondTimeout = connector.timeouts[1]
        assertTrue(
            cardFailure("resolution and the first attempt already spent time out of one ${TcpConnectivityProbe.CONNECT_TIMEOUT_MS} ms budget, so the second address was handed $secondTimeout ms of it - a per-address budget that resets to the full ${TcpConnectivityProbe.CONNECT_TIMEOUT_MS} ms lets the second address double the caller's wait, which is the thing the shared deadline exists to prevent"),
            secondTimeout < TcpConnectivityProbe.CONNECT_TIMEOUT_MS,
        )
    }

    // --- The 30 s cache window ---------------------------------------------------------

    @Test
    fun `a second question inside the cache window does not dial again`() {
        val clock = FakeClock(1_000L)
        val probe = probeOn("https://box.local", scripted = listOf(ProbeOutcome.CONNECTED), clock = clock)

        assertTrue(
            cardFailure("the priming question must dial for the answer to be worth caching"),
            probe.isServerReachable(),
        )
        assertEquals(
            cardFailure("the priming question must dial exactly once before the window is measured"),
            1,
            connector.callCount,
        )

        clock.instant += 29_999L
        probe.isServerReachable()

        assertEquals(
            cardFailure("the answer is cached for 30 s, so a question 29 999 ms after the priming dial is still inside the window and must not dial again - a probe that redials inside the window puts a dictation's worth of latency on the UI thread"),
            1,
            connector.callCount,
        )
    }

    @Test
    fun `a question at exactly the cache boundary dials again`() {
        val clock = FakeClock(1_000L)
        val probe = probeOn("https://box.local", scripted = listOf(ProbeOutcome.CONNECTED), clock = clock)

        probe.isServerReachable()
        assertEquals(
            cardFailure("the priming question must dial exactly once before the boundary is measured"),
            1,
            connector.callCount,
        )

        clock.instant += 30_000L
        probe.isServerReachable()

        assertEquals(
            cardFailure("the 30 s cache window closes at exactly 30_000 ms, so this question must connect"),
            2,
            connector.callCount,
        )
    }

    @Test
    fun `refresh re-probes now and returns the fresh answer`() {
        val probe = probeOn("https://box.local", scripted = listOf(ProbeOutcome.CONNECTED, ProbeOutcome.REFUSED))

        assertTrue(
            cardFailure("the first dial is accepted, so the cached answer must be reachable"),
            probe.isServerReachable(),
        )
        assertEquals(
            cardFailure("refresh is asked to re-probe, not to count the dial it is replacing"),
            1,
            connector.callCount,
        )

        assertFalse(
            cardFailure("refresh exists because the cached answer can be stale, so it must return the new answer rather than the one in the cache"),
            probe.refresh(),
        )
        assertEquals(
            cardFailure("a refresh that does not dial has re-probed nothing"),
            2,
            connector.callCount,
        )
    }

    @Test
    fun `refresh re-populates the cache so the next question does not dial`() {
        val probe = probeOn("https://box.local", scripted = listOf(ProbeOutcome.CONNECTED, ProbeOutcome.CONNECTED))

        probe.isServerReachable()
        probe.refresh()
        assertEquals(
            cardFailure("the priming dial and the refresh dial are the only dials so far"),
            2,
            connector.callCount,
        )

        assertTrue(
            cardFailure("the refreshed dial answered, so the question after it must see reachable"),
            probe.isServerReachable(),
        )
        assertEquals(
            cardFailure("refresh leaves a usable cache behind - a refresh that answers without caching forces the very next dictation to redial, which is the cost refresh exists to avoid"),
            2,
            connector.callCount,
        )
    }

    @Test
    fun `refresh on a cold probe dials and caches`() {
        val probe = probeOn("https://box.local", scripted = listOf(ProbeOutcome.CONNECTED))

        assertTrue(
            cardFailure("a refresh with nothing cached has no answer to reuse, so it must find one"),
            probe.refresh(),
        )
        assertEquals(
            cardFailure("a cold refresh must dial the server once"),
            1,
            connector.callCount,
        )

        assertTrue(
            cardFailure("the cold refresh's answer must be reachable"),
            probe.isServerReachable(),
        )
        assertEquals(
            cardFailure("a cold refresh that does not cache leaves the next question to dial again"),
            1,
            connector.callCount,
        )
    }

    // --- The configured address is part of the cache identity ------------------------

    @Test
    fun `a changed server address dials again at the same instant`() {
        var address = "https://box.local"
        val probe = probeOn(address, scripted = listOf(ProbeOutcome.CONNECTED)) { address }

        assertTrue(
            cardFailure("the first address is the configured one and its dial is accepted"),
            probe.isServerReachable(),
        )
        assertEquals(
            cardFailure("the first question must dial exactly once before the address is changed"),
            1,
            connector.callCount,
        )

        address = "https://other.local"
        assertTrue(
            cardFailure("the new server accepts, so the new question must answer reachable"),
            probe.isServerReachable(),
        )
        assertEquals(
            cardFailure("a cached answer is only an answer about the address it was dialed on - reusing it after the server address changed would report the old server's answer as the new server's"),
            2,
            connector.callCount,
        )
        assertEquals(
            cardFailure("the question after a change must dial the host that is configured now"),
            listOf("box.local", "other.local"),
            connector.hosts,
        )
    }

    @Test
    fun `the same server address keeps the cache`() {
        var address = "https://box.local"
        val probe = probeOn(address, scripted = listOf(ProbeOutcome.CONNECTED)) { address }

        probe.isServerReachable()
        address = "https://box.local"

        assertTrue(
            cardFailure("nothing about the server changed, so the cached answer still stands"),
            probe.isServerReachable(),
        )
        assertEquals(
            cardFailure("an unchanged address must not invalidate the cache - treating a re-read of the same address as a change redials on every dictation"),
            1,
            connector.callCount,
        )
    }

    // --- Port resolution ---------------------------------------------------------------

    @Test
    fun `an explicit port is dialled as given`() {
        probeOn("https://box.local:8443", scripted = listOf(ProbeOutcome.CONNECTED)).isServerReachable()
        assertEquals(
            cardFailure("the port in the configured address is the port the operator asked for, and dialling the default instead would probe a port nothing is listening on"),
            8443,
            connector.ports.single(),
        )
    }

    @Test
    fun `https dials 443 when no port is given`() {
        probeOn("https://box.local", scripted = listOf(ProbeOutcome.CONNECTED)).isServerReachable()
        assertEquals(
            cardFailure("https with no port means 443 - the server would not be found on any other port"),
            443,
            connector.ports.single(),
        )
    }

    @Test
    fun `http dials 80 when no port is given`() {
        probeOn("http://box.local", scripted = listOf(ProbeOutcome.CONNECTED)).isServerReachable()
        assertEquals(
            cardFailure("http with no port means 80 - probing 443 against an http server answers about a port nobody asked about"),
            80,
            connector.ports.single(),
        )
    }

    @Test
    fun `an address with no scheme is treated as https`() {
        probeOn("box.local", scripted = listOf(ProbeOutcome.CONNECTED)).isServerReachable()
        assertEquals(
            cardFailure("a bare host carries no scheme, and reading it as http would probe port 80 against a server the operator configured as secure"),
            443,
            connector.ports.single(),
        )
        assertEquals(
            cardFailure("a bare host is still the host that was configured"),
            "box.local",
            connector.hosts.single(),
        )
    }

    // --- Helpers ----------------------------------------------------------------------

    /**
     * How long the first of two attempts really spends.
     *
     * Small on purpose: it only has to be non-zero, so the shared deadline is
     * measurably shorter for the second attempt, and it stays two orders of
     * magnitude below the 1.5 s budget so the probe cannot run out of time
     * while the test waits.
     */
    private val FIRST_ATTEMPT_SPENT_MS = 5L

    private var connector: RecordingConnector = RecordingConnector()

    /**
     * A probe over a scripted connector and a clock the test moves by hand.
     *
     * The address is read through a provider so a test can change what is
     * configured after the probe exists, which is the only way to ask whether
     * the cached answer survives a change of address.
     */
    private fun probeOn(
        serverUrl: String,
        scripted: List<Any>,
        clock: FakeClock = FakeClock(1_000L),
        addressProvider: () -> String = { serverUrl },
    ): TcpConnectivityProbe {
        connector = RecordingConnector(scripted)
        return TcpConnectivityProbe(addressProvider, clock, FakeHostResolver(), connector)
    }
}
