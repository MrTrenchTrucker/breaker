package dev.breaker.dictation.sync

import java.io.File
import java.lang.AutoCloseable
import java.sql.Connection
import java.sql.DriverManager
import java.util.ArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The cursor table migration, on a real store file.
 *
 * A version 2 store (the queue table with parked_reason) gets the cursor table through
 * [SyncQueueSql.MIGRATE_V2_TO_V3] without losing a queue row, a seq or a parked state. A
 * version 1 store goes through both migrations, the 1 to 3 chain the phone takes when it
 * updates straight to this app. The Android adapter cannot run on a JVM, so the
 * migration statements are run on the desktop SQLite as raw JDBC, the way
 * `ParkedRowMigrationTest` runs the queue migration.
 */
class PullCursorMigrationTest {

    private var file: File? = null
    private val opened = ArrayList<AutoCloseable>()

    @Before
    fun createFile() {
        file = File.createTempFile("android-sync-cursor-migration", ".db")
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

    /** The cursor row count by raw SQL on the open module handle. */
    private fun cursorRowCount(s: DesktopSqlite): Long =
        s.query("SELECT COUNT(*) FROM " + SyncQueueSql.TABLE_PULL_CURSOR).single()[0] as Long

    private fun insertRow(c: Connection, id: String, text: String, source: String, model: String, durationMs: Long, createdAtMs: Long) {
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

    /**
     * Creates a version 2 store: the queue table with parked_reason, three rows, and the
     * middle row parked with a reason.
     */
    private fun createVersionTwoStore() {
        val c = openRawConnection()
        c.createStatement().use { statement -> statement.execute(SyncQueueSql.CREATE_QUEUE) }
        insertRow(c, clientId(1), "first text", "local", "small.en", 1_200L, 100L)
        insertRow(c, clientId(2), "second text", "server", "large-v3", 4_500L, 200L)
        insertRow(c, clientId(3), "third text", "local", "small.en", 1_200L, 300L)
        c.prepareStatement("UPDATE " + SyncQueueSql.TABLE_QUEUE + " SET parked_reason = ? WHERE client_id = ?").use { statement ->
            statement.setString(1, "refused")
            statement.setString(2, clientId(2))
            statement.executeUpdate()
        }
    }

    /**
     * Creates a version 1 store: the old queue table without parked_reason and three rows.
     */
    private fun createVersionOneStore() {
        val c = openRawConnection()
        c.createStatement().use { statement ->
            statement.execute(
                "CREATE TABLE IF NOT EXISTS " + SyncQueueSql.TABLE_QUEUE + " (" +
                    "seq INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "client_id TEXT NOT NULL UNIQUE, " +
                    "text TEXT NOT NULL, " +
                    "source TEXT NOT NULL, " +
                    "model TEXT NOT NULL, " +
                    "duration_ms INTEGER NOT NULL, " +
                    "created_at INTEGER NOT NULL)"
            )
        }
        insertRow(c, clientId(1), "first text", "local", "small.en", 1_200L, 100L)
        insertRow(c, clientId(2), "second text", "server", "large-v3", 4_500L, 200L)
        insertRow(c, clientId(3), "third text", "local", "small.en", 1_200L, 300L)
    }

    /** A failure here means the migration touches a queue row, drops a parked state, or leaves no cursor table. */
    @Test
    fun `a version two store keeps every row and gets an empty cursor table`() {
        createVersionTwoStore()

        val c = openRawConnection()
        c.prepareStatement(SyncQueueSql.MIGRATE_V2_TO_V3).executeUpdate()
        val sqlite = openModule()
        val db = JdbcSyncQueueDatabase(sqlite)

        assertEquals("android_sync: the parked row keeps its parked reason", "refused", db.parkedReason(clientId(2)))
        assertEquals("android_sync: the parked count is unchanged", 1, db.parkedCount())
        assertEquals("android_sync: the live count is unchanged", 2, db.count())
        val head = db.head()
        assertEquals("android_sync: the head is still the first row", clientId(1), head?.clientId)
        assertEquals("android_sync: the head keeps its seq", 1L, head?.seq)
        assertNull("android_sync: the migrated cursor table must be empty", db.pullCursor())
        assertEquals("android_sync: the cursor table must hold no row", 0L, cursorRowCount(sqlite))
    }

    /** A failure here means the migration is not idempotent, so a second run changes the store. */
    @Test
    fun `running the migration twice changes nothing`() {
        createVersionTwoStore()

        val c = openRawConnection()
        c.prepareStatement(SyncQueueSql.MIGRATE_V2_TO_V3).executeUpdate()
        val firstSqlite = openModule()
        val first = JdbcSyncQueueDatabase(firstSqlite)
        assertEquals("android_sync: the first run keeps the parked row", "refused", first.parkedReason(clientId(2)))
        assertEquals("android_sync: the first run keeps the live rows", 2, first.count())
        assertNull("android_sync: the first run leaves the cursor empty", first.pullCursor())
        assertEquals("android_sync: the first run adds no cursor row", 0L, cursorRowCount(firstSqlite))

        val secondConnection = openRawConnection()
        secondConnection.prepareStatement(SyncQueueSql.MIGRATE_V2_TO_V3).executeUpdate()
        val secondSqlite = openModule()
        val second = JdbcSyncQueueDatabase(secondSqlite)

        assertEquals("android_sync: the parked row must survive the second run", "refused", second.parkedReason(clientId(2)))
        assertEquals("android_sync: the live rows must survive the second run", 2, second.count())
        assertEquals("android_sync: the head must keep its seq after the second run", 1L, second.head()?.seq)
        assertNull("android_sync: the cursor must still be empty", second.pullCursor())
        assertEquals("android_sync: the cursor table must still hold no row", 0L, cursorRowCount(secondSqlite))
    }

    /** Creates a version 3 store: the queue table with parked_reason, three rows, and the
     * middle row parked with a reason. No kind column yet: it is what a v3 store holds
     * before the v3 to v4 migration adds it.
     */
    private fun createVersionThreeStore() {
        val c = openRawConnection()
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
                    "parked_reason TEXT)"
            )
        }
        insertRow(c, clientId(1), "first text", "local", "small.en", 1_200L, 100L)
        insertRow(c, clientId(2), "second text", "server", "large-v3", 4_500L, 200L)
        insertRow(c, clientId(3), "third text", "local", "small.en", 1_200L, 300L)
        c.prepareStatement("UPDATE " + SyncQueueSql.TABLE_QUEUE + " SET parked_reason = ? WHERE client_id = ?").use { statement ->
            statement.setString(1, "refused")
            statement.setString(2, clientId(2))
            statement.executeUpdate()
        }
    }

