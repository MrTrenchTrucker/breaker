package dev.breaker.dictation.sync

/** The reading of the pull answer's status. */
internal enum class PullStatus {
    /** The server answered 200: a page. */
    OK,
    /** The server answered 400: the `since` cursor is bad. */
    BAD_CURSOR,
    /** Every other status (including 401, 403, 410, negative and zero): decided by later pieces. */
    OTHER;

    companion object {
        /** Reads an HTTP status: 200 is OK, 400 is BAD_CURSOR, everything else is OTHER. */
        fun of(status: Int): PullStatus = when {
            status == 200 -> OK
            status == 400 -> BAD_CURSOR
            else -> OTHER
        }
    }
}
