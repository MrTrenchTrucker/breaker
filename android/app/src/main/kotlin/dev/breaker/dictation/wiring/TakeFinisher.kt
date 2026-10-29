package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.CommitOutcome
import dev.breaker.dictation.core.usecase.SendResult

/** How a take that was told to finish came out. */
internal sealed class TakeEnd {
    object Back : TakeEnd()
    /** A blank text: nothing is sent or saved, and the armed tile shows the nothing heard notice. */
    object NothingHeard : TakeEnd()
    class Sent(val outcome: CommitOutcome) : TakeEnd()
    class Failure(val sentence: String) : TakeEnd()
}

/** The background side of a finish: finishes the take and, when there is text, sends it once. */
internal class TakeFinisher(private val take: TakePort) {

    /** Runs on the background: finishes the take and, when there is text, sends it once. */
    fun finishTake(trimBeforeMs: Long? = null): TakeEnd {
        val finished: FinishResult = try {
            take.finish(trimBeforeMs)
        } catch (e: Exception) {
            return dropTake(RunnerSentences.COULD_NOT_FINISH)
        }
        return when (finished) {
            is FinishResult.Failed ->
                if (finished.sentence == EMPTY_TAKE_SENTENCE) TakeEnd.Back else TakeEnd.Failure(finished.sentence)
            is FinishResult.ReadyToSend -> sendText(finished)
        }
    }

    /** Sends the text once; clipboard-only text counts as sent, so a real committer needs its own sentence. */
    private fun sendText(ready: FinishResult.ReadyToSend): TakeEnd {
        if (ready.transcription.text.isBlank()) {
            // Nothing was said: there is nothing to commit and nothing worth keeping in history.
            guarded { take.cancel() }
            return TakeEnd.NothingHeard
        }
        val sent: SendResult? = try {
            take.send()
        } catch (e: Exception) {
            return dropTake(RunnerSentences.COULD_NOT_FINISH)
        }
        return when {
            sent == null -> TakeEnd.Failure(RunnerSentences.COULD_NOT_FINISH)
            sent.outcome.isSuccess -> TakeEnd.Sent(sent.outcome.outcome)
            else -> TakeEnd.Failure(sent.outcome.detail?.takeIf { it.isNotBlank() } ?: RunnerSentences.COULD_NOT_FINISH)
        }
    }

    /** A take that threw is dropped, so the next begin starts clean. */
    private fun dropTake(sentence: String): TakeEnd {
        guarded { take.cancel() }
        return TakeEnd.Failure(sentence)
    }

    private fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            // A take that fails to cancel must not reach the caller.
        }
    }
}
