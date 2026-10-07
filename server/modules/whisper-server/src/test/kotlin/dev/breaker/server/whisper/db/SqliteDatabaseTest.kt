package dev.breaker.server.whisper.db

import java.io.File
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

    private fun pragmaLong(temp: TempDatabase, pragma: String): Long =
        inTransaction(temp) { connection -> TestDatabases.longs(connection, "PRAGMA $pragma").single() }

    private fun walFile(temp: TempDatabase): File = File(temp.file.toString() + "-wal")

    @Test
    fun `an in memory database opens and has run the migrations`() {
        SqliteDatabase.open(":memory:").use { db ->
            val tables = runBlocking { db.transaction { connection -> TestDatabases.tableNames(connection) } }
            assertTrue("whisper-server: the jobs table is missing in the memory database, tables: $tables", "jobs" in tables)
        }
    }

    @Test
    fun `a blank path is refused`() {
        for (path in listOf("", "   ")) {
            TestDatabases.expectFailure<IllegalArgumentException>("open with the path '$path'") {
                SqliteDatabase.open(path).close()
            }
        }
    }

    @Test
    fun `a file database uses write ahead logging`() {
        val temp = openTemp()
        val mode = inTransaction(temp) { connection ->
            TestDatabases.strings(connection, "PRAGMA journal_mode").single()
        }
        assertEquals("whisper-server: journal mode of a file database", "wal", mode)
    }

    @Test
    fun `secure delete is on for a file database`() {
        assertEquals("whisper-server: PRAGMA secure_delete of a file database", 1L, pragmaLong(openTemp(), "secure_delete"))
    }

    @Test
    fun `secure delete is on for a memory database`() {
        SqliteDatabase.open(":memory:").use { db ->
            val value = runBlocking {
                db.transaction { connection -> TestDatabases.longs(connection, "PRAGMA secure_delete").single() }
            }
            assertEquals("whisper-server: PRAGMA secure_delete of a memory database", 1L, value)
        }
    }

    @Test
    fun `foreign keys are enforced on the connection`() {
        assertEquals("whisper-server: PRAGMA foreign_keys", 1L, pragmaLong(openTemp(), "foreign_keys"))
    }

    @Test
    fun `the busy timeout is five seconds`() {
        assertEquals("whisper-server: PRAGMA busy_timeout", 5000L, pragmaLong(openTemp(), "busy_timeout"))
    }

    @Test
    fun `a committed transaction is visible to a later transaction`() {
        val temp = openTemp(notesAndCounter)
        inTransaction(temp) { connection -> insertNote(connection, "first") }
        val seen = inTransaction(temp) { connection -> noteBodies(connection) }
        assertEquals("whisper-server: a committed row is not visible to the next transaction", listOf("first"), seen)
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
        // The next transaction proves the row is gone and that the failed one was
        // really ended: BEGIN would fail inside a transaction that is still open.
        val seen = inTransaction(temp) { connection -> noteBodies(connection) }
        assertEquals("whisper-server: a row from a failed transaction survived", emptyList<String>(), seen)
    }

    @Test
    fun `a failing rollback does not replace the exception that caused it`() {
        val temp = openTemp(notesAndCounter)
        // The block ends its own transaction, so the rollback that follows its failure
        // has nothing to roll back and fails too.
        TestDatabases.expectFailure<DeliberateFailure>("block that commits and then throws") {
            inTransaction<Unit>(temp) { connection ->
                insertNote(connection, "committed by the block")
                connection.executeStatement("COMMIT")
                throw DeliberateFailure()
            }
        }
        val seen = inTransaction(temp) { connection -> noteBodies(connection) }
        assertEquals("whisper-server: the database must stay usable after the double failure", listOf("committed by the block"), seen)
    }

    @Test
    fun `a committed transaction survives a reopen`() {
        val first = openTemp()
        inTransaction(first) { connection -> TestDatabases.insertJobRow(connection, TestDatabases.validJobRow()) }
        val second = reopen(first)
        val count = inTransaction(second) { connection -> TestDatabases.longs(connection, "SELECT count(*) FROM jobs").single() }
        assertEquals("whisper-server: a committed job did not survive the reopen", 1L, count)
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
        assertEquals(
            "whisper-server: increments were lost under concurrent transactions",
            (workers * incrementsPerWorker).toLong(),
            total,
        )
    }

    @Test
    fun `a transaction after close fails with an illegal state error`() {
        val temp = openTemp(notesAndCounter)
        temp.closeKeepingFiles()
        val failure = TestDatabases.expectFailure<IllegalStateException>("transaction on a closed database") {
            runBlocking { temp.db.transaction { connection -> insertNote(connection, "late") } }
        }
        assertTrue(
            "whisper-server: the message must start with the module prefix, was: ${failure.message}",
            failure.message.orEmpty().startsWith("whisper-server:"),
        )
    }

    @Test
    fun `a checkpoint after close fails with an illegal state error`() {
        val temp = openTemp()
        temp.closeKeepingFiles()
        val failure = TestDatabases.expectFailure<IllegalStateException>("checkpoint on a closed database") {
            runBlocking { temp.db.checkpointTruncate() }
        }
        assertTrue(
            "whisper-server: the message must start with the module prefix, was: ${failure.message}",
            failure.message.orEmpty().startsWith("whisper-server:"),
        )
    }

    @Test
    fun `a checkpoint reports success on a file database`() {
        val temp = openTemp()
        assertTrue("whisper-server: checkpointTruncate on a file database", runBlocking { temp.db.checkpointTruncate() })
    }

    @Test
    fun `a checkpoint reports success on a memory database that has no log`() {
        SqliteDatabase.open(":memory:").use { db ->
            assertTrue("whisper-server: checkpointTruncate on a memory database", runBlocking { db.checkpointTruncate() })
        }
    }

    @Test
    fun `a checkpoint empties the log file that writes filled`() {
        val temp = openTemp()
        inTransaction(temp) { connection ->
            for (index in 1..5) {
                TestDatabases.insertJobRow(connection, TestDatabases.validJobRow(mapOf("owner_account_id" to index.toLong())))
            }
        }
        val before = walFile(temp).length()
        assertTrue("whisper-server: precondition, the committed writes must be in the -wal file, length $before", before > 0L)

        val reported = runBlocking { temp.db.checkpointTruncate() }

        assertTrue("whisper-server: the checkpoint must report success", reported)
        assertEquals("whisper-server: the -wal file must be empty after the checkpoint", 0L, walFile(temp).length())
    }

    @Test
    fun `a failed open leaves the file usable and no connection holding it`() {
        val first = openTemp()
        first.closeKeepingFiles()
        val walPath: Path = first.file.resolveSibling(first.file.fileName.toString() + "-wal")
        assertFalse("whisper-server: precondition, a closed database leaves no -wal file", Files.exists(walPath))

        val broken = Migration(2, listOf("THIS IS NOT SQL"))
        TestDatabases.expectFailure<MigrationException>("open with a failing step") {
            SqliteDatabase.open(first.file.toString(), Migrations.ALL + broken)
        }
        // SQLite removes the -wal file only when the last connection to the file closes.
        assertFalse("whisper-server: a connection was left open after a failed open", Files.exists(walPath))

        val again = reopen(first)
        val version = inTransaction(again) { connection -> TestDatabases.userVersion(connection) }
        assertEquals("whisper-server: the file must open again with the good steps", 1, version)
    }
}
