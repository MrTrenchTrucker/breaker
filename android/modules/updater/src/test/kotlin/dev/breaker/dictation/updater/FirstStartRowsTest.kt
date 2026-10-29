package dev.breaker.dictation.updater

import org.junit.Assert.assertEquals
import org.junit.Test

/** Hand-written first-start cases, one per case the table must get right, each with its exact expected decision. */
internal class FirstStartRowsTest {

    @Test
    fun `an installed code above the last seen code with no record shows the installed state`() {
        val input = startInput(event = StartEvent.START, record = RecordState.NONE, installed = 3, lastSeen = 2)
        assertEquals(
            StartDecision(Shown.INSTALLED, null, RecordAction.KEEP, false, null, 3, true),
            FirstStartDecision.decide(input),
        )
    }

    @Test
    fun `a final status with no record is not shown and keeps the last seen code`() {
        val input = startInput(
            event = StartEvent.FINAL_STATUS,
            record = RecordState.NONE,
            installed = 3,
            lastSeen = 2,
            status = CommitStatus.Aborted,
        )
        assertEquals(
            StartDecision(Shown.NOTHING, null, RecordAction.KEEP, false, null, 2, true),
            FirstStartDecision.decide(input),
        )
    }

    @Test
    fun `a dismiss with an uncommitted session under a day shows not finished and abandons the session`() {
        val input = startInput(
            event = StartEvent.DISMISS,
            session = SessionState.OPEN_NOT_COMMITTED,
            ageMillis = 3_600_000L,
        )
        assertEquals(
            StartDecision(Shown.SENTENCE, UpdateSentences.NOT_FINISHED, RecordAction.CLEAR, true, null, 1, true),
            FirstStartDecision.decide(input),
        )
    }

    @Test
    fun `a dismiss with a committed session and a stored success shows the success sentence and clears the record`() {
        val input = startInput(
            event = StartEvent.DISMISS,
            session = SessionState.OPEN_COMMITTED,
            ageMillis = 3_600_000L,
            status = CommitStatus.Success,
        )
        assertEquals(
            StartDecision(Shown.SENTENCE, UpdateSentences.UNCONFIRMED, RecordAction.CLEAR, false, null, 1, true),
            FirstStartDecision.decide(input),
        )
    }

    @Test
    fun `a start with a gone session and a code that rose shows the installed state and clears the record`() {
        val input = startInput(session = SessionState.GONE, ageMillis = 3_600_000L, installed = 3, lastSeen = 2)
        assertEquals(
            StartDecision(Shown.INSTALLED, null, RecordAction.CLEAR, false, null, 3, true),
            FirstStartDecision.decide(input),
        )
    }

    @Test
    fun `a start with an uncommitted session and a code that rose shows the installed state and abandons the session`() {
        val input = startInput(
            session = SessionState.OPEN_NOT_COMMITTED,
            ageMillis = 3_600_000L,
            installed = 3,
            lastSeen = 2,
        )
        assertEquals(
            StartDecision(Shown.INSTALLED, null, RecordAction.CLEAR, true, null, 3, true),
            FirstStartDecision.decide(input),
        )
    }

    @Test
    fun `a start with an unknown session at exactly one day shows not finished and clears the record`() {
        val input = startInput(session = SessionState.UNKNOWN, ageMillis = 86_400_000L)
        assertEquals(
            StartDecision(Shown.SENTENCE, UpdateSentences.NOT_FINISHED, RecordAction.CLEAR, false, null, 1, true),
            FirstStartDecision.decide(input),
        )
    }

    @Test
    fun `a start with an uncommitted session at exactly one day shows not finished and abandons the session`() {
        val input = startInput(session = SessionState.OPEN_NOT_COMMITTED, ageMillis = 86_400_000L)
        assertEquals(
            StartDecision(Shown.SENTENCE, UpdateSentences.NOT_FINISHED, RecordAction.CLEAR, true, null, 1, true),
            FirstStartDecision.decide(input),
        )
    }

    @Test
    fun `a start with an uncommitted session just under one day keeps the record and blocks installs`() {
        val input = startInput(session = SessionState.OPEN_NOT_COMMITTED, ageMillis = 86_399_999L)
        assertEquals(
            StartDecision(Shown.SENTENCE, UpdateSentences.NOT_FINISHED, RecordAction.KEEP, true, null, 1, false),
            FirstStartDecision.decide(input),
        )
    }

    @Test
    fun `a start with a committed session and a negative age shows not finished and clears the record`() {
        val input = startInput(session = SessionState.OPEN_COMMITTED, ageMillis = -1_000L)
        assertEquals(
            StartDecision(Shown.SENTENCE, UpdateSentences.NOT_FINISHED, RecordAction.CLEAR, false, null, 1, true),
            FirstStartDecision.decide(input),
        )
    }

    @Test
    fun `a start with a committed session under a day shows waiting, keeps the record and blocks installs`() {
        val input = startInput(session = SessionState.OPEN_COMMITTED, ageMillis = 3_600_000L)
        assertEquals(
            StartDecision(Shown.SENTENCE, UpdateSentences.WAITING, RecordAction.KEEP, false, null, 1, false),
            FirstStartDecision.decide(input),
        )
    }

