package dev.breaker.dictation.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * The address-literal classifier, tested where it decides: before any lookup.
 *
 * The defect these tests pin down is that "looks like an address" was decided
 * by SHAPE alone. "999.1.1.1" is four all-digit groups, so the old classifier
 * called it a literal and handed it to [InetAddress.getByName] — which, on the
 * JDK, only attempts the numeric parse when the first character is a hex digit
 * or ':'. A group out of range fails that parse, and with no ':' in the text
 * there is no IPv6 branch to fall into, so the JDK falls through to a REAL
 * SYSTEM DNS LOOKUP: 70-120 ms of network for an address the operator never
 * configured, off the reachability budget's fast path and invisible to every
 * other seam in this module. Colon rows such as ".:", "..::1" and ".1::"
 * pass the character-set test and are not addresses either, but they are
 * refused by the JDK rather than looked up — the mechanism differs, the
 * refusal does not, which is why each row's failure message names only the
 * consequence that row actually has.
 *
 * So the two tests here meet the defect at the two ends of it. The table test
 * asserts the classifier itself, which is the ONLY place the range and
 * first-character rules are observable — an end-to-end probe cannot tell
 * "refused" from "looked up and failed", because both answer false. The
 * end-to-end test asserts the two things the platform can be asked about: how
 * many dials were attempted, and which names the resolver was asked for.
 *
 * Every address here is from a documentation range (192.0.2.0/24,
 * 198.51.100.0/24, 2001:db8::/32). No test here resolves a name, opens a
 * socket or sleeps.
 */
class TcpConnectivityProbeStrictAddressLiteralTest : ProbePoolIsolation() {

    // --- U1: the classifier itself ------------------------------------------------

    @Test
    fun `the classifier accepts a valid address literal and refuses malformed numeric text`() {
        // Each row states what the operator would have to have MEANT, and the
        // two lists are the whole contract. A valid literal must be dialled as
        // written; text that merely resembles one must be refused here, because
        // refusing is the only branch that never touches the network.
        val literals = listOf(
            "192.0.2.10",
            "2001:db8::1",
            "::1",
            "::ffff:192.0.2.1",
            // The unspecified address: all colons, no digits, and a valid
            // literal in every notation that has one.
            "::",
            // PINS CURRENT BEHAVIOUR, and only that: each group is read as
            // DECIMAL, so "010" is 10 and this is a literal. Measured on JDK
            // 17, InetAddress.getByName("01.2.3.4") parses as 1.2.3.4, so
            // dialling this address reaches 10.0.0.1 — which is what the
            // operator's own text means under decimal reading. This row does
            // NOT mean leading zeros are universally safe: a future reader
            // must not generalise it to octal-intent text, where "010" would
            // be 8 and the answer would silently point somewhere else. It
            // pins THIS classifier's digit rule, nothing more.
            "010.0.0.1",
        )
        // Each row carries the consequence that row actually has if it were
        // called a literal, because they are NOT all the same consequence: a
        // colon host is REFUSED by the JDK without any lookup, a digit-count
        // row PARSES into a different address and gets dialled, and only the
        // rows with no colon and no successful numeric parse fall through to a
        // real DNS lookup. One shared message would assert the last mechanism
        // for all ten rows, which is false for six of them.
        val notLiterals = listOf(
            "999.1.1.1" to numericParseFails,
            "1.2.3.256" to numericParseFails,
            ".:" to jdkRefuses,
            "..::1" to jdkRefuses,
            "zz::1" to jdkRefuses,
            // Group COUNT, both directions. Three groups is short and five is
            // long; neither is an address, and neither contains a colon, so
            // under a `groups.size < V4_GROUP_COUNT` (or `!=`) mutation
            // "1.2.3.4.5" counts as a literal and InetAddress.getByName is
            // called on it — a REAL system DNS lookup. These two rows exist
            // because the rows above are all refused by a STRICTER rule first,
            // so the count rule itself is otherwise unobserved.
            "1.2.3" to numericParseFails,
            "1.2.3.4.5" to numericParseFails,
            // DIGIT COUNT, not range: four digits in the first group and 255
            // is in range, so only "at most three digits" can refuse this. A
            // mutation that drops the length check would read "0255" as 255
            // and accept text that is not a group at all — and since the JDK
            // does read it as 255, calling it a literal DIALS 255.0.0.1.
            "0255.0.0.1" to jdkParsesAsAnotherAddress,
            // First character VALID, later character invalid — the two halves
            // of the IPv6 rule, separated so neither can be satisfied by the
            // other. "zz::1" already pins the failing first character; these
            // pin that the character SET is still applied after it passes.
            // All three contain a colon, so the JDK refuses them without a
            // lookup; the consequence is not a DNS query.
            "1:zz::1" to jdkRefuses,
            "::zz" to jdkRefuses,
        )

        literals.forEach { host ->
            assertTrue(
                cardFailure("'$host' is an address literal, so refusing it would send a perfectly good address to the resolver as a host name - and the answer that came back would name whatever the resolver invented"),
                isAddressLiteral(host),
            )
        }
        notLiterals.forEach { (host, consequence) ->
            assertFalse(
                cardFailure("'$host' is not an address in any notation, but calling it a literal hands it to InetAddress.getByName, $consequence - refusing it here is the only branch that never leaves the operator's own text"),
                isAddressLiteral(host),
            )
        }
    }

