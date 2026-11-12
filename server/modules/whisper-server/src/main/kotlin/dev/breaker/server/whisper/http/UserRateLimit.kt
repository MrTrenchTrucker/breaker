package dev.breaker.server.whisper.http

import java.time.Clock
import java.util.concurrent.atomic.AtomicReference

/** The outcome of a single [`allow`] check. */
internal sealed class UserLimitResult {
    /** The request is allowed: it is counted in the account's window. */
    object Allowed : UserLimitResult()
    /** The request is refused: the account is over its cap in the current window. */
    data class Refused(val retryAfterSeconds: Int) : UserLimitResult()
}

/**
 * In-memory per-account fixed-window rate limiter, off unless the admin sets the cap.
 *
 * Thread-safe: all state moves through a single AtomicReference compare-and-set loop
 * (monitor locks are banned by [SourceScanTest]). Applies to every live account (session
 * and agent alike). One counter per account id, counted by the upload route only (POST /v1/audio/transcriptions).
 *
 * Fixed 60 000 ms window starting at the first counted request of the key. Within the
 * window up to [cap] requests are allowed; the next is refused. A refused request is not
 * added to the count and does not move the window start. Once 60 000 ms have passed since
 * the window start, the next request opens a new window with count 1. A request exactly at
 * windowStart + 60 000 ms opens the new window.
 *
 * Windows older than a minute are dropped so the map cannot grow with traffic.
 *
 * The clock is injected: tests pass their own fake Clock and never sleep.
 */
internal class UserRateLimit(
    private val cap: Int,
    private val clock: Clock,
) {
    private data class WindowState(val windowStartMs: Long, val count: Int)
    private val state: AtomicReference<Map<Long, WindowState>> = AtomicReference(emptyMap())

    private companion object {
        private const val WINDOW_MS: Long = 60_000
    }

    fun allow(accountId: Long): UserLimitResult {
        while (true) {
            val nowMs = clock.millis()
            val current = state.get()
            // Drop windows older than a minute so the map cannot grow with traffic.
            val pruned = current.filter { (_, ws) -> nowMs - ws.windowStartMs < WINDOW_MS }
            val entry = pruned[accountId]
            val updated: Map<Long, WindowState>
            val result: UserLimitResult
            when {
                entry == null -> {
                    // First counted request of this account opens a new window at nowMs.
                    updated = pruned + (accountId to WindowState(nowMs, 1))
                    result = UserLimitResult.Allowed
                }
                entry.count < cap -> {
                    updated = pruned + (accountId to WindowState(entry.windowStartMs, entry.count + 1))
                    result = UserLimitResult.Allowed
                }
                else -> {
                    // Over the cap: refuse. Do not add to the count, do not move the window.
                    updated = pruned
                    val timeLeftMs = WINDOW_MS - (nowMs - entry.windowStartMs)
                    // Ceil division to whole seconds. timeLeftMs is positive (within-window), so the
                    // ceiling stays <= 60, which fits comfortably in an Int for retryAfterSeconds.
                    val retryAfter = ((timeLeftMs + 999) / 1000).coerceAtLeast(1)
                    result = UserLimitResult.Refused(retryAfter.toInt())
                }
            }
            if (state.compareAndSet(current, updated)) return result
            // Another thread moved first: retry with the latest state.
        }
    }

    /** Number of live windows tracked: exposed so a test can assert the map does not grow. */
    fun size(): Int = state.get().size
}
