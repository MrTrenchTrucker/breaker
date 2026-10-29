package dev.breaker.dictation.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stored outcome text that the enumeration places in the last-outcome input. */
private const val EARLIER_OUTCOME: String = UpdateSentences.WAITING

/** The reason given for the cells in which a session is not committed although the commit was already called. */
private const val UNREACHABLE_REASON: String =
    "a final status means commit was called, so the session cannot be OPEN_NOT_COMMITTED"

/**
 * One decision input. The record was written at [writtenAtMillis] (zero by default), and the clock reads
 * [ageMillis] later.
 */
internal fun startInput(
    event: StartEvent = StartEvent.START,
    record: RecordState = RecordState.IN_FLIGHT,
    session: SessionState = SessionState.GONE,
    ageMillis: Long = 0L,
    installed: Int? = 1,
    lastSeen: Int? = 1,
    status: CommitStatus? = null,
    lastOutcome: String? = null,
    writtenAtMillis: Long = 0L,
): StartInput = StartInput(
    event = event,
    record = record,
    recordWrittenAtMillis = writtenAtMillis,
    nowMillis = writtenAtMillis + ageMillis,
    installedCode = installed,
    lastSeenCode = lastSeen,
    finalStatus = status,
    session = session,
    lastOutcome = lastOutcome,
)

/** Every cell of the product of the input components, under the reachability constraints of the design. */
private fun allCells(): List<StartInput> {
    val cells = mutableListOf<StartInput>()
    val codes = listOf(Pair<Int?, Int?>(3, 2), Pair<Int?, Int?>(2, 2), Pair<Int?, Int?>(1, 2), Pair<Int?, Int?>(null, 2))
    val outcomes: List<String?> = listOf(null, EARLIER_OUTCOME)
    val fiveStatuses: List<CommitStatus?> = listOf(
        CommitStatus.Success,
        CommitStatus.Aborted,
        CommitStatus.RefusedByInstaller,
        CommitStatus.StorageFailure,
        CommitStatus.OtherFailure,
    )
    val anyStatus: List<CommitStatus?> = listOf<CommitStatus?>(null) + fiveStatuses
    val sessions = listOf(
        SessionState.GONE,
        SessionState.OPEN_COMMITTED,
        SessionState.OPEN_NOT_COMMITTED,
        SessionState.UNKNOWN,
    )
    val ages = listOf(UNDER_A_DAY_MILLIS, A_DAY_MILLIS)
    for (event in listOf(StartEvent.START, StartEvent.FINAL_STATUS, StartEvent.DISMISS)) {
        val recordlessStatuses: List<CommitStatus?> =
            if (event == StartEvent.FINAL_STATUS) fiveStatuses else listOf<CommitStatus?>(null)
        val inFlightStatuses: List<CommitStatus?> = if (event == StartEvent.FINAL_STATUS) fiveStatuses else anyStatus
        for (outcome in outcomes) {
            for ((installed, seen) in codes) {
                for (status in recordlessStatuses) {
                    cells.add(
                        startInput(
                            event = event,
                            record = RecordState.NONE,
                            session = SessionState.GONE,
                            ageMillis = UNDER_A_DAY_MILLIS,
                            installed = installed,
                            lastSeen = seen,
                            status = status,
                            lastOutcome = outcome,
                        ),
                    )
                }
                for (session in sessions) {
                    for (age in ages) {
                        for (status in inFlightStatuses) {
                            cells.add(
                                startInput(
                                    event = event,
                                    record = RecordState.IN_FLIGHT,
                                    session = session,
                                    ageMillis = age,
                                    installed = installed,
                                    lastSeen = seen,
                                    status = status,
                                    lastOutcome = outcome,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
    return cells
}

/** The reason an input cannot happen, or null when it is reachable. */
private fun unreachableReason(input: StartInput): String? {
    val inFlight = input.record == RecordState.IN_FLIGHT
    val notCommitted = input.session == SessionState.OPEN_NOT_COMMITTED
    return if (inFlight && notCommitted && input.finalStatus != null) UNREACHABLE_REASON else null
}

/** Pins the first-start table on every enumerated cell, with the counts and the invariants of the design. */
internal class FirstStartDecisionTest {

    @Test
    fun `every reachable input matches exactly one row and decides the way that row says`() {
        for (input in allCells()) {
            if (unreachableReason(input) != null) continue
            val matched = tableRows.filter { it.matches(input) }
            assertEquals("exactly one row must match $input", 1, matched.size)
            assertEquals("the decision for $input", matched[0].expect(input), FirstStartDecision.decide(input))
        }
    }

    @Test
    fun `the enumeration has 1144 cells with 904 reachable and 240 unreachable, each unreachable one with a reason`() {
        val cells = allCells()
        assertEquals(1144, cells.size)
        val withReason = cells.filter { unreachableReason(it) != null }
        val notCommitted = cells.filter {
            it.record == RecordState.IN_FLIGHT && it.session == SessionState.OPEN_NOT_COMMITTED && it.finalStatus != null
        }
        assertEquals(240, withReason.size)
        assertEquals(notCommitted, withReason)
        assertEquals(904, cells.size - withReason.size)
    }

    @Test
    fun `each row covers the number of reachable cells listed for it`() {
        val reachable = allCells().filter { unreachableReason(it) == null }
        assertEquals(904, reachable.size)
        for (row in tableRows) {
            assertEquals(row.describe, row.cells, reachable.count { row.matches(it) })
        }
    }

    @Test
    fun `every reachable input keeps the invariants the design lists`() {
        for (input in allCells()) {
            if (unreachableReason(input) != null) continue
            val decision = FirstStartDecision.decide(input)
            if (input.record == RecordState.IN_FLIGHT && isPastLimit(input)) {
                assertEquals("an expired record is cleared for $input", RecordAction.CLEAR, decision.recordAction)
                assertTrue("an expired record never blocks installs for $input", decision.installAllowed)
            }
            if (input.record == RecordState.IN_FLIGHT && input.finalStatus != null) {
                assertEquals("a record with a final status is cleared for $input", RecordAction.CLEAR, decision.recordAction)
            }
            if (decision.abandonRecordedSession) {
                assertTrue(
                    "abandon only for an uncommitted session for $input",
                    input.session == SessionState.OPEN_NOT_COMMITTED,
                )
            }
            if (input.event != StartEvent.FINAL_STATUS) {
                val stored = decision.storeLastOutcome
                assertTrue(
                    "a stored outcome is none or the input outcome for $input",
                    stored == null || stored == input.lastOutcome,
                )
            }
            if (input.installedCode == null) {
                assertEquals(
                    "an unreadable code is never stored for $input",
                    input.lastSeenCode,
                    decision.storeLastSeenCode,
                )
            }
        }
    }
}
