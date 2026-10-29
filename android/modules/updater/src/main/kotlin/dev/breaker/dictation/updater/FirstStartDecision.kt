package dev.breaker.dictation.updater

/** Whether an install record is open: none, or one attempt in flight. */
internal enum class RecordState { NONE, IN_FLIGHT }

/** The platform state of the recorded session; UNKNOWN stands for a session query that failed. */
internal enum class SessionState { GONE, OPEN_COMMITTED, OPEN_NOT_COMMITTED, UNKNOWN }

/** The call that asks for a decision: a start, a final status from the commit receiver, or the user's dismiss action. */
internal enum class StartEvent { START, FINAL_STATUS, DISMISS }

/** What the caller does with the install record after a decision. */
internal enum class RecordAction { KEEP, CLEAR }

/** What the first-start surface shows after a call. */
public enum class Shown {
    /** Show nothing. */
    NOTHING,

    /** Show the installed state. */
    INSTALLED,

    /** Show the sentence that the caller reads back. */
    SENTENCE,
}

/** The facts that one decision reads. Every time is in epoch milliseconds. */
internal data class StartInput(
    val event: StartEvent,
    val record: RecordState,
    val recordWrittenAtMillis: Long,
    val nowMillis: Long,
    val installedCode: Int?,
    val lastSeenCode: Int?,
    val finalStatus: CommitStatus?,
    val session: SessionState,
    val lastOutcome: String?,
)

/** The result of one decision. The record values are written after the call; a null text clears the stored outcome. */
internal data class StartDecision(
    val shown: Shown,
    val sentence: String?,
    val recordAction: RecordAction,
    val abandonRecordedSession: Boolean,
    val storeLastOutcome: String?,
    val storeLastSeenCode: Int?,
    val installAllowed: Boolean,
)

/** The first-start decision: a pure function of its input, with no clock read, no file and no shared state. */
internal object FirstStartDecision {
    private const val PENDING_LIMIT_MILLIS: Long = 86_400_000L

    /** Returns the decision for [input]. The table has one row for every reachable input. */
    internal fun decide(input: StartInput): StartDecision =
        if (input.record == RecordState.NONE) decideRecordless(input) else decideInFlight(input)

    private fun decideRecordless(input: StartInput): StartDecision = when (input.event) {
        StartEvent.FINAL_STATUS -> keepRecordless(input)
        StartEvent.DISMISS -> dismissRecordless(input)
        StartEvent.START -> decideRecordlessStart(input)
    }

    private fun keepRecordless(input: StartInput): StartDecision =
        StartDecision(Shown.NOTHING, null, RecordAction.KEEP, false, input.lastOutcome, input.lastSeenCode, true)

    private fun dismissRecordless(input: StartInput): StartDecision =
        StartDecision(Shown.NOTHING, null, RecordAction.KEEP, false, null, input.lastSeenCode, true)

    private fun decideRecordlessStart(input: StartInput): StartDecision {
        val installed = input.installedCode ?: return keepRecordless(input)
        if (installedAbove(input)) {
            return StartDecision(Shown.INSTALLED, null, RecordAction.KEEP, false, null, installed, true)
        }
        if (input.lastSeenCode == installed && input.lastOutcome != null) {
            return StartDecision(Shown.SENTENCE, input.lastOutcome, RecordAction.KEEP, false, null, installed, true)
        }
        return StartDecision(Shown.NOTHING, null, RecordAction.KEEP, false, null, installed, true)
    }

    private fun decideInFlight(input: StartInput): StartDecision {
        val seen = input.installedCode ?: input.lastSeenCode
        if (installedAbove(input)) {
            val abandon = input.session == SessionState.OPEN_NOT_COMMITTED
            return StartDecision(Shown.INSTALLED, null, RecordAction.CLEAR, abandon, null, seen, true)
        }
        val status = input.finalStatus
        if (status != null) {
            val sentence = sentenceFor(status, input.installedCode == null)
            val stored = if (input.event == StartEvent.FINAL_STATUS) sentence else null
            return StartDecision(Shown.SENTENCE, sentence, RecordAction.CLEAR, false, stored, seen, true)
        }
        if (input.event == StartEvent.DISMISS) {
            return clearedNotFinished(seen, input.session == SessionState.OPEN_NOT_COMMITTED)
        }
        return decideInFlightStart(input, seen, isExpired(input))
    }

    private fun decideInFlightStart(input: StartInput, seen: Int?, expired: Boolean): StartDecision =
        when (input.session) {
            SessionState.GONE -> clearedNotFinished(seen, false)
            SessionState.OPEN_NOT_COMMITTED ->
                if (expired) clearedNotFinished(seen, true) else keptNotFinished(input, seen, true)
            SessionState.OPEN_COMMITTED ->
                if (expired) clearedNotFinished(seen, false) else keptWaiting(input, seen)
            SessionState.UNKNOWN ->
                if (expired) clearedNotFinished(seen, false) else keptNotFinished(input, seen, false)
        }

    private fun clearedNotFinished(seen: Int?, abandon: Boolean): StartDecision =
        StartDecision(Shown.SENTENCE, UpdateSentences.NOT_FINISHED, RecordAction.CLEAR, abandon, null, seen, true)

    private fun keptNotFinished(input: StartInput, seen: Int?, abandon: Boolean): StartDecision =
        StartDecision(Shown.SENTENCE, UpdateSentences.NOT_FINISHED, RecordAction.KEEP, abandon, input.lastOutcome, seen, false)

    private fun keptWaiting(input: StartInput, seen: Int?): StartDecision =
        StartDecision(Shown.SENTENCE, UpdateSentences.WAITING, RecordAction.KEEP, false, input.lastOutcome, seen, false)

    private fun installedAbove(input: StartInput): Boolean {
        val installed = input.installedCode ?: return false
        val seen = input.lastSeenCode ?: return false
        return installed > seen
    }

    private fun isExpired(input: StartInput): Boolean {
        val age = input.nowMillis - input.recordWrittenAtMillis
        return age < 0L || age >= PENDING_LIMIT_MILLIS
    }

    private fun sentenceFor(status: CommitStatus, unreadable: Boolean): String = when (status) {
        CommitStatus.Success -> if (unreadable) UpdateSentences.NOT_FINISHED else UpdateSentences.UNCONFIRMED
        CommitStatus.Aborted -> UpdateSentences.CANCELLED
        CommitStatus.RefusedByInstaller -> UpdateSentences.INSTALLER_REFUSED
        CommitStatus.StorageFailure, CommitStatus.OtherFailure -> UpdateSentences.INSTALLER_FAILED
        CommitStatus.PendingUserAction -> UpdateSentences.NOT_FINISHED
    }
}
