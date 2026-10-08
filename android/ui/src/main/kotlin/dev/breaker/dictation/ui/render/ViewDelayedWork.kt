package dev.breaker.dictation.ui.render

import android.view.View
import dev.breaker.dictation.ui.screen.history.Cancellation
import dev.breaker.dictation.ui.screen.history.DelayedWork

/*
 * The one place in this module where work is put off.
 *
 * The history screen's undo window is a wait, and the queue this module uses for it
 * is the one the view already owns. The queue delivers the work on the main thread,
 * and so does the cancel, so nothing here needs a lock.
 */

/**
 * Runs work after a delay, on the main thread, through [view]'s own queue.
 *
 * The work is posted as one runnable, and the cancellation removes exactly that
 * runnable from the queue. Cancelling after the runnable has run does nothing, and
 * cancelling twice does nothing either.
 *
 * @param view the view whose queue runs the work.
 */
internal class ViewDelayedWork(private val view: View) : DelayedWork {
    override fun schedule(delayMs: Long, work: () -> Unit): Cancellation {
        val task = Runnable { work() }
        view.postDelayed(task, delayMs)
        return Cancellation { view.removeCallbacks(task) }
    }
}
