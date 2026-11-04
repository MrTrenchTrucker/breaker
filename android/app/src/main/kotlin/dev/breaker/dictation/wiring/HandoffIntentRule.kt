package dev.breaker.dictation.wiring

/**
 * Reads the data string of an incoming return intent. Only the view action with a non-empty
 * data string is read, and the string is passed on exactly as it arrived: it is never trimmed,
 * inspected or logged here.
 */
object HandoffIntentRule {
    /** The platform's name for the intent action that opens a link. */
    const val ACTION_VIEW: String = "android.intent.action.VIEW"

    /** The data string for a view intent with a non-empty data string, otherwise null. */
    fun read(action: String?, dataString: String?): String? {
        if (action != ACTION_VIEW) return null
        if (dataString.isNullOrEmpty()) return null
        return dataString
    }
}
