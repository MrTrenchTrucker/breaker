package dev.breaker.dictation.core.usecase

import dev.breaker.dictation.core.model.CommitOutcome
import dev.breaker.dictation.core.model.CommitRequest
import dev.breaker.dictation.core.model.DictationSession
import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.port.CommitOutcomeResult
import dev.breaker.dictation.core.port.HistoryStore
import dev.breaker.dictation.core.port.TextCommitter
import dev.breaker.dictation.core.testing.FakeTextCommitter
import dev.breaker.dictation.core.testing.InMemoryHistoryStore
import dev.breaker.dictation.core.testing.aTranscription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The detail of a send is a fixed sentence whenever the commit or the history
 * save fails. An exception's class name and its message are never user text,
 * so each test throws an anonymous exception whose message would show up in
 * the detail if the detail carried it.
 */
class SendUseCaseFailedTextTest {
    /** A committer that throws the given exception. */
    private class ThrowingCommitter(private val error: Throwable) : TextCommitter {
        override fun commit(request: CommitRequest): CommitOutcomeResult = throw error
    }

    /** A history store that throws the given exception on every save. */
    private class ThrowingHistoryStore(private val error: Throwable) : HistoryStore {
        override fun save(transcription: Transcription) {
            throw error
        }
        override fun list(limit: Int): List<Transcription> = emptyList()
        override fun delete(id: String): Boolean = false
    }

    private fun sending() = DictationSession().arm().startRecording()
        .transitionTo(DictationState.TRANSCRIBING)
        .withTranscription(aTranscription())

    @Test
    fun `an anonymous exception from the committer leaves only the fixed sentence`() {
        val marker = object : RuntimeException("marker text") {}
        val history = InMemoryHistoryStore()
        val useCase = SendUseCase(ThrowingCommitter(marker), history)

        val result = useCase.send(sending(), aTranscription(id = "kept-3", text = "words to keep"))

        assertEquals(CommitOutcome.FAILED, result.outcome.outcome)
        val detail = result.outcome.detail!!
        assertEquals("the detail is the fixed sentence", "The text could not be sent.", detail)
        assertFalse("the detail must not carry the exception message: $detail", detail.contains("marker text"))
        assertEquals(listOf("kept-3"), history.saved.map { it.id })
    }

    @Test
    fun `an anonymous exception from a save after the commit landed leaves the saved sentence`() {
        val marker = object : RuntimeException("marker text") {}
        val committer = FakeTextCommitter(CommitOutcome.COMMITTED)
        val useCase = SendUseCase(committer, ThrowingHistoryStore(marker))

        val result = useCase.send(sending(), aTranscription(id = "kept-4", text = "words to keep"))

        assertEquals(CommitOutcome.COMMITTED, result.outcome.outcome)
        assertEquals(DictationState.IDLE, result.session.state)
        val detail = result.outcome.detail!!
        assertEquals("the detail is the fixed sentence", "Sent, but not saved to history.", detail)
        assertFalse("the detail must not carry the exception message: $detail", detail.contains("marker text"))
    }

    @Test
    fun `an anonymous exception from a save after a failed commit leaves the lost sentence`() {
        val marker = object : RuntimeException("marker text") {}
        val committer = FakeTextCommitter(CommitOutcome.FAILED, "adapter detail text")
        val useCase = SendUseCase(committer, ThrowingHistoryStore(marker))

        val result = useCase.send(sending(), aTranscription(id = "lost-3", text = "words nobody got"))

        assertEquals(CommitOutcome.FAILED, result.outcome.outcome)
        assertEquals(DictationState.ERROR, result.session.state)
        val detail = result.outcome.detail!!
        assertEquals(
            "the detail is the fixed sentence",
            "The text could not be sent and was not saved.",
            detail,
        )
        assertFalse("the detail must not carry the exception message: $detail", detail.contains("marker text"))
        assertFalse(
            "the committer's detail is not carried into the lost sentence: $detail",
            detail.contains("adapter detail text"),
        )
    }

    @Test
    fun `a committer detail passes through unchanged when the save works`() {
        val history = InMemoryHistoryStore()
        val committer = FakeTextCommitter(CommitOutcome.FAILED, "the adapter reason, as given")
        val useCase = SendUseCase(committer, history)

        val result = useCase.send(sending(), aTranscription(id = "kept-5", text = "words to keep"))

        assertEquals(CommitOutcome.FAILED, result.outcome.outcome)
        assertEquals(
            "the committer's detail is returned unchanged",
            "the adapter reason, as given",
            result.outcome.detail,
        )
        assertEquals(listOf("kept-5"), history.saved.map { it.id })
    }
}