    /** The kind column of the queue table on the open module handle, by column name. */
    private fun kindColumnKind(s: DesktopSqlite): String? =
        s.query("PRAGMA table_info(" + SyncQueueSql.TABLE_QUEUE + ")").firstOrNull { it[1] == "kind" }?.let { it[2] as String }

    /** A failure here means the migration touches a queue row, drops a parked state, or leaves kind without its default. */
    @Test
    fun `a version three store keeps every row and kind defaults to save`() {
        createVersionThreeStore()

        val c = openRawConnection()
        c.prepareStatement(SyncQueueSql.MIGRATE_V3_TO_V4).executeUpdate()
        val sqlite = openModule()
        val db = JdbcSyncQueueDatabase(sqlite)

        assertEquals("android_sync: the migration added the kind column", "TEXT", kindColumnKind(sqlite))
        assertEquals("android_sync: the parked row keeps its parked reason", "refused", db.parkedReason(clientId(2)))
        assertEquals("android_sync: the parked count is unchanged", 1, db.parkedCount())
        assertEquals("android_sync: the live count is unchanged", 2, db.count())
        assertEquals("android_sync: every row survived the migration in its order", listOf(clientId(1), clientId(2), clientId(3)), db.queuedIds())
        assertEquals("android_sync: the head keeps its seq", 1L, db.head()!!.seq)
        val kinds = sqlite.query("SELECT kind FROM " + SyncQueueSql.TABLE_QUEUE + " ORDER BY seq").map { it[0] as String }
        assertEquals("android_sync: kind defaults to save on every migrated row", listOf("save", "save", "save"), kinds)
    }

    /** A failure here means the migration is not idempotent-safe: the onUpgrade chain a phone takes
     * straight from 1 to 4 runs every migration in order and ends at schema version 4. */
    @Test
    fun `a version one store goes through the chained migrations to version four`() {
        createVersionOneStore()

        val c = openRawConnection()
        c.prepareStatement(SyncQueueSql.MIGRATE_V1_TO_V2).executeUpdate()
        c.prepareStatement(SyncQueueSql.MIGRATE_V2_TO_V3).executeUpdate()
        c.prepareStatement(SyncQueueSql.MIGRATE_V3_TO_V4).executeUpdate()
        // S10: after the full chain the cursor table exists and holds no row (a fresh migration
        // must not carry a stale cursor). Check it on the raw connection before DesktopSqlite
        // recreates the schema.
        val cursorRows = c.prepareStatement("SELECT COUNT(*) FROM " + SyncQueueSql.TABLE_PULL_CURSOR).executeQuery().use { rs ->
            rs.next()
            rs.getLong(1)
        }
        assertEquals("android_sync: the full chain leaves an existing, empty cursor table", 0L, cursorRows)
        val sqlite = openModule()
        val db = JdbcSyncQueueDatabase(sqlite)

        assertEquals("android_sync: no row is parked after the chain", 0, db.parkedCount())
        assertEquals("android_sync: all three rows are live", 3, db.count())
        assertEquals(
            "android_sync: all three rows survive the chain in their order",
            listOf(clientId(1), clientId(2), clientId(3)),
            db.queuedIds(),
        )
        assertNull("android_sync: the cursor must be empty after the chain", db.pullCursor())
        var entry = db.head()
        val kinds = ArrayList<String>()
        while (entry != null) {
            kinds.add(entry.kind)
            entry = db.nextAfter(entry.seq)
        }
        assertEquals("android_sync: kind defaults to save on every row after the chain", listOf("save", "save", "save"), kinds)
    }
}
