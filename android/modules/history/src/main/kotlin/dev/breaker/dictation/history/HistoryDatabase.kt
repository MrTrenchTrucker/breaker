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

    /** Record [tombstone], replacing any tombstone already held for the same id. */
    fun putTombstone(tombstone: Tombstone)

    /** Tombstones newest first, at most [limit] of them. */
    fun newestTombstones(limit: Int): List<Tombstone>

    /**
     * Drop every tombstone recorded strictly before [cutoff].
     *
     * Never reached from the transcription purge. A tombstone that a delete
     * still needs is the record that the delete is not finished, and losing it
     * is how a deleted transcription comes back.
     */
    fun deleteTombstonesRecordedBefore(cutoff: Long): Int

    /** How many transcriptions are on file. Tombstones are not counted. */
    fun countTranscriptions(): Int

    /** Run [block] as one all-or-nothing unit: either it takes, or none of it does. */
    fun <T> transaction(block: () -> T): T

    /** Release the underlying handle. */
    fun close()
}