    @Test
    fun `a four group dotted quad outside zero to two five five is malformed rather than a literal`() {
        // The range rule is what makes "999.1.1.1" and "4294967295.0.0.0"
        // refusals rather than literals. Group COUNT alone cannot: both have
        // exactly four all-digit groups, and the JDK's numeric parse rejects
        // them, which is precisely the fall-through to DNS this rule prevents.
        listOf("999.1.1.1", "1.2.3.256", "4294967295.0.0.0", "256.0.0.1").forEach { host ->
            assertFalse(
                cardFailure("each group of an IPv4 literal is 0-255, so '$host' is malformed text and not an address - accepting it is what turns the probe into a DNS query for a name that was never a name"),
                isAddressLiteral(host),
            )
        }
        // And the boundary is inclusive on both ends, so this is not a rule
        // that merely rejects everything unusual.
        listOf("0.0.0.0", "255.255.255.255", "198.51.100.7").forEach { host ->
            assertTrue(
                cardFailure("'$host' is a valid IPv4 literal, so it must be dialled as written - a rule that refused the range boundaries would refuse every address a real deployment configures"),
                isAddressLiteral(host),
            )
        }
    }

    // --- U2: the same rule, end to end through the probe --------------------------

    @Test
    fun `a malformed numeric host is not reachable and is never looked up or dialled`() {
        // "http://999.1.1.1:8080" parses cleanly as an authority, so it reaches
        // the classifier as a host with no colon in it. The old classifier called
        // it a literal, InetAddress.getByName attempted a numeric parse, the
        // parse failed on the out-of-range group, and with no ':' in the text
        // the JDK fell through to a real system DNS lookup. The connector is
        // scripted CONNECTED, so a true here would mean the probe dialled
        // something the resolver invented; callCount and hostsAskedFor are the
        // two facts a platform can actually be asked about.
        val connector = RecordingConnector(script = listOf(ProbeOutcome.CONNECTED))
        val resolver = FakeHostResolver()
        val probe = TcpConnectivityProbe(
            { "http://999.1.1.1:8080" },
            FakeClock(1_000L),
            resolver,
            connector,
        )

        assertFalse(
            cardFailure("'999.1.1.1' names no address - the first group is out of range - so there is nothing to dial, and the connector is scripted CONNECTED, so any true here means the probe dialled an address the operator never configured"),
            probe.isServerReachable(),
        )
        assertEquals(
            cardFailure("malformed numeric text must be refused before any dial - a dial here reaches a host the operator never typed"),
            0,
            connector.callCount,
        )
        assertEquals(
            cardFailure("this is the defect: '999.1.1.1' was accepted as a literal and InetAddress.getByName fell through to a REAL SYSTEM DNS LOOKUP, so an empty hostsAskedFor is the fix, not the absence of a dial"),
            emptyList<String>(),
            resolver.hostsAskedFor,
        )
    }

    @Test
    fun `a malformed colon host is not reachable and is never looked up or dialled`() {
        // "http://[.:]:8080" is the bracketed half of the same defect. Brackets
        // strip to the host ".:", every character is a colon or a dot so it
        // passes a character-set test, and it is not an address in any
        // notation. Handed to InetAddress.getByName it fails the numeric parse
        // too - and unlike the dotted case there IS a colon in the text, which
        // is what makes this row a distinct failure from "999.1.1.1".
        listOf(".:", "..::1", ".1::").forEach { host ->
            val connector = RecordingConnector(script = listOf(ProbeOutcome.CONNECTED))
            val resolver = FakeHostResolver()
            val probe = TcpConnectivityProbe(
                { "http://[$host]:8080" },
                FakeClock(1_000L),
                resolver,
                connector,
            )

            assertFalse(
                cardFailure("'$host' is not an address in any notation, so it names no server and there is nothing to dial - and the connector is scripted CONNECTED, so any true here means the probe reached a dial for text that was never an address"),
                probe.isServerReachable(),
            )
            assertEquals(
                cardFailure("text that is not an address must be refused before any dial is attempted - a dial here reaches a host the operator never configured"),
                0,
                connector.callCount,
            )
            assertEquals(
                cardFailure("a colon host that is not an address literal is malformed, and a malformed authority must never be looked up - a lookup puts '$host' in front of whatever name server the device is configured with"),
                emptyList<String>(),
                resolver.hostsAskedFor,
            )
        }
    }

