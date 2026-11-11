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
 * text in this file; the migration statement is [SyncQueueSql.MIGRATE_V1_TO_V2].
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
        kind: String,
    ): Boolean =
        helper.writableDatabase.compileStatement(SyncQueueSql.INSERT_IF_ABSENT).use { statement ->
            statement.bindString(1, clientId)
            statement.bindString(2, text)
            statement.bindString(3, source)
            statement.bindString(4, model)
            statement.bindLong(5, durationMs)
            statement.bindLong(6, createdAtMs)
            statement.bindString(7, kind)
            // executeInsert returns -1 when OR IGNORE skipped the row.
            statement.executeInsert() != -1L
        }

    override fun insertDeleteIfAbsent(clientId: String): Boolean =
        helper.writableDatabase.compileStatement(SyncQueueSql.INSERT_IF_ABSENT).use { statement ->
            statement.bindString(1, clientId)
            statement.bindString(2, "")
            statement.bindString(3, "local")
            statement.bindString(4, "")
            statement.bindLong(5, 0L)
            statement.bindLong(6, 0L)
            statement.bindString(7, SyncQueueSql.KIND_DELETE)
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

    override fun removeSeq(seq: Long): Boolean =
        helper.writableDatabase.compileStatement(SyncQueueSql.DELETE_BY_SEQ).use { statement ->
            statement.bindString(1, seq.toString())
            statement.executeUpdateDelete() > 0
        }

    override fun count(): Int =
        helper.readableDatabase.rawQuery(SyncQueueSql.COUNT_QUEUE, emptyArray()).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    override fun parkedCount(): Int =
        helper.readableDatabase.rawQuery(SyncQueueSql.COUNT_PARKED, emptyArray()).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    override fun parkedReason(clientId: String): String? =
        helper.readableDatabase.rawQuery(SyncQueueSql.PARKED_REASON, arrayOf(clientId)).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    override fun isQueued(clientId: String): Boolean =
        helper.readableDatabase.rawQuery(SyncQueueSql.IS_QUEUED, arrayOf(clientId)).use { cursor ->
            cursor.moveToFirst()
        }

    override fun queuedIds(): List<String> =
        helper.readableDatabase.rawQuery(SyncQueueSql.SELECT_QUEUED_IDS, emptyArray()).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getString(0))
                }
            }
        }

    override fun parkRow(clientId: String, reason: String): Boolean =
        helper.writableDatabase.compileStatement(SyncQueueSql.PARK_ROW).use { statement ->
            statement.bindString(1, reason)
            statement.bindString(2, clientId)
            statement.executeUpdateDelete() > 0
        }

    override fun resaveRow(
        clientId: String,
        text: String,
        source: String,
        model: String,
        durationMs: Long,
        createdAtMs: Long,
    ): Boolean = helper.writableDatabase.compileStatement(SyncQueueSql.RESAVE_ROW).use { statement ->
            statement.bindString(1, text)
            statement.bindString(2, source)
            statement.bindString(3, model)
            statement.bindLong(4, durationMs)
            statement.bindLong(5, createdAtMs)
            statement.bindString(6, clientId)
            statement.executeUpdateDelete() > 0
        }

    override fun replaceQueuedDeleteWithSave(
        clientId: String,
        text: String,
        source: String,
        model: String,
        durationMs: Long,
        createdAtMs: Long,
    ): Boolean = helper.writableDatabase.compileStatement(SyncQueueSql.REPLACE_QUEUED_DELETE_WITH_SAVE).use { statement ->
        statement.bindString(1, text)
        statement.bindString(2, source)
        statement.bindString(3, model)
        statement.bindLong(4, durationMs)
        statement.bindLong(5, createdAtMs)
        statement.bindString(6, clientId)
        statement.executeUpdateDelete() > 0
    }

    override fun isDeleteQueued(clientId: String): Boolean =
        helper.readableDatabase.rawQuery(SyncQueueSql.IS_DELETE_QUEUED, arrayOf(clientId)).use { cursor ->
            cursor.moveToFirst()
        }

    override fun pullCursor(): String? =
        helper.readableDatabase.rawQuery(SyncQueueSql.SELECT_PULL_CURSOR, emptyArray()).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    override fun savePullCursor(cursor: String) {
        helper.writableDatabase.compileStatement(SyncQueueSql.SAVE_PULL_CURSOR).use { statement ->
            statement.bindString(1, cursor)
            statement.executeInsert()
        }
    }

    override fun clearPullCursor() {
        helper.writableDatabase.execSQL(SyncQueueSql.CLEAR_PULL_CURSOR)
    }

    override fun clearAll() =
        helper.writableDatabase.execSQL(SyncQueueSql.DELETE_ALL_QUEUE)

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
            kind = cursor.getString(7),
        )

    private class SyncQueueOpenHelper(context: Context) :
        SQLiteOpenHelper(context, SyncQueueSql.DATABASE_NAME, null, SyncQueueSql.SCHEMA_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            SyncQueueSql.CREATE_ALL.forEach(db::execSQL)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // One code path: the shipped onUpgrade delegates to the migration dispatch that the
            // JVM tests drive through the twin (runOnUpgrade). The step logic lives there, not in
            // this file, so a JVM test exercises exactly the code that ships.
            runOnUpgrade(db::execSQL, oldVersion, newVersion)
        }
    }
}
