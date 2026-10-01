package dev.breaker.dictation.core.usecase

import dev.breaker.dictation.core.model.CommitOutcome
import dev.breaker.dictation.core.model.DictationSession
import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.core.model.SttError
import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.core.testing.FakeTextCommitter
import dev.breaker.dictation.core.testing.InMemoryHistoryStore
import dev.breaker.dictation.core.testing.aTranscription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** SendUseCase: text reaches the user, and history keeps a copy either way. */
class SendUseCaseTest {
    private val history = InMemoryHistoryStore()

    private fun sending() = DictationSession().arm().startRecording()
        .transitionTo(DictationState.TRANSCRIBING)
        .withTranscription(aTranscription())

    @Test
    fun `text is committed to the focused field and the session goes idle`() {
        val committer = FakeTextCommitter(CommitOutcome.COMMITTED)
        val useCase = SendUseCase(committer, history)

        val result = useCase.send(sending(), aTranscription(text = "copy that fuel"))

        assertEquals(CommitOutcome.COMMITTED, result.outcome.outcome)
        assertTrue(result.isCommitted)
        assertEquals(listOf("copy that fuel"), committer.committed)
        assertEquals(DictationState.IDLE, result.session.state)
    }

    @Test
    fun `with no field to type into the text is copied and still saved`() {
        val committer = FakeTextCommitter(CommitOutcome.COPIED, "no field focused")
        val useCase = SendUseCase(committer, history)

        val result = useCase.send(sending(), aTranscription())

        assertEquals(CommitOutcome.COPIED, result.outcome.outcome)
        assertEquals("no field focused", result.outcome.detail)
        assertFalse(result.isCommitted)
        assertEquals(DictationState.IDLE, result.session.state)
        assertEquals(1, history.saved.size)
    }

    @Test
    fun `a failed commit is still saved to history so the dictation is not lost`() {
        val committer = FakeTextCommitter(CommitOutcome.FAILED, "nowhere to put it")
        val useCase = SendUseCase(committer, history)

        val result = useCase.send(sending(), aTranscription(id = "t-9", text = "lost work"))

        assertEquals(CommitOutcome.FAILED, result.outcome.outcome)
        assertFalse(result.outcome.isSuccess)
        assertEquals("lost work", history.saved.single().text)
        assertEquals(DictationState.ERROR, result.session.state)
        assertEquals(SttError.OTHER, result.session.lastError)
    }

    @Test
    fun `a locally transcribed dictation is saved just like a server one`() {
        val useCase = SendUseCase(FakeTextCommitter(), history)

        useCase.send(sending(), aTranscription(id = "local-1", source = TranscriptionSource.LOCAL))
        useCase.send(sending(), aTranscription(id = "server-1", source = TranscriptionSource.SERVER))

        assertEquals(listOf(TranscriptionSource.LOCAL, TranscriptionSource.SERVER), history.saved.map { it.source })
    }

    @Test
    fun `empty text is never handed to the committer`() {
        val committer = FakeTextCommitter()
        val useCase = SendUseCase(committer, history)

        val result = useCase.send(sending(), aTranscription(text = ""))

        assertEquals(0, committer.callCount)
        assertEquals(1, history.saved.size)
        assertEquals(CommitOutcome.FAILED, result.outcome.outcome)
        assertEquals(DictationState.ERROR, result.session.state)
    }

    @Test
    fun `sending from the wrong state is refused`() {
        // Every state but SENDING is refused, and the message names the state it
        // was given. Nothing reaches the committer or history from a refused send.
        DictationState.values().filter { it != DictationState.SENDING }.forEach { wrong ->
            val committer = FakeTextCommitter()
            val useCase = SendUseCase(committer, history)
            try {
                useCase.send(DictationSession(state = wrong), aTranscription())
                fail("expected sending from $wrong to be refused")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!, e.message!!.contains("SENDING"))
                assertTrue(e.message!!, e.message!!.contains("not ${wrong.name}"))
            }
            assertEquals("a refused send reached the committer ($wrong)", 0, committer.callCount)
        }
        assertEquals("a refused send was saved", 0, history.saved.size)
    }

    @Test
    fun `sending twice sends the text twice and keeps one row per id`() {
        val committer = FakeTextCommitter()
        val useCase = SendUseCase(committer, history)
        val session = sending()

        useCase.send(session, aTranscription(id = "t-1"))
        useCase.send(session, aTranscription(id = "t-1"))

        assertEquals(2, committer.callCount)
        assertEquals(1, history.saved.size)
    }
}
