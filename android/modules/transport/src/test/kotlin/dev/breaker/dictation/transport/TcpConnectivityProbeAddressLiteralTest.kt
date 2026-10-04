package dev.breaker.dictation.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

/**
 * Which addresses the probe may touch, and what it does with one it cannot turn
 * into a host name.
 *
 * The resolver here records every name it was asked about, and that record is
 * asserted rather than the absence of a dial. A name resolution is a contact
 * with the outside world exactly as much as a socket is: a probe that quietly
 * sent "2001:db8::1" or "zz::1" to the resolver has already left the configured
 * Local Server, whether or not a dial followed. So "this address never reaches
 * the resolver" is asserted as an empty list of names, and "this address is
 * dialled as written" is asserted as the ADDRESS that was handed to the
 * connector, not as the boolean that came back.
 *
 * The two live-socket cases at the end are the only tests in this module that
 * open a socket, and they open one on loopback only. Everything else is a
 * double, because a real dial to a real host would make the suite depend on
 * somebody's network.
 */
class TcpConnectivityProbeAddressLiteralTest : ProbePoolIsolation() {

    // --- An address literal is dialled as written, never looked up ------------------

    @Test
    fun `a bracketed address literal is dialled as written and never resolved`() {
        // RFC 3986 puts colons in an authority inside brackets precisely because
        // an unbracketed colon is indistinguishable from the port separator. So
        // the brackets are what says "this is an address, not a name", and a
        // probe that cannot read them turns a perfectly good literal into a DNS
        // query for text that is not a name.
        val connector = RecordingConnector(script = listOf(ProbeOutcome.CONNECTED))
        val resolver = FakeHostResolver()
        val probe = TcpConnectivityProbe(
            { "http://[2001:db8::1]:8080" },
            FakeClock(1_000L),
            resolver,
            connector,
        )

        assertTrue(
            cardFailure("a connect the server accepted means the server is there, so this must answer reachable"),
            probe.isServerReachable(),
        )
        assertEquals(
            cardFailure("one cold question must dial exactly once, not once per candidate address"),
            1,
            connector.callCount,
        )
        assertEquals(
            cardFailure("the brackets said 'this is an address', so the address must be dialled as written - a dial to a resolved name would be a dial to whatever that name happened to point at, which is not what the operator configured"),
            InetAddress.getByName("2001:db8::1"),
            connector.allAttempts.single().address,
        )
        assertEquals(
            cardFailure("the port after the closing bracket is the port the operator asked for"),
            8080,
            connector.ports.single(),
        )
        assertEquals(
            cardFailure("an address literal is already an address, so it must never reach the resolver - a lookup here sends the literal out as a host name, and the answer comes back naming whatever the resolver invented"),
            emptyList<String>(),
            resolver.hostsAskedFor,
        )
    }

    @Test
    fun `a numeric address is dialled as written and never resolved`() {
        // The dotted quad is the case a lookup cannot even survive: it is a
        // valid host NAME syntactically, so a resolver that is asked will answer
        // with something, and the answer is not necessarily this address.
        val connector = RecordingConnector(script = listOf(ProbeOutcome.CONNECTED))
        val resolver = FakeHostResolver()
        val probe = TcpConnectivityProbe(
            { "http://192.0.2.10:8080" },
            FakeClock(1_000L),
            resolver,
            connector,
        )

        assertTrue(
            cardFailure("a connect the server accepted means the server is there, so this must answer reachable"),
            probe.isServerReachable(),
        )
        assertEquals(
            cardFailure("one cold question must dial exactly once, not once per candidate address"),
            1,
            connector.callCount,
        )
        assertEquals(
            cardFailure("four dotted groups of digits name an address rather than a host, so the address itself must be dialled - dialling whatever a lookup returned would probe an address the operator never typed"),
            InetAddress.getByName("192.0.2.10"),
            connector.allAttempts.single().address,
        )
        assertEquals(
            cardFailure("the port in the configured address is the port the operator asked for"),
            8080,
            connector.ports.single(),
        )
        assertEquals(
            cardFailure("a numeric address must never reach the resolver - the resolver's own record is asserted because no dial is not the same as no lookup"),
            emptyList<String>(),
            resolver.hostsAskedFor,
        )
    }

    // --- A colon host that is not an address is refused, not looked up --------------

    @Test
    fun `a colon host that is not an address literal is refused and never resolved`() {
        // "zz" is not a hex group, so this is not an address written without
        // brackets - it is text that names nothing. The tempting failure is to
        // treat "host contains a colon" as "host is an IPv6 literal" and either
        // build an address from nonsense or hand the nonsense to the resolver.
        // Both leave the configured server: the first by dialling something
        // invented, the second by asking the network about a name that was
        // never a name. A host containing a colon is an address literal only
        // when every character in it is a hex digit, a colon or a dot.
        val connector = RecordingConnector(script = listOf(ProbeOutcome.CONNECTED))
        val resolver = FakeHostResolver()
        val probe = TcpConnectivityProbe(
            { "http://[zz::1]:8080" },
            FakeClock(1_000L),
            resolver,
            connector,
        )

        assertFalse(
            cardFailure("'zz::1' is not an address in any notation, so it names no server and there is nothing to dial - and the connector is scripted CONNECTED, so any true here means the probe reached a dial for text that was never an address"),
            probe.isServerReachable(),
        )
        assertEquals(
            cardFailure("text that is not an address must be refused before any dial is attempted - a dial here reaches a host the operator never configured"),
            0,
            connector.callCount,
        )
        assertEquals(
            cardFailure("a colon host that is not an address literal is malformed, and a malformed authority must never be looked up - a lookup puts 'zz::1' in front of whatever name server the device is configured with, which is leaving the configured server for a name that does not exist"),
            emptyList<String>(),
            resolver.hostsAskedFor,
        )
    }

    // --- The real socket connector, on loopback only --------------------------------

    @Test
    fun `the real connector answers connected on a bound loopback port and refused on a free one`() {
        // The only test here that opens a socket, and it opens one on loopback
        // with port 0 so the operating system picks a free port: a hardcoded
        // port would collide with whatever else the machine happens to be
        // running, and this module's promise is about a real connect, not about
        // a particular number.
        val listening = ServerSocket(0)
        try {
            val boundPort = listening.localPort
            assertTrue(
                cardFailure("the probe must find a server that is actually listening on loopback, or a healthy Local Server reads as unreachable and every dictation fails against a working setup"),
                probeOn("http://127.0.0.1:$boundPort").isServerReachable(),
            )
        } finally {
            listening.close()
        }

        // A port that was bound and then released is free AND unbound, which is
        // the ordinary "the Local Server is not running" case: the connect is
        // actively refused rather than timing out. Refused and timed-out both
        // answer not reachable at the probe, so this needs no network and no
        // sleep - the port answers immediately either way.
        val released = ServerSocket(0)
        val releasedPort = released.localPort
        released.close()
        assertFalse(
            cardFailure("nothing is listening on $releasedPort, so the connect is refused and the server is not there - a probe that answered true here would report a server that does not exist as reachable"),
            probeOn("http://127.0.0.1:$releasedPort").isServerReachable(),
        )
    }

    /**
     * The production probe: the platform resolver and the real socket.
     *
     * The two-argument constructor is the only shape `android/app` uses, so
     * going through it is what makes this a test of the shipped wiring and not
     * of a hand-assembled one. 127.0.0.1 is a numeric address, so the probe
     * dials it without consulting the platform's name lookup at all.
     */
    private fun probeOn(serverUrl: String): TcpConnectivityProbe =
        TcpConnectivityProbe({ serverUrl }, FakeClock(1_000L))
}
