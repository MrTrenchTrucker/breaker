package dev.breaker.dictation.sync

/** The stored pull cursor: opaque text, never parsed or built. */
internal interface PullCursorPort {
    /** The stored cursor text, or null when none is stored. */
    fun pullCursor(): String?
    /** Stores [cursor] as the cursor text. */
    fun savePullCursor(cursor: String)
    /** Clears the stored cursor. */
    fun clearPullCursor()
}
