package dev.breaker.dictation.sync

import java.io.File
import java.sql.DriverManager
import java.util.ArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** S9: a fresh store (onCreate) and a migrated store (v3->v4 appends `kind`) hold the same
 * columns as a set - order may differ - and onUpgrade really runs for 1->4, 2->4 and 3->4,
 * backfilling every existing row as 'save'. The statements are run on the desktop SQLite
 * twin and read back through PRAGMA; nothing is pinned to SQL text. */
class MigrationColumnsTest {

    private var file: File? = null
    private val opened = ArrayList<AutoCloseable>()
    private val files = ArrayList<File>()

    @Before
    fun createFile() {
        file = File.createTempFile("android-sync-s9-columns", ".db")
    }

    @After
    fun closeAndDeleteFile() {
        try {
            opened.forEach { it.close() }
        } finally {
            for (f in files) {
                val path = f.absolutePath
                for (suffix in listOf("", "-journal", "-wal", "-shm")) {
                    File(path + suffix).delete()
                }
            }
            val path = file?.path
            if (path != null) {
                for (suffix in listOf("", "-journal", "-wal", "-shm")) {
                    File(path + suffix).delete()
                }
            }
        }
    }

    /** Column names of the queue table on the open store, as a set. */
    private fun columnNames(s: DesktopSqlite): Set<String> =
        s.query("PRAGMA table_info(" + SyncQueueSql.TABLE_QUEUE + ")").map { it[1] as String }.toSet()

    /** A fresh store at schema version 4 straight from SyncQueueSql.CREATE_ALL. */
    private fun freshStore(): DesktopSqlite {
        val sqlite = DesktopSqlite(file)
        opened.add(sqlite)
        return sqlite
    }

    /** A store created at schema version [from] (1, 2 or 3), three rows queued, then migrated to 4. */
    private fun migratedStore(from: Int): File {
        val f = File.createTempFile("android-sync-s9-migrated-$from", ".db")
        files.add(f)
        val c = DriverManager.getConnection("jdbc:sqlite:" + f.absolutePath)
        opened.add(c)
        when (from) {
            1 -> c.createStatement().use { statement ->
                statement.execute("CREATE TABLE IF NOT EXISTS " + SyncQueueSql.TABLE_QUEUE + " (" +
                    "seq INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "client_id TEXT NOT NULL UNIQUE, " +
                    "text TEXT NOT NULL, " +
                    "source TEXT NOT NULL, " +
                    "model TEXT NOT NULL, " +
                    "duration_ms INTEGER NOT NULL, " +
                    "created_at INTEGER NOT NULL)")
            }
            else -> c.createStatement().use { statement ->
                statement.execute("CREATE TABLE IF NOT EXISTS " + SyncQueueSql.TABLE_QUEUE + " (" +
                    "seq INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "client_id TEXT NOT NULL UNIQUE, " +
                    "text TEXT NOT NULL, " +
                    "source TEXT NOT NULL, " +
                    "model TEXT NOT NULL, " +
                    "duration_ms INTEGER NOT NULL, " +
                    "created_at INTEGER NOT NULL, " +
                    "parked_reason TEXT)")
            }
        }
        insertRow(c, clientId(1), "first text", "local", "small.en", 1_200L, 100L)
        insertRow(c, clientId(2), "second text", "server", "large-v3", 4_500L, 200L)
        insertRow(c, clientId(3), "third text", "local", "small.en", 1_200L, 300L)
        if (from >= 2) {
            c.prepareStatement("UPDATE " + SyncQueueSql.TABLE_QUEUE + " SET parked_reason = ? WHERE client_id = ?").use { statement ->
                statement.setString(1, "refused")
                statement.setString(2, clientId(2))
                statement.executeUpdate()
            }
        }
        when (from) {
            1 -> {
                c.prepareStatement(SyncQueueSql.MIGRATE_V1_TO_V2).executeUpdate()
                c.prepareStatement(SyncQueueSql.MIGRATE_V2_TO_V3).executeUpdate()
                c.prepareStatement(SyncQueueSql.MIGRATE_V3_TO_V4).executeUpdate()
            }
            2 -> {
                c.prepareStatement(SyncQueueSql.MIGRATE_V2_TO_V3).executeUpdate()
                c.prepareStatement(SyncQueueSql.MIGRATE_V3_TO_V4).executeUpdate()
            }
            3 -> {
                c.prepareStatement(SyncQueueSql.MIGRATE_V3_TO_V4).executeUpdate()
            }
        }
        c.close()
        return f
    }

