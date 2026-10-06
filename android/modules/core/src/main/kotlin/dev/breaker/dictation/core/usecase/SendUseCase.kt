package dev.breaker.dictation.core.usecase

import dev.breaker.dictation.core.model.CommitOutcome
import dev.breaker.dictation.core.model.CommitRequest
import dev.breaker.dictation.core.model.DictationSession
import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.core.model.SttError
import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.port.CommitOutcomeResult
import dev.breaker.dictation.core.port.HistoryStore
import dev.breaker.dictation.core.port.TextCommitter

/** What a send did: the commit outcome, and the session after it. */
data class SendResult(val outcome: CommitOutcomeResult, val session: DictationSession) {
    /** True when the text reached the field the user was typing in. */
    val isCommitted: Boolean get() = outcome.outcome == CommitOutcome.COMMITTED

    /**
     * Prints the outcome, the session's state and the id of the transcription it
     * holds, never the text. The outcome's own reason is left out too: it is an
     * adapter's string, and this print does not depend on the adapter keeping the
     * text out of it.
     */
    override fun toString(): String =
        "SendResult(outcomeKind=${outcome.outcome}, sessionState=${session.state}, " +
            "transcriptionId=${session.lastTranscription?.id})"
}

/**
 * Puts a finished transcription where the user wants it, and keeps a copy.
 *
 * The order is deliberate: commit first, then save. The user gets their text
 * even if saving is slow, and history is the durable record either way.
 *
 * History is written on **every** outcome, including a failed commit. A commit
 * that fails everywhere is precisely the dictation the user would otherwise
 * lose, and history is where they can get it back from. Which engine produced
 * the text does not matter either — local and server transcriptions are both
 * saved, so switching engines never makes a dictation disappear.
 *
 * That holds for a dictation with nothing in it, too. A quiet recording
 * transcribes to an empty string, which is a legal transcription but has
 * nothing to type: the committer is not asked, the outcome is a failure with a
 * reason the user can read, and the dictation is still saved. A committer that
 * throws is treated the same way, as a failed commit, rather than letting the
 * exception skip the save. That covers checked exceptions too (an adapter is free to
 * throw an IOException or an InterruptedException); an interruption also leaves the
 * thread's interrupt flag set. An [Error] is not a failed commit and propagates.
 *
 * A throwing `HistoryStore` is contained the same way: send() never throws
 * after the commit; the outcome is the committer's, the detail says the
 * dictation was not saved to history, and the session follows the commit.
 *
 * This is text commit: it runs on the user's explicit send, into the field the
 * user focused, or into the clipboard when there is none.
 */
class SendUseCase(
    private val committer: TextCommitter,
    private val history: HistoryStore,
) {
    /**
     * Commit [transcription] and save it. [session] must be in the sending
     * state; it comes back idle when the text landed anywhere, and in error
     * when it did not.
     */
    fun send(session: DictationSession, transcription: Transcription): SendResult {
        check(session.state == DictationState.SENDING) {
            "Text can only be sent from the SENDING state, not ${session.state.name}"
        }
        val outcome = commitOutcomeOf(transcription.text)
        // The save is second, and a throwing store is contained the same way a
        // throwing committer is: send() never throws after the commit. A
        // caller that retries the send would commit the text a second time, so
        // the failure is reported in the detail, not thrown.
        val saveFailure = saveToHistory(transcription)
        val detail = when {
            saveFailure != null && outcome.isSuccess ->
                "Sent, but not saved to history ($saveFailure)"
            saveFailure != null ->
                "The text could not be sent and was not saved ($saveFailure)"
            else -> outcome.detail
        }
        // The session follows the COMMIT, not the history save: the outcome
        // tells the truth about the text.
        val next = if (outcome.isSuccess) {
            session.cancel()
        } else {
            session.withError(SttError.OTHER)
        }
        return SendResult(CommitOutcomeResult(outcome.outcome, detail), next)
    }

    /**
     * Save [transcription] to history, containing a throwing store the same
     * way a throwing committer is contained. Returns the exception's class
     * name when the save threw (the class, never its message — the same
     * redaction rule as the committer's catch), or null when the save ran.
     * An [Error] is not an adapter failure and propagates.
     */
    private fun saveToHistory(transcription: Transcription): String? {
        return try {
            history.save(transcription)
            null
        } catch (e: Exception) {
            // Re-armed, not folded: an interrupted save is the thread's, not just a
            // "not saved" class name (the threading rule in the root AGENTS.md).
            if (e is InterruptedException) Thread.currentThread().interrupt()
            e::class.simpleName ?: "error"
        }
    }

    /**
     * What the committer made of [text], without letting an empty text or a
     * throwing adapter stop [send] from saving the dictation.
     */
    private fun commitOutcomeOf(text: String): CommitOutcomeResult {
        if (text.isEmpty()) {
            return CommitOutcomeResult(CommitOutcome.FAILED, "Nothing was heard, so there is no text to send")
        }
        return try {
            committer.commit(CommitRequest(text))
        } catch (e: Exception) {
            // Re-armed, not folded: an interrupted commit is the thread's, not just a
            // FAILED class name (the threading rule in the root AGENTS.md).
            if (e is InterruptedException) Thread.currentThread().interrupt()
            // Name the failure, never its message: an adapter's message can
            // carry the text it was asked to type.
            CommitOutcomeResult(CommitOutcome.FAILED, "The text could not be sent (${e::class.simpleName ?: "error"})")
        }
    }
}
