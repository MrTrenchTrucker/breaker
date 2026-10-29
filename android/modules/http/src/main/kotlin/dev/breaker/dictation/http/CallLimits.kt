package dev.breaker.dictation.http

/**
 * The time and size limits of one call. The caller names every value; no value here has a default.
 *
 * Each timeout is from 1 to [MAX_TIMEOUT_MS] milliseconds. The reply cap is from 1 to
 * [MAX_STREAM_REPLY] bytes; the send call takes a smaller cap, up to [MAX_BYTES_REPLY].
 * A value out of range is refused with an IllegalArgumentException whose message holds no value.
 */
class CallLimits(
    val connectTimeoutMs: Int,
    val readTimeoutMs: Int,
    val maxReplyBytes: Long,
) {
    init {
        require(connectTimeoutMs in 1..MAX_TIMEOUT_MS) { OUT_OF_RANGE_TIMEOUT }
        require(readTimeoutMs in 1..MAX_TIMEOUT_MS) { OUT_OF_RANGE_TIMEOUT }
        require(maxReplyBytes in 1L..MAX_STREAM_REPLY) { OUT_OF_RANGE_CAP }
    }

    companion object {
        /** The largest connect or read timeout, in milliseconds. */
        const val MAX_TIMEOUT_MS: Int = 600_000

        /** The largest reply cap the send call accepts, in bytes. */
        const val MAX_BYTES_REPLY: Long = 16_777_216L

        /** The largest reply cap the open call accepts, in bytes. */
        const val MAX_STREAM_REPLY: Long = 1_073_741_824L

        private const val OUT_OF_RANGE_TIMEOUT = "a timeout is outside its range"
        private const val OUT_OF_RANGE_CAP = "a reply cap is outside its range"
    }
}
