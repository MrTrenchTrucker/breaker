package dev.breaker.dictation.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ordered refresh: [TcpConnectivityProbe.refresh] walks the stored entries IN LIST ORDER,
 * stops at the first that answers, records that entry, and records nothing when none answers.
 *
 * Every assertion reads what the connector was actually asked, so a refresh that reorders the
 * list, tries the single configured address (the last good / effective one) ahead of it, keeps
 * dialling after an answer, or writes a last good address it never measured fails on the record
 * and not on the boolean. The last good address is the caller's start value and no-answer
 * fallback, never a head start: the probe is handed a list and a recorder, and it never reads
 * [TcpConnectivityProbe] 's single-address provider to decide what to dial.
 *
 * No real DNS and no real sockets: the connector and the resolver are the module's doubles, and
 * the class drains the shared pool before and after like every other probe test.
 */
class TcpConnectivityProbeRefreshOrderTest : ProbePoolIsolation() {

    /** A recorder for the entries [TcpConnectivityProbe] hands to `recordLastGood`. */
    private fun recorder(): Pair<MutableList<String>, (String) -> Unit> {
        val recorded = mutableListOf<String>()
        return recorded to { address -> recorded += address }
    }

    @Test
    fun `refresh asks the entries in list order and stops at the first that answers`() {
        val connector = RecordingConnector(
            script = listOf(ProbeOutcome.REFUSED, ProbeOutcome.CONNECTED, ProbeOutcome.CONNECTED),
        )
        val (recorded, recordLastGood) = recorder()
        val probe = TcpConnectivityProbe(
            serverUrlProvider = { "https://configured.invalid" },
            clock = FakeClock(1_000L),
            resolver = FakeHostResolver(),
            connector = connector,
            serverAddressProvider = {
                listOf("https://entry-1.invalid", "https://entry-2.invalid", "https://entry-3.invalid")
            },
            recordLastGood = recordLastGood,
        )

        assertTrue(
            cardFailure("entry 1 refused and entry 2 accepted, so the refresh must answer reachable"),
            probe.refresh(),
        )
        assertEquals(
            cardFailure("the entries must be asked in list order and stop at the first answer - entry 3 must never be dialled"),
            listOf("entry-1.invalid", "entry-2.invalid"),
            connector.hosts,
        )
        assertEquals(
            cardFailure("two entries were asked, so exactly two dials were made"),
            2,
            connector.callCount,
        )
        assertEquals(
            cardFailure("the entry that answered is the one recorded"),
            listOf("https://entry-2.invalid"),
            recorded,
        )
    }

    @Test
    fun `isReachable answers about the address it is given`() {
        val connector = RecordingConnector(script = listOf(ProbeOutcome.CONNECTED))
        val probe = TcpConnectivityProbe(
            serverUrlProvider = { "https://configured.invalid" },
            clock = FakeClock(1_000L),
            resolver = FakeHostResolver(),
            connector = connector,
        )

        assertTrue(
            cardFailure("the address handed to isReachable answers, so it must be reachable"),
            probe.isReachable("https://other.invalid"),
        )
        assertEquals(
            cardFailure("isReachable must dial the address it was GIVEN, never the configured one - the selection's order is the caller's"),
            listOf("other.invalid"),
            connector.hosts,
        )
    }

    @Test
    fun `the single configured address is never asked ahead of the stored list`() {
        val connector = RecordingConnector(script = listOf(ProbeOutcome.CONNECTED))
        val (recorded, recordLastGood) = recorder()
        val probe = TcpConnectivityProbe(
            // The effective address: the last good address when nothing answers. A refresh that
            // dialed this first would give the last good a head start over entry 1.
            serverUrlProvider = { "https://last-good.invalid" },
            clock = FakeClock(1_000L),
            resolver = FakeHostResolver(),
            connector = connector,
            serverAddressProvider = { listOf("https://entry-1.invalid") },
            recordLastGood = recordLastGood,
        )

        assertTrue(
            cardFailure("the stored entry answers, so the refresh must answer reachable"),
            probe.refresh(),
        )
        assertEquals(
            cardFailure("the refresh must dial the stored list, never the single configured address ahead of it"),
            listOf("entry-1.invalid"),
            connector.hosts,
        )
        assertEquals(
            cardFailure("the stored entry is the winner"),
            listOf("https://entry-1.invalid"),
            recorded,
        )
    }

