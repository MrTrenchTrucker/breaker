package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.CommitOutcome
import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.overlay.TileState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The SENT_LOCAL push: the tile shows the fallback state exactly when a
 * committed or copied text came from the phone model after the server path
 * failed (the flag is set). A server commit and a clipboard-only commit keep
 * SENT. The existing priorities hold: off is IDLE, a busy face and a pushed
 * send come before it, and a failure wins over it.
 */
class TileStateMapLocalFallbackTest {

    @Test
    fun `a committed fallback text shows sent local whatever the session says`() {
        for (session in sessions()) {
            assertEquals(
                "app: committed with the fallback and $session should show SENT_LOCAL",
                TileState.SENT_LOCAL,
                tileStateFor(true, session, false, false, CommitOutcome.COMMITTED, false, true),
            )
        }
    }

    @Test
    fun `a copied fallback text shows sent local whatever the session says`() {
        for (session in sessions()) {
            assertEquals(
                "app: copied with the fallback and $session should show SENT_LOCAL",
                TileState.SENT_LOCAL,
                tileStateFor(true, session, false, false, CommitOutcome.COPIED, false, true),
            )
        }
    }

    @Test
    fun `a committed text without the fallback shows sent whatever the session says`() {
        for (session in sessions()) {
            assertEquals(
                "app: committed without the fallback and $session should show SENT",
                TileState.SENT,
                tileStateFor(true, session, false, false, CommitOutcome.COMMITTED, false, false),
            )
        }
    }

    @Test
    fun `a copied text without the fallback shows sent whatever the session says`() {
        for (session in sessions()) {
            assertEquals(
                "app: copied without the fallback and $session should show SENT",
                TileState.SENT,
                tileStateFor(true, session, false, false, CommitOutcome.COPIED, false, false),
            )
        }
    }

    @Test
    fun `a failed commit shows failed even with the fallback`() {
        for (session in sessions()) {
            assertEquals(
                "app: a FAILED commit with the fallback and $session should show FAILED",
                TileState.FAILED,
                tileStateFor(true, session, false, false, CommitOutcome.FAILED, false, true),
            )
        }
    }

    @Test
    fun `a failed take shows failed even with the fallback`() {
        for (session in sessions()) {
            assertEquals(
                "app: lastFailed with the fallback and $session should show FAILED",
                TileState.FAILED,
                tileStateFor(true, session, false, true, CommitOutcome.COMMITTED, false, true),
            )
        }
    }

    @Test
    fun `a switch that is off shows idle even with the fallback and a commit`() {
        for (session in sessions()) {
            for (outcome in listOf(CommitOutcome.COMMITTED, CommitOutcome.COPIED, CommitOutcome.FAILED)) {
                assertEquals(
                    "app: off with $outcome and the fallback and $session should show IDLE",
                    TileState.IDLE,
                    tileStateFor(false, session, false, false, outcome, false, true),
                )
            }
        }
    }

    @Test
    fun `a busy face wins over the fallback`() {
        assertEquals(
            "app: the busy face with the fallback and a commit should show MIC_BUSY",
            TileState.MIC_BUSY,
            tileStateFor(true, DictationState.IDLE, false, false, CommitOutcome.COMMITTED, true, true),
        )
    }

    @Test
    fun `a pushed send shows sending over the fallback`() {
        for (session in sessions()) {
            assertEquals(
                "app: sendPushed with the fallback and $session should show SENDING",
                TileState.SENDING,
                tileStateFor(true, session, true, false, CommitOutcome.COMMITTED, false, true),
            )
        }
    }

    @Test
    fun `no commit outcome and the fallback leaves the session map in charge`() {
        // The flag only matters once a commit happened; with no commit the session decides.
        val expected: List<TileState> = listOf(
            TileState.ARMED, // IDLE
            TileState.ARMED, // ARMED
            TileState.RECORDING, // RECORDING
            TileState.RECORDING, // TRANSCRIBING
            TileState.SENDING, // SENDING
            TileState.FAILED, // ERROR
        )
        val actual: List<TileState> = sessions().map {
            tileStateFor(true, it, false, false, null, false, true)
        }
        assertEquals("app: with no commit the fallback must not change the session map", expected, actual)
    }

    private fun sessions(): List<DictationState> = listOf(
        DictationState.IDLE,
        DictationState.ARMED,
        DictationState.RECORDING,
        DictationState.TRANSCRIBING,
        DictationState.SENDING,
        DictationState.ERROR,
    )
}
