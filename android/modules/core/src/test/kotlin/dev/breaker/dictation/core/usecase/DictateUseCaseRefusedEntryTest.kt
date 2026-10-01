package dev.breaker.dictation.core.usecase

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.DictationResult
import dev.breaker.dictation.core.model.DictationSession
import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.core.model.SttMode
import dev.breaker.dictation.core.testing.FakeAudioSource
import dev.breaker.dictation.core.testing.FakeWavEncoder
import dev.breaker.dictation.core.testing.FixedClock
import dev.breaker.dictation.core.testing.InMemorySettingsStore
import dev.breaker.dictation.core.testing.PassThroughFormatter
import dev.breaker.dictation.core.testing.RecordingSttEngine
import dev.breaker.dictation.core.testing.ScriptedConnectivityProbe
import dev.breaker.dictation.core.testing.SequentialIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A `stopCapture` or `cancel` the state machine refuses is a wiring bug, and —
 * like a refused `startCapture`, whose no-side-effect standard is pinned by
 * `DictateUseCaseTest` — it must not stop the source and must not discard the
 * capture, so the caller can try again with the right session.
 *
 * The concrete harm of a stale cancel: the caller hands an already-idle
 * session, and the in-progress capture's audio is discarded before the refusal
 * throws, so the live dictation is lost.
 */
class DictateUseCaseRefusedEntryTest {
    private val clock = FixedClock()
    private val ids = SequentialIds()
    private val local = RecordingSttEngine.succeeding("local words")
    private val server = RecordingSttEngine.succeeding("server words")

    private fun useCase(): DictateUseCase = DictateUseCase(
        settings = InMemorySettingsStore(AppSettings(mode = SttMode.LOCAL)),
        probe = ScriptedConnectivityProbe(reachable = true),
        localEngine = local,
        serverEngine = server,
        serverFormatter = PassThroughFormatter,
        wavEncoder = FakeWavEncoder,
        clock = clock,
        ids = ids,
        localFormatter = PassThroughFormatter,
    )

    @Test
    fun `a stopCapture the state machine refuses has no side effects and keeps the capture`() {
        val useCase = useCase()
        val audio = FakeAudioSource()
        val session = useCase.startCapture(DictationSession().arm().startRecording(), audio)
        audio.emitTone(1_000)

        // A wiring bug: the caller hands a session that is not RECORDING (here
        // IDLE). The state machine refuses it.
        try {
            useCase.stopCapture(DictationSession(), audio)
            fail("expected a stop from idle to be refused")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("IDLE -> TRANSCRIBING"))
        }

        // The module's own standard (pinned for startCapture): a refused entry
        // point has no side effects. A refused stop must not stop the source...
        assertEquals("a refused stop must not stop the source", 0, audio.stopCount)
        // ...nor discard the capture, so a correct stop still transcribes it.
        val result = useCase.stopCapture(session, audio)
        assertTrue("the capture was discarded by the refused stop: $result",
            result is DictationResult.Success)
        assertEquals(1_000L, (result as DictationResult.Success).transcription.durationMs)
    }

    @Test
    fun `a cancel the state machine refuses does not stop the source a second time`() {
        val useCase = useCase()
        val audio = FakeAudioSource()
        val session = useCase.startCapture(DictationSession().arm().startRecording(), audio)
        audio.emitTone(1_000)

        // A legal cancel (from RECORDING): stops the source, returns IDLE.
        val idle = useCase.cancel(session, audio)
        assertEquals(DictationState.IDLE, idle.state)
        assertEquals(1, audio.stopCount)

        // A wiring bug: the caller cancels again, handing the already-idle
        // session. The state machine refuses it.
        try {
            useCase.cancel(idle, audio)
            fail("expected a cancel from idle to be refused")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("IDLE -> IDLE"))
        }

        // A refused cancel must not stop the source a second time.
        assertEquals("a refused cancel must not stop the source", 1, audio.stopCount)
    }

    @Test
    fun `a stale cancel handed a fresh session does not kill the live capture`() {
        val useCase = useCase()
        val audio = FakeAudioSource()
        // A live capture is in progress.
        val live = useCase.startCapture(DictationSession().arm().startRecording(), audio)
        audio.emitTone(1_000)

        // A wiring bug: a stale (already-idle) session is handed to cancel.
        val stale = DictationSession()
        try {
            useCase.cancel(stale, audio)
            fail("expected a cancel from idle to be refused")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("IDLE -> IDLE"))
        }

        // The concrete harm, stated as the desired behaviour: a refused cancel
        // has no side effects, so the live capture is still intact and still
        // transcribes.
        val result = useCase.stopCapture(live, audio)
        assertTrue("the live capture was killed by the stale cancel: $result",
            result is DictationResult.Success)
        assertEquals(1_000L, (result as DictationResult.Success).transcription.durationMs)
    }
}
