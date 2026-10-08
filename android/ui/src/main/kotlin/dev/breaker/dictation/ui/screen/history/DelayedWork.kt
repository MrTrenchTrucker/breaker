package dev.breaker.dictation.ui.screen.history

/*
 * Work that runs later, and the means to take it back.
 *
 * The history model asks for its Undo window through this seam and never reads a
 * clock or starts a thread itself. The view supplies the real scheduler, and a
 * test supplies one that holds the work until the test runs it.
 */

/** Runs [work] after [delayMs] milliseconds, unless the returned [Cancellation] is used first. */
internal interface DelayedWork {
    fun schedule(delayMs: Long, work: () -> Unit): Cancellation
}

/** Takes back work that was scheduled and has not run yet. */
internal fun interface Cancellation {
    fun cancel()
}
