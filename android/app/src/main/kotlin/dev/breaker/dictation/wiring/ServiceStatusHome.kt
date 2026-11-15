package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.port.Clock
import dev.breaker.dictation.ui.HomeControl

/**
 * The service-status line's foreground wiring: the ask, the answer and the Home line.
 *
 * One ask on every resume of the launcher activity, then one every two hours while it stays resumed
 * (the injected [ServiceStatusSchedule]); [onPause] takes the queued ask back. The answer is handed
 * to the Home view the activity is showing, and the view's own [HomeControl.showServiceStatus] turns
 * the reply's names and up/down into the one line - the app never writes the words.
 *
 * Not asked (sync off, signed out, no address configured) is not a failure: there is no line, never
 * the unreachable sentence and never a placeholder.
 */
internal class ServiceStatusHome(
    private val asker: ServiceStatusAsker,
    private val asked: () -> Boolean,
    post: DelayedPost,
    clock: Clock,
) {
    private val schedule = ServiceStatusSchedule(asker, asked, post, clock) { answer -> show(answer) }
    private var home: HomeControl? = null

    /** The launcher came to the front with [control] on screen; null while no home view is up. */
    fun onResume(control: HomeControl?) {
        home = control
        schedule.onResume()
        if (!asked()) showNotAsked()
    }

    /** The launcher left the front. */
    fun onPause() = schedule.onPause()

    private fun show(answer: ServiceStatusAnswer) {
        val control = home ?: return
        when (answer) {
            is ServiceStatusAnswer.Answered -> control.showServiceStatus(
                services = answer.reply.services.map { it.name to it.up },
                failed = false,
                asked = true,
            )
            ServiceStatusAnswer.Failed -> control.showServiceStatus(
                services = null,
                failed = true,
                asked = true,
            )
        }
    }

    /** Nothing was asked, so the line is taken away: null, not an empty string and not a placeholder. */
    private fun showNotAsked() {
        home?.showServiceStatus(services = null, failed = false, asked = false)
    }
}
