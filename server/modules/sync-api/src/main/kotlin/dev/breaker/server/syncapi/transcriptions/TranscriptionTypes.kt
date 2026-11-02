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

/** One row the page query returns: a live or deleted row with its change number. */
internal data class PageRow(
    val clientId: String,
    val keyVersion: Int,
    val encrypted: String,
    val storedAtMs: Long,
    val changeNo: Long,
    val deleted: Int,
)

/** The row read before deciding: [keyVersion] and [deleted] (0 = live, 1 = deleted). */
internal data class StoredRow(val keyVersion: Int, val deleted: Int)

/** What a delete did. Deleted and NoOp are answered 204. */
internal sealed class DeleteResult {
    object Deleted : DeleteResult()
    object NoOp : DeleteResult()
}

/** One record of the feed page. A live record carries key_version and encrypted; a delete record
 * carries neither. All records carry client_id, change, deleted and stored_at. */
internal data class TranscriptionPageRecord(
    val clientId: String,
    val change: Long,
    val deleted: Boolean,
    val keyVersion: Int?,
    val encrypted: String?,
    val storedAtMs: Long,
)

/** The result of one store page read. */
internal sealed class PageResult {
    /** A page of up to 100 records within the size cap. [next] is the cursor the client sends back
     * as [since]; [more] is true when another matching row exists after the page. */
    data class Page(
        val records: List<TranscriptionPageRecord>,
        val next: Long,
        val more: Boolean,
    ) : PageResult()

    /** The cursor is below the account's purged floor: the client must resync without since. */
    object Gone : PageResult()
}
