package dev.breaker.dictation.wiring

import dev.breaker.dictation.auth.client.AddressedSession
import dev.breaker.dictation.core.model.AuthSession
import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.model.TranscriptionSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The session-live state: it derives from the addressed session only, and nowhere else. Tests drive it directly so
 * no file vault ever enters a case; what reads the store stays with the composition root (see the gate test). Each
 * assertion is computed from one pure line of code, drawn before it holds.
 */
class SessionLiveTest {

    /** A stored session: base address plus its AuthSession, built like the wiring fakes build one. */
    private fun session(base: String): AddressedSession =
        AddressedSession(base, AuthSession("id-1", "user@example.test", "tok-1", dev.breaker.dictation.core.model.UserRole.USER, setOf(dev.breaker.dictation.core.model.TokenScope.TRANSCRIBE)))

    @Test
    fun `the phone starts signed out on a fresh install`() {
        // A fresh install has no stored session; the seed read answers false.
        val state = SessionLiveState(false)
        assertEquals("a null addressed session derives not live", false, sessionLiveFrom { null })
        assertEquals("state already reflects its initial value", false, state.liveNow())

        // A stored session flips it to true: the paired test above is the accept control.
        assertEquals("a non-null addressed session derives live", true, sessionLiveFrom { session("https://example.test") })
    }

    @Test
    fun `the phone starts signed in when a session is stored`() {
        // A stored session is not null; the state is seeded true. Accept control: a null session answers false.
        assertEquals("a stored session seeds live", true, sessionLiveFrom { session("https://example.test") })
    }

    @Test
    fun `a key refusal on a finished take signs the phone out`() {
        val state = SessionLiveState(true)
        // A Failed with KEY_REFUSED text flips the state to false. Accept control: null leaves it true.
        onFinishResultFor(state, FinishResult.Failed(SessionRefusalSentences.KEY_REFUSED_TEXT))
        assertEquals("a key refusal signs the phone out", false, state.liveNow())

        val stillLive = SessionLiveState(true)
        // A Failed with any other sentence must leave the state true (accept control: null stays true).
        onFinishResultFor(stillLive, FinishResult.Failed("The recording could not be prepared for the server."))
        assertEquals("a different refusal leaves it live", true, stillLive.liveNow())
    }

    @Test
    fun `a refusal text with more words around it leaves the phone signed in`() {
        val state = SessionLiveState(true)
        onFinishResultFor(state, FinishResult.Failed(SessionRefusalSentences.KEY_REFUSED_TEXT + " Try again."))
        onFinishResultFor(state, FinishResult.Failed("Note: " + SessionRefusalSentences.NO_KEY_TEXT))
        assertEquals("a sentence that only contains a refusal text leaves it live", true, state.liveNow())
    }

    @Test
    fun `a no key answer on a finished take keeps the phone signed out`() {
        val fromFalse = SessionLiveState(false)
        // From false with NO_KEY text: stays false. Accept control: null leaves it as-is (false).
        onFinishResultFor(fromFalse, FinishResult.Failed(SessionRefusalSentences.NO_KEY_TEXT))
        assertEquals("a no key answer over a false state stays out", false, fromFalse.liveNow())

        val fromTrue = SessionLiveState(true)
        // From true with NO_KEY text: goes false. Accept control: null leaves it true.
        onFinishResultFor(fromTrue, FinishResult.Failed(SessionRefusalSentences.NO_KEY_TEXT))
        assertEquals("a no key answer over a live state goes out", false, fromTrue.liveNow())

        // A ReadyToSend does not flip the state (accept control: the two cases above).
        val ready = SessionLiveState(true)
        onFinishResultFor(ready, FinishResult.ReadyToSend(Transcription("t-1", "hello", TranscriptionSource.LOCAL, "model", 0L, 0)))
        assertEquals("a ready take keeps it live", true, ready.liveNow())
    }

    @Test
    fun `a ready take keeps the phone signed in`() {
        val state = SessionLiveState(true)
        // ReadyToSend and null both leave the state unchanged. Accept control: a Failed over another sentence flips false.
        onFinishResultFor(state, FinishResult.ReadyToSend(Transcription("t-1", "hello", TranscriptionSource.LOCAL, "model", 0L, 0)))
        assertEquals("a ready take stays live", true, state.liveNow())

        // A null result (the refused-background path) leaves the state unchanged too.
        onFinishResultFor(state, null)
        assertEquals("a null result leaves it live", true, state.liveNow())
    }
}
