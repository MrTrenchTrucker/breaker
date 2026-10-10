package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.CommitOutcome
import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.overlay.TileState
import org.junit.Assert.assertEquals
import org.junit.Test

class TileStateMapTest {
    /** The session states in declaration order, written out so a new state in core fails the count test. */
    private val sessions: List<DictationState> = listOf(
        DictationState.IDLE,
        DictationState.ARMED,
        DictationState.RECORDING,
        DictationState.TRANSCRIBING,
        DictationState.SENDING,
        DictationState.ERROR,
    )

    @Test
    fun `the table covers every session state the runner can have`() {
        assertEquals("app: the map table must list every dictation state", DictationState.entries.toList(), sessions)
    }

    @Test
    fun `a switch that is off shows idle whatever else is true`() {
        var rows = 0
        for (session in sessions) {
            for (sendPushed in listOf(false, true)) {
                for (lastFailed in listOf(false, true)) {
                    rows += 1
                    assertEquals(
                        "app: off with $session sendPushed=$sendPushed lastFailed=$lastFailed should show IDLE",
                        TileState.IDLE,
                        tileStateFor(false, session, sendPushed, lastFailed, null),
                    )
                }
            }
        }
        assertEquals("app: the off rows checked should be 6 x 2 x 2", 24, rows)
    }

    @Test
    fun `an armed switch with nothing pushed shows what the session says`() {
        val expected: List<TileState> = listOf(
            TileState.ARMED, // IDLE
            TileState.ARMED, // ARMED
            TileState.RECORDING, // RECORDING
            TileState.RECORDING, // TRANSCRIBING
            TileState.SENDING, // SENDING
            TileState.FAILED, // ERROR
        )
        val actual: List<TileState> = sessions.map { tileStateFor(true, it, false, false, null) }
        assertEquals("app: the armed rows should map IDLE, ARMED, RECORDING, TRANSCRIBING, SENDING, ERROR", expected, actual)
    }

    @Test
    fun `a pushed send shows sending for every session state and wins over a failure`() {
        for (session in sessions) {
            assertEquals(
                "app: sendPushed with $session should show SENDING",
                TileState.SENDING,
                tileStateFor(true, session, true, false, null),
            )
            assertEquals(
                "app: sendPushed with a failure and $session should still show SENDING",
                TileState.SENDING,
                tileStateFor(true, session, true, true, null),
            )
        }
    }

    @Test
    fun `a failed take shows failed for every session state when no send is pushed`() {
        for (session in sessions) {
            assertEquals(
                "app: lastFailed with $session should show FAILED",
                TileState.FAILED,
                tileStateFor(true, session, false, true, null),
            )
        }
    }

    @Test
    fun `transcribing shows recording because the runner reports recording until the text is ready`() {
        assertEquals(
            "app: TRANSCRIBING should show RECORDING",
            TileState.RECORDING,
            tileStateFor(true, DictationState.TRANSCRIBING, false, false, null),
        )
        assertEquals(
            "app: RECORDING should show RECORDING",
            TileState.RECORDING,
            tileStateFor(true, DictationState.RECORDING, false, false, null),
        )
    }

    @Test
    fun `an error session shows failed and an idle or armed session shows armed`() {
        assertEquals("app: ERROR should show FAILED", TileState.FAILED, tileStateFor(true, DictationState.ERROR, false, false, null))
        assertEquals("app: IDLE should show ARMED", TileState.ARMED, tileStateFor(true, DictationState.IDLE, false, false, null))
        assertEquals("app: ARMED should show ARMED", TileState.ARMED, tileStateFor(true, DictationState.ARMED, false, false, null))
    }

    @Test
    fun `a committed send shows sent whatever the session says`() {
        for (session in sessions) {
            assertEquals(
                "app: COMMITTED with $session should show SENT",
                TileState.SENT,
                tileStateFor(true, session, false, false, CommitOutcome.COMMITTED),
            )
        }
    }

    @Test
    fun `a copied text shows sent whatever the session says`() {
        for (session in sessions) {
            assertEquals(
                "app: COPIED with $session should show SENT",
                TileState.SENT,
                tileStateFor(true, session, false, false, CommitOutcome.COPIED),
            )
        }
    }

