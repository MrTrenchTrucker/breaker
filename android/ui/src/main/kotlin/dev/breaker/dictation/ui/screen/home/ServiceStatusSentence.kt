package dev.breaker.dictation.ui.screen.home

/**
 * The one line the home screen may show for service status, as an id.
 *
 * The decision returns one of these and never a sentence: the caller reads the
 * words from `ServiceStatusTexts`, so the words live in one place only.
 * [NONE] means no line at all.
 */
internal enum class ServiceStatusSentence {

    /** No line: the app did not ask, or the reply lists nothing to report. */
    NONE,

    /** Every listed service is up. */
    ALL_UP,

    /** whisper-server is down. */
    TRANSCRIPTION_DOWN,

    /** sync-api is down, or the container could not be reached. */
    SERVER_UNREACHABLE,
}

/** One service in a status reply: its name and whether it is up. */
internal data class ServiceStatusEntry(val name: String, val up: Boolean)

/**
 * The service-status decision, pure: a reply, a failure and whether the app
 * asked, in; one sentence id out.
 *
 * [reply] is the answer from `GET /v1/status`, or null when there is none.
 * [failed] says the ask itself failed: unreachable, oversize and unparseable
 * are one failure input, as ADR-044 rules. [asked] says
 * the caller asked at all; sync off, signed out or no server address means not
 * asked, and the caller decides that.
 *
 * A listed service whose name is neither `sync-api` nor `whisper-server` is
 * ignored. `whisper-server` down is the transcription line. `sync-api` down is
 * the server line. Both down is the server line: the worse one wins. An empty
 * list is no line. A failure is the server line. Not asked is no line.
 */
internal fun decideServiceStatus(
    reply: List<ServiceStatusEntry>?,
    failed: Boolean,
    asked: Boolean,
): ServiceStatusSentence = when {
    !asked -> ServiceStatusSentence.NONE
    failed -> ServiceStatusSentence.SERVER_UNREACHABLE
    reply == null -> ServiceStatusSentence.SERVER_UNREACHABLE
    reply.isEmpty() -> ServiceStatusSentence.NONE
    else -> statusOf(reply)
}

/** The mapping of one reply, a name that is neither known one already ignored. */
private fun statusOf(reply: List<ServiceStatusEntry>): ServiceStatusSentence {
    val whisperDown = reply.any { it.name == WHISPER_SERVER && !it.up }
    val syncDown = reply.any { it.name == SYNC_API && !it.up }
    return when {
        syncDown -> ServiceStatusSentence.SERVER_UNREACHABLE
        whisperDown -> ServiceStatusSentence.TRANSCRIPTION_DOWN
        else -> ServiceStatusSentence.ALL_UP
    }
}

private const val SYNC_API = "sync-api"
private const val WHISPER_SERVER = "whisper-server"
