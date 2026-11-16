package dev.breaker.dictation.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A READ of the effective address must NEVER dial.
 *
 * [TcpConnectivityProbe.isReachable] dials when the cache is cold. That is right for a caller who
 * can wait and wrong for every reader of the address: auth, hand-off, sync, status and the sign-in
 * browser all read it, on whatever thread they happen to be on, and a cold cache would put up to
 * [TcpConnectivityProbe.CONNECT_TIMEOUT_MS] per entry of socket work under them - on the main
 * thread, which Android refuses outright.
 *
 * So the answer source a read uses is [TcpConnectivityProbe.cachedAnswer], and these tests pin the
 * property that matters: asking it never reaches the connector. Every assertion reads
 * [RecordingConnector.callCount], so a cachedAnswer that dialled even once fails here.
 *
 * No real DNS and no real sockets: the connector and the resolver are the module's doubles, and the
 * class drains the shared pool before and after like every other probe test.
 */
class TcpConnectivityProbeCachedAnswerTest : ProbePoolIsolation() {

    private val entry1 = "https://entry-1.invalid"
    private val entry2 = "https://entry-2.invalid"

    private fun probe(
        connector: RecordingConnector,
        entries: () -> List<String> = { listOf(entry1, entry2) },
    ) = TcpConnectivityProbe(
        serverUrlProvider = { "https://configured.invalid" },
        clock = FakeClock(1_000L),
        resolver = FakeHostResolver(),
        connector = connector,
        serverAddressProvider = entries,
    )

    @Test
    fun `a cold probe answers nothing and never dials`() {
        val connector = RecordingConnector(script = listOf(ProbeOutcome.REFUSED))
        val probe = probe(connector)

        assertNull(
            cardFailure("nothing has been measured, so the read must have no answer to give"),
            probe.cachedAnswer(entry1),
        )
        assertNull(
            cardFailure("the second entry is equally unmeasured"),
            probe.cachedAnswer(entry2),
        )
        assertEquals(
            cardFailure("a READ must never dial: two reads cost zero dials"),
            0,
            connector.callCount,
        )
    }

    @Test
    fun `an address that cannot be turned into a connection answers nothing and never dials`() {
        val connector = RecordingConnector(script = listOf(ProbeOutcome.CONNECTED))
        val probe = probe(connector)

        assertNull(
            cardFailure("an unusable address has no cache entry, so no answer"),
            probe.cachedAnswer("not a valid address"),
        )
        assertEquals(
            cardFailure("an unusable address must cost no dial either"),
            0,
            connector.callCount,
        )
    }

    @Test
    fun `after a refresh the winner answers from the cache and the read dials nothing`() {
        val connector = RecordingConnector(
            script = listOf(ProbeOutcome.REFUSED, ProbeOutcome.CONNECTED, ProbeOutcome.REFUSED),
        )
        val probe = probe(connector)

        assertTrue(
            cardFailure("the refresh must reach entry 2, which answers"),
            probe.refresh(),
        )
        val dialsAfterRefresh = connector.callCount

        assertTrue(
            cardFailure("the measured winner must be answered from the cache"),
            probe.cachedAnswer(entry2) == true,
        )
        assertFalse(
            cardFailure("the losing entry has no fresh cache entry, so it has no answer"),
            probe.cachedAnswer(entry1) == true,
        )
        assertEquals(
            cardFailure("the reads after the refresh must cost no further dial"),
            dialsAfterRefresh,
            connector.callCount,
        )
    }

    @Test
    fun `when entry 1 answers again at the next refresh the cache follows it`() {
        val connector = RecordingConnector(
            script = listOf(ProbeOutcome.REFUSED, ProbeOutcome.CONNECTED, ProbeOutcome.CONNECTED),
        )
        val probe = probe(connector)

        assertTrue(cardFailure("the first refresh reaches entry 2"), probe.refresh())
        assertTrue(
            cardFailure("entry 2 is the cached winner after the first refresh"),
            probe.cachedAnswer(entry2) == true,
        )

        assertTrue(
            cardFailure("the second refresh really dialled entry 1 and it answered this time"),
            probe.refresh(),
        )
        assertTrue(
            cardFailure("entry 1 answered last, so it is the cached answer now"),
            probe.cachedAnswer(entry1) == true,
        )
    }

    @Test
    fun `an expired cache entry answers nothing rather than dialling`() {
        val connector = RecordingConnector(
            script = listOf(ProbeOutcome.CONNECTED, ProbeOutcome.REFUSED),
        )
        val clock = FakeClock(1_000L)
        val probe = TcpConnectivityProbe(
            serverUrlProvider = { "https://configured.invalid" },
            clock = clock,
            resolver = FakeHostResolver(),
            connector = connector,
            serverAddressProvider = { listOf(entry1) },
        )

        assertTrue(cardFailure("the refresh caches entry 1"), probe.refresh())
        assertTrue(cardFailure("inside the window the answer is cached"), probe.cachedAnswer(entry1) == true)

        clock.instant = clock.instant + TcpConnectivityProbe.CACHE_TTL_MS + 1
        assertNull(
            cardFailure("past the TTL the measurement is stale, so the read has no answer"),
            probe.cachedAnswer(entry1),
        )
        assertEquals(
            cardFailure("the stale read must not dial to replace the measurement - only refresh() dialles"),
            1,
            connector.callCount,
        )
    }

    @Test
    fun `the default address provider is a function, so a changed configured address is picked up`() {
        val connector = RecordingConnector(
            script = listOf(ProbeOutcome.CONNECTED, ProbeOutcome.CONNECTED),
        )
        var configured = "https://one.invalid"
        // No serverAddressProvider: this is the DEFAULT, which must be a lambda reading the
        // configured address at refresh time, not a list captured at construction.
        val probe = TcpConnectivityProbe(
            serverUrlProvider = { configured },
            clock = FakeClock(1_000L),
            resolver = FakeHostResolver(),
            connector = connector,
        )

        assertTrue(cardFailure("the first refresh dials the configured address"), probe.refresh())
        configured = "https://two.invalid"
        assertTrue(
            cardFailure("the second refresh must dial the NEW configured address, so the default provider is read each time"),
            probe.refresh(),
        )
        assertEquals(
            cardFailure("the default provider must follow the configured address across refreshes"),
            listOf("one.invalid", "two.invalid"),
            connector.hosts,
        )
    }
}