    @Test
    fun `a failed commit shows failed whatever the session says`() {
        for (session in sessions) {
            assertEquals(
                "app: a FAILED commit with $session should show FAILED",
                TileState.FAILED,
                tileStateFor(true, session, false, false, CommitOutcome.FAILED),
            )
        }
    }

    @Test
    fun `no commit outcome leaves the session map in charge`() {
        val expected: List<TileState> = listOf(
            TileState.ARMED, // IDLE
            TileState.ARMED, // ARMED
            TileState.RECORDING, // RECORDING
            TileState.RECORDING, // TRANSCRIBING
            TileState.SENDING, // SENDING
            TileState.FAILED, // ERROR
        )
        val actual: List<TileState> = sessions.map { tileStateFor(true, it, false, false, null) }
        assertEquals("app: with no commit outcome the armed rows should follow the session map", expected, actual)
    }

    @Test
    fun `a switch that is off shows idle even after a commit outcome`() {
        for (session in sessions) {
            for (outcome in listOf(CommitOutcome.COMMITTED, CommitOutcome.COPIED, CommitOutcome.FAILED)) {
                assertEquals(
                    "app: off with $session and $outcome should show IDLE",
                    TileState.IDLE,
                    tileStateFor(false, session, false, false, outcome),
                )
            }
        }
    }

    @Test
    fun `a pushed send shows sending over any commit outcome`() {
        for (session in sessions) {
            for (outcome in listOf<CommitOutcome?>(CommitOutcome.COMMITTED, CommitOutcome.COPIED, CommitOutcome.FAILED, null)) {
                assertEquals(
                    "app: sendPushed with $session and $outcome should show SENDING",
                    TileState.SENDING,
                    tileStateFor(true, session, true, false, outcome),
                )
            }
        }
    }

    @Test
    fun `a failed take shows failed over any commit outcome`() {
        for (session in sessions) {
            for (outcome in listOf(CommitOutcome.COMMITTED, CommitOutcome.COPIED, CommitOutcome.FAILED)) {
                assertEquals(
                    "app: lastFailed with $session and $outcome should show FAILED",
                    TileState.FAILED,
                    tileStateFor(true, session, false, true, outcome),
                )
            }
        }
    }

    // ---- micBusy coverage (additive; the signature gained micBusy as its last parameter) ----

    @Test
    fun `a busy face shows MIC_BUSY even with no send fail or commit`() {
        assertEquals(
            "app: micBusy with no push/fail/commit should show MIC_BUSY",
            TileState.MIC_BUSY,
            tileStateFor(true, DictationState.IDLE, false, false, null, true),
        )
    }

    @Test
    fun `a busy face wins over a pushed send`() {
        assertEquals(
            "app: micBusy beats a pushed send which comes after it",
            TileState.MIC_BUSY,
            tileStateFor(true, DictationState.IDLE, true, false, null, true),
        )
    }

    @Test
    fun `a busy face wins over a last failed`() {
        assertEquals(
            "app: micBusy beats a failure which comes after it",
            TileState.MIC_BUSY,
            tileStateFor(true, DictationState.IDLE, false, true, null, true),
        )
    }

    @Test
    fun `a busy face wins over a committed send`() {
        assertEquals(
            "app: micBusy beats a committed outcome which comes after it",
            TileState.MIC_BUSY,
            tileStateFor(true, DictationState.IDLE, false, false, CommitOutcome.COMMITTED, true),
        )
    }

    @Test
    fun `a dormant busy flag reports the session's own state`() {
        assertEquals(
            "app: micBusy false keeps today's behaviour (session decides)",
            TileState.ARMED,
            tileStateFor(true, DictationState.ARMED, false, false, null, false),
        )
    }

    @Test
    fun `an off switch shows IDLE regardless of busy`() {
        assertEquals(
            "app: the off switch wins over a busy flag",
            TileState.IDLE,
            tileStateFor(false, DictationState.IDLE, false, false, null, true),
        )
    }
}
