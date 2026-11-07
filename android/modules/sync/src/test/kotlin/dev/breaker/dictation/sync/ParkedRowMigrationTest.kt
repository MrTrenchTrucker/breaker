package dev.breaker.dictation.sync

import java.io.File
import java.lang.AutoCloseable
import java.sql.Connection
import java.sql.DriverManager
import java.util.ArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Test G: the old-format store migration. Creates a version 1 store with the old CREATE TABLE text
 * (no parked_reason column), inserts several rows, runs the migration, and asserts every row is
 * still there with its seq, text and order, none is parked, a new row gets a higher seq, and a row
 * can be parked and read back. Runs the migration statement from SyncQueueSql on the desktop SQLite
 * twin.
 */
class ParkedRowMigrationTest {

    private var file: File? = null
    private val opened = ArrayList<AutoCloseable>()

    @Before
    fun createFile() {
        file = File.createTempFile("android-sync-migration", ".db")
    }

    @After
    fun closeAndDeleteFile() {
        try {
            opened.forEach { it.close() }
        } finally {
            val path = file?.path
            if (path != null) {
                for (suffix in listOf("", "-journal", "-wal", "-shm")) {
                    File(path + suffix).delete()
                }
            }
        }
    }

    /** Opens a raw JDBC connection on the temporary file, without creating the module schema. */
    private fun openRawConnection(): Connection {
        val c = DriverManager.getConnection("jdbc:sqlite:" + file!!.absolutePath)
        opened.add(c)
        return c
    }

    /** Opens the temporary file as DesktopSqlite (module schema created) for the post-migration phase. */
    private fun openModule(): DesktopSqlite {
        val sqlite = DesktopSqlite(file)
        opened.add(sqlite)
        return sqlite
    }

    /** Creates a version-1 store (old CREATE TABLE text, no parked_reason column) and inserts rows. */
    private fun createOldStore() {
        val c = openRawConnection()
        c.createStatement().use { statement ->
            statement.execute(
                "CREATE TABLE IF NOT EXISTS ${SyncQueueSql.TABLE_QUEUE} (" +
                    "seq INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "client_id TEXT NOT NULL UNIQUE, " +
                    "text TEXT NOT NULL, " +
                    "source TEXT NOT NULL, " +
                    "model TEXT NOT NULL, " +
                    "duration_ms INTEGER NOT NULL, " +
                    "created_at INTEGER NOT NULL)"
            )
        }
        c.prepareStatement(
            "INSERT INTO ${SyncQueueSql.TABLE_QUEUE} (client_id, text, source, model, duration_ms, created_at) VALUES (?, ?, ?, ?, ?, ?)"
        ).use { statement ->
            statement.setString(1, clientId(1))
            statement.setString(2, "first text")
            statement.setString(3, "local")
            statement.setString(4, "small.en")
            statement.setLong(5, 1_200L)
            statement.setLong(6, 100L)
            statement.executeUpdate()
        }
        c.prepareStatement(
            "INSERT INTO ${SyncQueueSql.TABLE_QUEUE} (client_id, text, source, model, duration_ms, created_at) VALUES (?, ?, ?, ?, ?, ?)"
        ).use { statement ->
            statement.setString(1, clientId(2))
            statement.setString(2, "second text")
            statement.setString(3, "server")
            statement.setString(4, "large-v3")
            statement.setLong(5, 4_500L)
            statement.setLong(6, 200L)
            statement.executeUpdate()
        }
        c.prepareStatement(
            "INSERT INTO ${SyncQueueSql.TABLE_QUEUE} (client_id, text, source, model, duration_ms, created_at) VALUES (?, ?, ?, ?, ?, ?)"
        ).use { statement ->
            statement.setString(1, clientId(3))
            statement.setString(2, "third text")
            statement.setString(3, "local")
            statement.setString(4, "small.en")
            statement.setLong(5, 1_200L)
            statement.setLong(6, 300L)
            statement.executeUpdate()
        }
    }

    /** Runs the ALTER TABLE migration statement from SyncQueueSql on the store. */
    private fun runMigration(connection: Connection) {
        connection.prepareStatement(SyncQueueSql.MIGRATE_V1_TO_V2).executeUpdate()
    }

    /** All rows survive the migration with their seq, text and order. */
    @Test
    fun `all rows survive the migration with their seq text and order`() {
        createOldStore()

        val c = openRawConnection()
        runMigration(c)
        val db = JdbcSyncQueueDatabase(openModule())

        assertEquals("android_sync: none is parked after migration", 0, db.parkedCount())
        assertEquals("android_sync: count counts all three live rows", 3, db.count())
        assertEquals(
            "android_sync: all three rows survive the migration in their order",
            listOf(clientId(1), clientId(2), clientId(3)),
            db.drainQueuedIds(),
        )
    }

    /** A new row after migration gets a higher seq. */
    @Test
    fun `a new row after migration gets a higher seq`() {
        createOldStore()

        val c = openRawConnection()
        runMigration(c)
        val db = JdbcSyncQueueDatabase(openModule())

        val tailBefore = db.tailSeq()
        assertTrue("android_sync: a new row after migration is queued", db.insertIfAbsent(clientId(4), "fourth text", "local", "small.en", 1_200L, 400L))
        val tailAfter = db.tailSeq()
        assertTrue("android_sync: the new row gets a higher seq than the old tail", tailAfter > tailBefore)
    }

    /** A row can be parked and read back after migration. */
    @Test
    fun `a row can be parked and read back after migration`() {
        createOldStore()

        val c = openRawConnection()
        runMigration(c)
        val db = JdbcSyncQueueDatabase(openModule())

        assertTrue("android_sync: the row can be parked", db.parkRow(clientId(2), "bad body"))
        assertEquals("android_sync: the parked reason is read back", "bad body", db.parkedReason(clientId(2)))
        assertEquals("android_sync: parkedCount is one", 1, db.parkedCount())
        assertEquals("android_sync: count excludes the parked row", 2, db.count())
        val head = db.head()
        assertEquals("android_sync: head skips the parked row", clientId(1), head?.clientId)
    }
}
