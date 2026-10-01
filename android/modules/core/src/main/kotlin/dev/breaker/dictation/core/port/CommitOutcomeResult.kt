package dev.breaker.dictation.core.port

import dev.breaker.dictation.core.model.CommitOutcome

/**
 * The commit port's return value: the outcome plus a short reason when it did
 * not work.
 *
 * The reason is for the user ("no text field is focused"). It never carries the
 * text itself.
 */
data class CommitOutcomeResult(val outcome: CommitOutcome, val detail: String? = null) {
    val isSuccess: Boolean get() = outcome != CommitOutcome.FAILED
}
