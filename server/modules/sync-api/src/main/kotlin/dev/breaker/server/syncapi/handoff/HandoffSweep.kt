package dev.breaker.server.syncapi.handoff

import dev.breaker.server.syncapi.reset.ResetStore
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal val HANDOFF_SWEEP_INTERVAL: Duration = Duration.ofSeconds(60)

/**
 * Deletes expired sign-in codes: one sweep at once (the boot sweep), then one every [interval],
 * until the coroutine is cancelled. A failure of one sweep is swallowed and nothing is logged; the
 * next tick tries again. A cancellation is never swallowed. [pause] is the seam for a test: it
 * waits for [interval], and a test passes a recording pause so no test waits a real minute.
 */
internal suspend fun runHandoffSweep(
    store: HandoffStore,
    resetStore: ResetStore? = null,
    interval: Duration = HANDOFF_SWEEP_INTERVAL,
    pause: suspend (Duration) -> Unit = { delay(it.toMillis()) },
) {
    while (true) {
        try {
            store.deleteExpired()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Nothing is logged: the table holds only hashes and sealed bytes, and the next tick retries.
        }
        // The expired reset codes go in the same loop, in their own try, so a failure of the first
        // delete never stops this one.
        try {
            resetStore?.deleteExpired()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Nothing is logged: the table holds only hashes, and the next tick retries.
        }
        pause(interval)
    }
}
