package dev.breaker.dictation.core.model

import dev.breaker.dictation.core.testing.aTranscription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * A session must not describe a state it has left. The error of a failed
 * attempt and the text of a finished one belong to the state that produced
 * them, so a move that ends that state takes them with it.
 *
 * The constructor is deliberately permissive (tests and callers build any
 * combination they like); coherence is enforced on the way through
 * [DictationSession.transitionTo], which every move — [DictationSession.arm],
 * [DictationSession.cancel] and the rest — goes through.
 */
class DictationSessionCoherenceTest {
    private val staleError = SttError.TIMEOUT
    private val staleText = aTranscription(id = "stale", text = "left over from before")

    /** A session in [state] that still carries both leftovers. */
    private fun dirty(state: DictationState) =
        DictationSession(state = state, lastError = staleError, lastTranscription = staleText)

    // Which leftovers each destination may keep. Written out as the rule, not
    // computed from the implementation.
    private val destinationsThatKeepTheError = setOf(DictationState.ERROR)
    private val destinationsThatKeepTheText = setOf(DictationState.SENDING, DictationState.ERROR)

    @Test
    fun `every legal move drops exactly the leftovers its destination does not keep`() {
        var checked = 0
        DictationSession.LEGAL_TRANSITIONS.forEach { (from, destinations) ->
            destinations.forEach { to ->
                val moved = dirty(from).transitionTo(to)
                val label = "$from -> $to"

                assertEquals(label, to, moved.state)
                if (to in destinationsThatKeepTheError) {
                    assertEquals("$label keeps the error", staleError, moved.lastError)
                } else {
                    assertNull("$label must not carry an error into ${to.name}", moved.lastError)
                }
                if (to in destinationsThatKeepTheText) {
                    assertSame("$label keeps the text", staleText, moved.lastTranscription)
                } else {
                    assertNull("$label must not carry a transcription into ${to.name}", moved.lastTranscription)
                }
                checked++
            }
        }
        assertEquals("the table has 10 legal moves", 10, checked)
    }

    @Test
    fun `arming a session that still carries an old failure starts clean`() {
        val armed = dirty(DictationState.IDLE).arm()

        assertEquals(DictationState.ARMED, armed.state)
        assertNull(armed.lastError)
        assertNull(armed.lastTranscription)
    }

    @Test
    fun `cancelling clears the error and the text`() {
        listOf(DictationState.ARMED, DictationState.RECORDING, DictationState.SENDING, DictationState.ERROR)
            .forEach { from ->
                val idle = dirty(from).cancel()

                assertEquals(DictationState.IDLE, idle.state)
                assertNull("cancel() from $from left an error behind", idle.lastError)
                assertNull("cancel() from $from left a transcription behind", idle.lastTranscription)
            }
    }

    @Test
    fun `a failed send keeps the text so it can be recovered and records the error`() {
        val text = aTranscription(id = "t-keep", text = "do not lose this")
        val sending = DictationSession().arm().startRecording()
            .transitionTo(DictationState.TRANSCRIBING)
            .withTranscription(text)

        val failed = sending.withError(SttError.OTHER)

        assertEquals(DictationState.ERROR, failed.state)
        assertEquals(SttError.OTHER, failed.lastError)
        assertSame(text, failed.lastTranscription)
    }

    @Test
    fun `a session that leaves sending for idle keeps no transcription`() {
        // A session in SENDING that also carries a stale error, so that both leftovers
        // are there to be seen going.
        val sending = DictationSession().arm().startRecording()
            .transitionTo(DictationState.TRANSCRIBING)
            .withTranscription(aTranscription())
            .copy(lastError = staleError)
        assertEquals(DictationState.SENDING, sending.state)
        assertNotNull("the fixture must hold a transcription", sending.lastTranscription)
        assertNotNull("the fixture must hold a stale error", sending.lastError)

        val idle = sending.cancel()

        assertEquals(DictationState.IDLE, idle.state)
        assertNull("the transcription survived leaving SENDING", idle.lastTranscription)
        assertNull("the stale error survived leaving SENDING", idle.lastError)
    }

    @Test
    fun `the next attempt after a failure carries nothing from the failed one`() {
        val failed = DictationSession().arm().startRecording()
            .transitionTo(DictationState.TRANSCRIBING)
            .withTranscription(aTranscription(id = "failed-1", text = "from the failed attempt"))
            .withError(SttError.LOCAL_MODEL_MISSING)
        assertEquals(SttError.LOCAL_MODEL_MISSING, failed.lastError)
        assertNotNull("the failed attempt still holds its text", failed.lastTranscription)

        val secondAttempt = failed.cancel().arm().startRecording().transitionTo(DictationState.TRANSCRIBING)

        assertEquals(DictationState.TRANSCRIBING, secondAttempt.state)
        assertNull("the earlier error is still on the new attempt", secondAttempt.lastError)
        assertNull("the earlier text is still on the new attempt", secondAttempt.lastTranscription)
    }

    @Test
    fun `a full dictation from idle back to idle leaves the session empty`() {
        val done = DictationSession().arm().startRecording()
            .transitionTo(DictationState.TRANSCRIBING)
            .withTranscription(aTranscription())
            .cancel()

        assertEquals(DictationSession(), done)
    }
}
