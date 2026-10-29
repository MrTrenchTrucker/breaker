package dev.breaker.dictation.updater

/** Test-side model of one row of the first-start table: its predicate, its expected decision and its cell count. */
internal class CellRow(
    val describe: String,
    val matches: (StartInput) -> Boolean,
    val expect: (StartInput) -> StartDecision,
    val cells: Int,
)

/** One day in milliseconds: the age at which a record counts as expired. */
internal const val A_DAY_MILLIS: Long = 86_400_000L

/** An age under one day, in milliseconds. */
internal const val UNDER_A_DAY_MILLIS: Long = 3_600_000L

private fun codeRose(input: StartInput): Boolean {
    val installed = input.installedCode ?: return false
    val seen = input.lastSeenCode ?: return false
    return installed > seen
}

/** True when the record is at least one day old, or was written after the clock read, so it is not counted as current. */
internal fun isPastLimit(input: StartInput): Boolean {
    val age = input.nowMillis - input.recordWrittenAtMillis
    return age < 0L || age >= A_DAY_MILLIS
}

private fun isUnderLimit(input: StartInput): Boolean = !isPastLimit(input)

private fun seenOf(input: StartInput): Int? = input.installedCode ?: input.lastSeenCode

private fun sentenceOf(status: CommitStatus, unreadable: Boolean): String = when (status) {
    CommitStatus.Success -> if (unreadable) UpdateSentences.NOT_FINISHED else UpdateSentences.UNCONFIRMED
    CommitStatus.Aborted -> UpdateSentences.CANCELLED
    CommitStatus.RefusedByInstaller -> UpdateSentences.INSTALLER_REFUSED
    CommitStatus.StorageFailure, CommitStatus.OtherFailure -> UpdateSentences.INSTALLER_FAILED
    CommitStatus.PendingUserAction -> UpdateSentences.NOT_FINISHED
}

private fun keepsEverything(input: StartInput): StartDecision =
    StartDecision(Shown.NOTHING, null, RecordAction.KEEP, false, input.lastOutcome, input.lastSeenCode, true)

private fun expectInstalledFromNone(input: StartInput): StartDecision =
    StartDecision(Shown.INSTALLED, null, RecordAction.KEEP, false, null, input.installedCode, true)

private fun expectStoredSentence(input: StartInput): StartDecision =
    StartDecision(Shown.SENTENCE, input.lastOutcome, RecordAction.KEEP, false, null, input.installedCode, true)

private fun expectNothingFromNone(input: StartInput): StartDecision =
    StartDecision(Shown.NOTHING, null, RecordAction.KEEP, false, null, input.installedCode, true)

private fun expectDismissFromNone(input: StartInput): StartDecision =
    StartDecision(Shown.NOTHING, null, RecordAction.KEEP, false, null, input.lastSeenCode, true)

private fun expectInstalledInFlight(input: StartInput): StartDecision = StartDecision(
    Shown.INSTALLED,
    null,
    RecordAction.CLEAR,
    input.session == SessionState.OPEN_NOT_COMMITTED,
    null,
    seenOf(input),
    true,
)

private fun expectFinalSentence(input: StartInput): StartDecision {
    val status = requireNotNull(input.finalStatus)
    val sentence = sentenceOf(status, input.installedCode == null)
    val stored = if (input.event == StartEvent.FINAL_STATUS) sentence else null
    return StartDecision(Shown.SENTENCE, sentence, RecordAction.CLEAR, false, stored, seenOf(input), true)
}

private fun expectDismissNotFinished(input: StartInput): StartDecision = StartDecision(
    Shown.SENTENCE,
    UpdateSentences.NOT_FINISHED,
    RecordAction.CLEAR,
    input.session == SessionState.OPEN_NOT_COMMITTED,
    null,
    seenOf(input),
    true,
)

private fun expectClearedNotFinished(input: StartInput): StartDecision = StartDecision(
    Shown.SENTENCE,
    UpdateSentences.NOT_FINISHED,
    RecordAction.CLEAR,
    false,
    null,
    seenOf(input),
    true,
)

private fun expectUncommittedKept(input: StartInput): StartDecision = StartDecision(
    Shown.SENTENCE,
    UpdateSentences.NOT_FINISHED,
    RecordAction.KEEP,
    true,
    input.lastOutcome,
    seenOf(input),
    false,
)

private fun expectUncommittedExpired(input: StartInput): StartDecision = StartDecision(
    Shown.SENTENCE,
    UpdateSentences.NOT_FINISHED,
    RecordAction.CLEAR,
    true,
    null,
    seenOf(input),
    true,
)

private fun expectCommittedWaiting(input: StartInput): StartDecision = StartDecision(
    Shown.SENTENCE,
    UpdateSentences.WAITING,
    RecordAction.KEEP,
    false,
    input.lastOutcome,
    seenOf(input),
    false,
)

private fun expectUnknownKept(input: StartInput): StartDecision = StartDecision(
    Shown.SENTENCE,
    UpdateSentences.NOT_FINISHED,
    RecordAction.KEEP,
    false,
    input.lastOutcome,
    seenOf(input),
    false,
)

