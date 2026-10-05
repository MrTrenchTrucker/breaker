package dev.breaker.dictation.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cache is case-insensitive in the host, the same way the single-flight
 * layer is.
 *
 * A host name is one name however it is spelled: the single-flight layer folds
 * case (see [ProbeSingleFlight]), so a case-only change of the configured
 * server is the same name to the lookup. The cache must agree, or a case-only
 * re-read of the same server misses the window and dials again for a server
 * that has not changed. This test pins that the two layers treat the name the
 * same way.
 *
 * The observation is behavioural, not reflective: the connector records every
 * dial, so "served from the cache" is `callCount == 1` and "dialled again" is
 * `callCount == 2`. No private field of the probe or the pool is read.
 */
class TcpConnectivityProbeCacheCaseInsensitiveTest : ProbePoolIsolation() {

    /**
     * A case-only change of the configured server is the same name, so the
     * cached answer is served for it and the probe dials once, not twice.
     */
    @Test
    fun `a case-only change of the configured server is served from the cache without a second dial`() {
        val clock = FakeClock(1_000L)
        var address = "https://Box.local:8443"
        val connector = RecordingConnector(script = listOf(ProbeOutcome.CONNECTED))
        val probe = TcpConnectivityProbe({ address }, clock, FakeHostResolver(), connector)

        // Prime the cache under the first spelling.
        assertTrue(
            cardFailure("the priming dial is accepted, so the first question must answer reachable"),
            probe.isServerReachable(),
        )
        assertEquals(
            cardFailure("the priming question must dial exactly once before the case change is measured"),
            1,
            connector.callCount,
        )

        // The same DNS name, written the other way round. Nothing about the
        // network, the resolver or the port has changed; only the case of the
        // host text. The clock does not move, so the first answer is still
        // inside the 30 s window.
        address = "https://box.local:8443"

        assertTrue(
            cardFailure("the server has not changed and the first answer is still inside the 30 s window, so the case-only re-read must be served from the cache and answer reachable"),
            probe.isServerReachable(),
        )
        assertEquals(
            cardFailure("host names are case-insensitive, and the single-flight layer already folds case - so a case-only change of the configured server is the same name and must be served from the cache, not dialled again. A second dial means the cache compared the host exactly while the lookup compared it folded. Dials recorded: " + connector.hosts),
            1,
            connector.callCount,
        )
    }
}
