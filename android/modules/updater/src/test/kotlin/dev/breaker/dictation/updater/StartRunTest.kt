package dev.breaker.dictation.updater

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The engine's start calls: each path of the first-start decision, with its reads, its abandon and its one write. */
internal class StartRunTest {
    private val rig = StartRig()
    private val start = rig.start

    @Test
    fun `a start with no record and a code that rose shows the installed state and stores the code`() {
        rig.slot.values = ledgerWith(null, 10, null)
        val result = start.onStart()
        assertEquals(Shown.INSTALLED, result.shown)
        assertNull(result.sentence)
        assertEquals(11, rig.slot.values.lastSeenCode)
        assertNull(rig.slot.values.record)
    }

    @Test
    fun `a start with equal codes and a stored sentence shows it once and clears it`() {
        rig.slot.values = ledgerWith(null, 11, UpdateSentences.CANCELLED)
        val result = start.onStart()
        assertEquals(Shown.SENTENCE, result.shown)
        assertEquals(UpdateSentences.CANCELLED, result.sentence)
        assertNull(rig.slot.values.lastOutcome)
    }

    @Test
    fun `a start that finds the session gone clears the record and stores not finished for the next start`() {
        rig.slot.values = ledgerWith(LedgerRecord(7, writtenAgo(1_000L), null), 11, null)
        rig.sessions.known = false
        val result = start.onStart()
        assertEquals(Shown.SENTENCE, result.shown)
        assertEquals(UpdateSentences.NOT_FINISHED, result.sentence)
        assertNull(rig.slot.values.record)
        assertEquals(UpdateSentences.NOT_FINISHED, rig.slot.values.lastOutcome)
    }

    @Test
    fun `an expired uncommitted session with a successful abandon decides again and shows nothing`() {
        rig.slot.values = ledgerWith(LedgerRecord(7, writtenAgo(A_DAY_MILLIS + 1_000L), null), 11, null)
        val result = start.onStart()
        assertEquals(Shown.NOTHING, result.shown)
        assertEquals(1, rig.abandon.calls)
        assertNull(rig.slot.values.record)
    }

    @Test
    fun `an uncommitted session under a day whose abandon fails with an IO error keeps the record`() {
        rig.slot.values = ledgerWith(LedgerRecord(7, writtenAgo(3_600_000L), null), 11, null)
        rig.abandon.thrown = IOException("the test abandon fails")
        val result = start.onStart()
        assertEquals(Shown.SENTENCE, result.shown)
        assertEquals(UpdateSentences.NOT_FINISHED, result.sentence)
        assertEquals(1, rig.abandon.calls)
        assertEquals(7, rig.slot.values.record?.sessionId)
    }

    @Test
    fun `an uncommitted session under a day whose abandon succeeds decides again and clears the record`() {
        rig.slot.values = ledgerWith(LedgerRecord(7, writtenAgo(3_600_000L), null), 11, null)
        val result = start.onStart()
        assertEquals(Shown.NOTHING, result.shown)
        assertEquals(1, rig.abandon.calls)
        assertNull(rig.slot.values.record)
    }

    @Test
    fun `an uncommitted session under a day whose abandon fails with a security error keeps the record`() {
        rig.slot.values = ledgerWith(LedgerRecord(7, writtenAgo(3_600_000L), null), 11, null)
        rig.abandon.thrown = SecurityException("the test abandon is refused")
        val result = start.onStart()
        assertEquals(Shown.SENTENCE, result.shown)
        assertEquals(1, rig.abandon.calls)
        assertEquals(7, rig.slot.values.record?.sessionId)
    }

    @Test
    fun `a session query that throws shows not finished and keeps the record under a day`() {
        rig.slot.values = ledgerWith(LedgerRecord(7, writtenAgo(3_600_000L), null), 11, null)
        rig.sessions.failure = IllegalStateException("the test session query fails")
        val result = start.onStart()
        assertEquals(Shown.SENTENCE, result.shown)
        assertEquals(UpdateSentences.NOT_FINISHED, result.sentence)
        assertEquals(7, rig.slot.values.record?.sessionId)
        assertEquals(0, rig.abandon.calls)
    }

    @Test
    fun `an unreadable installed code with a stored success shows not finished`() {
        rig.slot.values = ledgerWith(LedgerRecord(7, writtenAgo(3_600_000L), "Success"), 2, null)
        rig.installed.reading = InstalledReading.Unreadable
        rig.sessions.known = false
        val result = start.onStart()
        assertEquals(Shown.SENTENCE, result.shown)
        assertEquals(UpdateSentences.NOT_FINISHED, result.sentence)
        assertNull(rig.slot.values.record)
    }

    @Test
    fun `a committed session at a day or more clears the record without an abandon`() {
        rig.slot.values = ledgerWith(LedgerRecord(7, writtenAgo(A_DAY_MILLIS), null), 11, null)
        rig.sessions.committed = true
        val result = start.onStart()
        assertEquals(Shown.SENTENCE, result.shown)
        assertEquals(UpdateSentences.NOT_FINISHED, result.sentence)
        assertEquals(0, rig.abandon.calls)
        assertNull(rig.slot.values.record)
    }

    @Test
    fun `a committed session under a day shows waiting and keeps the record`() {
        rig.slot.values = ledgerWith(LedgerRecord(7, writtenAgo(3_600_000L), null), 11, null)
        rig.sessions.committed = true
        val result = start.onStart()
        assertEquals(Shown.SENTENCE, result.shown)
        assertEquals(UpdateSentences.WAITING, result.sentence)
        assertEquals(7, rig.slot.values.record?.sessionId)
        assertEquals(0, rig.abandon.calls)
    }

    @Test
    fun `the calls run in the order clock, slot, installed code, session, abandon, one write`() {
        rig.slot.values = ledgerWith(LedgerRecord(7, writtenAgo(3_600_000L), null), 11, null)
        start.onStart()
        assertEquals(
            listOf(
                "clock",
                "slot.read",
                "installed.current",
                "session.exists:7",
                "session.committed:7",
                "abandon:7",
                "slot.applyChange:true",
            ),
            rig.log.snapshot(),
        )
    }

    @Test
    fun `a clock that throws propagates and nothing is read or written`() {
        val engine = StartRun(ThrowingClock(rig.log), rig.slot, rig.installed, rig.sessions, rig.abandon, rig.drops)
        val thrown = runCatching { engine.onStart() }.exceptionOrNull()
        assertNotNull(thrown)
        assertEquals(listOf("clock.throws"), rig.log.snapshot())
    }

    @Test
    fun `a failed write on a start still returns the sentence`() {
        rig.slot.values = ledgerWith(LedgerRecord(7, writtenAgo(3_600_000L), null), 11, null)
        rig.sessions.committed = true
        rig.slot.applyFails = true
        val result = start.onStart()
        assertEquals(Shown.SENTENCE, result.shown)
        assertEquals(UpdateSentences.WAITING, result.sentence)
    }

    @Test
    fun `an expired uncommitted session whose abandon fails still clears the record`() {
        rig.slot.values = ledgerWith(LedgerRecord(7, writtenAgo(A_DAY_MILLIS + 1_000L), null), 11, null)
        rig.abandon.thrown = IOException("the test abandon fails")
        val result = start.onStart()
        assertEquals(Shown.SENTENCE, result.shown)
        assertEquals(UpdateSentences.NOT_FINISHED, result.sentence)
        assertNull(rig.slot.values.record)
        assertEquals(1, rig.abandon.calls)
    }
}
