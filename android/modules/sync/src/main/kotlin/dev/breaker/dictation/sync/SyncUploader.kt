package dev.breaker.dictation.sync

import dev.breaker.shared.api.SyncPush

/**
 * What the uploader says happened to one push or delete.
 *
 * Exactly three outcomes. The queue acts on each one differently:
 * [CONFIRMED] removes the entry, [UNREACHABLE] stops the drain quietly and keeps
 * the entry, and [FAILED] stops the drain, counts one failure and keeps the entry.
 */
enum class UploadOutcome {
    /** The server stored the record, or already held the same (client id, key version) pair. */
    CONFIRMED,

    /** The server could not be reached. Nothing is wrong with the record; try again later. */
    UNREACHABLE,

    /** The upload or delete went wrong, or the server refused the record. The entry stays queued. */
    FAILED,
 }

/**
 * Sends one push or one delete to the server.
 *
 * This module has no HTTP client. The app supplies the uploader and its
 * transport: [upload] sends the [SyncPush] it is given to `POST /v1/sync` with
 * the user's token; [delete] calls `DELETE /v1/transcriptions/{clientId}` with
 * the user's token and no body. With no RecordKeys port the push is built by
 * SyncBodyCodec.plainPush at key_version 0, so its encrypted field holds the body as padded
 * base64 with no cryptography.
 *
 * An implementation that throws is treated as [UploadOutcome.FAILED] by the queue.
 */
interface SyncUploader {
    /** Send [push] and say what happened. */
    fun upload(push: SyncPush): UploadOutcome

    /** Delete the transcription [clientId] and say what happened. A 400 or 413 answer throws
     * [PermanentRefusalException] and the queue parks the delete row, like a refused save. */
    fun delete(clientId: String): UploadOutcome
}