    private fun insertRow(c: java.sql.Connection, id: String, text: String, source: String, model: String, durationMs: Long, createdAtMs: Long) {
        c.prepareStatement(
            "INSERT INTO " + SyncQueueSql.TABLE_QUEUE +
                " (client_id, text, source, model, duration_ms, created_at) VALUES (?, ?, ?, ?, ?, ?)"
        ).use { statement ->
            statement.setString(1, id)
            statement.setString(2, text)
            statement.setString(3, source)
            statement.setString(4, model)
            statement.setLong(5, durationMs)
            statement.setLong(6, createdAtMs)
            statement.executeUpdate()
        }
    }

    /** A failure here means the migration appended `kind` without the fresh store's column set matching it as a set. */
    @Test
    fun `a fresh store and a migrated store hold the same column set`() {
        val fresh = freshStore()
        val f = migratedStore(1)
        val migrated = DesktopSqlite(f)
        opened.add(migrated)

        assertEquals(
            "android_sync: fresh and migrated hold the same columns as a set, order may differ",
            columnNames(fresh),
            columnNames(migrated),
        )
        assertEquals("android_sync: the migrated row is backfilled as save", "save", JdbcSyncQueueDatabase(migrated).head()!!.kind)
        fresh.close()
        migrated.close()
    }

    /** onUpgrade runs 1->4, 2->4 and 3->4 and backfills kind as save. */
    @Test
    fun `onUpgrade runs 1 to 4, 2 to 4 and 3 to 4 backfills kind as save`() {
        for (from in listOf(1, 2, 3)) {
            val f = migratedStore(from)
            val sqlite = DesktopSqlite(f)
            opened.add(sqlite)
            val db = JdbcSyncQueueDatabase(sqlite)

            assertTrue("android_sync: $from->4 added the kind column", "kind" in columnNames(sqlite))
            val kinds = sqlite.query("SELECT kind FROM " + SyncQueueSql.TABLE_QUEUE + " ORDER BY seq").map { it[0] as String }
            assertEquals(
                "android_sync: every row migrated by $from->4 is backfilled as save",
                listOf("save", "save", "save"),
                kinds,
            )
            assertEquals("android_sync: $from->4 kept all three rows", 3, db.queuedIds().size)
            sqlite.close()
        }
    }

    /** The queue table at schema version 1: no parked_reason column, no kind column. */
    private fun createQueueV1(c: java.sql.Connection) {
        c.createStatement().use { statement ->
            statement.execute(
                "CREATE TABLE IF NOT EXISTS " + SyncQueueSql.TABLE_QUEUE + " (" +
                    "seq INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "client_id TEXT NOT NULL UNIQUE, " +
                    "text TEXT NOT NULL, " +
                    "source TEXT NOT NULL, " +
                    "model TEXT NOT NULL, " +
                    "duration_ms INTEGER NOT NULL, " +
                    "created_at INTEGER NOT NULL" +
                ")")
        }
    }

    /** The queue table at schema version 2 or 3: parked_reason column, no kind column. */
    private fun createQueueV2(c: java.sql.Connection) {
        c.createStatement().use { statement ->
            statement.execute(
                "CREATE TABLE IF NOT EXISTS " + SyncQueueSql.TABLE_QUEUE + " (" +
                    "seq INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "client_id TEXT NOT NULL UNIQUE, " +
                    "text TEXT NOT NULL, " +
                    "source TEXT NOT NULL, " +
                    "model TEXT NOT NULL, " +
                    "duration_ms INTEGER NOT NULL, " +
                    "created_at INTEGER NOT NULL, " +
                    "parked_reason TEXT" +
                ")")
        }
    }

