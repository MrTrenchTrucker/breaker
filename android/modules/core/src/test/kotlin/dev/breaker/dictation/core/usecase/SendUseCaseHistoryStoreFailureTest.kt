package dev.breaker.dictation.core.usecase

import dev.breaker.dictation.core.model.CommitOutcome
import dev.breaker.dictation.core.model.DictationSession
import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.port.HistoryStore
import dev.breaker.dictation.core.testing.FakeTextCommitter
import dev.breaker.dictation.core.testing.aTranscription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A `HistoryStore` that throws is contained the same way a throwing
 * committer is contained: `send` never throws after the commit.
 *
 * The real harm it protects against: `send` commits first and saves second,
 * so a throw from the save used to escape `send` AFTER the committer already
 * received the text — and a caller that retries the send commits the text a
 * second time.
 *
 * The contract the tests hold:
 * - the committer's outcome is returned unchanged (COMMITTED/COPIED/FAILED);
 *   it tells the truth about the text;
 * - the session follows the COMMIT, not the history save: commit landed ->
 *   IDLE, commit failed -> ERROR;
 * - the detail says what was lost, naming the exception's class, never its
 *   message: commit landed + save threw -> "Sent, but not saved to history
 *   (<Class>)"; commit failed + save threw -> "The text could not be sent
 *   and was not saved (<Class>)". Saving is the user's only way to recover
 *   failed text, so that case says so.
 */
class SendUseCaseHistoryStoreFailureTest {
    private class ThrowingHistoryStore : HistoryStore {
        override fun save(transcription: Transcription) =
            throw IllegalStateException("the history write failed")
        override fun list(limit: Int): List<Transcription> = emptyList()
        override fun delete(id: String): Boolean = false
    }

    private class InterruptingHistoryStore : HistoryStore {
        override fun save(transcription: Transcription) =
            throw InterruptedException("the history write was interrupted")
        override fun list(limit: Int): List<Transcription> = emptyList()
        override fun delete(id: String): Boolean = false
    }

    /** Not a failed save: a fault in the runtime itself. */
    private class Meltdown : Error("the runtime is gone")

    private class MeltingHistoryStore : HistoryStore {
        override fun save(transcription: Transcription) = throw Meltdown()
        override fun list(limit: Int): List<Transcription> = emptyList()
        override fun delete(id: String): Boolean = false
    }

    private fun sending() = DictationSession().arm().startRecording()
        .transitionTo(DictationState.TRANSCRIBING)
        .withTranscription(aTranscription())

    @Test
    fun `a throwing save after the commit landed does not change the outcome or the session`() {
        val committer = FakeTextCommitter(CommitOutcome.COMMITTED)
        val useCase = SendUseCase(committer, ThrowingHistoryStore())

        val result = useCase.send(
            sending(),
            aTranscription(id = "kept-1", text = "words worth keeping"),
        )

        assertEquals(
            "the text was committed exactly once",
            listOf("words worth keeping"),
            committer.committed,
        )
        // The outcome is the committer's, unchanged: the text landed.
        assertEquals(CommitOutcome.COMMITTED, result.outcome.outcome)
        // The session follows the COMMIT, not the failed save: the text
        // landed, so it is done — IDLE, not ERROR.
        assertEquals(DictationState.IDLE, result.session.state)
        // The detail says what was lost, naming the class, never the message.
        val detail = result.outcome.detail!!
        assertTrue(
            "the detail names the exception class: $detail",
            detail.contains("IllegalStateException"),
        )
        assertFalse(
            "the detail must not carry the exception message: $detail",
            detail.contains("the history write failed"),
        )
        assertFalse(
            "the detail must not carry the dictated text: $detail",
            detail.contains("words worth keeping"),
        )
        assertEquals(
            "Sent, but not saved to history (IllegalStateException)",
            detail,
        )
        // A plain store failure is not an interruption: it must leave the
        // thread's interrupt flag alone (the committer's twin pins the same).
        assertFalse(
            "an ordinary failure must not set the interrupt flag",
            Thread.currentThread().isInterrupted,
        )
    }