    @Test
    fun `a start with a gone session, a stored success and an unreadable code shows not finished and keeps the last seen code`() {
        val input = startInput(
            session = SessionState.GONE,
            ageMillis = 3_600_000L,
            installed = null,
            lastSeen = 2,
            status = CommitStatus.Success,
        )
        assertEquals(
            StartDecision(Shown.SENTENCE, UpdateSentences.NOT_FINISHED, RecordAction.CLEAR, false, null, 2, true),
            FirstStartDecision.decide(input),
        )
    }

    @Test
    fun `a final status of aborted shows the cancelled sentence and stores it as the last outcome`() {
        val input = startInput(
            event = StartEvent.FINAL_STATUS,
            session = SessionState.OPEN_COMMITTED,
            ageMillis = 3_600_000L,
            status = CommitStatus.Aborted,
            lastOutcome = UpdateSentences.WAITING,
        )
        assertEquals(
            StartDecision(
                Shown.SENTENCE,
                UpdateSentences.CANCELLED,
                RecordAction.CLEAR,
                false,
                UpdateSentences.CANCELLED,
                1,
                true,
            ),
            FirstStartDecision.decide(input),
        )
    }

    @Test
    fun `a start with a gone session, a stored success and an unchanged code shows the unconfirmed sentence`() {
        val input = startInput(session = SessionState.GONE, ageMillis = 3_600_000L, status = CommitStatus.Success)
        assertEquals(
            StartDecision(Shown.SENTENCE, UpdateSentences.UNCONFIRMED, RecordAction.CLEAR, false, null, 1, true),
            FirstStartDecision.decide(input),
        )
    }

    @Test
    fun `a start with an unknown session under a day shows not finished, keeps the record and blocks installs`() {
        val input = startInput(session = SessionState.UNKNOWN, ageMillis = 3_600_000L)
        assertEquals(
            StartDecision(Shown.SENTENCE, UpdateSentences.NOT_FINISHED, RecordAction.KEEP, false, null, 1, false),
            FirstStartDecision.decide(input),
        )
    }

    @Test
    fun `a start with a committed session at exactly one day clears the record and allows installs`() {
        val input = startInput(
            session = SessionState.OPEN_COMMITTED,
            ageMillis = 86_400_000L,
            writtenAtMillis = 1_000L,
        )
        assertEquals(
            StartDecision(Shown.SENTENCE, UpdateSentences.NOT_FINISHED, RecordAction.CLEAR, false, null, 1, true),
            FirstStartDecision.decide(input),
        )
    }

    @Test
    fun `a dismiss with an uncommitted session and a code that rose shows the installed state and abandons the session`() {
        val input = startInput(
            event = StartEvent.DISMISS,
            session = SessionState.OPEN_NOT_COMMITTED,
            ageMillis = 3_600_000L,
            installed = 3,
            lastSeen = 2,
        )
        assertEquals(
            StartDecision(Shown.INSTALLED, null, RecordAction.CLEAR, true, null, 3, true),
            FirstStartDecision.decide(input),
        )
    }

    @Test
    fun `a missing last seen code is not read as a code that rose`() {
        val input = startInput(session = SessionState.GONE, ageMillis = 3_600_000L, installed = 3, lastSeen = null)
        assertEquals(
            StartDecision(Shown.SENTENCE, UpdateSentences.NOT_FINISHED, RecordAction.CLEAR, false, null, 3, true),
            FirstStartDecision.decide(input),
        )
    }

    @Test
    fun `a record written at the instant of the start is not yet expired`() {
        val input = startInput(session = SessionState.OPEN_COMMITTED, ageMillis = 0L)
        assertEquals(
            StartDecision(Shown.SENTENCE, UpdateSentences.WAITING, RecordAction.KEEP, false, null, 1, false),
            FirstStartDecision.decide(input),
        )
    }

    @Test
    fun `a dismiss without an install record shows nothing and clears the stored outcome`() {
        val input = startInput(
            event = StartEvent.DISMISS,
            record = RecordState.NONE,
            installed = 3,
            lastSeen = 2,
            lastOutcome = UpdateSentences.WAITING,
        )
        assertEquals(
            StartDecision(Shown.NOTHING, null, RecordAction.KEEP, false, null, 2, true),
            FirstStartDecision.decide(input),
        )
    }

    @Test
    fun `a start that abandons an uncommitted session decides again without the record and allows the install`() {
        val first = startInput(
            event = StartEvent.START,
            record = RecordState.IN_FLIGHT,
            session = SessionState.OPEN_NOT_COMMITTED,
            ageMillis = 3_600_000L,
            installed = 2,
            lastSeen = 2,
        )
        assertEquals(
            StartDecision(Shown.SENTENCE, UpdateSentences.NOT_FINISHED, RecordAction.KEEP, true, null, 2, false),
            FirstStartDecision.decide(first),
        )
        val again = startInput(
            event = StartEvent.START,
            record = RecordState.NONE,
            session = SessionState.GONE,
            installed = 2,
            lastSeen = 2,
        )
        assertEquals(
            StartDecision(Shown.NOTHING, null, RecordAction.KEEP, false, null, 2, true),
            FirstStartDecision.decide(again),
        )
    }
}
