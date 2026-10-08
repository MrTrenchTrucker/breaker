package dev.breaker.dictation.ui.render

import android.content.Context
import android.widget.ScrollView
import dev.breaker.dictation.core.port.HistoryStore
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.history.AsyncHistory
import dev.breaker.dictation.ui.screen.history.Cancellation
import dev.breaker.dictation.ui.screen.history.ClipboardSink
import dev.breaker.dictation.ui.screen.history.DelayedWork
import dev.breaker.dictation.ui.screen.history.HistoryScreen
import dev.breaker.dictation.ui.screen.history.TimestampFormat
import dev.breaker.dictation.ui.theme.PaletteSlot
import dev.breaker.dictation.ui.theme.Theme
import kotlinx.coroutines.CoroutineDispatcher

/*
 * The view the app puts on the display for the history screen.
 *
 * It does the job SetupHostView does for the setup steps, with two differences. A
 * row can be deleted, and a deletion waits for a short window before it happens, so
 * the screen changes when that wait ends and nothing else asks for it. And the
 * screen is closed when the view leaves the window, which ends every wait still open:
 * AsyncHistory then carries out the pending deletes once, after the view is gone, on
 * the serial dispatcher.
 */

/** The id of the notice node, as the history screen names it. */
private const val NOTICE_ID = "history.notice"

/**
 * A scrolling container that shows the history screen and keeps it up to date.
 *
 * The rows are drawn by [renderer]. What they mean is decided by [AsyncHistory], which
 * runs every store and clipboard call on [serial] and hands each drawn screen back to
 * [show] on the main thread. A tap goes to [AsyncHistory.tap] and its result is drawn
 * when it is ready. The current screen is read when the view joins a window and each
 * time the window gains focus.
 *
 * Leaving the window calls [AsyncHistory.close] once, which hands every deletion still
 * waiting to the serial queue. A wait is scheduled through the [scheduler] of this view,
 * and when a wait ends the screen is drawn again.
 *
 * Taps, attachment and focus are handled on the main thread. The store reads and the
 * deletes run on [serial], one job at a time, and a wait runs on this view's own queue.
 *
 * @param context the context the views are built with, normally an activity's.
 * @param history the store every read and delete goes through.
 * @param clipboard the clipboard that Copy writes to.
 * @param format the time text each row shows.
 * @param work the scheduler for the undo windows, bound once the view exists.
 * @param serial the one queue the model runs its store and clipboard work on.
 * @param theme the colours and sizes the screen is drawn in.
 * @param renderer draws a described screen into views.
 */
internal class HistoryHostView(
    context: Context,
    history: HistoryStore,
    clipboard: ClipboardSink,
    format: TimestampFormat,
    work: DelayedWork,
    serial: CoroutineDispatcher,
    private val theme: Theme,
    private val renderer: ScreenRenderer,
) : ScrollView(context) {
    private val asyncHistory = AsyncHistory(
        history,
        clipboard,
        format,
        work,
        HistoryScreen(),
        serial,
        postToMain = { task -> post(Runnable { task() }) },
        draw = ::show,
    )

    init {
        // A screen is taller than a short window in landscape, and without this
        // the colour of the child would stop short of the visible area.
        isFillViewport = true
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
     * The scheduler the model uses for the undo window.
     *
     * A wait runs on this view's own queue. When it ends, the model's work runs its store
     * call on the serial queue and then draws the screen, so the row is gone or back at once.
     */
    fun scheduler(): DelayedWork {
        val posted = ViewDelayedWork(this)
        return object : DelayedWork {
            override fun schedule(delayMs: Long, work: () -> Unit): Cancellation =
                posted.schedule(delayMs, work)
        }
    }

    /** Hands a tap on this screen to the model, which draws the result. */
    private fun onIntent(intent: ScreenIntent) {
        if (intent is ScreenIntent.History) asyncHistory.tap(intent)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        asyncHistory.reopen()
        asyncHistory.load()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) {
            asyncHistory.load()
        }
    }

    override fun onDetachedFromWindow() {
        asyncHistory.close()
        super.onDetachedFromWindow()
    }
}

/**
 * A scheduler that is bound once the view it posts on exists.
 *
 * The model needs its scheduler when it is built, and the view needs the model, so the
 * entry builds this, builds the view on it, and binds this to the view's scheduler before
 * anything can be tapped. A wait asked for before the binding is not scheduled, so nothing
 * is deleted for it.
 */
internal class LateDelayedWork : DelayedWork {
    @Volatile
    private var target: DelayedWork? = null

    /** Sends every wait from now on to [scheduler]. */
    fun bind(scheduler: DelayedWork) {
        target = scheduler
    }

    override fun schedule(delayMs: Long, work: () -> Unit): Cancellation =
        target?.schedule(delayMs, work) ?: Cancellation { }
}
