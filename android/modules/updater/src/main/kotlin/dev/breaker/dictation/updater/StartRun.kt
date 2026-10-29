package dev.breaker.dictation.updater

import dev.breaker.dictation.core.port.Clock
import java.io.IOException

/** What the install gate does after the start decision for one install attempt. */
internal sealed class InstallStep {
    /** The install may go ahead. A record that the decision cleared has already been removed. */
    internal object Go : InstallStep()

    /** The install is refused with [sentence]. Nothing was written. */
    internal class Blocked(val sentence: String) : InstallStep()

    /** The record could not be cleared, so the install does not start. */
    internal object WriteFailed : InstallStep()
}

/**
 * The first-start engine. Each call reads the clock, the ledger, the installed build and the platform's session
 * state, decides, and writes the ledger once. The engine is used from one thread and keeps no state except the
 * count of dropped final statuses.
 */
internal class StartRun(
    private val clock: Clock,
    private val slot: LedgerSlot,
    private val installed: InstalledVersion,
    private val sessions: SessionQuery,
    private val abandon: SessionAbandon,
    private val drops: DropLog,
) {
    /** The number of final statuses that were dropped. */
    internal var droppedStatuses: Int = 0
        private set

    /** The call at each start, before each daily check, and after a pending confirmation. */
    internal fun onStart(): StartResult = settleAndShow(StartEvent.START, null)

    /** The call for the user's dismiss action. */
    internal fun onDismiss(): StartResult = settleAndShow(StartEvent.DISMISS, null)

    /** The call for a final status from the commit receiver, with the session id that the platform sent. */
    internal fun onFinalStatus(sessionId: Int, status: CommitStatus): StartResult =
        settleAndShow(StartEvent.FINAL_STATUS, IncomingStatus(sessionId, status))

    /**
     * The decision for one install attempt. Only the start rules apply, and the only write is the removal of a record
     * that the decision clears. A refusal leaves the ledger as it was.
     */
    internal fun beforeInstall(): InstallStep {
        val settled = requireNotNull(settle(StartEvent.START, null))
        val decision = settled.decision
        if (!decision.installAllowed) return InstallStep.Blocked(requireNotNull(decision.sentence))
        if (!settled.removeRecord) return InstallStep.Go
        return try {
            slot.applyChange(true, FieldEdit.Keep, FieldEdit.Keep)
            InstallStep.Go
        } catch (error: IOException) {
            InstallStep.WriteFailed
        }
    }

    private fun settleAndShow(event: StartEvent, incoming: IncomingStatus?): StartResult {
        val settled = settle(event, incoming) ?: return StartResult(Shown.NOTHING, null)
        writeOutcome(settled)
        return StartResult(settled.decision.shown, settled.decision.sentence)
    }

    /**
     * Reads the state, stores a received final status, decides, and abandons the recorded session when the decision
     * asks for it. Returns null, and writes nothing, when the received status belongs to another session.
     */
    private fun settle(event: StartEvent, incoming: IncomingStatus?): Settled? {
        val now = clock.nowEpochMillis()
        val values = slot.read()
        val record = values.record
        if (incoming != null && record?.sessionId != incoming.sessionId) {
            droppedStatuses += 1
            drops.dropped(incoming.sessionId, record?.sessionId)
            return null
        }
        if (incoming != null) {
            try { slot.storeFinalStatus(wireName(finalStatusOf(incoming.status))) } catch (error: IOException) { Unit }
        }
        val status: CommitStatus? = if (incoming != null) finalStatusOf(incoming.status) else storedStatus(record?.finalStatus)
        val reading = installed.current()
        val installedCode: Int? = if (reading is InstalledReading.Known) reading.build.versionCode else null
        val session = if (record == null) SessionState.GONE else sessionStateOf(record.sessionId)
        val input = StartInput(
            event = event,
            record = if (record == null) RecordState.NONE else RecordState.IN_FLIGHT,
            recordWrittenAtMillis = record?.writtenAtMs ?: 0L,
            nowMillis = now,
            installedCode = installedCode,
            lastSeenCode = values.lastSeenCode,
            finalStatus = status,
            session = session,
            lastOutcome = values.lastOutcome,
        )
        var decision = FirstStartDecision.decide(input)
        var removeRecord = decision.recordAction == RecordAction.CLEAR
        if (decision.abandonRecordedSession && record != null && abandonRecorded(record.sessionId)) {
            removeRecord = true
            if (event == StartEvent.START) {
                decision = FirstStartDecision.decide(
                    input.copy(record = RecordState.NONE, recordWrittenAtMillis = 0L, session = SessionState.GONE, finalStatus = null),
                )
            }
        }
        return Settled(decision, removeRecord, storedOutcomeOf(event, decision))
    }

    /** The one write of a call: the record action, the last seen code and the outcome. A failed write is not thrown to the caller. */
    private fun writeOutcome(settled: Settled) {
        val code = settled.decision.storeLastSeenCode
        val lastSeenEdit: FieldEdit<Int> = if (code == null) FieldEdit.Keep else FieldEdit.Put(code)
        val outcome = settled.storedOutcome
        val outcomeEdit: FieldEdit<String> = if (outcome == null) FieldEdit.Clear else FieldEdit.Put(outcome)
        try { slot.applyChange(settled.removeRecord, lastSeenEdit, outcomeEdit) } catch (error: IOException) { Unit }
    }

    /** The outcome to store. A start that clears a record whose sentence is not finished stores that sentence for the next start. */
    private fun storedOutcomeOf(event: StartEvent, decision: StartDecision): String? =
        if (event == StartEvent.START && decision.recordAction == RecordAction.CLEAR && decision.sentence == UpdateSentences.NOT_FINISHED) UpdateSentences.NOT_FINISHED else decision.storeLastOutcome

    private fun sessionStateOf(sessionId: Int): SessionState = try {
        when {
            !sessions.exists(sessionId) -> SessionState.GONE
            sessions.isCommitted(sessionId) -> SessionState.OPEN_COMMITTED
            else -> SessionState.OPEN_NOT_COMMITTED
        }
    } catch (error: Exception) {
        SessionState.UNKNOWN
    }

    /** True when the abandon went through. An IOException or a SecurityException means the session was not abandoned. */
    private fun abandonRecorded(sessionId: Int): Boolean =
        try { abandon.abandon(sessionId); true } catch (error: IOException) { false } catch (error: SecurityException) { false }

    /** A pending user action has no confirmation here, so it is recorded and decided as an other failure. */
    private fun finalStatusOf(status: CommitStatus): CommitStatus =
        if (status == CommitStatus.PendingUserAction) CommitStatus.OtherFailure else status

    /** The status for a stored name. A name that is not one of the five final statuses reads as no status. */
    private fun storedStatus(name: String?): CommitStatus? = when (name) {
        "Success" -> CommitStatus.Success
        "Aborted" -> CommitStatus.Aborted
        "RefusedByInstaller" -> CommitStatus.RefusedByInstaller
        "StorageFailure" -> CommitStatus.StorageFailure
        "OtherFailure" -> CommitStatus.OtherFailure
        else -> null
    }

    private fun wireName(status: CommitStatus): String = when (status) {
        CommitStatus.Success -> "Success"
        CommitStatus.PendingUserAction -> "PendingUserAction"
        CommitStatus.Aborted -> "Aborted"
        CommitStatus.RefusedByInstaller -> "RefusedByInstaller"
        CommitStatus.StorageFailure -> "StorageFailure"
        CommitStatus.OtherFailure -> "OtherFailure"
    }

    /** A final status with the session id that the platform sent. */
    private class IncomingStatus(val sessionId: Int, val status: CommitStatus)

    /** The decision of one call, whether the record is removed, and the outcome to store. */
    private class Settled(val decision: StartDecision, val removeRecord: Boolean, val storedOutcome: String?)
}
