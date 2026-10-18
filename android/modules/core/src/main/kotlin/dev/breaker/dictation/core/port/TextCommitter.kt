package dev.breaker.dictation.core.port

import dev.breaker.dictation.core.model.CommitRequest

/**
 * Puts text where the user is typing.
 *
 * Implemented by the commit module: into the focused field when a text-insert
 * mechanism has published one, into the clipboard when there is not.
 *
 * This is *text commit*. It fires on the user's explicit send and only ever
 * types into the field the user focused — it never reads or writes another
 * app's data.
 */
interface TextCommitter {
    /** Commit [request] and report what happened. */
    fun commit(request: CommitRequest): CommitOutcomeResult
}
