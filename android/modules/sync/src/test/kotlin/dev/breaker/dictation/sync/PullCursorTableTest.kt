package dev.breaker.dictation.sync

import java.io.File
import java.sql.SQLException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The cursor table itself, on the desktop SQLite: its shape, its one-row CHECK, its lack of
 * AUTOINCREMENT, and the fact that saving a cursor adds no sequence row. The queue table keeps its
 * counter and nothing else: the sqlite_sequence check keeps the existing `SyncQueueSqlTest` green,
 * and this test says so on purpose.
 */
class PullCursorTableTest {

    private var file: File? = null
    private var sqlite: DesktopSqlite? = null

    @Before
    fun createFile() {
        file = File.createTempFile("android-sync-cursor-table", ".db")
    }

    @After
    fun closeAndDeleteFile() {
        try {
            sqlite?.close()
        } finally {
            val path = file?.path
            if (path != null) {
                for (suffix in listOf("", "-journal", "-wal", "-shm")) {
                    File(path + suffix).delete()
                }
            }
        }
    }

    /** Opens the temporary file once, for every test. */
    private fun open(): DesktopSqlite {
        val s = DesktopSqlite(file)
        sqlite = s
        return s
    }

    /** A failure here means the cursor table is missing, renamed, or its columns changed. */
    @Test
    fun `the store holds exactly one cursor table with the right columns`() {
        val s = open()
        val master = s.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?", SyncQueueSql.TABLE_PULL_CURSOR)
        assertEquals("android_sync: sqlite_master must hold exactly one cursor table", 1, master.size)
        val columns = s.query("PRAGMA table_info(" + SyncQueueSql.TABLE_PULL_CURSOR + ")")
        assertEquals("android_sync: the cursor columns in order", listOf("id", "next_cursor"), columns.map { it[1] })
        assertEquals("android_sync: the cursor column types in order", listOf("INTEGER", "TEXT"), columns.map { it[2] })
        assertEquals("android_sync: only id is the primary key", listOf(1L, 0L), columns.map { it[5] })
        assertEquals("android_sync: the cursor text is NOT NULL", 1L, columns.last()[3])
    }

    /** A failure here means the CHECK is gone, so a second row can exist and the read can return the wrong one. */
    @Test
    fun `a row with an id other than one is refused by the check`() {
        val s = open()
        val db = JdbcSyncQueueDatabase(s)
        db.savePullCursor("one")
        val failed = thrownBy {
            s.exec("INSERT INTO " + SyncQueueSql.TABLE_PULL_CURSOR + " (id, next_cursor) VALUES (2, 'other')")
        }
        assertTrue("android_sync: a raw insert of id 2 must be refused by the CHECK", failed is SQLException)
        val rows = s.query("SELECT COUNT(*) FROM " + SyncQueueSql.TABLE_PULL_CURSOR)
        assertEquals("android_sync: the table must still hold one row", 1L, rows.single()[0] as Long)
        assertEquals("android_sync: the read must still return the stored text", "one", db.pullCursor())
    }

    /** Control for the CHECK test: the id 1 insert the save statement makes must be accepted. */
    @Test
    fun `a row with id one is accepted by the same insert`() {
        val s = open()
        val accepted = s.exec("INSERT OR REPLACE INTO " + SyncQueueSql.TABLE_PULL_CURSOR + " (id, next_cursor) VALUES (1, 'control')")
        assertEquals("android_sync: the id 1 insert must change one row", 1, accepted)
        assertEquals("android_sync: the read must return the control text", "control", JdbcSyncQueueDatabase(s).pullCursor())
    }

    /** A failure here means the cursor table grew an index of its own. */
    @Test
    fun `the cursor table holds no index of its own`() {
        val s = open()
        val indexes = s.query("PRAGMA index_list(" + SyncQueueSql.TABLE_PULL_CURSOR + ")")
        assertEquals("android_sync: the cursor table must have no indexes", 0, indexes.size)
    }

    /** A failure here means the cursor row took a seq, and the queue's sequence test would go red. */
    @Test
    fun `saving a cursor adds no sequence row for the cursor table`() {
        val s = open()
        val db = JdbcSyncQueueDatabase(s)
        db.insertIfAbsent("queued", "text for queued", "local", "small.en", 1_200L, 100L)
        db.savePullCursor("the-cursor")
        db.savePullCursor("the-next-cursor")
        val counters = s.query("SELECT name, seq FROM sqlite_sequence")
        assertEquals(
            "android_sync: sqlite_sequence must hold only the queue counter after a save",
            listOf(listOf<Any?>(SyncQueueSql.TABLE_QUEUE, 1L)),
            counters,
        )
        assertEquals("android_sync: the cursor read is unaffected", "the-next-cursor", db.pullCursor())
    }
}
