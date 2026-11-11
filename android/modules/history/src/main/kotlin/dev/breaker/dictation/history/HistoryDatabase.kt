package dev.breaker.dictation.history

/**
 * The database the history store needs, and nothing else.
 *
 * This is deliberately smaller than a SQL layer. The store owns the *rules* —
 * which rows fall outside the window, what a delete leaves behind, what order
 * history is read in — and a plain `SQLiteOpenHelper` adapter implements the
 * operations. The rules are therefore testable on a plain JVM against an
 * in-memory database, with no device and no Android framework in the way, and
 * the SQL that carries them out is pinned separately by the SQL tests.
 *
 * A wider interface — cursors, transactions spelled out in SQL — would push
 * the rules back into the adapter, and rules that only exist in an adapter that
 * needs a device are rules nobody tests.
 */
internal interface HistoryDatabase {
    /** Insert [row], replacing any row with the same id. */
    fun save(row: TranscriptionRow)

    /** Rows newest first, by creation time then id, at most [limit] of them. */
    fun newest(limit: Int): List<TranscriptionRow>

    /** Remove the row with [id]. True when a row was removed. */
    fun deleteById(id: String): Boolean

    /**
     * Ids of every row created **strictly before** [cutoff], oldest first.
     *
     * Strictly before, and that word carries the retention boundary: a row
     * created at exactly the cutoff is inside the window.
     */
    fun idsCreatedBefore(cutoff: Long): List<String>

    /** Remove every row created strictly before [cutoff]. Returns how many went. */
    fun deleteCreatedBefore(cutoff: Long): Int

    /**
     * The ids of every stored transcription, no tombstones.
     *
     * What retainOnly iterates over when it decides which local rows fall outside
     * the keep set after a finished full list.
     */
    fun allIds(): List<String>

    /** Record [tombstone], replacing any tombstone already held for the same id. */
    fun putTombstone(tombstone: Tombstone)

    /**
     * Tombstone rows newest first, at most [limit] of them, exactly as they are
     * stored.
     *
     * The stored reason is handed back as the word on the disk, not as a
     * `Reason`: a word this module does not know is the store's to skip and
     * report, and a read that threw instead would let one unreadable row stop
     * every tombstone behind it.
     */
    fun newestTombstones(limit: Int): List<Tombstone.StoredRow>

    /**
     * USER-reason tombstone rows, id descending, at most [limit] of them,
     * exactly as they are stored: page 1 of the backfill's keyset walk.
     */
    fun newestUserTombstones(limit: Int): List<Tombstone.StoredRow>

    /**
     * USER-reason tombstone rows with an id strictly below [lastId], id
     * descending, at most [limit] of them: the next page of the walk, which
     * covers every USER tombstone exactly once.
     */
    fun newestUserTombstonesAfter(lastId: String, limit: Int): List<Tombstone.StoredRow>

    /**
     * Drop every tombstone recorded strictly before [cutoff].
     *
     * Never reached from the transcription purge. A tombstone that a delete
     * still needs is the record that the delete is not finished, and losing it
     * is how a deleted transcription comes back.
     */
    fun deleteTombstonesRecordedBefore(cutoff: Long): Int

    /** Drop every tombstone. Returns how many went. */
    fun deleteAllTombstones(): Int

    /**
     * Drop the tombstone held for [id], but only when its stored reason is
     * [reason]. Returns how many went: 0 or 1, since the id is the tombstone
     * table's primary key.
     *
     * The match is on both the id and the reason, on purpose. The id alone is
     * not enough: a tombstone for the same id whose reason is not [reason]
     * belongs to another kind of delete and must survive. The reason alone is
     * not enough either: it would reach into other ids' tombstones that happen
     * to carry the same reason. The caller that uses this is the store's save
     * of a transcription, which clears the local delete's tombstone when the
     * user saves the same id again; a remote or a retention tombstone is left
     * in place.
     */
    fun deleteTombstoneByIdAndReason(id: String, reason: Tombstone.Reason): Int

    /** True when a tombstone of ANY reason is stored for [id]. */
    fun hasTombstone(id: String): Boolean

    /** How many transcriptions are on file. Tombstones are not counted. */
    fun countTranscriptions(): Int

    /** Run [block] as one all-or-nothing unit: either it takes, or none of it does. */
    fun <T> transaction(block: () -> T): T

    /** Release the underlying handle. */
    fun close()
}
