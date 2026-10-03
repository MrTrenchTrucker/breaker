package dev.breaker.dictation.history

/**
 * An in-memory [HistoryDatabase] for testing the store's rules on a plain JVM.
 *
 * It is a test double, not a second implementation of history, and it is
 * deliberately dumb: it stores rows in a map and applies the boundary it is
 * told to apply. That is the point — the *rule* under test (what the store asks
 * to be removed, and what it leaves behind) lives in [SqliteHistoryStore], and
 * this double lets a test set up a row one millisecond either side of the
 * cutoff and see which one the store reaches for.
 *
 * Two behaviours are reproduced faithfully because the store's correctness
 * depends on them:
 *
 * - `INSERT OR REPLACE` semantics: one row per id, later save wins;
 * - transactions roll back on a thrown block, so a delete that fails half way
 *   through cannot leave a row removed with no tombstone.
 */
internal class InMemoryHistoryDatabase : HistoryDatabase {

    private val rows = LinkedHashMap<String, TranscriptionRow>()
    private val tombstones = LinkedHashMap<String, Tombstone>()
    private var open = true

    /** True while a transaction is in progress; used to assert atomicity. */
    var inTransaction: Boolean = false
        private set

    override fun save(row: TranscriptionRow) {
        rows[row.id] = row
    }

    override fun newest(limit: Int): List<TranscriptionRow> = rows.values
        .sortedWith(compareByDescending<TranscriptionRow> { it.createdAt }.thenByDescending { it.id })
        .take(limit)

    override fun deleteById(id: String): Boolean = rows.remove(id) != null

    override fun idsCreatedBefore(cutoff: Long): List<String> = rows.values
        .filter { RetentionBoundary.isExpired(it.createdAt, cutoff) }
        .sortedWith(compareBy<TranscriptionRow> { it.createdAt }.thenBy { it.id })
        .map { it.id }

    override fun deleteCreatedBefore(cutoff: Long): Int =
        idsCreatedBefore(cutoff).count { rows.remove(it) != null }

    override fun putTombstone(tombstone: Tombstone) {
        tombstones[tombstone.id] = tombstone
    }

    override fun newestTombstones(limit: Int): List<Tombstone> = tombstones.values
        .sortedWith(compareByDescending<Tombstone> { it.deletedAt }.thenByDescending { it.id })
        .take(limit)

    override fun deleteTombstonesRecordedBefore(cutoff: Long): Int {
        val doomed = tombstones.values.filter { RetentionBoundary.isExpired(it.deletedAt, cutoff) }
        doomed.forEach { tombstones.remove(it.id) }
        return doomed.size
    }

    override fun countTranscriptions(): Int = rows.size

    override fun <T> transaction(block: () -> T): T {
        if (inTransaction) return block() // nesting joins the outer transaction
        val rowsBefore = LinkedHashMap(rows)
        val tombstonesBefore = LinkedHashMap(tombstones)
        inTransaction = true
        try {
            val result = block()
            inTransaction = false
            return result
        } catch (failure: Throwable) {
            rows.clear()
            rows.putAll(rowsBefore)
            tombstones.clear()
            tombstones.putAll(tombstonesBefore)
            inTransaction = false
            throw failure
        }
    }

    override fun close() {
        open = false
    }

    /** False once [close] has been called, so a test can check lifecycle handling. */
    val isOpen: Boolean get() = open

    /** The rows on file, for assertions that need the storage shape itself. */
    fun rowsSnapshot(): List<TranscriptionRow> = rows.values.toList()

    /** The tombstones on file, for assertions that need the storage shape itself. */
    fun tombstonesSnapshot(): List<Tombstone> = tombstones.values.toList()
}
