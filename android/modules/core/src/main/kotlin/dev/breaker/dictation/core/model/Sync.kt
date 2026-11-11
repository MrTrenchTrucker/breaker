package dev.breaker.dictation.core.model

/**
 * The result of one push of the pending queue.
 *
 * [pushed] and [failed] are counts, not identities: the queue is retried until
 * it drains, and a retry must never produce a second copy on the server, so the
 * count of what left the phone is what the user is told.
 */
data class SyncReport(
    val pushed: Int,
    val failed: Int,
    val parked: Int = 0,
    /** The Retry-After seconds of a 429 that stopped the drain (1..3600), or null when there was none. */
    val retryAfterSeconds: Int? = null,
) {
    init {
        require(pushed >= 0) { "pushed cannot be negative: $pushed" }
        require(failed >= 0) { "failed cannot be negative: $failed" }
        require(parked >= 0) { "parked cannot be negative: $parked" }
    }
}
