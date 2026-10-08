package dev.breaker.dictation.ui.screen.history

import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.port.HistoryStore
import dev.breaker.dictation.ui.screen.HistoryActions
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent

/*
 * The history screen's memory, and the one place its taps are acted on.
 *
 * The rows held are the last page read from the store. A Delete hides its row at
 * once and starts an Undo window; nothing is removed until the window ends, so an
 * Undo inside it leaves the store as it was. Every store and clipboard call is
 * guarded: an exception is read as a failure and is never shown or passed on.
 *
 * Call it from the main thread only; it does no locking.
 */

/**
 * Acts on the intents the history screen reports and says what to draw next.
 *
 * @param history the phone's transcription store, read and deleted through its port only.
 * @param clipboard the phone's clipboard, for Copy.
 * @param format the time text each row shows.
 * @param work the scheduler for the Undo windows.
 * @param screen the pure screen.
 */
internal class HistoryIntentHandler(
    private val history: HistoryStore,
    private val clipboard: ClipboardSink,
    private val format: TimestampFormat,
    private val work: DelayedWork,
    private val screen: HistoryScreen,
) {
    private var held: List<Transcription> = emptyList()
    private var fullPage: Boolean = false
    private var listFailed: Boolean = false
    private var shown: Int = PAGE_SIZE
    private var notice: HistoryNotice? = null
    private val pending = LinkedHashMap<String, Cancellation>()

    /**
     * Draws the screen again when a window ends on its own, after its row has been
     * deleted or put back. The view sets it; nothing is drawn while it is not set.
     */
    var onChange: (Screen) -> Unit = {}

    /** The first page, read from the store, as the screen stands now. */
    fun current(): Screen {
        reload(shown)
        return draw()
    }

    /**
     * Acts on [intent] and returns the screen as it stands afterwards.
     *
     * The `when` names every intent the sealed hierarchy has, so an intent added
     * later is a compile error here. Any intent this screen does not own is drawn
     * again from what is held, without a call to the store, the clipboard or the
     * scheduler.
     */
    fun handle(intent: ScreenIntent): Screen = when (intent) {
        is ScreenIntent.History -> act(intent)
        ScreenIntent.ToggleTheme,
        ScreenIntent.UseSystemTheme,
        is ScreenIntent.SetRoutingMode,
        is ScreenIntent.SetSetting,
        is ScreenIntent.Setup,
        -> draw()
    }

    /**
     * Closing the screen deletes every row still waiting out its Undo window, once each. A row
     * whose delete worked is also left out of the rows held; one the store refused stays in them.
     */
    fun close() {
        for (id in pending.keys.toList()) {
            pending.remove(id)?.cancel()
            if (guarded { history.delete(id) }) {
                held = held.filterNot { it.id == id }
            }
        }
    }

    private fun act(intent: ScreenIntent.History): Screen = when (intent.action) {
        HistoryActions.COPY -> copy(intent.id)
        HistoryActions.DELETE -> delete(intent.id)
        HistoryActions.UNDO -> undo(intent.id)
        HistoryActions.MORE -> more()
        else -> draw()
    }

    private fun copy(id: String): Screen {
        val row = held.firstOrNull { it.id == id && it.id !in pending } ?: return draw()
        notice = if (guarded { clipboard.copy(row.text) }) HistoryNotice.COPIED else HistoryNotice.COPY_FAILED
        return draw()
    }

    /** Hides the row and opens its window. The store is not touched until the window ends. */
    private fun delete(id: String): Screen {
        if (id in pending || held.none { it.id == id }) return draw()
        pending[id] = work.schedule(UNDO_WINDOW_MS) { windowEnded(id) }
        return draw()
    }

    private fun undo(id: String): Screen {
        val cancellation = pending.remove(id) ?: return draw()
        cancellation.cancel()
        return draw()
    }

    private fun more(): Screen {
        if (shown < ROW_CAP) reload(minOf(shown + PAGE_SIZE, ROW_CAP))
        return draw()
    }

    /**
     * The window for [id] ended: the row is deleted and left out of the rows held, so a re-read
     * that fails cannot bring it back, or put back with a notice if the store refused.
     */
    private fun windowEnded(id: String) {
        if (pending.remove(id) == null) return
        if (guarded { history.delete(id) }) {
            held = held.filterNot { it.id == id }
        } else {
            notice = HistoryNotice.DELETE_FAILED
        }
        reload(shown)
        onChange(draw())
    }

    /**
     * Reads the first [limit] rows. A read that fails keeps the rows already held and sets the
     * failure notice; a read that works clears the failure.
     */
    private fun reload(limit: Int) {
        val page = try {
            history.list(limit)
        } catch (failure: Exception) {
            listFailed = true
            notice = HistoryNotice.LIST_FAILED
            return
        }
        held = page
        fullPage = page.size >= limit
        shown = limit
        listFailed = false
    }

    /** Runs one store or clipboard call. Any exception is a failure and goes no further. */
    private fun guarded(call: () -> Boolean): Boolean = try {
        call()
    } catch (failure: Exception) {
        false
    }

    /** The screen as the rows held and the open windows say it is now. A notice is handed out once. */
    private fun draw(): Screen {
        val state = HistoryState(
            rows = held.map { HistoryRow(it.id, format.format(it.createdAt), it.source, it.text) },
            pending = pending.keys.toSet(),
            shown = shown,
            fullPage = fullPage,
            listFailed = listFailed,
        )
        val drawn = notice
        notice = null
        return screen.render(state, drawn)
    }
}
