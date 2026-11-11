package dev.breaker.server.syncapi.accounts

import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal val RATE_LIMIT_SWEEP_INTERVAL: Duration = Duration.ofMinutes(1)

/**
 * Deletes counter rows whose window has expired: one sweep at boot, then one every
 * [interval], until the coroutine is cancelled. A failure of one sweep is swallowed;
 * the next tick retries. Cancellation is never swallowed. [pause] is the seam for a
 * test: it waits for [interval], and a test passes a recording pause so no test waits
 * a minute.
 *
 * Counters older than one fixed 60 s window are dead weight; dropping them keeps the
 * table bounded. A row whose window is still live is never deleted.
 */
internal suspend fun runRateLimitCounterSweep(
    counterStore: RateLimitCounterStore,
    interval: Duration = RATE_LIMIT_SWEEP_INTERVAL,
    pause: suspend (Duration) -> Unit = { delay(it.toMillis()) },
) {
    while (true) {
        try {
            counterStore.deleteStale()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Nothing is logged: the table holds only counts and timestamps, and the next tick retries.
        }
        pause(interval)
    }
}
