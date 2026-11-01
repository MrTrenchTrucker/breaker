package dev.breaker.dictation.stt.server

/**
 * The fixed sentences the user sees when a transcription fails, and the cleaning of the server's
 * own failure text. Every sentence is short plain ASCII, carries no key and no address, and is
 * different from the others. [jobFailed] is the only place server text reaches a sentence.
 */
internal object FailureSentences {
    const val NO_ADDRESS = "The server address is not set."
    const val ADDRESS_NOT_HTTPS = "The server address must be an http or https address with no spaces or user name."
    const val NO_KEY = "The server key is not set."
    const val KEY_NOT_USABLE = "The server key contains characters that cannot be sent."
    const val REQUEST_NOT_USABLE = "The recording could not be prepared for the server."
    const val KEY_REFUSED = "The server refused the key."
    const val TOO_LARGE = "The recording is too large for the server."
    const val NO_ENDPOINT = "The server does not offer transcription."
    const val NOT_TAKING_WORK = "The server is not taking work right now."
    const val UNEXPECTED_STATUS = "The server sent an answer that was not expected."
    const val JOB_GONE = "The server no longer knows this transcription job."
    const val BAD_ANSWER = "The server sent an answer that could not be read."
    const val UNREACHABLE = "The server could not be reached."
    const val TLS_FAILED = "The secure connection to the server failed."
    const val CONNECTION_LOST = "The connection to the server was lost."
    const val NO_ANSWER_IN_TIME = "The server did not answer in time."
    const val BUDGET_USED = "The server did not finish within the time allowed."
    const val INTERRUPTED = "Transcription was stopped before it finished."
    const val UNEXPECTED = "Transcription failed for an unknown reason."

    /** The start of a job-failure sentence. The cleaned server text follows it directly. */
    const val JOB_FAILED_PREFIX = "The server could not transcribe the recording: "

    private const val MAX_SERVER_TEXT_UNITS = 160
    private const val MIN_TOKEN_RUN = 24
    private const val REDACTED = "[redacted]"

    /**
     * The job-failure sentence for the server's failure [serverText]. The cleaning runs in this
     * order: each run of control characters and line breaks becomes one space; every occurrence of
     * [key] is removed (a blank key removes nothing); each run of 24 or more token characters becomes
     * the redacted marker; the text is trimmed; it is cut to 160 UTF-16 units without splitting a
     * surrogate pair. If nothing is left, or the text is null or blank, the result is the bare prefix.
     */
    fun jobFailed(serverText: String?, key: String): String {
        if (serverText == null || serverText.isBlank()) return JOB_FAILED_PREFIX
        var text = spaceForControls(serverText)
        if (key.isNotBlank()) text = text.replace(key, "")
        text = redactTokenRuns(text).trim()
        text = cutWithoutSplitting(text)
        return if (text.isEmpty()) JOB_FAILED_PREFIX else JOB_FAILED_PREFIX + text
    }

    private fun spaceForControls(text: String): String {
        val out = StringBuilder(text.length)
        var inRun = false
        for (c in text) {
            if (isBreakOrControl(c)) {
                if (!inRun) out.append(' ')
                inRun = true
            } else {
                out.append(c)
                inRun = false
            }
        }
        return out.toString()
    }

    private fun isBreakOrControl(c: Char): Boolean =
        Character.isISOControl(c) || c == '\u2028' || c == '\u2029'

    private fun redactTokenRuns(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (isTokenChar(text[i])) {
                var end = i
                while (end < text.length && isTokenChar(text[end])) end++
                if (end - i >= MIN_TOKEN_RUN) out.append(REDACTED) else out.append(text, i, end)
                i = end
            } else {
                out.append(text[i])
                i++
            }
        }
        return out.toString()
    }

    private fun isTokenChar(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' ||
            c == '.' || c == '_' || c == '~' || c == '+' || c == '/' || c == '=' || c == '-'

    private fun cutWithoutSplitting(text: String): String {
        if (text.length <= MAX_SERVER_TEXT_UNITS) return text
        var end = MAX_SERVER_TEXT_UNITS
        if (Character.isHighSurrogate(text[end - 1])) end--
        return text.substring(0, end)
    }
}
