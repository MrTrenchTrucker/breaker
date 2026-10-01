package dev.breaker.dictation.core.usecase

import dev.breaker.dictation.core.model.CommitOutcome
import dev.breaker.dictation.core.model.CommitRequest
import dev.breaker.dictation.core.model.DictationSession
import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.core.model.SttError
import dev.breaker.dictation.core.port.CommitOutcomeResult
import dev.breaker.dictation.core.port.TextCommitter
import dev.breaker.dictation.core.testing.InMemoryHistoryStore
import dev.breaker.dictation.core.testing.aTranscription
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A dictation is saved to history on every outcome, and that includes a committer
 * that throws. An adapter is free to throw whatever it likes, so the checked
 * exceptions a clipboard write or a blocked hand-off produce must not skip the
 * save either. An [Error] is not a failed commit and is left to propagate.
 */
class SendUseCaseCommitterFailureTest {
    private val history = InMemoryHistoryStore()
    private val secret = "adapter exploded while typing the secret words"

    private class ThrowingCommitter(private val error: Throwable) : TextCommitter {
        override fun commit(request: CommitRequest): CommitOutcomeResult = throw error
    }

    /** Not a failed commit: a fault in the runtime itself. */
    private class Meltdown : Error("the runtime is gone")

    private fun sending() = DictationSession().arm().startRecording()
        .transitionTo(DictationState.TRANSCRIBING)
        .withTranscription(aTranscription())

    private fun assertSavedAndFailed(error: Throwable, expectedName: String) {
        val useCase = SendUseCase(ThrowingCommitter(error), history)

        val result = useCase.send(sending(), aTranscription(id = "kept-1", text = "words worth keeping"))

        assertEquals(listOf("kept-1"), history.saved.map { it.id })
        assertEquals(CommitOutcome.FAILED, result.outcome.outcome)
        assertEquals(DictationState.ERROR, result.session.state)
        assertEquals(SttError.OTHER, result.session.lastError)
        val detail = result.outcome.detail!!
        assertTrue("the detail names the failure: $detail", detail.contains(expectedName))
        assertFalse("the detail must not carry the exception message: $detail", detail.contains("secret words"))
        assertFalse("the detail must not carry the dictated text: $detail", detail.contains("words worth keeping"))
    }

    @Test
    fun `a checked exception from the committer still leaves the dictation in history`() {
        assertSavedAndFailed(IOException(secret), "IOException")
        assertFalse("an ordinary failure must not set the interrupt flag", Thread.currentThread().isInterrupted)
    }

    @Test
    fun `a runtime exception of any kind from the committer still leaves the dictation in history`() {
        assertSavedAndFailed(IllegalArgumentException(secret), "IllegalArgumentException")
        history.saved.clear()
        assertSavedAndFailed(UnsupportedOperationException(secret), "UnsupportedOperationException")
        assertFalse("an ordinary failure must not set the interrupt flag", Thread.currentThread().isInterrupted)
    }

    @Test
    fun `an interruption in the committer is a failed commit and the interrupt flag is restored`() {
        try {
            assertSavedAndFailed(InterruptedException(secret), "InterruptedException")
            assertTrue("the interrupt flag was swallowed", Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted() // leave the test thread clean for the next test
        }
    }

    @Test
    fun `an error from the committer is not a failed commit and still propagates`() {
        val useCase = SendUseCase(ThrowingCommitter(Meltdown()), history)

        try {
            useCase.send(sending(), aTranscription())
            fail("expected the Error to propagate")
        } catch (expected: Meltdown) {
            // propagated, as it should
        }
    }
}
