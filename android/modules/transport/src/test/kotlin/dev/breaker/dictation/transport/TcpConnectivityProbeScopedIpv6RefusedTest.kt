package dev.breaker.dictation.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * A scoped IPv6 literal is refused by design, and this pins that refusal.
 *
 * A link-local address with a zone id (written [fe80::1%eth0]) is a real
 * address, but the zone id varies by platform and the population is narrow, so
 * the module does not treat it as a dialable literal. It is refused: it yields
 * no address, dials nothing, and the probe answers "not reachable" without a
 * lookup, a dial, an exception or a hang. The refusal is the contract, and this
 * test keeps it - a future change that starts dialling a scoped literal, or that
 * lets it reach the resolver, breaks this pin.
 *
 * The observation is behavioural, not reflective: the connector records every
 * dial, so "refused, nothing dialled" is `callCount == 0` with a not-reachable
 * answer, and "it started dialling" is `callCount >= 1`. No private field of the
 * probe or the pool is read.
 */
class TcpConnectivityProbeScopedIpv6RefusedTest : ProbePoolIsolation() {

    /**
     * A scoped IPv6 literal is refused by design: the probe answers not
     * reachable and dials nothing, even though a connector was scripted to
     * accept.
     */
    @Test
    fun `a scoped ipv6 literal is refused by design with no dial`() {
        val connector = RecordingConnector(script = listOf(ProbeOutcome.CONNECTED))
        val probe = TcpConnectivityProbe(
            { "https://[fe80::1%eth0]:8443" },
            FakeClock(1_000L),
            FakeHostResolver(),
            connector,
        )

        val answer = probe.isServerReachable()

        assertFalse(
            cardFailure("a scoped ipv6 literal is refused by design, so the probe must answer not reachable - a reachable here means the literal was dialled or resolved, which is the change this pin exists to catch. Dials recorded: " + connector.hosts),
            answer,
        )
        assertEquals(
            cardFailure("the refusal is the contract: a scoped ipv6 literal yields no address, so nothing is dialled - a dial here means the literal reached the connector, which is the change this pin exists to catch"),
            0,
            connector.callCount,
        )
    }
}
