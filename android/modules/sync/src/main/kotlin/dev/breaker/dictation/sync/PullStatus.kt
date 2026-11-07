package dev.breaker.dictation.sync

/** The reading of the pull answer's status. */
internal enum class PullStatus {
    /** The server answered 200: a page. */
    OK,
    /** The server answered 400: the `since` cursor is bad. */
    BAD_CURSOR,
    /** The server answered 410: the stored cursor is below the purged floor. */
    GONE,
    /** Any answer that was not 200, 400 or 410 (401, 403 and 500). */
    OTHER;

    companion object {
        /** Reads an HTTP status: 200 is OK, 400 is BAD_CURSOR, 410 is GONE, everything else is OTHER. */
        fun of(status: Int): PullStatus = when {
            status == 200 -> OK
            status == 400 -> BAD_CURSOR
            status == 410 -> GONE
            else -> OTHER
        }
    }
}
