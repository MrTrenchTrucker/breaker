package dev.breaker.dictation.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The engine's final-status and dismiss calls: the drop rule, the write of the status and the stored sentences. */
internal class StartRunFinalTest {
    private val rig = StartRig()
    private val start = rig.start

    @Test
    fun `a final status for the recorded session is stored and shows its sentence`() {
        rig.slot.values = ledgerWith(LedgerRecord(7, writtenAgo(1_000L), null), 11, null)
        val result = start.onFinalStatus(7, CommitStatus.Aborted)
        assertEquals(Shown.SENTENCE, result.shown)
        assertEquals(UpdateSentences.CANCELLED, result.sentence)
        assertEquals(UpdateSentences.CANCELLED, rig.slot.values.lastOutcome)
        assertNull(rig.slot.values.record)
        assertTrue(rig.log.snapshot().contains("slot.storeFinalStatus:Aborted"))
    }

    @Test
    fun `a final status with no record is dropped and counted once`() {
        val result = start.onFinalStatus(7, CommitStatus.Aborted)
        assertEquals(Shown.NOTHING, result.shown)
        assertEquals(1, start.droppedStatuses)
        assertEquals(listOf(Pair(7, null)), rig.drops.reported)
        assertNoWrite()
    }

    @Test
    fun `a final status for another session id is dropped and leaves the record unchanged`() {
        rig.slot.values = ledgerWith(LedgerRecord(7, writtenAgo(1_000L), null), 11, null)
        val before = rig.slot.values
        val result = start.onFinalStatus(9, CommitStatus.Aborted)
        assertEquals(Shown.NOTHING, result.shown)
        assertEquals(listOf(Pair(9, 7)), rig.drops.reported)
        assertEquals(before, rig.slot.values)
        assertNoWrite()
    }

    @Test
    fun `two dropped final statuses are counted twice and reported twice`() {
        rig.slot.values = ledgerWith(LedgerRecord(7, writtenAgo(1_000L), null), 11, null)
        start.onFinalStatus(9, CommitStatus.Aborted)
        start.onFinalStatus(9, CommitStatus.Success)
        assertEquals(2, start.droppedStatuses)
        assertEquals(2, rig.drops.reported.size)
    }

    @Test
    fun `a final status for the recorded session is not a drop`() {
        rig.slot.values = ledgerWith(LedgerRecord(7, writtenAgo(1_000L), null), 11, null)
        val result = start.onFinalStatus(7, CommitStatus.Success)
        assertEquals(0, start.droppedStatuses)
        assertTrue(rig.drops.reported.isEmpty())
        assertEquals(UpdateSentences.UNCONFIRMED, result.sentence)
    }

    @Test
    fun `a late final status for an earlier session leaves a newer committed record in place and still blocks the install`() {
        rig.slot.values = ledgerWith(LedgerRecord(9, writtenAgo(1_000L), null), 11, null)
        rig.sessions.committed = true
        val result = start.onFinalStatus(7, CommitStatus.Aborted)
        assertEquals(Shown.NOTHING, result.shown)
        assertEquals(1, start.droppedStatuses)
        assertEquals(9, rig.slot.values.record?.sessionId)
        val blocked = start.beforeInstall() as? InstallStep.Blocked
        assertEquals(UpdateSentences.WAITING, blocked?.sentence)
    }

    @Test
    fun `a failed write of a final status is caught and the call still clears the record`() {
        rig.slot.values = ledgerWith(LedgerRecord(7, writtenAgo(1_000L), null), 11, null)
        rig.slot.storeFails = true
        val result = start.onFinalStatus(7, CommitStatus.Aborted)
        assertEquals(Shown.SENTENCE, result.shown)
        assertEquals(UpdateSentences.CANCELLED, result.sentence)
        assertNull(rig.slot.values.record)
    }

    @Test
    fun `a pending user action given as a final status is stored as other failure`() {
        rig.slot.values = ledgerWith(LedgerRecord(7, writtenAgo(1_000L), null), 11, null)
        val result = start.onFinalStatus(7, CommitStatus.PendingUserAction)
        assertTrue(rig.log.snapshot().contains("slot.storeFinalStatus:OtherFailure"))
        assertEquals(UpdateSentences.INSTALLER_FAILED, result.sentence)
    }

    @Test
    fun `a dismiss with a stored final status shows that sentence and clears the record`() {
        rig.slot.values = ledgerWith(LedgerRecord(7, writtenAgo(1_000L), "Success"), 11, null)
        val result = start.onDismiss()
        assertEquals(Shown.SENTENCE, result.shown)
        assertEquals(UpdateSentences.UNCONFIRMED, result.sentence)
        assertNull(rig.slot.values.record)
        assertEquals(0, rig.abandon.calls)
    }

    @Test
    fun `a dismiss with no record shows nothing and clears the stored outcome`() {
        rig.slot.values = ledgerWith(null, 11, UpdateSentences.WAITING)
        val result = start.onDismiss()
        assertEquals(Shown.NOTHING, result.shown)
        assertNull(rig.slot.values.lastOutcome)
        assertEquals(11, rig.slot.values.lastSeenCode)
    }

    @Test
    fun `a stored status name that is not a final status is read as no status`() {
        rig.slot.values = ledgerWith(LedgerRecord(7, writtenAgo(1_000L), "Bogus"), 11, null)
        rig.sessions.committed = true
        val result = start.onDismiss()
        assertEquals(Shown.SENTENCE, result.shown)
        assertEquals(UpdateSentences.NOT_FINISHED, result.sentence)
    }

    @Test
    fun `a dismiss with an uncommitted session under a day abandons it and shows not finished`() {
        rig.slot.values = ledgerWith(LedgerRecord(7, writtenAgo(3_600_000L), null), 11, null)
        val result = start.onDismiss()
        assertEquals(Shown.SENTENCE, result.shown)
        assertEquals(UpdateSentences.NOT_FINISHED, result.sentence)
        assertEquals(1, rig.abandon.calls)
        assertNull(rig.slot.values.record)
    }

    /** Asserts that no ledger write was attempted: no final-status write and no change write. */
    private fun assertNoWrite() {
        assertTrue(rig.log.snapshot().none { it.startsWith("slot.storeFinalStatus") || it.startsWith("slot.applyChange") })
    }
}