/** The fifteen rows of the first-start table, each with its predicate, its expected decision and its cell count. */
internal val tableRows: List<CellRow> = listOf(
    CellRow(
        describe = "no record and a final status",
        matches = { it.record == RecordState.NONE && it.event == StartEvent.FINAL_STATUS },
        expect = ::keepsEverything,
        cells = 40,
    ),
    CellRow(
        describe = "no record and a dismiss",
        matches = { it.record == RecordState.NONE && it.event == StartEvent.DISMISS },
        expect = ::expectDismissFromNone,
        cells = 8,
    ),
    CellRow(
        describe = "no record, a start, and an installed code above the last seen code",
        matches = { it.record == RecordState.NONE && it.event == StartEvent.START && codeRose(it) },
        expect = ::expectInstalledFromNone,
        cells = 2,
    ),
    CellRow(
        describe = "no record, a start, equal codes, and a stored sentence",
        matches = {
            it.record == RecordState.NONE && it.event == StartEvent.START &&
                it.installedCode != null && it.installedCode == it.lastSeenCode && it.lastOutcome != null
        },
        expect = ::expectStoredSentence,
        cells = 1,
    ),
    CellRow(
        describe = "no record, a start, a readable code that is not above, and no stored sentence for equal codes",
        matches = {
            it.record == RecordState.NONE && it.event == StartEvent.START &&
                it.installedCode != null && !codeRose(it) &&
                !(it.installedCode == it.lastSeenCode && it.lastOutcome != null)
        },
        expect = ::expectNothingFromNone,
        cells = 3,
    ),
    CellRow(
        describe = "no record, a start, and an unreadable installed code",
        matches = { it.record == RecordState.NONE && it.event == StartEvent.START && it.installedCode == null },
        expect = ::keepsEverything,
        cells = 2,
    ),
    CellRow(
        describe = "a record in flight and an installed code above the last seen code",
        matches = { it.record == RecordState.IN_FLIGHT && codeRose(it) },
        expect = ::expectInstalledInFlight,
        cells = 212,
    ),
    CellRow(
        describe = "a record in flight, a final status, and no code above the last seen code",
        matches = { it.record == RecordState.IN_FLIGHT && !codeRose(it) && it.finalStatus != null },
        expect = ::expectFinalSentence,
        cells = 540,
    ),
    CellRow(
        describe = "a record in flight, a dismiss, no final status, and no code above the last seen code",
        matches = {
            it.record == RecordState.IN_FLIGHT && it.event == StartEvent.DISMISS &&
                !codeRose(it) && it.finalStatus == null
        },
        expect = ::expectDismissNotFinished,
        cells = 48,
    ),
    CellRow(
        describe = "a record in flight, a start, a gone session, and no final status",
        matches = {
            it.record == RecordState.IN_FLIGHT && it.event == StartEvent.START &&
                !codeRose(it) && it.session == SessionState.GONE && it.finalStatus == null
        },
        expect = ::expectClearedNotFinished,
        cells = 12,
    ),
    CellRow(
        describe = "a record in flight, a start, an uncommitted session under a day, and no final status",
        matches = {
            it.record == RecordState.IN_FLIGHT && it.event == StartEvent.START &&
                !codeRose(it) && it.session == SessionState.OPEN_NOT_COMMITTED &&
                isUnderLimit(it) && it.finalStatus == null
        },
        expect = ::expectUncommittedKept,
        cells = 6,
    ),
    CellRow(
        describe = "a record in flight, a start, an uncommitted session at a day or more, and no final status",
        matches = {
            it.record == RecordState.IN_FLIGHT && it.event == StartEvent.START &&
                !codeRose(it) && it.session == SessionState.OPEN_NOT_COMMITTED &&
                isPastLimit(it) && it.finalStatus == null
        },
        expect = ::expectUncommittedExpired,
        cells = 6,
    ),
    CellRow(
        describe = "a record in flight, a start, a committed session under a day, and no final status",
        matches = {
            it.record == RecordState.IN_FLIGHT && it.event == StartEvent.START &&
                !codeRose(it) && it.session == SessionState.OPEN_COMMITTED &&
                isUnderLimit(it) && it.finalStatus == null
        },
        expect = ::expectCommittedWaiting,
        cells = 6,
    ),
    CellRow(
        describe = "a record in flight, a start, an unknown session under a day, and no final status",
        matches = {
            it.record == RecordState.IN_FLIGHT && it.event == StartEvent.START &&
                !codeRose(it) && it.session == SessionState.UNKNOWN &&
                isUnderLimit(it) && it.finalStatus == null
        },
        expect = ::expectUnknownKept,
        cells = 6,
    ),
    CellRow(
        describe = "a record in flight, a start, a committed or unknown session at a day or more, and no final status",
        matches = {
            it.record == RecordState.IN_FLIGHT && it.event == StartEvent.START &&
                !codeRose(it) &&
                (it.session == SessionState.OPEN_COMMITTED || it.session == SessionState.UNKNOWN) &&
                isPastLimit(it) && it.finalStatus == null
        },
        expect = ::expectClearedNotFinished,
        cells = 12,
    ),
)
