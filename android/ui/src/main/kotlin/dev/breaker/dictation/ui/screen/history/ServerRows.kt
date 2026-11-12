package dev.breaker.dictation.ui.screen.history

import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.port.OlderBound
import dev.breaker.dictation.core.port.OlderResult

/*
 * The server rows of one history visit, held beside the store's rows.
 *
 * Everything here is visit-only: server rows are shown for the session and are
 * never saved to the phone's database, so they are gone when the view closes.
 * The handler owns one of these and delegates the server phase of Load more to it.
 *
 * The result types come straight from the core OlderHistory port
 * (OlderResult / OlderResult.Page); there is no visit-local copy of them.
 */

/** Holds the server rows of one visit and the facts the server phase of Load more needs. */
internal class ServerRows(initialOfferAllowed: Boolean = false) {
    /** The server rows shown so far this visit, newest first. */
    private val rows = mutableListOf<Transcription>()
    /** The `next` bound of the last page, or null when there is no more. */
    private var lastNext: OlderBound? = null
    /** Whether the server may still have older rows. Starts true; a page or NoneOlder sets it false. */
    private var more = true
    /** Whether the server may still have older rows; public so the handler and tests can read it. */
    val hasMore: Boolean get() = more
    /** Whether a server read is in flight; a second press while out does nothing. */
    private var inFlight = false
    /** Ids deleted this visit; later pages skip them, and copy/delete lookups skip them. */
    private val deletedThisVisit = mutableSetOf<String>()

    /** How many server rows have been shown this visit. */
    val count: Int get() = rows.size
    /** Whether the button should be gone for the visit. */
    val gone: Boolean get() = !more || count >= SERVER_ROW_CAP
    /** Whether the server phase may start at all. */
    var offerAllowed: Boolean = initialOfferAllowed

    /** The rows drawn in server order, newest first. */
    val list: List<Transcription> get() = rows.toList()
    /** The ids deleted this visit; the app's delete route sends them through older.delete. */
    val deletedIds: Set<String> get() = deletedThisVisit.toSet()

    /**
     * Starts a server read: true when the call may go out (not in flight, allowed,
     * and not already gone), false otherwise. Sets in flight.
     */
    fun mayRead(): Boolean {
        if (inFlight || gone || !offerAllowed) return false
        inFlight = true
        return true
    }

    /** Marks the in-flight read as done. Call this once the read's answer is handled. */
    fun readDone() {
        inFlight = false
    }

    /**
     * The bound to send: createdAt of the last held row on the first call, else
     * the previous Page.next unchanged. Never sends lastNext once more went false.
     */
    fun firstBound(heldRows: List<Transcription>): OlderBound? =
        when {
            more && lastNext != null -> lastNext
            heldRows.isEmpty() -> null
            else -> OlderBound(heldRows.last().createdAt, null)
        }

    /**
     * Applies one page of server rows: appends, updates next/more, dedupes by id
     * against both the server list and the held rows, and skips deleted ids.
     */
    fun applyPage(page: OlderResult.Page, heldIds: Set<String>) {
        val incoming = page.rows.filter { it.id !in deletedThisVisit && it.id !in heldIds }
        val existingIds = rows.map { it.id }.toSet()
        rows.addAll(incoming.filter { it.id !in existingIds })
        lastNext = page.next
        more = page.more
    }

    /** No more pages: stop offering the server phase. */
    fun noMore() {
        more = false
    }

    /** Marks one id as deleted this visit so later pages never bring it back. */
    fun markDeleted(id: String) {
        deletedThisVisit.add(id)
        rows.removeAll { it.id == id }
    }

    /** The notice to show for one older-rows answer, or null when the answer needs none. */
    fun noticeFor(result: OlderResult): HistoryNotice? = when (result) {
        is OlderResult.Page ->
            if (result.rows.isEmpty() && result.skipped > 0 && result.more) HistoryNotice.SERVER_EMPTY_PAGE else null
        OlderResult.NoneOlder -> null
        OlderResult.SignedOut -> HistoryNotice.SERVER_SIGNED_OUT
        OlderResult.NotAllowed -> HistoryNotice.SERVER_UNAVAILABLE
        is OlderResult.Busy -> HistoryNotice.SERVER_BUSY
        OlderResult.Unavailable -> HistoryNotice.SERVER_UNAVAILABLE
        OlderResult.BadRequest -> HistoryNotice.LIST_FAILED
    }

    /** Clears everything: called when the visit ends. */
    fun clear() {
        rows.clear()
        lastNext = null
        more = true
        inFlight = false
        deletedThisVisit.clear()
    }
}
