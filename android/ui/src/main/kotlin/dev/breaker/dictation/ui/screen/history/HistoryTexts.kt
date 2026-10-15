package dev.breaker.dictation.ui.screen.history

/*
 * The words the history screen shows.
 *
 * Every sentence the user reads on that screen is one of these. The only other
 * text drawn is the time of a row, which is written by the phone's own formatter,
 * and the transcriptions themselves.
 */

/** The words of the history screen, in plain ASCII. */
internal object HistoryTexts {
    const val TITLE = "History"
    const val EMPTY = "Nothing here yet. What you dictate will show up here."

    /** The heading of the empty state: the first sentence of EMPTY, with its full stop. */
    val EMPTY_HEADING: String = EMPTY.substringBefore(". ") + "."

    /** The hint of the empty state: the rest of EMPTY after the heading. */
    val EMPTY_HINT: String = EMPTY.substringAfter(". ")
    const val TAG_LOCAL = "Phone"
    const val TAG_SERVER = "Server"
    const val BUTTON_COPY = "Copy"
    const val BUTTON_DELETE = "Delete"
    const val BUTTON_LOAD_MORE = "Load more"
    const val BLANK_TEXT = "(nothing was heard)"
    const val NOTICE_COPIED = "Copied."
    const val NOTICE_COPY_FAILED = "Could not copy."
    const val NOTICE_DELETE_FAILED = "Could not delete it."
    const val NOTICE_DELETED = "Deleted."
    const val NOTICE_LIST_FAILED = "Could not load history."
    const val BUTTON_UNDO = "Undo"
    const val LIMIT_NOTE = "Showing the 300 most recent."
    const val BUTTON_SHOW_ALL = "Show all"
    const val BUTTON_HIDE = "Hide"
}
