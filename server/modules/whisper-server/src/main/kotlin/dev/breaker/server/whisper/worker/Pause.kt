package dev.breaker.server.whisper.worker

import java.time.Duration
import kotlinx.coroutines.delay

/**
 * The only place the worker waits between attempts. It is a seam so that a caller can
 * make the wait instant and record it instead of sleeping.
 */
fun interface Pause {
    suspend fun pause(duration: Duration)

    companion object {
        val REAL: Pause = Pause { duration -> delay(duration.toMillis()) }
    }
}