    /** The names of the tables in the open store. */
    private fun tableNames(s: DesktopSqlite): Set<String> =
        s.query("SELECT name FROM sqlite_master WHERE type = 'table'").map { it[0] as String }.toSet()

    /** Drives the real onUpgrade dispatch (runOnUpgrade), not the MIGRATE_* text: 1->4, 2->4 and
     * 3->4 all backfill kind as save and keep every row. */
    @Test
    fun `onUpgrade really runs 1 to 4, 2 to 4 and 3 to 4, backfilling kind as save`() {
        for (from in listOf(1, 2, 3)) {
            val f = File.createTempFile("android-sync-s9-upgrade-$from", ".db")
            files.add(f)
            val c = DriverManager.getConnection("jdbc:sqlite:" + f.absolutePath)
            opened.add(c)
            when (from) {
                1 -> createQueueV1(c)
                else -> createQueueV2(c)
            }
            insertRow(c, clientId(1), "first text", "local", "small.en", 1_200, 100)
            insertRow(c, clientId(2), "second text", "server", "large-v3", 4_500, 200)
            insertRow(c, clientId(3), "third text", "local", "small.en", 1_200, 300)
            if (from >= 2) {
                c.prepareStatement("UPDATE " + SyncQueueSql.TABLE_QUEUE + " SET parked_reason = ? WHERE client_id = ?").use { statement ->
                    statement.setString(1, "refused")
                    statement.setString(2, clientId(2))
                    statement.executeUpdate()
                }
            }
            c.close()
            val sqlite = DesktopSqlite(f)
            opened.add(sqlite)
            runOnUpgrade({ sqlite.exec(it) }, from, 4)

            assertTrue("android_sync: $from->4 added the kind column", "kind" in columnNames(sqlite))
            val kinds = sqlite.query("SELECT kind FROM " + SyncQueueSql.TABLE_QUEUE + " ORDER BY seq").map { it[0] as String }
            assertEquals(
                "android_sync: $from->4 backfills every row as save",
                listOf("save", "save", "save"),
                kinds,
            )
            assertEquals("android_sync: $from->4 kept all three rows", 3, JdbcSyncQueueDatabase(sqlite).queuedIds().size)
            sqlite.close()
        }
    }

    /** Each single step of the real dispatch: 1->2 adds parked_reason, 2->3 adds the cursor table,
     * 3->4 adds the kind column and backfills it as save. */
    @Test
    fun `onUpgrade single steps 1 to 2, 2 to 3 and 3 to 4`() {
        val f = File.createTempFile("android-sync-s9-upgrade-steps", ".db")
        files.add(f)
        val c = DriverManager.getConnection("jdbc:sqlite:" + f.absolutePath)
        opened.add(c)
        createQueueV1(c)
        insertRow(c, clientId(1), "first text", "local", "small.en", 1_200, 100)
        c.close()

        val sqlite = DesktopSqlite(f)
        opened.add(sqlite)

        runOnUpgrade({ sqlite.exec(it) }, 1, 2)
        assertTrue("android_sync: 1->2 added parked_reason", "parked_reason" in columnNames(sqlite))
        assertFalse("android_sync: 1->2 did not add kind", "kind" in columnNames(sqlite))

        runOnUpgrade({ sqlite.exec(it) }, 2, 3)
        assertTrue("android_sync: 2->3 created the cursor table", "sync_pull_cursor" in tableNames(sqlite))

        runOnUpgrade({ sqlite.exec(it) }, 3, 4)
        assertTrue("android_sync: 3->4 added the kind column", "kind" in columnNames(sqlite))
        val kinds = sqlite.query("SELECT kind FROM " + SyncQueueSql.TABLE_QUEUE + " ORDER BY seq").map { it[0] as String }
        assertEquals("android_sync: 3->4 backfills the row as save", listOf("save"), kinds)
        assertEquals("android_sync: 3->4 kept the row", 1, JdbcSyncQueueDatabase(sqlite).queuedIds().size)

        sqlite.close()
    }
}
