package dev.breaker.dictation.history

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * [HistoryDatabase] on the phone's own SQLite.
 *
 * Three things are deliberate here.
 *
 * **The file lives in the app-private directory.** The name is checked by
 * [AppPrivateStorage.databaseFileName] before it is handed to the platform, so
 * it is a bare file name and cannot be a path out of the sandbox. The file
 * itself is created by the framework inside the app's own data directory, which
 * is `0700` to the app's uid, and the database is opened through that private
 * path only: no code here changes the file's mode, exports it, or hands it a
 * URI another app can read. The [SQLiteOpenHelper] constructor used is the one
 * that takes no explicit mode, which is the private mode.
 *
 * **The statements come from [HistorySql] and nowhere else.** Every `WHERE`
 * clause that touches the retention boundary is a constant in that file, so the
 * boundary can be read, and broken on purpose, from a plain JVM test.
 *
 * Only this file knows about Android. The rules it carries out live in
 * [SqliteHistoryStore], which is where they are tested.
 */
internal class SqliteHistoryDatabase(
    context: Context,
    databaseName: String = AppPrivateStorage.DEFAULT_DATABASE_NAME,
) : HistoryDatabase {

    // Checked here rather than at the default, so a caller that supplies its
    // own name cannot point the history database outside the app's own
    // directory.
    private val helper = HistoryOpenHelper(
        context.applicationContext,
        AppPrivateStorage.databaseFileName(databaseName),
    )

    override fun save(row: TranscriptionRow) {
        helper.writableDatabase.execSQL(
            HistorySql.INSERT_OR_REPLACE,
            arrayOf<Any?>(
                row.id,
                row.text,
                row.source,
                row.model,
                row.durationMs,
                row.createdAt,
                row.audioPath,
            ),
        )
    }

    override fun newest(limit: Int): List<TranscriptionRow> =
        helper.readableDatabase.rawQuery(HistorySql.SELECT_NEWEST, arrayOf(limit.toString())).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.toTranscriptionRow())
                }
            }
        }

    override fun deleteById(id: String): Boolean {
        val database = helper.writableDatabase
        // execSQL cannot report a row count, and "was a row actually removed" is
        // the whole answer the caller asked for: a delete for an id this device
        // never held must not claim to have deleted something.
        val affected = database.delete(
            HistorySql.TABLE_TRANSCRIPTIONS,
            "id = ?",
            arrayOf<String?>(id),
        )
        return affected > 0
    }

    override fun idsCreatedBefore(cutoff: Long): List<String> =
        helper.readableDatabase
            .rawQuery(HistorySql.SELECT_IDS_CREATED_BEFORE, arrayOf(cutoff.toString()))
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(cursor.getString(0))
                    }
                }
            }

    override fun deleteCreatedBefore(cutoff: Long): Int =
        helper.writableDatabase.delete(
            HistorySql.TABLE_TRANSCRIPTIONS,
            // The predicate is the tail of HistorySql.SELECT_IDS_CREATED_BEFORE,
            // taken from the same constant, so the rows selected for a tombstone
            // and the rows actually deleted can never be different sets.
            HistorySql.CREATED_AT_STRICTLY_BEFORE_WHERE,
            arrayOf<String?>(cutoff.toString()),
        )

    override fun putTombstone(tombstone: Tombstone) {
        helper.writableDatabase.execSQL(
            HistorySql.INSERT_OR_REPLACE_TOMBSTONE,
            arrayOf<Any?>(tombstone.id, tombstone.deletedAt, tombstone.reason.stored),
        )
    }

    override fun newestTombstones(limit: Int): List<Tombstone> =
        helper.readableDatabase
            .rawQuery(HistorySql.SELECT_NEWEST_TOMBSTONES, arrayOf(limit.toString()))
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(0)
                        val deletedAt = cursor.getLong(1)
                        val stored = cursor.getString(2)
                        val reason = Tombstone.Reason.fromStored(stored)
                        if (reason == null) {
                            throw MappingFailure("Tombstone $id has an unknown reason '$stored'")
                        }
                        add(Tombstone(id = id, deletedAt = deletedAt, reason = reason))
                    }
                }
            }

    override fun deleteTombstonesRecordedBefore(cutoff: Long): Int =
        helper.writableDatabase.delete(
            HistorySql.TABLE_TOMBSTONES,
            HistorySql.DELETED_AT_STRICTLY_BEFORE_WHERE,
            arrayOf<String?>(cutoff.toString()),
        )

    override fun countTranscriptions(): Int =
        helper.readableDatabase.rawQuery(HistorySql.COUNT_TRANSCRIPTIONS, emptyArray()).use { cursor ->
            if (cursor.moveToNext()) cursor.getInt(0) else 0
        }

    override fun <T> transaction(block: () -> T): T {
        val database = helper.writableDatabase
        database.beginTransaction()
        try {
            val result = block()
            database.setTransactionSuccessful()
            return result
        } finally {
            // endTransaction() without setTransactionSuccessful() rolls back, so
            // a delete that threw half way through cannot leave the row removed
            // and the tombstone unwritten.
            database.endTransaction()
        }
    }

    override fun close() = helper.close()

    private class HistoryOpenHelper(context: Context, name: String) :
        SQLiteOpenHelper(context, name, null, HistorySql.SCHEMA_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            HistorySql.CREATE_ALL.forEach(db::execSQL)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Version 1 is the first version, so there is no earlier shape to
            // migrate from. When one arrives it is written here, and the tables
            // are NOT dropped: dropping them would delete every transcription
            // the user has, which is never a migration strategy for a history
            // table.
        }
    }
}

private fun android.database.Cursor.toTranscriptionRow(): TranscriptionRow = TranscriptionRow(
    id = getString(0),
    text = getString(1),
    source = getString(2),
    model = getString(3),
    durationMs = getLong(4),
    createdAt = getLong(5),
    audioPath = if (isNull(6)) null else getString(6),
)
