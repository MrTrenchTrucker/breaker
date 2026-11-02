package dev.breaker.server.syncapi.accounts

import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal val SIGNIN_SWEEP_INTERVAL: Duration = Duration.ofMinutes(5)

/**
 * Deletes dead wrong-password rows: one sweep at boot, then one every [interval],
 * until the coroutine is cancelled. A failure of one sweep is swallowed; the next
 * tick retries. Cancellation is never swallowed. [pause] is the seam for a test: it
 * waits for [interval], and a test passes a recording pause so no test waits five
 * minutes.
 */
internal suspend fun runSigninAttemptSweep(
    store: SigninAttemptStore,
    interval: Duration = SIGNIN_SWEEP_INTERVAL,
    pause: suspend (Duration) -> Unit = { delay(it.toMillis()) },
) {
    while (true) {
        try {
            store.deleteStale()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Nothing is logged: the table holds only counts and timestamps, and the next tick retries.
        }
        pause(interval)
    }
}
