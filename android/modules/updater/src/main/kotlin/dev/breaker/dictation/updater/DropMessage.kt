package dev.breaker.dictation.updater

/**
 * The text of the log line for a dropped final status: the platform session id that arrived, and either the recorded
 * session id or the words that no session is recorded.
 */
internal fun dropMessage(platformSessionId: Int, recordedSessionId: Int?): String {
    val head: String = "final status for session " + platformSessionId
    return if (recordedSessionId == null) {
        head + " dropped; no session recorded"
    } else {
        head + " dropped; recorded session " + recordedSessionId
    }
}
