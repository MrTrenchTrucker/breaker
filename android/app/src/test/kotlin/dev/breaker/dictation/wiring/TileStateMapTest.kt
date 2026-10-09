package dev.breaker.dictation.wiring

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
                        tileStateFor(false, session, sendPushed, lastFailed),
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
        val actual: List<TileState> = sessions.map { tileStateFor(true, it, false, false) }
        assertEquals("app: the armed rows should map IDLE, ARMED, RECORDING, TRANSCRIBING, SENDING, ERROR", expected, actual)
    }

    @Test
    fun `a pushed send shows sending for every session state and wins over a failure`() {
        for (session in sessions) {
            assertEquals(
                "app: sendPushed with $session should show SENDING",
                TileState.SENDING,
                tileStateFor(true, session, true, false),
            )
            assertEquals(
                "app: sendPushed with a failure and $session should still show SENDING",
                TileState.SENDING,
                tileStateFor(true, session, true, true),
            )
        }
    }

    @Test
    fun `a failed take shows failed for every session state when no send is pushed`() {
        for (session in sessions) {
            assertEquals(
                "app: lastFailed with $session should show FAILED",
                TileState.FAILED,
                tileStateFor(true, session, false, true),
            )
        }
    }

    @Test
    fun `transcribing shows recording because the runner reports recording until the text is ready`() {
        assertEquals(
            "app: TRANSCRIBING should show RECORDING",
            TileState.RECORDING,
            tileStateFor(true, DictationState.TRANSCRIBING, false, false),
        )
        assertEquals(
            "app: RECORDING should show RECORDING",
            TileState.RECORDING,
            tileStateFor(true, DictationState.RECORDING, false, false),
        )
    }

    @Test
    fun `an error session shows failed and an idle or armed session shows armed`() {
        assertEquals("app: ERROR should show FAILED", TileState.FAILED, tileStateFor(true, DictationState.ERROR, false, false))
        assertEquals("app: IDLE should show ARMED", TileState.ARMED, tileStateFor(true, DictationState.IDLE, false, false))
        assertEquals("app: ARMED should show ARMED", TileState.ARMED, tileStateFor(true, DictationState.ARMED, false, false))
    }
}
