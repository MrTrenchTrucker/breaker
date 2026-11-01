package dev.breaker.dictation.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * The probe dials the host and the port of the configured address and nothing else, for the cases the
 * other address tests do not already pin: an http host name with an explicit port, an address with no
 * scheme and a port, a bracketed IPv6 literal with no port, and a configured address that changes
 * between two questions.
 *
 * The probe opens a socket to the host and the port and sends nothing. The scheme only picks the port
 * when none is written: 80 for http and 443 otherwise, so an address with no scheme takes 443.
 *
 * Nothing here opens a socket or looks a name up: the connector and the resolver are doubles. A dial
 * to an address literal is read from the address the connector was handed, never from its host name,
 * because asking an address literal for its host name can start a real reverse lookup.
 */
class TcpConnectivityProbeConfiguredTargetTest : ProbePoolIsolation() {

    // --- An explicit port is dialled as given, whatever the scheme ----------------------

    @Test
    fun `an http host name with an explicit port is dialled on that host and that port`() {
        val connector = RecordingConnector(script = listOf(ProbeOutcome.CONNECTED))
        val resolver = FakeHostResolver()
        val probe = TcpConnectivityProbe({ "http://box.local:8081" }, FakeClock(1_000L), resolver, connector)

        assertTrue(
            cardFailure("a connect the server accepted means the server is there, so this must answer reachable"),
            probe.isServerReachable(),
        )
        assertEquals(
            cardFailure("one cold question must dial exactly once"),
            1,
            connector.callCount,
        )
        assertEquals(
            cardFailure("the host of the configured address is the only host that may be dialled"),
            listOf("box.local"),
            connector.hosts,
        )
        assertEquals(
            cardFailure("the port in the configured http address is the port the operator asked for, not 80"),
            listOf(8081),
            connector.ports,
        )
        assertEquals(
            cardFailure("only the configured host may be looked up"),
            listOf("box.local"),
            resolver.hostsAskedFor,
        )
    }

    @Test
    fun `an address with no scheme and a port is dialled on that host and that port`() {
        val connector = RecordingConnector(script = listOf(ProbeOutcome.CONNECTED))
        val resolver = FakeHostResolver()
        val probe = TcpConnectivityProbe({ "box.local:8443" }, FakeClock(1_000L), resolver, connector)

        assertTrue(
            cardFailure("a connect the server accepted means the server is there, so this must answer reachable"),
            probe.isServerReachable(),
        )
        assertEquals(
            cardFailure("one cold question must dial exactly once"),
            1,
            connector.callCount,
        )
        assertEquals(
            cardFailure("a host written with no scheme is still the configured host"),
            listOf("box.local"),
            connector.hosts,
        )
        assertEquals(
            cardFailure("the port after the host is the port the operator asked for, not the default"),
            listOf(8443),
            connector.ports,
        )
    }

    // --- A bracketed literal with no port takes the default port of its scheme ----------

    @Test
    fun `a bracketed address literal with no port is dialled on the default port of its scheme`() {
        val cases = listOf("http://[2001:0db8::1]" to 80, "https://[2001:0db8::1]" to 443)
        for ((address, expectedPort) in cases) {
            val connector = RecordingConnector(script = listOf(ProbeOutcome.CONNECTED))
            val resolver = FakeHostResolver()
            val probe = TcpConnectivityProbe({ address }, FakeClock(1_000L), resolver, connector)

            assertTrue(
                cardFailure("a connect the server accepted means the server is there, so '$address' must answer reachable"),
                probe.isServerReachable(),
            )
            assertEquals(
                cardFailure("one cold question must dial exactly once for '$address'"),
                1,
                connector.callCount,
            )
            assertEquals(
                cardFailure("the literal in brackets must be dialled as written for '$address'"),
                InetAddress.getByName("2001:0db8::1"),
                connector.allAttempts.single().address,
            )
            assertEquals(
                cardFailure("with no port written, '$address' takes the default port of its scheme"),
                expectedPort,
                connector.allAttempts.single().port,
            )
            assertEquals(
                cardFailure("an address literal must never reach the resolver for '$address'"),
                emptyList<String>(),
                resolver.hostsAskedFor,
            )
        }
    }

    // --- A changed configured address changes what is dialled -----------------------------

    @Test
    fun `a configured address that changes only its port changes the dialled port`() {
        var address = "http://box.local:8081"
        val connector = RecordingConnector(script = listOf(ProbeOutcome.CONNECTED))
        val probe = TcpConnectivityProbe({ address }, FakeClock(1_000L), FakeHostResolver(), connector)

        probe.isServerReachable()
        address = "http://box.local:8082"
        probe.isServerReachable()

        assertEquals(
            cardFailure("a changed port is a changed address, so the second question must dial again - two dials in all"),
            2,
            connector.callCount,
        )
        assertEquals(
            cardFailure("each question must dial the port that is configured at that moment, not the first one again"),
            listOf(8081, 8082),
            connector.ports,
        )
        assertEquals(
            cardFailure("the host did not change, so both dials are to it"),
            listOf("box.local", "box.local"),
            connector.hosts,
        )
    }

    @Test
    fun `a configured http address that changes its host changes the dialled host`() {
        var address = "http://box.local"
        val connector = RecordingConnector(script = listOf(ProbeOutcome.CONNECTED))
        val resolver = FakeHostResolver()
        val probe = TcpConnectivityProbe({ address }, FakeClock(1_000L), resolver, connector)

        probe.isServerReachable()
        address = "http://other.local"
        probe.isServerReachable()

        assertEquals(
            cardFailure("a changed host is a changed address, so the second question must dial again - two dials in all"),
            2,
            connector.callCount,
        )
        assertEquals(
            cardFailure("each question must dial the host that is configured at that moment"),
            listOf("box.local", "other.local"),
            connector.hosts,
        )
        assertEquals(
            cardFailure("an http address with no port dials the plain port each time"),
            listOf(80, 80),
            connector.ports,
        )
        assertEquals(
            cardFailure("only the two configured hosts may be looked up, each once"),
            listOf("box.local", "other.local"),
            resolver.hostsAskedFor,
        )
    }

    @Test
    fun `a configured address that changes only its scheme changes the default port that is dialled`() {
        var address = "http://box.local"
        val connector = RecordingConnector(script = listOf(ProbeOutcome.CONNECTED))
        val probe = TcpConnectivityProbe({ address }, FakeClock(1_000L), FakeHostResolver(), connector)

        probe.isServerReachable()
        address = "https://box.local"
        probe.isServerReachable()

        assertEquals(
            cardFailure("moving from the plain port to the secure port is a changed address, so the second question must dial again"),
            2,
            connector.callCount,
        )
        assertEquals(
            cardFailure("the default port follows the scheme that is configured at that moment"),
            listOf(80, 443),
            connector.ports,
        )
        assertEquals(
            cardFailure("the host did not change, so both dials are to it"),
            listOf("box.local", "box.local"),
            connector.hosts,
        )
    }
}
