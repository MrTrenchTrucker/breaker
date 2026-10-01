package dev.breaker.dictation.core.usecase

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.AudioFormat
import dev.breaker.dictation.core.model.CommitOutcome
import dev.breaker.dictation.core.model.CommitRequest
import dev.breaker.dictation.core.model.DictationResult
import dev.breaker.dictation.core.model.DictationSession
import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.core.model.SttError
import dev.breaker.dictation.core.port.CommitOutcomeResult
import dev.breaker.dictation.core.port.TextCommitter
import dev.breaker.dictation.core.testing.FakeTextCommitter
import dev.breaker.dictation.core.testing.FakeWavEncoder
import dev.breaker.dictation.core.testing.FixedClock
import dev.breaker.dictation.core.testing.InMemoryHistoryStore
import dev.breaker.dictation.core.testing.InMemorySettingsStore
import dev.breaker.dictation.core.testing.PassThroughFormatter
import dev.breaker.dictation.core.testing.RecordingSttEngine
import dev.breaker.dictation.core.testing.ScriptedConnectivityProbe
import dev.breaker.dictation.core.testing.SequentialIds
import dev.breaker.dictation.core.testing.aTranscription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Sending a dictation that has no text in it. A quiet recording transcribes to
 * an empty string, which is a legal transcription, so the send step has to
 * cope with it: nothing to type, but the dictation still belongs in history.
 */
class SendUseCaseEmptyTextTest {
    private val history = InMemoryHistoryStore()

    private fun sending() = DictationSession().arm().startRecording()
        .transitionTo(DictationState.TRANSCRIBING)
        .withTranscription(aTranscription())

    /** A committer that fails the way a broken adapter would: by throwing. */
    private class ThrowingCommitter : TextCommitter {
        var calls = 0
            private set

        override fun commit(request: CommitRequest): CommitOutcomeResult {
            calls++
            throw IllegalStateException("adapter exploded while typing the secret words")
        }
    }

    @Test
    fun `an empty transcription is saved and reported as failed without asking the committer`() {
        val committer = FakeTextCommitter(CommitOutcome.COMMITTED)
        val useCase = SendUseCase(committer, history)

        val result = useCase.send(sending(), aTranscription(id = "quiet-1", text = ""))

        assertEquals("the committer was asked to type nothing", 0, committer.callCount)
        assertEquals(CommitOutcome.FAILED, result.outcome.outcome)
        assertFalse(result.isCommitted)
        assertEquals(
            "the reason comes from the empty-text check, not from the committer's guard",
            "Nothing was heard, so there is no text to send",
            result.outcome.detail,
        )
        assertEquals(listOf("quiet-1"), history.saved.map { it.id })
        assertEquals(DictationState.ERROR, result.session.state)
        assertEquals(SttError.OTHER, result.session.lastError)
    }

    @Test
    fun `whitespace-only text is still committed as it was dictated`() {
        val committer = FakeTextCommitter(CommitOutcome.COMMITTED)
        val useCase = SendUseCase(committer, history)

        val result = useCase.send(sending(), aTranscription(text = "  "))

        assertEquals(listOf("  "), committer.committed)
        assertEquals(CommitOutcome.COMMITTED, result.outcome.outcome)
        assertEquals(DictationState.IDLE, result.session.state)
        assertEquals(1, history.saved.size)
    }

    @Test
    fun `a committer that throws still leaves the dictation in history`() {
        val committer = ThrowingCommitter()
        val useCase = SendUseCase(committer, history)

        val result = useCase.send(sending(), aTranscription(id = "t-throw", text = "words worth keeping"))

        assertEquals(1, committer.calls)
        assertEquals(CommitOutcome.FAILED, result.outcome.outcome)
        assertEquals("words worth keeping", history.saved.single().text)
        assertEquals(DictationState.ERROR, result.session.state)
        assertEquals(SttError.OTHER, result.session.lastError)
        val detail = result.outcome.detail
        assertNotNull(detail)
        assertTrue("the detail names the failure: $detail", detail!!.contains("IllegalStateException"))
        assertFalse("the detail must not carry the exception message: $detail", detail.contains("secret words"))
        assertFalse("the detail must not carry the dictated text: $detail", detail.contains("words worth keeping"))
    }

    @Test
    fun `an empty transcription sent from the wrong state is refused before anything happens`() {
        val committer = FakeTextCommitter()
        val useCase = SendUseCase(committer, history)

        try {
            useCase.send(DictationSession(), aTranscription(text = ""))
            fail("expected sending from idle to be refused")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!, expected.message!!.contains("SENDING"))
        }

        assertEquals(0, committer.callCount)
        assertEquals("nothing may be saved from a refused send", 0, history.saved.size)
    }

    @Test
    fun `a quiet recording dictated and then sent leaves exactly one row in history`() {
        val committer = FakeTextCommitter()
        val dictate = DictateUseCase(
            settings = InMemorySettingsStore(AppSettings()),
            probe = ScriptedConnectivityProbe(reachable = true),
            localEngine = RecordingSttEngine.succeeding("unused"),
            serverEngine = RecordingSttEngine.succeeding(""),
            serverFormatter = PassThroughFormatter,
            wavEncoder = FakeWavEncoder,
            clock = FixedClock(),
            ids = SequentialIds(),
            localFormatter = PassThroughFormatter,
        )
        val quiet = FloatArray(AudioFormat.SAMPLE_RATE_HZ)

        val dictated = dictate.dictate(DictationSession().arm().startRecording(), quiet)
        assertTrue("a quiet recording is a success with no text", dictated is DictationResult.Success)
        dictated as DictationResult.Success
        assertEquals("", dictated.transcription.text)

        val sent = SendUseCase(committer, history).send(dictated.session, dictated.transcription)

        assertEquals(0, committer.callCount)
        assertEquals(CommitOutcome.FAILED, sent.outcome.outcome)
        assertEquals(
            "the reason comes from the empty-text check, not from the committer's guard",
            "Nothing was heard, so there is no text to send",
            sent.outcome.detail,
        )
        assertEquals(1, history.saved.size)
        assertEquals(dictated.transcription, history.saved.single())
    }
}
