package dev.breaker.dictation.history

import android.content.Context
import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.port.Clock
import dev.breaker.dictation.core.port.HistoryStore

/**
 * Transcription history on the phone's own database.
 *
 * Three things this class is careful about, and each is a way a history store
 * loses a user's dictation:
 *
 * 1. **Nothing is skipped on the way in.** [save] has no branch on the source
 *    (F7). A server transcription is stored exactly like a local one, so
 *    switching engines never makes a dictation disappear, and an id that comes
 *    back — a retry, a re-sync — replaces its own row rather than arriving
 *    twice.
 *
 * 2. **The retention boundary is exact.** [purgeExpired] keeps every row created
 *    at or after the cutoff and removes every row created before it.
 *    `RetentionPolicy` supplies the cutoff instant only; the strict less-than is
 *    `RetentionBoundary`'s predicate, run in SQL, and `RetentionPolicy.isExpired`
 *    is modelled and tested, not shipped. The delete and the tombstone sweep are
 *    separate calls, so the cleanup can never take a tombstone that a delete
 *    still depends on.
 *
 * 3. **A delete leaves a tombstone.** [delete] removes the row and records why,
 *    in one transaction. A row that vanishes without a tombstone is a row the
 *    server still has, and the next sync hands it back.
 *
 * Tombstones are written and kept. No shipped code in this module or in the app
 * reads or sweeps them yet: the sweep and the list of tombstones waiting to travel
 * are internal.
 *
 * The store is the rules; `HistoryDatabase` is the storage; time arrives
 * through [Clock] so the boundary is reproducible in a test instead of being
 * whatever the wall clock happened to say.
 *
 * Built only through [create]. The constructor is internal because it takes the
 * storage seam, which is not part of this module's surface. It takes no
 * retention window: the window is the fixed ADR-010 precise rule, not something
 * a caller chooses.
 */
class SqliteHistoryStore internal constructor(
    private val database: HistoryDatabase,
    private val clock: Clock,
) : HistoryStore {

    private val retention = RetentionPolicy()

    /**
     * Store [transcription], replacing any row already held under its id.
     *
     * Blank text is stored. Recording silence transcribes to nothing, and
     * whether that is worth keeping is not this module's call — dropping it
     * here would mean the user cannot see that a dictation happened at all.
     */
    override fun save(transcription: Transcription) {
        val row = TranscriptionRow.of(transcription)
        database.transaction { database.save(row) }
    }

    /**
     * The [limit] most recent transcriptions, newest first.
     *
     * Ties on creation time are broken by id, descending, so the same rows come
     * back in the same order: two rows saved in the same millisecond do not swap
     * places between calls.
     */
    override fun list(limit: Int): List<Transcription> {
        require(limit >= 0) { "limit cannot be negative: $limit" }
        if (limit == 0) return emptyList()
        return database.newest(limit).map { it.toTranscription() }
    }

    /**
     * Delete one transcription and record a tombstone for it.
     *
     * Returns true when a row was removed. An unknown id removes nothing and
     * leaves no tombstone: there was no local row to delete, and inventing a
     * tombstone for text this device never held would tell the server to delete
     * something the user never asked it to.
     */
    override fun delete(id: String): Boolean {
        val removed = database.transaction {
            val didDelete = database.deleteById(id)
            if (didDelete) {
                database.putTombstone(
                    Tombstone(
                        id = id,
                        deletedAt = clock.nowEpochMillis(),
                        reason = Tombstone.Reason.USER,
                    ),
                )
            }
            didDelete
        }
        return removed
    }

    /**
     * Remove every transcription that has fallen out of the retention window
     * (F28).
     *
     * The cutoff comes from `RetentionPolicy` and is the *only* thing that
     * decides what goes. A row created exactly at the cutoff is kept; one
     * millisecond earlier is removed.
     *
     * Every removed row is tombstoned, in the same transaction as the delete, so
     * that a sync carrying the tombstone lets the server drop its copy too and
     * history does not come back on the next sync. Tombstones for these rows are
     * *not* swept here — a tombstone that has not been pushed yet is the only
     * record that the delete exists, and this module has no way to know whether
     * sync has carried it. Sweeping tombstones is a separate, explicit call, and
     * nothing makes it yet.
     */
    fun purgeExpired(): PurgeReport = purgeExpired(clock.nowEpochMillis())

    /** [purgeExpired] against an explicit [now] in epoch milliseconds. */
    internal fun purgeExpired(now: Long): PurgeReport {
        val cutoff = retention.cutoff(java.time.Instant.ofEpochMilli(now)).toEpochMilli()
        return database.transaction {
            val doomed = database.idsCreatedBefore(cutoff)
            val removed = database.deleteCreatedBefore(cutoff)
            doomed.forEach { id ->
                database.putTombstone(Tombstone(id, deletedAt = now, reason = Tombstone.Reason.RETENTION))
            }
            PurgeReport(purged = removed, tombstoned = doomed.size, cutoff = cutoff)
        }
    }

    /**
     * Drop tombstones recorded before the tombstone window, once sync has had
     * the window to carry them.
     *
     * Separate from [purgeExpired] on purpose. Folding the two together would
     * mean a cleanup that expires transcriptions also expires the record of
     * the deletes that did not reach the server yet, and the deletes would then
     * never happen at all.
     */
    internal fun purgeExpiredTombstones(): TombstonePurgeReport = purgeExpiredTombstones(clock.nowEpochMillis())

    /** [purgeExpiredTombstones] against an explicit [now] in epoch milliseconds. */
    internal fun purgeExpiredTombstones(now: Long): TombstonePurgeReport {
        val cutoff = retention.tombstoneCutoff(java.time.Instant.ofEpochMilli(now)).toEpochMilli()
        val dropped = database.transaction { database.deleteTombstonesRecordedBefore(cutoff) }
        return TombstonePurgeReport(dropped = dropped, cutoff = cutoff)
    }

    /** The tombstones waiting to travel, newest first. */
    internal fun pendingTombstones(limit: Int): List<Tombstone> {
        require(limit >= 0) { "limit cannot be negative: $limit" }
        if (limit == 0) return emptyList()
        return database.newestTombstones(limit)
    }

    /**
     * Apply a delete that was made elsewhere — on the web front end, or on
     * another device — and keep a tombstone so the delete still reaches
     * everyone else (F34).
     *
     * Returns true when this device held a row to remove. A delete for text
     * this device never had is still recorded: the user deleted it somewhere,
     * and the tombstone is how this device tells the others.
     *
     * Nothing outside the tests calls this yet.
     */
    internal fun applyRemoteDelete(id: String, deletedAt: Long): Boolean = database.transaction {
        val removed = database.deleteById(id)
        database.putTombstone(
            Tombstone(id = id, deletedAt = deletedAt, reason = Tombstone.Reason.REMOTE),
        )
        removed
    }

    /** How many transcriptions are on file, tombstones excluded. */
    internal fun count(): Int = database.countTranscriptions()

    /** Release the database handle. */
    internal fun close() = database.close()

    companion object {
        /**
         * The store on the phone's own SQLite, in the app-private database.
         *
         * The one way to build a [SqliteHistoryStore]. [clock] is the time source
         * every timestamp and every retention decision is taken from.
         *
         * Create it once per process and share it. Each call opens its own handle
         * on the same database file, so two stores on one file would contend for
         * the write lock (expected from how SQLite locks a file; no test here
         * shows it).
         */
        fun create(context: Context, clock: Clock): SqliteHistoryStore =
            SqliteHistoryStore(SqliteHistoryDatabase(context), clock)
    }
}
