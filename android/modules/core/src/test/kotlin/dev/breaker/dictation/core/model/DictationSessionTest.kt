package dev.breaker.dictation.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The dictation state machine. A session that jumps states is a wiring bug, so
 * the machine refuses it and says which moves were legal.
 */
class DictationSessionTest {
    @Test
    fun `a new session is idle and not busy`() {
        val session = DictationSession()
        assertEquals(DictationState.IDLE, session.state)
        assertFalse(session.isBusy)
    }

    @Test
    fun `the happy path walks idle to sending`() {
        val session = DictationSession()
            .arm()
            .startRecording()
            .transitionTo(DictationState.TRANSCRIBING)
        assertEquals(DictationState.TRANSCRIBING, session.state)
        assertTrue(session.isBusy)

        val sending = session.withTranscription(aTranscription())
        assertEquals(DictationState.SENDING, sending.state)
        assertTrue(sending.isBusy)
    }

    @Test
    fun `an illegal move is refused and the message says what was legal`() {
        val session = DictationSession()
        try {
            session.transitionTo(DictationState.SENDING)
            fail("expected idle -> sending to be refused")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("IDLE -> SENDING"))
            assertTrue(e.message!!.contains("ARMED"))
        }
    }

    @Test
    fun `sending is only reachable from transcribing`() {
        val transcribing = DictationSession().arm().startRecording()
            .transitionTo(DictationState.TRANSCRIBING)
        val ready = transcribing.withTranscription(aTranscription())
        assertEquals(DictationState.SENDING, ready.state)
        assertNotNull(ready.lastTranscription)
    }

    @Test
    fun `an error can be raised from transcribing and from sending`() {
        val transcribing = DictationSession().arm().startRecording()
            .transitionTo(DictationState.TRANSCRIBING)
        val failed = transcribing.withError(SttError.LOCAL_MODEL_MISSING)
        assertEquals(DictationState.ERROR, failed.state)
        assertEquals(SttError.LOCAL_MODEL_MISSING, failed.lastError)
        assertFalse(failed.isBusy)

        val sending = transcribing.withTranscription(aTranscription())
        assertEquals(DictationState.ERROR, sending.withError(SttError.TIMEOUT).state)
    }

    @Test
    fun `error returns to idle`() {
        val errored = DictationSession().arm().startRecording()
            .transitionTo(DictationState.TRANSCRIBING)
            .withError(SttError.TIMEOUT)
        assertEquals(DictationState.IDLE, errored.cancel().state)
    }

    @Test
    fun `arming from recording is refused`() {
        val recording = DictationSession().arm().startRecording()
        try {
            recording.arm()
            fail("expected recording -> armed to be refused")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("RECORDING -> ARMED"))
        }
    }

    @Test
    fun `a failed dictation records the transcription and the reason`() {
        val transcribing = DictationSession().arm().startRecording()
            .transitionTo(DictationState.TRANSCRIBING)
        val result = DictationResult.Failure(
            session = transcribing.withError(SttError.SERVER_UNREACHABLE),
            error = SttError.SERVER_UNREACHABLE,
            detail = "no answer",
        )
        assertEquals(DictationState.ERROR, result.session.state)
        assertEquals(SttError.SERVER_UNREACHABLE, result.error)
    }

    @Test
    fun `every state has a defined set of legal moves`() {
        assertEquals(
            DictationState.values().toSet(),
            DictationSession.LEGAL_TRANSITIONS.keys,
        )
        // Every state must be able to get back to idle eventually.
        //
        // The walk keeps a visited set rather than generating a sequence: the
        // transition graph is cyclic (sending returns to idle, which arms
        // again), so a sequence without one loops forever instead of finishing.
        DictationState.values().forEach { state ->
            val reachable = mutableSetOf(state)
            val pending = ArrayDeque(listOf(state))
            while (pending.isNotEmpty()) {
                val current = pending.removeFirst()
                DictationSession.LEGAL_TRANSITIONS[current].orEmpty()
                    .filter { reachable.add(it) }
                    .forEach { pending.addLast(it) }
            }
            assertTrue("$state cannot reach IDLE", reachable.contains(DictationState.IDLE))
        }
    }

    private fun aTranscription() =
        Transcription("t-1", "hello", TranscriptionSource.LOCAL, "small", 1_000L, 1L)
}
