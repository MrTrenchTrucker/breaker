package dev.breaker.dictation.stt.server

/**
 * The checks that run before anything is sent, and the URLs the engine calls.
 *
 * Each check returns the fixed sentence that explains a refusal, or null when the value is usable.
 * A sentence never echoes the value it refuses. The address must be https with a host and no user
 * information, spaces or control characters. One trailing slash on the base is dropped before a path
 * is added, so a base with the slash and a base without it give the same URLs.
 */
internal object ServerEndpoint {
    private const val HTTPS_PREFIX = "https://"
    private const val TRANSCRIPTIONS_PATH = "/v1/audio/transcriptions"
    private const val JOBS_PATH = "/v1/jobs/"
    private const val MAX_JOB_ID_CHARS = 128

    /** Returns the refusal for a base address, or null when the address is usable. */
    fun addressProblem(base: String?): String? {
        if (base == null || base.isBlank()) return FailureSentences.NO_ADDRESS
        if (base.any { it == ' ' || Character.isISOControl(it) }) return FailureSentences.ADDRESS_NOT_HTTPS
        if (!base.regionMatches(0, HTTPS_PREFIX, 0, HTTPS_PREFIX.length, ignoreCase = true)) {
            return FailureSentences.ADDRESS_NOT_HTTPS
        }
        val authority = base.substring(HTTPS_PREFIX.length).substringBefore('/')
        val usable = authority.isNotEmpty() && !authority.startsWith(':') && !authority.contains('@')
        return if (usable) null else FailureSentences.ADDRESS_NOT_HTTPS
    }

    /** Returns the refusal for an API key, or null when the key can be sent in a header. */
    fun keyProblem(key: String?): String? {
        if (key == null || key.isBlank()) return FailureSentences.NO_KEY
        if (key.any { it == '\r' || it == '\n' || it == '\u0000' }) return FailureSentences.KEY_NOT_USABLE
        return null
    }

    /** Returns the refusal for the model and language form fields, or null when both can be sent. */
    fun fieldProblem(model: String, language: String): String? =
        if (MultipartBodies.isSafeField(model) && MultipartBodies.isSafeField(language)) null else FailureSentences.REQUEST_NOT_USABLE

    /** True when [jobId] matches the job-id pattern: 1 to 128 letters, digits, underscores or hyphens. */
    fun isJobIdUsable(jobId: String): Boolean =
        jobId.length in 1..MAX_JOB_ID_CHARS && jobId.all { isJobIdChar(it) }

    /** The URL that accepts an audio upload, built from [base]. */
    fun transcriptionsUrl(base: String): String = withoutTrailingSlash(base) + TRANSCRIPTIONS_PATH

    /** The URL that reports on one job. Refuses a [jobId] that does not match the job-id pattern. */
    fun jobUrl(base: String, jobId: String): String {
        require(isJobIdUsable(jobId)) { "job id is not usable" }
        return withoutTrailingSlash(base) + JOBS_PATH + jobId
    }

    private fun withoutTrailingSlash(base: String): String = base.removeSuffix("/")

    private fun isJobIdChar(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '_' || c == '-'
}
