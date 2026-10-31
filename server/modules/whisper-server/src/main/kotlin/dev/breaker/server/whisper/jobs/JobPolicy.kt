package dev.breaker.server.whisper.jobs

import java.time.Duration

internal object JobPolicy {
    /** The first call plus three retries. */
    const val MAX_ATTEMPTS: Int = 4

    val RETRY_DELAY: Duration = Duration.ofSeconds(10)

    /** How long a finished result stays fetchable: 24 hours. */
    const val RESULT_TTL_MS: Long = 86_400_000L

    /** How long a finished result stays idempotently fetchable after completion: 10 minutes by
     *  default (BREAKER_WHISPER_RESULT_TTL_S). Every GET inside this window returns the same
     *  result; it is separate from the 24h [RESULT_TTL_MS] purge, which stays untouched. */
    const val RESULT_WINDOW_MS: Long = 600_000L

    const val ERROR_TEXT_LIMIT: Int = 500

    // The one place that decides when a result is too old. A result finished at f is
    // gone when f <= expiryCutoffMs(now), that is AT f + 24 h exactly. Fetch and purge
    // both call this, so the two can never disagree about the boundary.
    fun expiryCutoffMs(nowMs: Long): Long = nowMs - RESULT_TTL_MS

    // The fetch window's boundary, separate from the 24h one above: a result finished at f is past
    // its window when f <= resultWindowCutoffMs(now, ttl), that is AT f + ttl exactly. Fetch's lazy
    // clear and the TTL purge both call this with the store's configured ttl, so neither disagrees.
    fun resultWindowCutoffMs(nowMs: Long, ttlMs: Long): Long = nowMs - ttlMs
}
