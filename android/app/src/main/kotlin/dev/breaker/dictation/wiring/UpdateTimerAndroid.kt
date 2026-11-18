package dev.breaker.dictation.wiring

import android.os.Handler
import android.os.HandlerThread
import dev.breaker.dictation.updater.TimerScheduler

/**
 * Runs one task after a delay on one background thread of its own, and drops the tasks still waiting on cancel.
 *
 * The thread is created on first use and named for the update timer. A negative delay counts as zero.
 * Nothing here throws: a task that cannot be posted is dropped, and a cancel that fails changes nothing.
 *
 * It is the updater's [TimerScheduler] port, given to the updater when the app is wired to it.
 */
class HandlerTimerScheduler : TimerScheduler {
    private val lock: Any = Any()
    private var handler: Handler? = null

    /** Runs [task] once after [afterMs] milliseconds on the timer thread. Never throws. */
    override fun scheduleOnce(afterMs: Long, task: Runnable) {
        try {
            handlerOrCreate().postDelayed(task, afterMs.coerceAtLeast(0L))
        } catch (e: Exception) {
            // best effort: a task that cannot be posted is dropped
        }
    }

    /** Drops every task still waiting on the timer thread. Never throws. */
    override fun cancel() {
        try {
            synchronized(lock) { handler?.removeCallbacksAndMessages(null) }
        } catch (e: Exception) {
            // best effort: nothing waits when the timer thread never started
        }
    }

    private fun handlerOrCreate(): Handler =
        synchronized(lock) {
            handler ?: startHandler().also { handler = it }
        }

    private fun startHandler(): Handler {
        val thread = HandlerThread(THREAD_NAME)
        thread.start()
        return Handler(thread.looper)
    }

    private companion object {
        const val THREAD_NAME: String = "update-timer"
    }
}