    @Test
    fun `when no entry answers nothing is recorded`() {
        val connector = RecordingConnector(script = listOf(ProbeOutcome.REFUSED))
        val (recorded, recordLastGood) = recorder()
        val probe = TcpConnectivityProbe(
            serverUrlProvider = { "https://configured.invalid" },
            clock = FakeClock(1_000L),
            resolver = FakeHostResolver(),
            connector = connector,
            serverAddressProvider = {
                listOf("https://entry-1.invalid", "https://entry-2.invalid", "https://entry-3.invalid")
            },
            recordLastGood = recordLastGood,
        )

        assertFalse(
            cardFailure("every entry refused, so the refresh must answer not reachable"),
            probe.refresh(),
        )
        assertEquals(
            cardFailure("every entry must still be asked, in order, before the refresh gives up"),
            listOf("entry-1.invalid", "entry-2.invalid", "entry-3.invalid"),
            connector.hosts,
        )
        assertTrue(
            cardFailure("a refresh that measured no answer must not write a last good address - recording one would give the next start an address that never answered"),
            recorded.isEmpty(),
        )
    }

    @Test
    fun `an entry that cannot be dialled is skipped without a dial`() {
        val connector = RecordingConnector(script = listOf(ProbeOutcome.CONNECTED))
        val (recorded, recordLastGood) = recorder()
        val probe = TcpConnectivityProbe(
            serverUrlProvider = { "https://configured.invalid" },
            clock = FakeClock(1_000L),
            resolver = FakeHostResolver(),
            connector = connector,
            serverAddressProvider = { listOf("not a valid address", "https://entry-2.invalid") },
            recordLastGood = recordLastGood,
        )

        assertTrue(
            cardFailure("the second entry answers, so the refresh must answer reachable"),
            probe.refresh(),
        )
        assertEquals(
            cardFailure("the unusable entry must cost no dial and the usable one must be dialled"),
            listOf("entry-2.invalid"),
            connector.hosts,
        )
        assertEquals(
            cardFailure("the usable entry is the winner"),
            listOf("https://entry-2.invalid"),
            recorded,
        )
    }

    @Test
    fun `a cached answer does not short-circuit the ordered refresh`() {
        val connector = RecordingConnector(
            script = listOf(ProbeOutcome.CONNECTED, ProbeOutcome.REFUSED, ProbeOutcome.CONNECTED),
        )
        val (recorded, recordLastGood) = recorder()
        val probe = TcpConnectivityProbe(
            serverUrlProvider = { "https://configured.invalid" },
            clock = FakeClock(1_000L),
            resolver = FakeHostResolver(),
            connector = connector,
            serverAddressProvider = { listOf("https://entry-1.invalid", "https://entry-2.invalid") },
            recordLastGood = recordLastGood,
        )

        assertTrue(
            cardFailure("the first refresh: entry 1 answers and is cached"),
            probe.refresh(),
        )
        assertTrue(
            cardFailure("the second refresh: entry 1 now refuses and entry 2 answers, so the refresh must reach entry 2"),
            probe.refresh(),
        )
        assertEquals(
            cardFailure("each refresh must REALLY dial every entry it asks - a cached answer for entry 1 would leave the second refresh dialling only entry 2, and the winner would be decided by a stale measurement"),
            listOf("entry-1.invalid", "entry-1.invalid", "entry-2.invalid"),
            connector.hosts,
        )
        assertEquals(
            cardFailure("each refresh records its own winner"),
            listOf("https://entry-1.invalid", "https://entry-2.invalid"),
            recorded,
        )
    }
}
