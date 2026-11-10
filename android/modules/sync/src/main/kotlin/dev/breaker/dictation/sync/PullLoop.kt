package dev.breaker.dictation.sync

import kotlin.collections.ArrayList
import dev.breaker.shared.api.TranscriptionPage
import dev.breaker.shared.api.TranscriptionRecord

/** One result of one pull. */
internal sealed class PullOutcome {
    /** All records of all pages in page order, and the final [next]. */
    data class Ok(val records: List<TranscriptionRecord>, val next: String) : PullOutcome()
    /** The first answer said 400: the cursor is bad. */
    object BadCursor : PullOutcome()
    /** The server answered 410: the cursor is below the purged floor. */
    object Gone : PullOutcome()
    /** Any answer that was not 200, 400 or 410 (401, 403 and 500). */
    class Other(val status: Int) : PullOutcome()
    /** The server could not be reached. Records so far are dropped. */
    object Unreachable : PullOutcome()
    /** The pull stopped early for a rule reason: [reason] names the rule only. */
    class Refused(val reason: String) : PullOutcome()
}

/** Runs one pull: page after page, starting at [startCursor], until the last page or a stop. */
internal object PullLoop {
    /** A page cap so a hostile server cannot make the pull loop forever. */
    const val MAX_PAGES: Int = 1000

    /** Two decimal parts: <account_id>-<change>, digits only. */
    private val INCREMENTAL_CURSOR = Regex("^[0-9]+-[0-9]+$")

    /**
     * Refuses a page whose [next] is not strictly past the [cursor] just sent, for incremental
     * cursors only. Returns the Refused outcome when the rule fires, null otherwise. The account
     * parts are compared as text and the change parts as Long, so a 19-digit change number never
     * truncates. A cursor whose account differs is hostile (a page of another account's cursor).
     */
    private fun refuseUnlessCursorAdvanced(cursor: String?, next: String): PullOutcome.Refused? {
        if (cursor == null) return null
        if (!INCREMENTAL_CURSOR.matches(cursor) || !INCREMENTAL_CURSOR.matches(next)) return null
        val cursorParts = cursor.split("-")
        val nextParts = next.split("-")
        if (cursorParts[0] != nextParts[0]) {
            return PullOutcome.Refused("no progress: the page's next names another account")
        }
        val cursorChange = cursorParts[1].toLongOrNull()
        val nextChange = nextParts[1].toLongOrNull()
        if (cursorChange == null || nextChange == null) return null
        if (nextChange <= cursorChange) {
            return PullOutcome.Refused("no progress: the page's next is not past the cursor just sent")
        }
        return null
    }

    /** Ask [transport] for page after page, starting at [startCursor], until
     * the last page or a stop. Returns one [PullOutcome].
     */
    fun run(transport: PullTransport, startCursor: String? = null): PullOutcome {
        var cursor: String? = startCursor
        val records = ArrayList<TranscriptionRecord>()
        var pages = 0
        while (true) {
            val request = if (cursor == null || cursor.isEmpty()) {
                PullRequest.first()
            } else {
                PullRequest.after(cursor)
            }
            pages += 1
            if (pages > MAX_PAGES) {
                return PullOutcome.Refused("page cap: no last page after " + MAX_PAGES + " pages")
            }
            val answer = transport.fetch(request)
            when (answer) {
                is PullTransportResult.Unreachable -> return PullOutcome.Unreachable
                is PullTransportResult.Answered -> {
                    val status = PullStatus.of(answer.status)
                    when (status) {
                        PullStatus.OK -> {
                            val page = try {
                                PullPageDecoder.decode(answer.body)
                            } catch (e: PullPageException) {
                                return PullOutcome.Refused("page refused: " + e.message)
                            }
                            records.addAll(page.items)
                            if (!page.more) {
                                return PullOutcome.Ok(records, page.next)
                            }
                            if (page.next == cursor) {
                                return PullOutcome.Refused(
                                    "no progress: the page's next equals the cursor just sent"
                                )
                            }
                            refuseUnlessCursorAdvanced(cursor, page.next)?.let { return it }
                            if (page.items.isEmpty()) {
                                return PullOutcome.Refused(
                                    "no progress: a page with more true holds no records"
                                )
                            }
                            cursor = page.next
                        }
                        PullStatus.BAD_CURSOR -> return PullOutcome.BadCursor
                        PullStatus.GONE -> return PullOutcome.Gone
                        PullStatus.OTHER -> return PullOutcome.Other(answer.status)
                    }
                }
            }
        }
    }
}
