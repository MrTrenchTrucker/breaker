package dev.breaker.dictation.ui.render

import android.content.Context
import android.widget.ScrollView
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.history.Cancellation
import dev.breaker.dictation.ui.screen.history.DelayedWork
import dev.breaker.dictation.ui.screen.history.HistoryIntentHandler
import dev.breaker.dictation.ui.theme.PaletteSlot
import dev.breaker.dictation.ui.theme.Theme

/*
 * The view the app puts on the display for the history screen.
 *
 * It does the job SetupHostView does for the setup steps, with two differences. A
 * row can be deleted, and a deletion waits for a short window before it happens, so
 * the screen changes when that wait ends and nothing else asks for it. And the
 * screen is closed when the view leaves the window, which ends every wait still open:
 * the handler then deletes what is pending, at once.
 */

/** The id of the notice node, as the history screen names it. */
private const val NOTICE_ID = "history.notice"

/**
 * A scrolling container that shows the history screen and keeps it up to date.
 *
 * The rows are drawn by [renderer] and what they mean is decided by [handler]. A tap
 * goes to [HistoryIntentHandler.handle] and the result is drawn. The current screen
 * is drawn when the view joins a window and each time the window gains focus.
 *
 * Leaving the window calls [HistoryIntentHandler.close] once, which commits every
 * deletion still waiting. A wait is scheduled through the [scheduler] of this view,
 * and when a wait ends the screen is drawn again.
 *
 * Everything here runs on the main thread: taps, attachment, focus and the work a
 * wait runs. There is no lock.
 *
 * @param context the context the views are built with, normally an activity's.
 * @param handler turns intents into the screen to draw next.
 * @param theme the colours and sizes the screen is drawn in.
 * @param renderer draws a described screen into views.
 */
internal class HistoryHostView(
    context: Context,
    private val handler: HistoryIntentHandler,
    private val theme: Theme,
    private val renderer: ScreenRenderer,
) : ScrollView(context) {
    init {
        // A screen is taller than a short window in landscape, and without this
        // the colour of the child would stop short of the visible area.
        isFillViewport = true
        handler.onChange = ::show
        show(handler.current())
    }

    /**
     * Replaces the rows with [screen], keeping the position on the screen.
     *
     * The position is read before the child goes away and asked for again afterwards.
     * A screen that holds the notice is drawn from the top, so the notice is in sight.
     */
    fun show(screen: Screen) {
        val held = if (screen.nodes.any { it.id == NOTICE_ID }) 0 else scrollY
        removeAllViews()
        addView(renderer.render(screen, theme, ::onIntent))
        setBackgroundColor(theme.color(PaletteSlot.BACKGROUND).argb)
        scrollTo(0, held)
    }

    /**
     * The scheduler the handler uses for the undo window.
     *
     * A wait runs on this view's own queue. The handler's work draws the screen itself,
     * through its change hook, so the row is gone or back at once.
     */
    fun scheduler(): DelayedWork {
        val posted = ViewDelayedWork(this)
        return object : DelayedWork {
            override fun schedule(delayMs: Long, work: () -> Unit): Cancellation =
                posted.schedule(delayMs, work)
        }
    }

    /** Acts on [intent] and draws the result. */
    private fun onIntent(intent: ScreenIntent) {
        show(handler.handle(intent))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        show(handler.current())
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) {
            show(handler.current())
        }
    }

    override fun onDetachedFromWindow() {
        handler.close()
        super.onDetachedFromWindow()
    }
}

/**
 * A scheduler that is bound once the view it posts on exists.
 *
 * The handler needs its scheduler when it is built, and the view needs the handler,
 * so the entry builds the handler on this, builds the view, and binds this to the
 * view's scheduler before anything can be tapped. A wait asked for before the binding
 * is not scheduled, so nothing is deleted for it.
 */
internal class LateDelayedWork : DelayedWork {
    private var target: DelayedWork? = null

    /** Sends every wait from now on to [scheduler]. */
    fun bind(scheduler: DelayedWork) {
        target = scheduler
    }

    override fun schedule(delayMs: Long, work: () -> Unit): Cancellation =
        target?.schedule(delayMs, work) ?: Cancellation { }
}
