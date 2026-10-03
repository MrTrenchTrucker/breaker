package dev.breaker.dictation.history

/**
 * [HistoryDatabase] on [DesktopSqlite]: a JVM twin of `SqliteHistoryDatabase`, the Android adapter.
 *
 * It exists so the store's rules can be run down to real SQL, which the in-memory double cannot do. It is
 * not the adapter, and it does not run the adapter's code.
 *
 * **Run from the adapter's source text.** The three delete statements, that is the table and the `WHERE` of
 * the delete by id, of the delete of rows created before a cutoff and of the delete of tombstones recorded
 * before one, are read out of the adapter's own source by [AdapterStatements], checked for shape, and run
 * here as the adapter wrote them. A change to one of them in the adapter changes what this class runs.
 *
 * **Taken from [HistorySql], as the adapter takes them.** The other statements are [HistorySql] constants the
 * adapter names too. The adapter's own use of the constants for the queries is checked as text, in
 * `AdapterStatementsTest`, and `AdapterBindingsTest` checks, as text, the statements the adapter's `save`,
 * `putTombstone` and query methods run. The order in which [save] and [putTombstone] bind their values is typed
 * out here again: `AdapterBindingsTest` checks the adapter's own order against the statements, and the
 * real-SQLite tests here would show a twin that drifted from the statements, but the twin itself is not
 * compared with the adapter.
 *
 * **Modelled, and not checked against Android.** How `SQLiteDatabase.delete` assembles
 * `DELETE FROM table WHERE where` (in [DesktopSqlite.delete]); the transaction, which switches autocommit off
 * and on where the adapter uses `beginTransaction` and `endTransaction`; and the row mapping, which reads the
 * driver's values and not an Android `Cursor`. The binding of the delete arguments (the id, and the cutoff as
 * text) is written here, and only the text of the adapter's argument array is pinned.
 */
internal class JdbcHistoryDatabase(private val sqlite: DesktopSqlite) : HistoryDatabase {

    override fun save(row: TranscriptionRow) {
        sqlite.exec(
            HistorySql.INSERT_OR_REPLACE,
            row.id, row.text, row.source, row.model, row.durationMs, row.createdAt, row.audioPath,
        )
    }

    override fun newest(limit: Int): List<TranscriptionRow> =
        sqlite.query(HistorySql.SELECT_NEWEST, limit.toString()).map { row ->
            TranscriptionRow(
                id = row[0] as String,
                text = row[1] as String,
                source = row[2] as String,
                model = row[3] as String,
                durationMs = row[4] as Long,
                createdAt = row[5] as Long,
                audioPath = row[6] as String?,
            )
        }

    override fun deleteById(id: String): Boolean {
        val statement = AdapterStatements.deleteById
        return sqlite.delete(statement.table, statement.where, id) > 0
    }

    override fun idsCreatedBefore(cutoff: Long): List<String> =
        sqlite.firstColumn(HistorySql.SELECT_IDS_CREATED_BEFORE, cutoff.toString()).map { it as String }

    override fun deleteCreatedBefore(cutoff: Long): Int {
        val statement = AdapterStatements.deleteCreatedBefore
        return sqlite.delete(statement.table, statement.where, cutoff.toString())
    }

    override fun putTombstone(tombstone: Tombstone) {
        sqlite.exec(
            HistorySql.INSERT_OR_REPLACE_TOMBSTONE,
            tombstone.id, tombstone.deletedAt, tombstone.reason.stored,
        )
    }

    override fun newestTombstones(limit: Int): List<Tombstone> =
        sqlite.query(HistorySql.SELECT_NEWEST_TOMBSTONES, limit.toString()).map { row ->
            Tombstone(
                id = row[0] as String,
                deletedAt = row[1] as Long,
                reason = Tombstone.Reason.fromStored(row[2] as String)
                    ?: throw MappingFailure("Tombstone ${row[0]} has an unknown reason '${row[2]}'"),
            )
        }

    override fun deleteTombstonesRecordedBefore(cutoff: Long): Int {
        val statement = AdapterStatements.deleteTombstonesRecordedBefore
        return sqlite.delete(statement.table, statement.where, cutoff.toString())
    }

    override fun countTranscriptions(): Int =
        (sqlite.firstColumn(HistorySql.COUNT_TRANSCRIPTIONS).single() as Long).toInt()

    override fun <T> transaction(block: () -> T): T {
        val connection = sqlite.connection
        if (!connection.autoCommit) return block() // nesting joins the outer transaction
        connection.autoCommit = false
        try {
            val result = block()
            connection.commit()
            return result
        } catch (failure: Throwable) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = true
        }
    }

    override fun close() = sqlite.close()
}
