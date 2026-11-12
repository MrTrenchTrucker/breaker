package dev.breaker.dictation.ui.screen.history

import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.port.HistoryStore
import dev.breaker.dictation.core.port.OlderHistory
import dev.breaker.dictation.core.port.OlderResult
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
 * Server rows (Load more phase two) are held in a ServerRows delegate: visit-only,
 * never stored, gone when the view closes. The handler delegates the server phase
 * to it and only adds the MORE routing and the lookups that search both lists.
 *
 * Call it from one thread at a time; it does no locking. AsyncHistory runs it on its
 * serial dispatcher.
 */

/**
 * Acts on the intents the history screen reports and says what to draw next.
 *
 * @param history the phone's transcription store, read and deleted through its port only.
 * @param clipboard the phone's clipboard, for Copy.
 * @param format the time text each row shows.
 * @param work the scheduler for the Undo windows.
 * @param screen the pure screen.
 * @param older the older-rows read port, or null when Load more stays one-phase.
 */
internal class HistoryIntentHandler(
    private val history: HistoryStore,
    private val clipboard: ClipboardSink,
    private val format: TimestampFormat,
    private val work: DelayedWork,
    private val screen: HistoryScreen,
    private val older: OlderHistory? = null,
) {
    private var held: List<Transcription> = emptyList()
    private var fullPage: Boolean = false
    private var listFailed: Boolean = false
    private var shown: Int = PAGE_SIZE
    private var notice: HistoryNotice? = null
    private val pending = LinkedHashMap<String, Cancellation>()
    private val opened = LinkedHashSet<String>()
    /** The server rows of this visit; null when the older-rows port is not offered. */
    internal val serverRows: ServerRows? = older?.let { ServerRows(initialOfferAllowed = it.available()) }

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
     * Reads the page older than the oldest row on screen from the server. The first call
     * sends the createdAt of the last held row; later calls send the previous Page.next
     * unchanged. Nothing is stored.
     */
    fun readOlder() {
        val rows = serverRows ?: return
        if (!rows.mayRead()) return
        val bound = rows.firstBound(held)
        if (bound == null) {
            rows.readDone()
            return
        }
        val result = try {
            older?.older(bound)
        } catch (failure: Exception) {
            OlderResult.Unavailable
        }
        if (result == null) rows.readDone() else serverReadDone(result)
    }

    /** Handles one older-rows answer: applies the page or sets the notice, never stores. The caller draws. */
    private fun serverReadDone(result: OlderResult) {
        val rows = serverRows ?: return
        val shownNotice = rows.noticeFor(result)
        when (result) {
            is OlderResult.Page -> rows.applyPage(result, held.map { it.id }.toSet())
            OlderResult.NoneOlder -> rows.noMore()
            is OlderResult.Busy -> { /* notice only; no retry loop */ }
            else -> { /* failure kinds: notice only */ }
        }
        rows.readDone()
        notice = shownNotice
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
        is ScreenIntent.SetThemeMode,
        is ScreenIntent.Setup,
        is ScreenIntent.Home,
        ScreenIntent.Back,
        -> draw()
    }

    /**
     * Closing the screen commits every pending deletion once, through the store that owns it:
     * a held id goes through history.delete, a server id goes through older.delete. A deletion
     * whose window already ran in windowEnded is not re-sent.
     */
    fun close() {
        val pendingIds = pending.keys.toList()
        for (id in pendingIds) {
            pending.remove(id)?.cancel()
        }
        val heldIds = held.map { it.id }.toSet()
        val serverIds = serverRows?.list.orEmpty().map { it.id }.toSet()
        for (id in pendingIds) {
            if (id in heldIds) {
                if (guarded { history.delete(id) }) {
                    held = held.filterNot { it.id == id }
                }
            } else if (id in serverIds) {
                guarded { older?.delete(id) == true }
            }
        }
    }

    private fun act(intent: ScreenIntent.History): Screen = when (intent.action) {
        HistoryActions.COPY -> copy(intent.id)
        HistoryActions.DELETE -> delete(intent.id)
        HistoryActions.UNDO -> undo(intent.id)
        HistoryActions.MORE -> more()
        HistoryActions.SHOW_ALL -> show(intent.id, open = true)
        HistoryActions.HIDE -> show(intent.id, open = false)
        else -> draw()
    }

    private fun copy(id: String): Screen {
        val row = held.firstOrNull { it.id == id && it.id !in pending }
            ?: serverRows?.list.orEmpty().firstOrNull { it.id == id && it.id !in pending } ?: return draw()
        notice = if (guarded { clipboard.copy(row.text) }) HistoryNotice.COPIED else HistoryNotice.COPY_FAILED
        return draw()
    }

    /** Hides the row and opens its window. The store is not touched until the window ends. */
    private fun delete(id: String): Screen {
        val heldHas = held.any { it.id == id }
        val serverHas = serverRows?.list.orEmpty().any { it.id == id }
        if (id in pending || (!heldHas && !serverHas)) return draw()
        pending[id] = work.schedule(UNDO_WINDOW_MS) { windowEnded(id) }
        return draw()
    }

    private fun undo(id: String): Screen {
        val cancellation = pending.remove(id) ?: return draw()
        cancellation.cancel()
        return draw()
    }

    /** MORE: route by phase. SERVER reads older rows from the server; every other phase is today's local reload. */
    private fun more(): Screen = when (serverPhase) {
        LoadMorePhase.SERVER -> {
            readOlder()
            draw()
        }
        else -> drawLocalMore()
    }

    private fun drawLocalMore(): Screen {
        if (shown < ROW_CAP) reload(minOf(shown + PAGE_SIZE, ROW_CAP))
        return draw()
    }

    /** Opens or closes the row [id] in full. A row not held changes nothing. Draws the screen either way. */
    private fun show(id: String, open: Boolean): Screen {
        if (held.any { it.id == id } || serverRows?.list.orEmpty().any { it.id == id }) {
            if (open) opened.add(id) else opened.remove(id)
        }
        return draw()
    }

    /**
     * The window for [id] ended: the row is deleted and left out of the rows held, so a re-read
     * that fails cannot bring it back, or put back with a notice if the store refused.
     * A held row goes through history.delete; a server row goes through older.delete first,
     * and only if it answers true is the row dropped and remembered.
     */
    private fun windowEnded(id: String) {
        if (pending.remove(id) == null) return
        val heldContainedId = held.any { it.id == id }
        if (heldContainedId) {
            if (guarded { history.delete(id) }) {
                held = held.filterNot { it.id == id }
            } else {
                notice = HistoryNotice.DELETE_FAILED
            }
        } else {
            val deleted = guarded { older?.delete(id) == true }
            if (deleted) {
                serverRows?.markDeleted(id)
            } else {
                notice = HistoryNotice.DELETE_FAILED
            }
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

    /**
     * The screen as the rows held and the open windows say it is now. A notice is handed out once.
     * Server rows are appended after held rows; a server row whose id is in held is skipped by
     * ServerRows.applyPage, so no id is drawn twice.
     */
    private fun draw(): Screen {
        val serverList = serverRows?.list.orEmpty().map {
            HistoryRow(it.id, format.format(it.createdAt), it.source, it.text, heldForVisitOnly = true)
        }
        val state = HistoryState(
            rows = held.map { HistoryRow(it.id, format.format(it.createdAt), it.source, it.text) } + serverList,
            pending = pending.keys.toSet(),
            shown = shown,
            fullPage = fullPage,
            listFailed = listFailed,
            opened = opened.toSet(),
            serverPhase = serverPhase,
            serverOfferAllowed = serverRows?.offerAllowed == true,
            serverRowsShown = serverRows?.count ?: 0,
        )
        val drawn = notice
        notice = null
        return screen.render(state, drawn)
    }

    /** Which phase the Load more button is in: the local list first, the server only once the local list is exhausted. */
    internal val serverPhase: LoadMorePhase
        get() {
            val rows = serverRows ?: return LoadMorePhase.LOCAL
            if (!rows.offerAllowed) return LoadMorePhase.LOCAL
            if (fullPage) return LoadMorePhase.LOCAL
            if (held.isEmpty()) return LoadMorePhase.LOCAL
            if (rows.count >= SERVER_ROW_CAP) return LoadMorePhase.CAP
            if (!rows.hasMore) return LoadMorePhase.GONE
            return LoadMorePhase.SERVER
        }
}
