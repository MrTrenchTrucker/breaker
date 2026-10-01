package dev.breaker.dictation.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which addresses the probe is allowed to touch: the ones that must produce no
 * dial at all, and the guard that keeps every dial on the configured host.
 *
 * The connector is scripted and records what it was asked, so every assertion
 * here can say what the probe DID and not merely what it returned. That
 * distinction matters for the promises this module makes: "not reachable" is
 * only honest if something was actually dialled, and an address that names no
 * server is only honest if nothing was dialled for it at all.
 *
 * Note on the 1.5 s connect budget: production bounds the DNS and connect work
 * with `System.nanoTime()`, not with the injected clock. The injected clock is
 * here for the 30 s cache window and for nothing else. A frozen fake clock
 * therefore CANNOT make a stalled probe look instant, and no test below tries to
 * prove it does - a test that waited on the clock to time out a stalled dial
 * would pass against code with no timeout at all.
 */
class TcpConnectivityProbeAddressGuardTest {

    // --- Addresses that must never produce a dial ------------------------------------

    @Test
    fun `a blank server address is not reachable and dials nothing`() {
        assertNoDial(
            cardFailure("a blank address has no server to ask about"),
            serverUrl = "",
        )
    }

    @Test
    fun `a whitespace-only server address is not reachable and dials nothing`() {
        assertNoDial(
            cardFailure("whitespace is not a host - a probe that trims its way to a dial here would be dialling a name it invented"),
            serverUrl = "   ",
        )
    }

    @Test
    fun `a malformed server address is not reachable and dials nothing`() {
        assertNoDial(
            cardFailure("an address with a scheme but no host names no server, so there is nothing to dial"),
            serverUrl = "http://",
        )
        assertNoDial(
            cardFailure("an address with no scheme at all names no server, so there is nothing to dial"),
            serverUrl = "://box.local",
        )
    }

    @Test
    fun `a scheme other than http or https is refused and dials nothing`() {
        // `file:` is the sharp one: it parses, and the host after it looks
        // exactly like a host to dial. A probe that only checked "is there
        // something after the ://" would dial it, and a scheme that is not a
        // network transport must not produce a network dial at all.
        listOf("ftp://box.local", "ws://box.local", "file://box.local").forEach { serverUrl ->
            assertNoDial(
                cardFailure("a scheme that is not http or https is not a server to probe, but it parses and names a host - '$serverUrl' must produce no dial"),
                serverUrl = serverUrl,
            )
        }
    }

    // --- The host guard ---------------------------------------------------------------

    @Test
    fun `every dial on one probe is the configured host and nothing else`() {
        var address = "https://box.local"
        val connector = RecordingConnector(script = listOf(ProbeOutcome.CONNECTED, ProbeOutcome.REFUSED, ProbeOutcome.TIMED_OUT, ProbeOutcome.NO_NETWORK, ProbeOutcome.CONNECTED))
        val resolver = FakeHostResolver()
        val clock = FakeClock(1_000L)
        val probe = TcpConnectivityProbe({ address }, clock, resolver, connector)

        probe.isServerReachable()

        clock.instant += 30_000L
        probe.isServerReachable()

        clock.instant += 30_000L
        probe.refresh()

        address = "https://other.local"
        probe.isServerReachable()

        clock.instant += 30_000L
        probe.refresh()

        // Asserted first, and on purpose: every host below is checked with an
        // "all of these are permitted" predicate, and a predicate over an empty
        // list passes vacuously. A probe that dialled nothing at all would
        // otherwise sail through a guard written to catch a probe that dialled
        // the wrong thing.
        assertTrue(
            cardFailure("these cases reach, fail, refresh and change address, so dials must have been made - an empty recording would make every host check below pass without testing anything"),
            connector.allAttempts.isNotEmpty(),
        )

        assertEquals(
            cardFailure("this probe may only ever contact the configured Local Server; the reachable, refused, timed-out, no-network, refresh and address-change cases above must all have dialled that host and nothing else"),
            setOf("box.local", "other.local"),
            connector.hosts.toSet(),
        )
        connector.hosts.forEach { host ->
            assertTrue(
                cardFailure("this probe dialled '$host', which is not the configured Local Server"),
                host == "box.local" || host == "other.local",
            )
        }
        assertEquals(
            cardFailure("the reachable, refused and timed-out cases plus the two refreshes share one address and the address-change case uses another, so there are five dials - a case that quietly skipped its dial would leave the guard above passing on fewer cases than it appears to cover"),
            5,
            connector.callCount,
        )
        assertEquals(
            cardFailure("a name resolution is a contact with the outside world as much as a dial is, so only the configured host may be looked up"),
            setOf("box.local", "other.local"),
            resolver.hostsAskedFor.toSet(),
        )
    }

    /** Asserts an address produces "not reachable" without ever dialling. */
    private fun assertNoDial(why: String, serverUrl: String) {
        val own = RecordingConnector(script = listOf(ProbeOutcome.CONNECTED))
        val probe = TcpConnectivityProbe(
            { serverUrl },
            FakeClock(1_000L),
            FakeHostResolver(),
            own,
        )
        assertFalse(
            cardFailure("$why, so this must answer not reachable"),
            probe.isServerReachable(),
        )
        assertEquals(
            cardFailure("$why, and an unusable address must be refused before any dial is attempted - a dial here reaches a host the operator never configured"),
            0,
            own.callCount,
        )
    }
}
