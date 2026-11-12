package dev.breaker.dictation.sync

import dev.breaker.dictation.core.port.RecordKeys

/** What sending one push row gave: the uploader's answer, or how the drain has to go on. */
internal sealed class PushRowResult {
    /** The uploader answered [outcome]; [retryAfterSeconds] is set only for a 429. */
    class Done(val outcome: UploadOutcome, val retryAfterSeconds: Int?) : PushRowResult()

    /** The server refused the row for good; the row is parked and the drain goes on. */
    object Parked : PushRowResult()

    /** The row cannot be pushed; nothing is sent, the drain goes on. */
    object Skipped : PushRowResult()

    /** The port holds no keys (version below 1, or it could not seal). */
    object NoKeys : PushRowResult()

    /** The 409 memory refuses the version the port reports. */
    object KeyVersionRefused : PushRowResult()
}

/**
 * The push side of the drain: it builds the push of one queued row and sends it. With no port the
 * push is plain, with a port the port seals it. It touches the queue only to park a row the server
 * refused for good, and the 409 memory only to remember a refused key version. Like the drain, it
 * runs only while the drain lock is held, so it needs no lock of its own.
 */
internal class PushRow(
    private val database: SyncQueueDatabase,
    private val uploader: SyncUploader,
    private val recordKeys: RecordKeys?,
    private val refusedVersion: RefusedKeyVersion,
) {
    /**
     * Builds the push of [entry] (plain with no port, sealed by the port otherwise) and sends it.
     * An exception thrown by the port propagates; one thrown by the uploader is [UploadOutcome.FAILED].
     */
    fun send(entry: QueueEntry): PushRowResult {
        val keys = recordKeys
        val push = if (keys == null) {
            buildPush(entry)
        } else {
            val version = keys.currentKeyVersion()
            if (version < 1) {
                return PushRowResult.NoKeys
            }
            if (refusedVersion.refuses(version)) {
                return PushRowResult.KeyVersionRefused
            }
            when (val built = buildPortPush(keys, entry)) {
                is PortPush.Ready -> built.push
                PortPush.Skip -> null
                PortPush.NoKeys -> return PushRowResult.NoKeys
            }
        }
        if (push == null) {
            return PushRowResult.Skipped
        }
        return try {
            PushRowResult.Done(uploader.upload(push), null)
        } catch (e: PermanentRefusalException) {
            if (PermanentRefusalException.isPermanentRefusal(e.status)) {
                // A permanent refusal about this row: park it and keep its reason.
                database.transaction {
                    database.parkRow(entry.clientId, PermanentRefusalException.capReason(e.reason))
                }
                PushRowResult.Parked
            } else {
                // A refusal about this row that is not permanent (401, 403, 409, 429, 503, ...):
                // nothing parked, the drain stops. Only a 429 carries its Retry-After seconds.
                val wait =
                    if (e.status == PermanentRefusalException.RATE_LIMITED_429) e.retryAfterSeconds else null
                if (keys != null && e.status == 409) refusedVersion.remember(push.keyVersion)
                PushRowResult.Done(UploadOutcome.FAILED, wait)
            }
        } catch (e: Exception) {
            PushRowResult.Done(UploadOutcome.FAILED, null)
        }
    }
}
