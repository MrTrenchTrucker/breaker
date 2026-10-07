package dev.breaker.dictation.commit

import dev.breaker.dictation.core.model.CommitOutcome
import dev.breaker.dictation.core.model.CommitRequest
import dev.breaker.dictation.core.port.CommitOutcomeResult
import dev.breaker.dictation.core.port.TextCommitter
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job

/**
 * Puts dictated text where the user is typing.
 *
 * It types into the focused field of our own keyboard when there is one, and
 * otherwise copies the text to the clipboard. The call blocks; the caller owns
 * the threading. It never throws an [Exception], and no string it builds can
 * contain the text.
 *
 * A block that has started on the main thread is waited for, and the result
 * reports what it did. A block that has not started when the deadline passes is
 * dropped: it never runs, and the call fails as not responding. The deadline
 * only frees the caller; it cannot stop a step that is already running.
 */
class CommitService internal constructor(
    private val focus: FocusedFieldSource,
    private val clipboard: ClipboardWriter,
    private val notice: UserNotice,
    private val mainThread: MainThread,
    private val sdkInt: Int,
) : TextCommitter {

    /** What the main-thread steps decided, and whether one of them was interrupted. */
    private class Attempt(val result: CommitOutcomeResult, val interrupted: Boolean)

    override fun commit(request: CommitRequest): CommitOutcomeResult {
        val text: String = request.text
        // This call's "still wanted" flag. It is cancelled when the hop gives up.
        // The hop itself drops a block that has not started at the deadline; this
        // flag is a second guard for a hop that may still run the block late, so
        // that block finds the flag inactive and touches nothing. A step already
        // running when the hop gives up cannot be stopped; the flag only prevents
        // the next one.
        val attempt: CompletableJob = Job()
        val done: Attempt = try {
            mainThread.call { platformSteps(text, attempt) }
        } catch (e: Exception) {
            attempt.cancel()
            // Re-armed: the interrupt belongs to the caller's thread, and the
            // catch would otherwise swallow it (the threading rule in the root card).
            if (e is InterruptedException) Thread.currentThread().interrupt()
            return CommitOutcomeResult(CommitOutcome.FAILED, CommitTexts.FAILED_NOT_RESPONDING)
        }
        if (done.interrupted) {
            // Re-armed: a step was interrupted inside the hop; the flag goes back
            // to the caller's thread (the threading rule in the root card).
            Thread.currentThread().interrupt()
        }
        return done.result
    }

    /** Every platform call of one commit. Runs inside the one main-thread hop. */
    private fun platformSteps(text: String, attempt: CompletableJob): Attempt {
        var interrupted = false
        var refused = false

        if (attempt.isActive) {
            try {
                val field: FocusedField? = focus.current()
                if (field != null) {
                    when (field.commitText(text)) {
                        FieldCommit.ACCEPTED ->
                            return Attempt(CommitOutcomeResult(CommitOutcome.COMMITTED), interrupted)
                        FieldCommit.REFUSED -> refused = true
                    }
                }
            } catch (e: Exception) {
                // A field that throws counts as a refusal. The message is never read.
                if (e is InterruptedException) interrupted = true
                refused = true
            }
        }

        val copied: Boolean = if (!attempt.isActive) {
            false
        } else {
            try {
                clipboard.copy(text, true)
            } catch (e: Exception) {
                if (e is InterruptedException) interrupted = true
                false
            }
        }
        if (!copied) {
            return Attempt(CommitOutcomeResult(CommitOutcome.FAILED, CommitTexts.FAILED_NOWHERE), interrupted)
        }

        if (sdkInt <= CommitTexts.LAST_SDK_WITHOUT_SYSTEM_CONFIRMATION && attempt.isActive) {
            try {
                notice.showCopied()
            } catch (e: Exception) {
                // The text is already on the clipboard, so a failing toast is not a failed commit.
                if (e is InterruptedException) interrupted = true
            }
        }

        val detail: String? = if (refused) CommitTexts.COPIED_AFTER_REFUSAL else null
        return Attempt(CommitOutcomeResult(CommitOutcome.COPIED, detail), interrupted)
    }
}
