package dev.breaker.dictation.core.port

import dev.breaker.dictation.core.model.Transcription

/** One older-rows bound: rows older than [storedAtMs] and, when [change] is present, with change below it. */
data class OlderBound(val storedAtMs: Long, val change: Long?) {
    /** Renders the bound as the client sends it: `<ms>` or `<ms>-<change>`. */
    fun render(): String = if (change == null) storedAtMs.toString() else "$storedAtMs-$change"

    companion object {
        private val SHAPE = Regex("[0-9]{1,15}(-[0-9]{1,19})?")

        /**
         * Parses a bound the server sent as `next`: `<ms>` (up to 15 digits) or `<ms>-<change>` (up to 19 digits).
         * Returns null when the text is null, does not match that digit pattern, or the change does not fit a Long.
         * Leading zeros are accepted: the value is what counts.
         */
        fun parse(text: String?): OlderBound? {
            if (text == null || !SHAPE.matches(text)) return null
            val parts = text.split("-")
            val ms = parts[0].toLong()
            val change = if (parts.size == 2) parts[1].toLongOrNull() ?: return null else null
            return OlderBound(ms, change)
        }
    }
}

/** The answer of one older-rows read: domain values only, never records or raw bodies. */
sealed class OlderResult {
    /**
     * One page of rows, newest first, with the bound to send back for the next page.
     * [skipped] counts rows of the page that could not be read and were left out.
     */
    data class Page(val rows: List<Transcription>, val skipped: Int, val next: OlderBound, val more: Boolean) : OlderResult() {
        /** Prints the row count and never the rows: they carry what the user said. */
        override fun toString(): String = "Page(${rows.size} rows, skipped=$skipped, next=${next.render()}, more=$more)"
    }
    /** The server has no older rows. */
    object NoneOlder : OlderResult()
    /** No live session (401 or no session). */
    object SignedOut : OlderResult()
    /** The token is an agent token (403). */
    object NotAllowed : OlderResult()
    /** The server is rate-limiting (429); [retryAfterSeconds] is null when the transport carries no Retry-After. */
    data class Busy(val retryAfterSeconds: Int?) : OlderResult()
    /** The server could not be reached, answered 503 or an unlisted status, or sent a page that cannot be read. */
    object Unavailable : OlderResult()
    /** The bound was malformed (400). */
    object BadRequest : OlderResult()
}

/** Older rows from the server: read only, shown for the session, never saved to the phone. */
interface OlderHistory {
    /** True when the phone may offer an older-rows read. */
    fun available(): Boolean
    /** One page of rows older than [bound], newest first. */
    fun older(bound: OlderBound): OlderResult
    /** Delete one shown row from the server. True when the delete reached the queue. */
    fun delete(id: String): Boolean
}
