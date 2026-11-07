package dev.breaker.dictation.core.port

/**
 * The delete made on another device (on the web front end or on another phone) handed to the
 * module that owns the local store.
 *
 * `id` is the client id of the transcription. `deletedAt` is the time of the delete in epoch
 * milliseconds. The answer is true when the device held a row to remove; the tombstone is kept
 * either way, so the delete still reaches every other device through sync.
 *
 * Nothing calls this yet and nothing wires it; the app does that later. History implements it and
 * stays the only writer of the local store.
 */
interface PullSink {
    /**
     * Apply a delete made elsewhere. Returns true when this device held a row to remove.
     *
     * @param id the client id of the transcription
     * @param deletedAt the time of the delete in epoch milliseconds
     * @return true when the device held a row to remove, false otherwise; a tombstone is kept either way
     */
    fun applyRemoteDelete(id: String, deletedAt: Long): Boolean
}
