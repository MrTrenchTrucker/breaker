package dev.breaker.server.syncapi.db

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class SqliteDatabaseTest : TempDatabaseTest() {

    private class DeliberateFailure : RuntimeException("deliberate failure inside the transaction block")

    private val notesAndCounter: List<Migration> = listOf(
        Migration(
            1,
            listOf(
                "CREATE TABLE notes (id INTEGER PRIMARY KEY, body TEXT NOT NULL)",
                "CREATE TABLE counter (id INTEGER PRIMARY KEY, n INTEGER NOT NULL)",
                "INSERT INTO counter (id, n) VALUES (1, 0)",
            ),
        ),
    )

    private fun insertNote(connection: Connection, body: String) {
        connection.prepareStatement("INSERT INTO notes (body) VALUES (?)").use { statement ->
            statement.setString(1, body)
            statement.executeUpdate()
        }
    }

    private fun noteBodies(connection: Connection): List<String> =
        TestDatabases.strings(connection, "SELECT body FROM notes ORDER BY id")

    private fun incrementCounter(connection: Connection) {
        val current = TestDatabases.longs(connection, "SELECT n FROM counter WHERE id = 1").single()
        connection.prepareStatement("UPDATE counter SET n = ? WHERE id = 1").use { statement ->
            statement.setLong(1, current + 1)
            statement.executeUpdate()
        }
    }

    @Test
    fun `a committed transaction is visible to a later transaction`() {
        val temp = openTemp(notesAndCounter)
        inTransaction(temp) { connection -> insertNote(connection, "first") }
        val seen = inTransaction(temp) { connection -> noteBodies(connection) }
        assertEquals("sync-api: committed row not visible to the next transaction", listOf("first"), seen)
    }

    @Test
    fun `a block that throws rolls back and the same exception type propagates`() {
        val temp = openTemp(notesAndCounter)
        TestDatabases.expectFailure<DeliberateFailure>("block that throws") {
            inTransaction<Unit>(temp) { connection ->
                insertNote(connection, "never committed")
                throw DeliberateFailure()
            }
        }
        // The next transaction both proves the row is gone and that the failed one
        // was really ended: BEGIN would fail inside a still-open transaction.
        val seen = inTransaction(temp) { connection -> noteBodies(connection) }
        assertEquals("sync-api: a row from a failed transaction survived", emptyList<String>(), seen)
    }

    @Test
    fun `a file database uses write ahead logging`() {
        val temp = openTemp()
        val mode = inTransaction(temp) { connection ->
            TestDatabases.strings(connection, "PRAGMA journal_mode").single()
        }
        assertEquals("sync-api: journal mode of a file database", "wal", mode)
    }

    @Test
    fun `foreign keys are enforced on the connection`() {
        val temp = openTemp()
        val enabled = inTransaction(temp) { connection ->
            TestDatabases.longs(connection, "PRAGMA foreign_keys").single()
        }
        assertEquals("sync-api: PRAGMA foreign_keys", 1L, enabled)
    }

    @Test
    fun `the busy timeout is five seconds`() {
        val temp = openTemp()
        val timeout = inTransaction(temp) { connection ->
            TestDatabases.longs(connection, "PRAGMA busy_timeout").single()
        }
        assertEquals("sync-api: PRAGMA busy_timeout", 5000L, timeout)
    }

    @Test
    fun `an in memory database runs the migrations and skips write ahead logging`() {
        SqliteDatabase.open(":memory:").use { db ->
            val tables = runBlocking { db.transaction { connection -> TestDatabases.tableNames(connection) } }
            assertTrue("sync-api: accounts table missing in the memory database, tables: $tables", "accounts" in tables)
            val mode = runBlocking {
                db.transaction { connection -> TestDatabases.strings(connection, "PRAGMA journal_mode").single() }
            }
            assertEquals("sync-api: a memory database cannot use WAL", "memory", mode)
        }
    }

    @Test
    fun `concurrent read modify write transactions never lose an update`() {
        val temp = openTemp(notesAndCounter)
        val workers = 8
        val incrementsPerWorker = 25
        runBlocking {
            withTimeout(30_000) {
                (1..workers).map {
                    async(Dispatchers.Default) {
                        repeat(incrementsPerWorker) {
                            temp.db.transaction { connection -> incrementCounter(connection) }
                        }
                    }
                }.awaitAll()
            }
        }
        val total = inTransaction(temp) { connection ->
            TestDatabases.longs(connection, "SELECT n FROM counter WHERE id = 1").single()
        }
        assertEquals("sync-api: increments were lost under concurrent transactions", (workers * incrementsPerWorker).toLong(), total)
    }

    @Test
    fun `a transaction after close fails with an illegal state error`() {
        val temp = openTemp(notesAndCounter)
        temp.closeKeepingFiles()
        TestDatabases.expectFailure<IllegalStateException>("transaction on a closed database") {
            runBlocking { temp.db.transaction { connection -> insertNote(connection, "late") } }
        }
    }

    @Test
    fun `a failed migration at open leaves no connection holding the file`() {
        val first = openTemp(notesAndCounter)
        first.closeKeepingFiles()
        val walFile: Path = first.file.resolveSibling(first.file.fileName.toString() + "-wal")
        assertFalse("sync-api: precondition, a closed database leaves no -wal file", Files.exists(walFile))

        val broken = Migration(2, listOf("THIS IS NOT SQL"))
        TestDatabases.expectFailure<MigrationException>("open with a failing step") {
            SqliteDatabase.open(first.file.toString(), notesAndCounter + broken)
        }
        // SQLite removes the -wal file only when the last connection to the file closes.
        assertFalse("sync-api: connection left open after a failed open", Files.exists(walFile))
    }
}
