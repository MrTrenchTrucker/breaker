package dev.breaker.dictation.sync

/** A permanent refusal from the server about one queue row. Carries the HTTP status and the server's reason. */
class PermanentRefusalException(
    /** The HTTP status the server answered for this row. */
    val status: Int,
    /** The server's short error word or message for this row only. Never the row body or dictated text. */
    val reason: String,
    cause: Throwable? = null,
) : Exception(reason, cause) {

    companion object {
        /** 400 bad body: the row itself is refused. Permanent. */
        const val PERMANENT_REFUSAL_400 = 400

        /** 413 body too large: the row itself is refused. Permanent. */
        const val PERMANENT_REFUSAL_413 = 413

        /** The module stores the reason text truncated to this many characters. */
        const val REASON_MAX_LENGTH = 200

        /** True when [status] is a permanent refusal about the row itself (400 or 413). */
        fun isPermanentRefusal(status: Int): Boolean =
            status == PERMANENT_REFUSAL_400 || status == PERMANENT_REFUSAL_413

        /** Truncates [reason] to [REASON_MAX_LENGTH] characters. */
        fun capReason(reason: String): String =
            if (reason.length <= REASON_MAX_LENGTH) reason else reason.substring(0, REASON_MAX_LENGTH)
    }
}
