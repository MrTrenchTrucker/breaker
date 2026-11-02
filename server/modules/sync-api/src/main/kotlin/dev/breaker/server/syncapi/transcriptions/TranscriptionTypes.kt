package dev.breaker.server.syncapi.transcriptions

/**
 * What a push did. Stored, NoOp and Replaced are answered 204. RefusedAboveAccount and RefusedLower
 * are answered 409, with different texts: the first when the pushed key version is above the
 * account's current one, the second when the same client id is stored at a higher key version or a
 * brand-new client id is below the account's current key version.
 */
internal sealed class PushResult {
    object Stored : PushResult()

    object NoOp : PushResult()

    object Replaced : PushResult()

    object RefusedAboveAccount : PushResult()

    object RefusedLower : PushResult()
}

/**
 * One stored transcription of an account. [storedAtMs] is the server clock at the last write
 * of the row, in milliseconds since the epoch.
 */
internal data class TranscriptionRow(
    val clientId: String,
    val keyVersion: Int,
    val encrypted: String,
    val storedAtMs: Long,
)

/** The row read before deciding: [keyVersion] and [deleted] (0 = live, 1 = deleted). */
internal data class StoredRow(val keyVersion: Int, val deleted: Int)
