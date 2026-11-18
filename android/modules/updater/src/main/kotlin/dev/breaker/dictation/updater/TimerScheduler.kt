package dev.breaker.dictation.updater

/**
 * Runs one task after a wait, on a background thread that is not the UI thread.
 *
 * [cancel] drops a task that is still waiting. A task that has already started is not stopped.
 */
public interface TimerScheduler {
    /** Runs [task] once, after [afterMs] milliseconds, on a background thread. */
    fun scheduleOnce(afterMs: Long, task: Runnable)

    /** Drops the task that is still waiting, if there is one. */
    fun cancel()
}