    @Test
    fun `a throwing save after a failed commit says the text was neither sent nor saved`() {
        val committer = FakeTextCommitter(CommitOutcome.FAILED)
        val useCase = SendUseCase(committer, ThrowingHistoryStore())

        val result = useCase.send(
            sending(),
            aTranscription(id = "lost-1", text = "words nobody got"),
        )

        assertEquals(CommitOutcome.FAILED, result.outcome.outcome)
        // The commit failed, so the session is ERROR — as today.
        assertEquals(DictationState.ERROR, result.session.state)
        // Saving is the user's only way to recover failed text, so the
        // detail says both were lost, naming the class, never the message.
        val detail = result.outcome.detail!!
        assertFalse(
            "the detail must not carry the exception message: $detail",
            detail.contains("the history write failed"),
        )
        assertFalse(
            "the detail must not carry the dictated text: $detail",
            detail.contains("words nobody got"),
        )
        assertEquals(
            "The text could not be sent and was not saved (IllegalStateException)",
            detail,
        )
        // A plain store failure is not an interruption: it must leave the
        // thread's interrupt flag alone (the committer's twin pins the same).
        assertFalse(
            "an ordinary failure must not set the interrupt flag",
            Thread.currentThread().isInterrupted,
        )
    }

    @Test
    fun `an interrupted save after the commit landed keeps the commit and restores the interrupt flag`() {
        try {
            val committer = FakeTextCommitter(CommitOutcome.COMMITTED)
            val useCase = SendUseCase(committer, InterruptingHistoryStore())

            val result = useCase.send(
                sending(),
                aTranscription(id = "kept-2", text = "interrupted words"),
            )

            // The commit landed, so the outcome and the session are the
            // commit's: the text was sent and the session is done.
            assertEquals(CommitOutcome.COMMITTED, result.outcome.outcome)
            assertEquals(DictationState.IDLE, result.session.state)
            // The detail names the class, never the message.
            val detail = result.outcome.detail!!
            assertFalse(
                "the detail must not carry the exception message: $detail",
                detail.contains("the history write was interrupted"),
            )
            assertFalse(
                "the detail must not carry the dictated text: $detail",
                detail.contains("interrupted words"),
            )
            assertEquals(
                "Sent, but not saved to history (InterruptedException)",
                detail,
            )
            // An interruption is not a failure the code may swallow: the
            // flag is restored for whatever runs next on this thread.
            assertTrue(
                "the interrupt flag was swallowed",
                Thread.currentThread().isInterrupted,
            )
        } finally {
            Thread.interrupted() // leave the test thread clean for the next test
        }
    }

    @Test
    fun `an error from the store is not a failed save and still propagates`() {
        val committer = FakeTextCommitter(CommitOutcome.COMMITTED)
        val useCase = SendUseCase(committer, MeltingHistoryStore())

        try {
            useCase.send(
                sending(),
                aTranscription(id = "gone-1", text = "words the runtime lost"),
            )
            fail("expected the Error to propagate")
        } catch (expected: Meltdown) {
            // propagated, as it should — an Error is not a failed save the
            // code may contain.
        }
        // send() commits first and saves second: the commit had already
        // happened when the store threw.
        assertEquals(
            "the text was committed before the store faulted",
            listOf("words the runtime lost"),
            committer.committed,
        )
    }

    @Test
    fun `an interrupted save after a failed commit keeps the failure and restores the interrupt flag`() {
        try {
            val committer = FakeTextCommitter(CommitOutcome.FAILED)
            val useCase = SendUseCase(committer, InterruptingHistoryStore())

            val result = useCase.send(
                sending(),
                aTranscription(id = "lost-2", text = "interrupted loss"),
            )

            // The commit failed, so the session is ERROR — the save's
            // interruption does not change that.
            assertEquals(CommitOutcome.FAILED, result.outcome.outcome)
            assertEquals(DictationState.ERROR, result.session.state)
            val detail = result.outcome.detail!!
            assertFalse(
                "the detail must not carry the exception message: $detail",
                detail.contains("the history write was interrupted"),
            )
            assertFalse(
                "the detail must not carry the dictated text: $detail",
                detail.contains("interrupted loss"),
            )
            assertEquals(
                "The text could not be sent and was not saved (InterruptedException)",
                detail,
            )
            assertTrue(
                "the interrupt flag was swallowed",
                Thread.currentThread().isInterrupted,
            )
        } finally {
            Thread.interrupted() // leave the test thread clean for the next test
        }
    }
}