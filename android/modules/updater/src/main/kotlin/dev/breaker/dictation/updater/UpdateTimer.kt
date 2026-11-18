package dev.breaker.dictation.updater

import dev.breaker.dictation.core.model.UpdateCheckResult

/** The waits between automatic update checks, in milliseconds. */
internal object UpdateTimerDelays {
    /** The wait after a check that answered up to date or available: one day. */
    const val DAILY_MS: Long = 86_400_000L

    /** The wait after a failed check, a check that was not due, or a check that threw: one hour. */
    const val RETRY_MS: Long = 3_600_000L
}

/**
 * Runs the automatic update check on its own schedule.
 *
 * [start] marks the timer running, drops any earlier wait, runs one check on the calling thread and
 * then waits for the next one. The length of that wait depends on the answer: a day after an up to
 * date or an available answer, an hour after a failed answer, an hour after a null answer (the daily
 * check found it not due) and an hour after a check that threw.
 *
 * [stop] marks the timer not running and drops the waiting task. A task that fires after [stop] runs
 * no check and schedules nothing, and a stop that lands while a check is running also schedules nothing.
 *
 * Nothing here throws: an exception from [runCheck] or from [scheduler] is swallowed, and the timer
 * keeps going with the wait that the answer gives.
 */
internal class UpdateTimer(
    private val runCheck: () -> UpdateCheckResult?,
    private val scheduler: TimerScheduler,
) {
    private val lock = Any()
    private var running = false
    private var generation = 0L

    /** Marks the timer running, drops any earlier wait, runs one check now and schedules the next. */
    fun start() {
        val token = synchronized(lock) {
            running = true
            generation += 1
            cancelQuietly()
            generation
        }
        cycle(token)
    }

    /** Marks the timer not running and drops the waiting task. */
    fun stop() {
        synchronized(lock) {
            running = false
            generation += 1
            cancelQuietly()
        }
    }

    /** One check and the wait after it, for the run that [token] belongs to. */
    private fun cycle(token: Long) {
        if (!isCurrent(token)) return
        val delayMs = delayAfterCheck()
        synchronized(lock) {
            if (isCurrentLocked(token)) scheduleQuietly(delayMs, Runnable { cycle(token) })
        }
    }

    /** Runs the check and answers the wait that follows it. */
    private fun delayAfterCheck(): Long {
        val answer = try {
            runCheck()
        } catch (_: Exception) {
            return UpdateTimerDelays.RETRY_MS
        }
        return when (answer) {
            is UpdateCheckResult.UpToDate -> UpdateTimerDelays.DAILY_MS
            is UpdateCheckResult.Available -> UpdateTimerDelays.DAILY_MS
            else -> UpdateTimerDelays.RETRY_MS
        }
    }

    private fun isCurrent(token: Long): Boolean = synchronized(lock) { isCurrentLocked(token) }

    private fun isCurrentLocked(token: Long): Boolean = running && token == generation

    private fun scheduleQuietly(delayMs: Long, task: Runnable) {
        try {
            scheduler.scheduleOnce(delayMs, task)
        } catch (_: Exception) {
            // best effort: a scheduler that refuses the wait leaves none pending
        }
    }

    private fun cancelQuietly() {
        try {
            scheduler.cancel()
        } catch (_: Exception) {
            // best effort: the stop still marks the timer not running
        }
    }
}
