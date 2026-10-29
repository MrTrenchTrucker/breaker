package dev.breaker.server.syncapi.transcriptions

/** The outcome of one push. A refused version or a repeated pair leaves the stored rows as they were. */
internal sealed class PushResult {
    object Stored : PushResult()

    object AlreadyHeld : PushResult()

    object VersionRefused : PushResult()
}

/**
 * One stored row as the store returns it. [encrypted] is the text exactly as it was pushed.
 * Logs, messages and printed output must never carry it, so [toString] names the client id
 * and the key version only.
 */
internal data class StoredTranscription(
    val clientId: String,
    val keyVersion: Int,
    val encrypted: String,
    val storedAtMs: Long,
) {
    override fun toString(): String = "StoredTranscription(clientId=$clientId, keyVersion=$keyVersion)"
}
