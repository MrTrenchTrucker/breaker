package dev.breaker.dictation.ui.screen.firstrun

import dev.breaker.dictation.ui.screen.history.Cancellation
import dev.breaker.dictation.ui.screen.history.DelayedWork

/*
 * The welcome page: how long it stays, what it shows and when it hands over.
 *
 * The page shows its content for a fixed time and then hands over. A tap hands over at
 * once. Whichever comes first, the hand-over happens exactly once. The content is one
 * swappable piece, so a later change replaces the picture without touching the timing.
 */

/** How long the welcome page stays before it hands over on its own. */
internal const val WELCOME_MS: Long = 3000L

/** The swappable content of the welcome page: the picture it shows for a theme. */
internal interface WelcomeContent {
    /** The resource name of the picture for the given theme. */
    fun pictureName(dark: Boolean): String
}

/** The welcome content for now: the logo of the theme. */
internal object LogoWelcome : WelcomeContent {
    override fun pictureName(dark: Boolean): String = FirstRunPictures.logoName(dark)
}

/**
 * The welcome timer: one wait of [WELCOME_MS], a tap that ends it at once, and one hand-over.
 *
 * [work] runs the wait. [onDone] is called once, whether the wait ended on its own or a tap
 * ended it, and never after that.
 */
internal class WelcomeTimer(private val work: DelayedWork, private val onDone: () -> Unit) {
    /** Whether the hand-over has happened, by a tap or by the wait. */
    var finished: Boolean = false
        private set

    /** The wait that is running, or null when none is. */
    private var waiting: Cancellation? = null

    /** Changes whenever a wait starts or ends, so a wait that was ended cannot hand over. */
    private var generation: Int = 0

    /** Schedules one wait of [WELCOME_MS]; does nothing when a wait is running or the hand-over has happened. */
    fun start() {
        if (finished || waiting != null) return
        val mine = ++generation
        waiting = work.schedule(WELCOME_MS) { timedOut(mine) }
    }

    /** Ends the welcome at once: cancels the wait and hands over, once. */
    fun tap() {
        endWait()
        handOver()
    }

    /** Cancels the wait without handing over; a later start may schedule again unless the hand-over happened. */
    fun cancel() {
        endWait()
    }

    private fun timedOut(token: Int) {
        if (token != generation) return
        waiting = null
        handOver()
    }

    private fun endWait() {
        waiting?.cancel()
        waiting = null
        generation++
    }

    private fun handOver() {
        if (finished) return
        finished = true
        onDone()
    }
}
