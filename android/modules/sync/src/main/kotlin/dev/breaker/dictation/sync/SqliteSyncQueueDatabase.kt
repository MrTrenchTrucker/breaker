package dev.breaker.dictation.sync

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * [SyncQueueDatabase] on the phone's own SQLite.
 *
 * **The file is private to the app.** The [SQLiteOpenHelper] constructor used is
 * the one that takes no explicit mode, so the framework creates the file in the
 * app's own data directory and no other app can read it.
 *
 * **The statements come from [SyncQueueSql] and nowhere else.** There is no SQL
 * text in this file.
 *
 * **A row is read as it is stored.** The source is returned as text, so a read
 * never throws because of the source. The queue decides what to do with a row it
 * cannot send.
 *
 * Only this file knows about Android. The rules the queue follows live in
 * [QueuedSyncService].
 */
internal class SqliteSyncQueueDatabase(context: Context) : SyncQueueDatabase {

    private val helper = SyncQueueOpenHelper(context.applicationContext)

    override fun insertIfAbsent(
        clientId: String,
        text: String,
        source: String,
        model: String,
        durationMs: Long,
        createdAtMs: Long,
    ): Boolean =
        helper.writableDatabase.compileStatement(SyncQueueSql.INSERT_IF_ABSENT).use { statement ->
            statement.bindString(1, clientId)
            statement.bindString(2, text)
            statement.bindString(3, source)
            statement.bindString(4, model)
            statement.bindLong(5, durationMs)
            statement.bindLong(6, createdAtMs)
            // executeInsert returns -1 when OR IGNORE skipped the row.
            statement.executeInsert() != -1L
        }

    override fun head(): QueueEntry? = readOne(SyncQueueSql.SELECT_HEAD, emptyArray())

    override fun nextAfter(seq: Long): QueueEntry? =
        readOne(SyncQueueSql.SELECT_NEXT_AFTER, arrayOf(seq.toString()))

    override fun tailSeq(): Long =
        helper.readableDatabase.rawQuery(SyncQueueSql.SELECT_TAIL_SEQ, emptyArray()).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }

    override fun remove(clientId: String): Boolean =
        helper.writableDatabase.compileStatement(SyncQueueSql.DELETE_BY_CLIENT_ID).use { statement ->
            statement.bindString(1, clientId)
            statement.executeUpdateDelete() > 0
        }

    override fun count(): Int =
        helper.readableDatabase.rawQuery(SyncQueueSql.COUNT_QUEUE, emptyArray()).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
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
            // a block that threw leaves no change behind.
            database.endTransaction()
        }
    }

    override fun close() = helper.close()

    private fun readOne(sql: String, args: Array<String>): QueueEntry? =
        helper.readableDatabase.rawQuery(sql, args).use { cursor ->
            if (cursor.moveToFirst()) readEntry(cursor) else null
        }

    private fun readEntry(cursor: Cursor): QueueEntry =
        QueueEntry(
            seq = cursor.getLong(0),
            clientId = cursor.getString(1),
            text = cursor.getString(2),
            source = cursor.getString(3),
            model = cursor.getString(4),
            durationMs = cursor.getLong(5),
            createdAtMs = cursor.getLong(6),
        )

    private class SyncQueueOpenHelper(context: Context) :
        SQLiteOpenHelper(context, SyncQueueSql.DATABASE_NAME, null, SyncQueueSql.SCHEMA_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            SyncQueueSql.CREATE_ALL.forEach(db::execSQL)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Version 1 is the first version, so there is no earlier shape to
            // migrate from. The queue table is never dropped here: it holds
            // transcriptions that have not been sent yet.
        }
    }
}
