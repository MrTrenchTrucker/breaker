package dev.breaker.dictation.sync

import java.io.File
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The v4 to v5 migration on a real database: queued rows and the pull cursor survive, pending_save is added empty
 * with the run_token column beside created_at (MIGRATE_V4_TO_V5 is still CREATE_PENDING_SAVE: no extra step). */
class PendingSaveMigrationTest {

    private var file: File? = null
    private val opened = ArrayList<AutoCloseable>()
    private val files = ArrayList<File>()

    @Before
    fun createFile() {
        file = File.createTempFile("android-sync-s2a-migrate-v5", ".db")
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

    /** The queue table at schema version 4 (kind column present, parked_reason present). */
    private fun createQueueV4(c: java.sql.Connection) {
        c.createStatement().use { statement ->
            statement.execute(
                "CREATE TABLE IF NOT EXISTS ${SyncQueueSql.TABLE_QUEUE} (" +
                    "seq INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "client_id TEXT NOT NULL UNIQUE, " +
                    "text TEXT NOT NULL, " +
                    "source TEXT NOT NULL, " +
                    "model TEXT NOT NULL, " +
                    "duration_ms INTEGER NOT NULL, " +
                    "created_at INTEGER NOT NULL, " +
                    "kind TEXT NOT NULL DEFAULT 'save', " +
                    "parked_reason TEXT" +
                ")"
            )
            statement.execute(SyncQueueSql.CREATE_PULL_CURSOR)
        }
    }

    private fun insertRow(c: java.sql.Connection, id: String, text: String, source: String, model: String, durationMs: Long, createdAtMs: Long) {
        c.prepareStatement(
            "INSERT INTO ${SyncQueueSql.TABLE_QUEUE} (client_id, text, source, model, duration_ms, created_at) VALUES (?, ?, ?, ?, ?, ?)"
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

    /** The names of the tables in the open store. */
    private fun tableNames(s: DesktopSqlite): Set<String> =
        s.query("SELECT name FROM sqlite_master WHERE type = 'table'").map { it[0] as String }.toSet()

    /** The column names of a table in the open store. */
    private fun columnNames(s: DesktopSqlite, table: String): Set<String> =
        s.query("PRAGMA table_info($table)").map { it[1] as String }.toSet()

    /** A v4 store with two queued rows and a pull cursor, migrated to v5 through the real dispatch. */
    @Test
    fun `v4 to v5 keeps queue rows and the pull cursor and adds empty pending_save`() {
        val f = File.createTempFile("android-sync-s2a-upgrade-v5", ".db")
        files.add(f)
        val c = DriverManager.getConnection("jdbc:sqlite:" + f.absolutePath)
        opened.add(c)
        createQueueV4(c)
        insertRow(c, clientId(1), "first text", "local", "small.en", 1_200L, 100L)
        insertRow(c, clientId(2), "second text", "server", "large-v3", 4_500L, 200L)
        c.prepareStatement(SyncQueueSql.SAVE_PULL_CURSOR).use { statement ->
            statement.setString(1, "cursor-token")
            statement.executeUpdate()
        }
        c.close()

        val sqlite = DesktopSqlite(f)
        opened.add(sqlite)
        runOnUpgrade({ sqlite.exec(it) }, 4, 5)

        assertTrue("android_sync: 4->5 added the pending_save table", "pending_save" in tableNames(sqlite))
        assertTrue("android_sync: 4->5 pending_save has client_id, created_at and run_token",
            setOf("client_id", "created_at", "run_token").all { it in columnNames(sqlite, "pending_save") })
        val migrated = JdbcSyncQueueDatabase(sqlite)
        assertEquals("android_sync: 4->5 kept both queue rows", 2, migrated.queuedIds().size)
        assertEquals("android_sync: 4->5 kept the pull cursor", "cursor-token", migrated.pullCursor())
        assertEquals("android_sync: 4->5 pending_save is empty", 0, migrated.pendingSaveIds(1000, null).size)
        sqlite.close()
    }

    /** The queue table at schema version 1 (no parked_reason, no kind). */
    private fun createQueueV1(c: java.sql.Connection) {
        c.createStatement().use { statement -> statement.execute(
            "CREATE TABLE IF NOT EXISTS ${SyncQueueSql.TABLE_QUEUE} (" +
                "seq INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "client_id TEXT NOT NULL UNIQUE, " +
                "text TEXT NOT NULL, " +
                "source TEXT NOT NULL, " +
                "model TEXT NOT NULL, " +
                "duration_ms INTEGER NOT NULL, " +
                "created_at INTEGER NOT NULL" +
            ")"
        )}
    }

    /** The queue table at schema version 2 or 3 (parked_reason present, no kind). */
    private fun createQueueV2(c: java.sql.Connection) {
        c.createStatement().use { statement -> statement.execute(
            "CREATE TABLE IF NOT EXISTS ${SyncQueueSql.TABLE_QUEUE} (" +
                "seq INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "client_id TEXT NOT NULL UNIQUE, " +
                "text TEXT NOT NULL, " +
                "source TEXT NOT NULL, " +
                "model TEXT NOT NULL, " +
                "duration_ms INTEGER NOT NULL, " +
                "created_at INTEGER NOT NULL, " +
                "parked_reason TEXT" +
            ")"
        )}
    }

    /** A v3 store migrated to v5 through the real dispatch reaches v5 with its queue rows intact and an empty pending_save. */
    @Test
    fun `v3 to v5 keeps queue rows and adds empty pending_save`() {
        val f = File.createTempFile("android-sync-s2a-upgrade-v3-to-v5", ".db")
        files.add(f)
        val c = DriverManager.getConnection("jdbc:sqlite:" + f.absolutePath)
        opened.add(c)
        createQueueV2(c)
        insertRow(c, clientId(1), "first text", "local", "small.en", 1_200L, 100L)
        insertRow(c, clientId(2), "second text", "server", "large-v3", 4_500L, 200L)
        c.close()

        val sqlite = DesktopSqlite(f)
        opened.add(sqlite)
        runOnUpgrade({ sqlite.exec(it) }, 3, 5)

        assertTrue("android_sync: 3->5 added the pending_save table", "pending_save" in tableNames(sqlite))
        assertTrue("android_sync: 3->5 pending_save has client_id, created_at and run_token",
            setOf("client_id", "created_at", "run_token").all { it in columnNames(sqlite, "pending_save") })
        val migrated = JdbcSyncQueueDatabase(sqlite)
        assertEquals("android_sync: 3->5 kept both queue rows", 2, migrated.queuedIds().size)
        assertEquals("android_sync: 3->5 pending_save is empty", 0, migrated.pendingSaveIds(1000, null).size)
        sqlite.close()
    }

    /** A v2 store migrated to v5 through the real dispatch reaches v5 with its queue rows intact and an empty pending_save. */
    @Test
    fun `v2 to v5 keeps queue rows and adds empty pending_save`() {
        val f = File.createTempFile("android-sync-s2a-upgrade-v2-to-v5", ".db")
        files.add(f)
        val c = DriverManager.getConnection("jdbc:sqlite:" + f.absolutePath)
        opened.add(c)
        createQueueV2(c)
        insertRow(c, clientId(1), "first text", "local", "small.en", 1_200L, 100L)
        insertRow(c, clientId(2), "second text", "server", "large-v3", 4_500L, 200L)
        c.close()

        val sqlite = DesktopSqlite(f)
        opened.add(sqlite)
        runOnUpgrade({ sqlite.exec(it) }, 2, 5)

        assertTrue("android_sync: 2->5 added the pending_save table", "pending_save" in tableNames(sqlite))
        assertTrue("android_sync: 2->5 pending_save has client_id, created_at and run_token",
            setOf("client_id", "created_at", "run_token").all { it in columnNames(sqlite, "pending_save") })
        val migrated = JdbcSyncQueueDatabase(sqlite)
        assertEquals("android_sync: 2->5 kept both queue rows", 2, migrated.queuedIds().size)
        assertEquals("android_sync: 2->5 pending_save is empty", 0, migrated.pendingSaveIds(1000, null).size)
        sqlite.close()
    }

    /** A v1 store migrated to v5 through the real dispatch reaches v5 with its queue rows intact and an empty pending_save. */
    @Test
    fun `v1 to v5 keeps queue rows and adds empty pending_save`() {
        val f = File.createTempFile("android-sync-s2a-upgrade-v1-to-v5", ".db")
        files.add(f)
        val c = DriverManager.getConnection("jdbc:sqlite:" + f.absolutePath)
        opened.add(c)
        createQueueV1(c)
        insertRow(c, clientId(1), "first text", "local", "small.en", 1_200L, 100L)
        insertRow(c, clientId(2), "second text", "server", "large-v3", 4_500L, 200L)
        c.close()

        val sqlite = DesktopSqlite(f)
        opened.add(sqlite)
        runOnUpgrade({ sqlite.exec(it) }, 1, 5)

        assertTrue("android_sync: 1->5 added the pending_save table", "pending_save" in tableNames(sqlite))
        assertTrue("android_sync: 1->5 pending_save has client_id, created_at and run_token",
            setOf("client_id", "created_at", "run_token").all { it in columnNames(sqlite, "pending_save") })
        val migrated = JdbcSyncQueueDatabase(sqlite)
        assertEquals("android_sync: 1->5 kept both queue rows", 2, migrated.queuedIds().size)
        assertEquals("android_sync: 1->5 pending_save is empty", 0, migrated.pendingSaveIds(1000, null).size)
        sqlite.close()
    }

    /** RAW-FILE proof (no DesktopSqlite, no JdbcSyncQueueDatabase): after runOnUpgrade(4,5) alone, a raw
     * JDBC connection sees pending_save in sqlite_master. DesktopSqlite.init runs CREATE_ALL, so opening
     * through it would create the table whether or not the migrator ran; this test never opens through it.
     * Kills the "4->5 branch does nothing" mutant on the real file, by assertion name. */
    @Test
    fun `v4 to v5 on the raw file creates pending_save before any twin touches it`() {
        val f = File.createTempFile("android-sync-s2a-raw-v4-to-v5", ".db")
        files.add(f)
        val setup = DriverManager.getConnection("jdbc:sqlite:" + f.absolutePath)
        createQueueV4(setup)
        insertRow(setup, clientId(1), "first text", "local", "small.en", 1_200L, 100L)
        setup.close()

        // Raw connection only: drive the production dispatch, then read sqlite_master directly.
        val raw = DriverManager.getConnection("jdbc:sqlite:" + f.absolutePath)
        opened.add(raw)
        runOnUpgrade({ sql -> raw.createStatement().use { it.execute(sql) } }, 4, 5)

        val tables = raw.createStatement().use { statement ->
            val rs = statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'")
            val names = mutableSetOf<String>()
            while (rs.next()) names.add(rs.getString(1))
            names
        }
        assertTrue("android_sync: raw file after runOnUpgrade(4,5) alone has pending_save", "pending_save" in tables)

        val columns = raw.createStatement().use { statement ->
            val rs = statement.executeQuery("PRAGMA table_info(pending_save)")
            val names = mutableSetOf<String>()
            while (rs.next()) names.add(rs.getString("name"))
            names
        }
        assertTrue("android_sync: raw pending_save has client_id, created_at and run_token",
            setOf("client_id", "created_at", "run_token").all { it in columns })

        val count = raw.createStatement().use { statement ->
            val rs = statement.executeQuery("SELECT COUNT(*) FROM pending_save")
            rs.next()
            rs.getInt(1)
        }
        assertEquals("android_sync: raw pending_save is empty after the migration", 0, count)
        raw.close()
    }
}
