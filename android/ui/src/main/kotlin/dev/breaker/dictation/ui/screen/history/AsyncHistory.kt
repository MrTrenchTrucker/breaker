package dev.breaker.dictation.ui.screen.history

import dev.breaker.dictation.core.port.HistoryStore
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/*
 * The history model, with every store and clipboard call moved off the main thread.
 *
 * The view calls load, tap and close on the main side. The handler is built here and
 * is touched only by the jobs this file launches on the serial queue, so it needs no
 * lock. A result goes back to the main side through postToMain and is drawn there.
 */

/**
 * The history screen's model, run on a serial queue and drawn on the main side.
 *
 * Every store and clipboard call, and every window-end delete, runs as one job on
 * [serial], one job at a time. Four rules hold:
 *
 * - Generation: a read is drawn only if no newer read or tap was made after it, so an
 *   older read that finishes late cannot overwrite a newer screen.
 * - Tap always drawn: a tap's result is drawn while the view is attached, so a one-shot
 *   notice is never lost, even when a read was made after the tap.
 * - Carried notice: a read whose draw is dropped keeps its failure notice, and the next
 *   tap or window-end draw shows that notice after its own. A drawn read clears it.
 * - Close commits once after detach: close stops every later draw and then runs the
 *   pending deletes once, in tap order, on [serial]. A process death in the gap between
 *   detach and those deletes keeps the rows, because the deletes had not run yet.
 *
 * A result parked on a detached view (View.post parks it) and run after the view is
 * attached again is dropped by the epoch, which reopen bumps, so an earlier life of the
 * view never draws into a later one.
 *
 * The handler's waits are called from the serial queue, not from the main side. The
 * view's postDelayed and removeCallbacks are documented as callable from any thread,
 * so the waits are not posted to main. The handler's "main thread only" note therefore
 * means one thread at a time.
 *
 * The main-side state (generation, attached, closed, epoch, carried) is plain: it is read
 * and written only in load, tap, close, reopen and the lambdas posted through postToMain.
 * The window epoch is written and read only inside serial jobs. Results are posted in
 * submit order, because the serial queue runs one job at a time and postToMain is first
 * in, first out.
 */
internal class AsyncHistory(
    history: HistoryStore,
    clipboard: ClipboardSink,
    format: TimestampFormat,
    work: DelayedWork,
    screen: HistoryScreen,
    private val serial: CoroutineDispatcher,
    private val postToMain: (() -> Unit) -> Unit,
    private val draw: (Screen) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + serial)
    private val handler = HistoryIntentHandler(history, clipboard, format, SerialWork(work), screen)
    private var generation = 0
    private var attached = true
    private var closed = false
    private var epoch = 0
    private var carried: Label? = null
    private var windowEpoch = 0

    init {
        handler.onChange = { s ->
            val we = windowEpoch
            postToMain { if (attached && we == epoch) draw(withCarried(s)) }
        }
    }

    /** A read: the first page, drawn unless a newer read or tap was made after it. */
    fun load() {
        if (!attached) return
        val g = ++generation
        val e = epoch
        scope.launch {
            val s = handler.current()
            postToMain {
                if (attached && e == epoch) {
                    if (g == generation) {
                        carried = null
                        draw(s)
                    } else {
                        carried = s.noticeLabel() ?: carried
                    }
                }
            }
        }
    }

    /** A tap: copy, delete, undo or more. Its result is drawn while the view is attached. */
    fun tap(intent: ScreenIntent.History) {
        if (!attached) return
        ++generation
        val e = epoch
        scope.launch {
            val s = handler.handle(intent)
            postToMain { if (attached && e == epoch) draw(withCarried(s)) }
        }
    }

    /** The view left the window: nothing more is drawn, and the pending deletes run once, in order. */
    fun close() {
        if (closed) return
        closed = true
        attached = false
        carried = null
        scope.launch { handler.close() }
    }

    /** Adds the carried notice to [s], once. Call it only inside a draw posted to the main side. */
    private fun withCarried(s: Screen): Screen {
        val c = carried ?: return s
        carried = null // the notice is shown once
        return s.withCarriedNotice(c)
    }

    /** The handler's wait: the real wait runs as given, and its end is handed to the serial queue. */
    private inner class SerialWork(private val real: DelayedWork) : DelayedWork {
        override fun schedule(delayMs: Long, work: () -> Unit): Cancellation =
            real.schedule(delayMs) {
                val e = epoch
                scope.launch {
                    windowEpoch = e
                    work()
                }
            }
    }

    /** The view was attached again; close can then run again. A read made before is dropped. */
    fun reopen() {
        attached = true
        closed = false
        epoch += 1
        carried = null
    }
}
