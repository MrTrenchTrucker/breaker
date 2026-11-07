package dev.breaker.server.syncapi.transcriptions

// One cursor the server issues and the client sends back as since. Two kinds:
// - Incremental: <account_id>-<change>, today's cursor, unchanged.
// - Listing:   <account_id>-<position>-<head>, only as next of a non-final page of a no-since
//   list. A list is a snapshot: live rows with position < change_no <= first-page head.
//   position is the change of the last live row emitted; head is the first-page head.
//
// parse returns null for every refused text: a malformed value, a value whose account part
// has a leading zero, a part that does not fit a Long, a three-part value whose position is
// not in 1..(head - 1), and any other shape (four parts, letters, leading or trailing dash).
internal sealed class FeedCursor {
    data class Incremental(val accountId: Long, val change: Long) : FeedCursor()
    data class Listing(val accountId: Long, val position: Long, val head: Long) : FeedCursor()

    /** Renders this cursor back to the text the client sends as since. */
    fun render(): String = when (this) {
        is Incremental -> "$accountId-$change"
        is Listing -> "$accountId-$position-$head"
    }

    companion object {
        // The union pattern: two-part incremental or three-part listing. Any other text is
        // refused before any number is read.
        private val CURSOR_PATTERN = Regex("^[0-9]{1,19}-[0-9]{1,19}(-[0-9]{1,19})?$")

        /** Parses [text] into a FeedCursor, or null when it must be refused. */
        fun parse(text: String): FeedCursor? {
            if (!CURSOR_PATTERN.matches(text)) return null
            val parts = text.split("-")
            if (parts.size < 2 || parts.size > 3) return null
            val accountPart = parts[0]
            // The account part is the decimal of the account id: no leading zero except a
            // single 0. The other parts may have leading zeros, as today.
            if (accountPart.length > 1 && accountPart.startsWith("0")) return null
            val account = accountPart.toLongOrNull() ?: return null
            val change = parts[1].toLongOrNull() ?: return null
            if (parts.size == 2) return Incremental(account, change)
            // Three parts: a listing is valid only when 1 <= position < head.
            val position = parts[1].toLongOrNull() ?: return null
            val head = parts[2].toLongOrNull() ?: return null
            if (position < 1 || position >= head) return null
            return Listing(account, position, head)
        }
    }
}
