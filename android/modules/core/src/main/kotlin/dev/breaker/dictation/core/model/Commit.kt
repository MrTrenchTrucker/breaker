package dev.breaker.dictation.core.model

/**
 * What happened to the text when the app tried to put it where the user wanted.
 *
 * `COMMITTED` — the text went into the focused field through the keyboard.
 * `COPIED` — there was no field to type into, so the text went to the
 * clipboard instead and the user was told.
 * `FAILED` — neither worked.
 *
 * This is *text commit* / *text insertion*: the app types or copies text on the
 * user's explicit send. It never reaches into another app's data.
 */
enum class CommitOutcome { COMMITTED, COPIED, FAILED }

/** A request to put [text] where the user is typing. */
data class CommitRequest(val text: String) {
    init {
        require(text.isNotEmpty()) { "There is nothing to commit from empty text" }
    }

    /** Prints the length of the text, never the text. */
    override fun toString(): String = "CommitRequest(${text.length} chars)"
}