    @Test
    fun `a numeric host that is not a valid address never reaches the resolver`() {
        // These are the rows of the same defect that a double CAN see, and so
        // are what makes it RED end to end rather than only in the table test.
        // "1.2.3" is three groups and "1.2.3.4.5" is five, so the old
        // classifier called neither a literal — and neither contains a colon,
        // which was the only other thing that refused a host. So they fell
        // straight through to resolver.resolve, putting "1.2.3" in front of
        // whatever name server the device is configured with. A host name with
        // no colon used to be the ONLY thing the resolver was allowed to see,
        // and this is the case that broke that.
        listOf("1.2.3", "1.2.3.4.5", "192.0.2.999", "300.300.300.300").forEach { host ->
            val connector = RecordingConnector(script = listOf(ProbeOutcome.CONNECTED))
            val resolver = FakeHostResolver()
            val probe = TcpConnectivityProbe(
                { "http://$host:8080" },
                FakeClock(1_000L),
                resolver,
                connector,
            )

            assertFalse(
                cardFailure("'$host' is not a valid address in any notation, so it names no server and there is nothing to dial - and the connector is scripted CONNECTED, so any true here means the probe dialled whatever a lookup invented"),
                probe.isServerReachable(),
            )
            assertEquals(
                cardFailure("text that is not an address must be refused before any dial is attempted - a dial here reaches a host the operator never configured"),
                0,
                connector.callCount,
            )
            assertEquals(
                cardFailure("'$host' is written as an address and is not one, so it is malformed rather than a name: the resolver's record is asserted because a lookup of this text is a real DNS query for a name that does not exist"),
                emptyList<String>(),
                resolver.hostsAskedFor,
            )
        }
    }

    @Test
    fun `a valid address literal still bypasses the resolver and is dialled as written`() {
        // The other direction of the same rule: tightening the classifier must
        // not start refusing real addresses. A literal that reaches the
        // resolver has left the configured server before it is dialled, so this
        // asserts the ADDRESS handed to the connector, not just the boolean.
        listOf(
            "192.0.2.10" to 8080,
            "2001:db8::1" to 8080,
            "::1" to 8080,
            "::ffff:192.0.2.1" to 8080,
        ).forEach { (host, port) ->
            val connector = RecordingConnector(script = listOf(ProbeOutcome.CONNECTED))
            val resolver = FakeHostResolver()
            val probe = TcpConnectivityProbe(
                { "http://[$host]:$port" },
                FakeClock(1_000L),
                resolver,
                connector,
            )

            assertTrue(
                cardFailure("'$host' is a valid literal, so the probe must dial it as written and a connect the server accepted must answer reachable - a false here would mean the tightened rule started refusing good addresses"),
                probe.isServerReachable(),
            )
            assertEquals(
                cardFailure("one cold question must dial exactly once, not once per candidate address"),
                1,
                connector.callCount,
            )
            assertEquals(
                cardFailure("a literal must be dialled as written - dialling whatever a lookup returned would probe an address the operator never typed"),
                InetAddress.getByName(host),
                connector.allAttempts.single().address,
            )
            assertEquals(
                cardFailure("an address literal is already an address, so it must never reach the resolver - the resolver's own record is asserted because no dial is not the same as no lookup"),
                emptyList<String>(),
                resolver.hostsAskedFor,
            )
        }
    }

    @Test
    fun `a host name with no colon still reaches the resolver and is dialled`() {
        // The tightening is scoped to text that looks numeric. A plain host
        // name has no colon and no numeric shape, so it must take the resolver
        // path exactly as before: a probe that refused names would report every
        // Local Server on a DNS name as unreachable.
        val connector = RecordingConnector(script = listOf(ProbeOutcome.CONNECTED))
        val resolver = FakeHostResolver()
        val probe = TcpConnectivityProbe(
            { "https://breaker.invalid" },
            FakeClock(1_000L),
            resolver,
            connector,
        )

        assertTrue(
            cardFailure("a name with no colon is a name, so the probe must resolve it and dial what came back - a probe that refused names would report every Local Server configured by name as unreachable"),
            probe.isServerReachable(),
        )
        assertEquals(
            cardFailure("the name must reach the resolver, which is the only way a Local Server on a DNS name can ever be found"),
            listOf("breaker.invalid"),
            resolver.hostsAskedFor,
        )
        assertEquals(
            cardFailure("the resolved address is the one that must be dialled"),
            443,
            connector.ports.single(),
        )
    }

    private companion object {
        // The consequence clauses used by the U1 "not a literal" table. Each
        // is a complete sentence tail, so the message reads as one thought:
        // "...hands it to InetAddress.getByName, <clause> - refusing it here...".
        // They are kept apart because the JDK does three DIFFERENT things with
        // these ten rows, and only one of them is a lookup.
        const val numericParseFails =
            "which attempts a numeric parse, fails it, and - with no ':' in the text to fall into - falls through to a REAL SYSTEM DNS LOOKUP: 70-120 ms of network for an address the operator never configured"
        const val jdkRefuses =
            "where the JDK refuses the text outright because it contains a ':', so no lookup happens - the rule still has to refuse it, because a hostname-shaped exception here would be the wrong shape to trust"
        const val jdkParsesAsAnotherAddress =
            "where the JDK reads each group as decimal, so this parses as 255.0.0.1 and the probe DIALS 255.0.0.1 - a real socket to an address the operator never configured, with no lookup to make it visible"
    }
}
