package dev.breaker.dictation.sync

/**
 * One pull cycle, exposed as a public, never-throwing seam: the app runs it after each push
 * drain, and nothing the pull does can crash the drain or change its report.
 *
 * QueuedSyncService builds the trigger: it owns the cycle (transport + its own database as the
 * cursor store and the queue probe + the app's PullSink) and exposes `pullTrigger`. The trigger's
 * run() calls the cycle and swallows every exception, so a failing pull leaves the stored cursor
 * untouched and the next drain retries.
 */
public fun interface PullTrigger {
    /** Runs one pull cycle. Never throws. */
    fun run()
}
