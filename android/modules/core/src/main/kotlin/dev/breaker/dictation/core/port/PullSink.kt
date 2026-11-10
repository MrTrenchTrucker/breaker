package dev.breaker.dictation.core.port

import dev.breaker.dictation.core.model.Transcription

/**
 * The delete made on another device (on the web front end or on another phone) handed to the
 * module that owns the local store, and the pulled live row that same device is handed after a pull.
 *
 * `id` is the client id of the transcription. `deletedAt` is the time of the delete in epoch
 * milliseconds. The answer is true when the device held a row to remove; the tombstone is kept
 * either way, so the delete still reaches every other device through sync.
 * `row` is one pulled live row (its id is the client id); true when the row was stored, false when
 * the store refused it because the device holds a tombstone for that id, of any reason; nothing is
 * stored then and the tombstone stays.
 *
 * PullApplier calls these and the app wires it (#521). History implements them
 * and stays the only writer of the local store.
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

    /**
     * Apply a pulled live row. Returns true when the row was stored, false when the store refused
     * it because the device holds a tombstone for that id, of any reason; nothing is stored then
     * and the tombstone stays.
     *
     * @param row the pulled live row (its id is the client id)
     * @return true when the row was stored, false when refused by a tombstone
     */
    fun applyRemoteRow(row: Transcription): Boolean

    /**
     * After a finished full list (no since), keep exactly these client ids and remove every
     * other local row. The server dropped the rest; no tombstone is written for a removed
     * row, because a tombstone would push a delete for an id the server does not hold.
     * Tombstones already held are left as they are. The answer is how many rows were
     * removed, and the whole removal is one transaction that removes nothing on failure.
     *
     * @param keep the client ids to keep (every page of the finished full list plus the ids
     * still waiting in the push queue)
     * @return how many rows were removed
     */
    fun retainOnly(keep: Set<String>): Int
}
