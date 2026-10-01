package dev.breaker.dictation.core.model

/** Where a transcription stands with the server. */
enum class SyncState { PENDING, SYNCED, FAILED }

/**
 * The result of one push of the pending queue.
 *
 * [pushed] and [failed] are counts, not identities: the queue is retried until
 * it drains, and a retry must never produce a second copy on the server, so the
 * count of what left the phone is what the user is told.
 */
data class SyncReport(val pushed: Int, val failed: Int) {
    init {
        require(pushed >= 0) { "pushed cannot be negative: $pushed" }
        require(failed >= 0) { "failed cannot be negative: $failed" }
    }

    val isClean: Boolean get() = failed == 0
}
