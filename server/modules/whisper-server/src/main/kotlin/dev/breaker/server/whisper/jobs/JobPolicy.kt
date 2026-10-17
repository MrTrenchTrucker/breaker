package dev.breaker.server.whisper.jobs

import java.time.Duration

internal object JobPolicy {
    /** The first call plus three retries. */
    const val MAX_ATTEMPTS: Int = 4

    val RETRY_DELAY: Duration = Duration.ofSeconds(10)

    /** How long a finished result stays fetchable: 24 hours. */
    const val RESULT_TTL_MS: Long = 86_400_000L

    const val ERROR_TEXT_LIMIT: Int = 500

    // The one place that decides when a result is too old. A result finished at f is
    // gone when f <= expiryCutoffMs(now), that is AT f + 24 h exactly. Fetch and purge
    // both call this, so the two can never disagree about the boundary.
    fun expiryCutoffMs(nowMs: Long): Long = nowMs - RESULT_TTL_MS
}
