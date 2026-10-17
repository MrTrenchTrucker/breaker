package dev.breaker.server.whisper.db

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class MigrationsTest : TempDatabaseTest() {

    private fun logV1(): Migration =
        Migration(1, listOf("CREATE TABLE log (n INTEGER NOT NULL)", "INSERT INTO log (n) VALUES (1)"))

    private fun logV2(): Migration = Migration(2, listOf("INSERT INTO log (n) VALUES (2)"))

    private fun brokenStep(version: Int): Migration =
        Migration(version, listOf("CREATE TABLE extra_table (x INTEGER)", "THIS IS NOT SQL"))

    private fun <T> withMemoryConnection(block: (Connection) -> T): T =
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection -> block(connection) }

    // A second, plain connection: it reads what is on disk without going through the
    // code under test.
    private fun <T> withRawConnection(file: Path, block: (Connection) -> T): T =
        DriverManager.getConnection("jdbc:sqlite:$file").use { connection -> block(connection) }

    private fun logRows(connection: Connection): List<Long> =
        TestDatabases.longs(connection, "SELECT n FROM log ORDER BY rowid")

    @Test
    fun `the migration versions run from 1 without a gap and end at 1`() {
        val versions = Migrations.ALL.map { step -> step.version }
        assertEquals(
            "whisper-server: migration versions must be consecutive from 1",
            (1..Migrations.ALL.size).toList(),
            versions,
        )
        assertEquals("whisper-server: the last migration version", 1, Migrations.ALL.last().version)
    }

    @Test
    fun `a fresh database file ends at the last version with the jobs table`() {
        val temp = openTemp()
        inTransaction(temp) { connection ->
            assertEquals(
                "whisper-server: fresh database user_version must be the last migration's version",
                Migrations.ALL.last().version,
                TestDatabases.userVersion(connection),
            )
            assertEquals("whisper-server: fresh database user_version", 1, TestDatabases.userVersion(connection))
            assertEquals(
                "whisper-server: the jobs table is missing after the migrations",
                listOf("jobs"),
                TestDatabases.strings(connection, "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'jobs'"),
            )
        }
    }

    @Test
    fun `reopening a migrated file keeps the stored rows and the version`() {
        val first = openTemp()
        inTransaction(first) { connection -> TestDatabases.insertJobRow(connection, TestDatabases.validJobRow()) }
        val second = reopen(first)
        inTransaction(second) { connection ->
            assertEquals("whisper-server: user_version after a reopen", 1, TestDatabases.userVersion(connection))
            assertEquals(
                "whisper-server: the stored job was lost or duplicated by a second migrate",
                listOf(1L),
                TestDatabases.longs(connection, "SELECT count(*) FROM jobs"),
            )
        }
    }

    @Test
    fun `a file with a newer version than the code knows is refused and left as found`() {
        val first = openTemp()
        first.closeKeepingFiles()
        val newer = Migrations.ALL.last().version + 1
        withRawConnection(first.file) { connection -> connection.executeStatement("PRAGMA user_version = $newer") }

        val failure = TestDatabases.expectFailure<MigrationException>("open of a newer file") {
            SqliteDatabase.open(first.file.toString())
        }

        assertTrue(
            "whisper-server: the message must start with the module prefix, was: ${failure.message}",
            failure.message.orEmpty().startsWith("whisper-server:"),
        )
        assertTrue(
            "whisper-server: the message must say the schema is newer, was: ${failure.message}",
            failure.message.orEmpty().contains("newer than this code knows"),
        )
        withRawConnection(first.file) { connection ->
            assertEquals("whisper-server: user_version must stay as found", newer, TestDatabases.userVersion(connection))
        }
    }

    @Test
    fun `a newer version is refused before any table is created`() {
        withMemoryConnection { connection ->
            connection.executeStatement("PRAGMA user_version = ${Migrations.ALL.last().version + 1}")
            TestDatabases.expectFailure<MigrationException>("newer schema") {
                Migrations.migrate(connection, Migrations.ALL)
            }
            assertEquals(
                "whisper-server: a refused database must not be modified",
                emptyList<String>(),
                TestDatabases.tableNames(connection),
            )
        }
    }

    @Test
    fun `steps run exactly once and in order`() {
        withMemoryConnection { connection ->
            val version = Migrations.migrate(connection, listOf(logV1(), logV2()))
            assertEquals("whisper-server: returned version", 2, version)
            assertEquals("whisper-server: log rows show the order the steps ran in", listOf(1L, 2L), logRows(connection))
        }
    }

    @Test
    fun `migrating twice with the same steps applies nothing the second time`() {
        withMemoryConnection { connection ->
            Migrations.migrate(connection, listOf(logV1(), logV2()))
            val version = Migrations.migrate(connection, listOf(logV1(), logV2()))
            assertEquals("whisper-server: returned version", 2, version)
            assertEquals("whisper-server: a step ran again", listOf(1L, 2L), logRows(connection))
        }
    }

    @Test
    fun `upgrading a version 1 file with steps 1 and 2 applies only step 2`() {
        val first = openTemp(listOf(logV1()))
        val second = reopen(first, listOf(logV1(), logV2()))
        inTransaction(second) { connection ->
            assertEquals("whisper-server: user_version after the upgrade", 2, TestDatabases.userVersion(connection))
            assertEquals("whisper-server: step 1 must not run again", listOf(1L, 2L), logRows(connection))
        }
    }

    @Test
    fun `a failing step throws with the cause attached and leaves the version and tables as they were`() {
        withMemoryConnection { connection ->
            Migrations.migrate(connection, listOf(logV1()))
            val failure = TestDatabases.expectFailure<MigrationException>("failing step") {
                Migrations.migrate(connection, listOf(logV1(), brokenStep(2)))
            }
            assertTrue(
                "whisper-server: the message must start with the module prefix, was: ${failure.message}",
                failure.message.orEmpty().startsWith("whisper-server:"),
            )
            assertNotNull("whisper-server: the original SQL error must be attached as the cause", failure.cause)
            assertEquals("whisper-server: user_version after a failed step", 1, TestDatabases.userVersion(connection))
            assertEquals(
                "whisper-server: the table of a failed step survived",
                emptyList<String>(),
                TestDatabases.strings(connection, "SELECT name FROM sqlite_master WHERE name = 'extra_table'"),
            )
            // Proves the failed transaction was really ended on this connection.
            connection.executeStatement("BEGIN IMMEDIATE")
            connection.executeStatement("ROLLBACK")
        }
    }

    @Test
    fun `a failing step on a file leaves it at the last good version with the jobs table`() {
        val first = openTemp()
        first.closeKeepingFiles()

        TestDatabases.expectFailure<MigrationException>("open with a failing second step") {
            SqliteDatabase.open(first.file.toString(), Migrations.ALL + brokenStep(2))
        }

        withRawConnection(first.file) { connection ->
            assertEquals("whisper-server: user_version after a failed upgrade", 1, TestDatabases.userVersion(connection))
            assertEquals(
                "whisper-server: only the jobs tables may exist after a failed upgrade",
                listOf("jobs", "sqlite_sequence"),
                TestDatabases.tableNames(connection),
            )
        }
    }

    @Test
    fun `a failing step also undoes a version number the step itself wrote`() {
        withMemoryConnection { connection ->
            Migrations.migrate(connection, listOf(logV1()))
            val failing = Migration(
                2,
                listOf("CREATE TABLE extra_table (x INTEGER)", "PRAGMA user_version = 99", "THIS IS NOT SQL"),
            )
            TestDatabases.expectFailure<MigrationException>("failing step") {
                Migrations.migrate(connection, listOf(logV1(), failing))
            }
            assertEquals(
                "whisper-server: user_version must roll back with the failed step",
                1,
                TestDatabases.userVersion(connection),
            )
        }
    }

    @Test
    fun `a step that failed can be applied once it is fixed`() {
        withMemoryConnection { connection ->
            Migrations.migrate(connection, listOf(logV1()))
            TestDatabases.expectFailure<MigrationException>("failing step") {
                Migrations.migrate(connection, listOf(logV1(), brokenStep(2)))
            }
            val fixed = Migration(2, listOf("CREATE TABLE extra_table (x INTEGER)"))
            val version = Migrations.migrate(connection, listOf(logV1(), fixed))
            assertEquals("whisper-server: version after the fixed step", 2, version)
            assertEquals(
                "whisper-server: the fixed step should have created extra_table",
                listOf("extra_table"),
                TestDatabases.strings(connection, "SELECT name FROM sqlite_master WHERE name = 'extra_table'"),
            )
        }
    }

    @Test
    fun `versions with a gap are refused before the database is changed`() {
        withMemoryConnection { connection ->
            val failure = TestDatabases.expectFailure<MigrationException>("versions 1 and 3") {
                Migrations.migrate(connection, listOf(logV1(), Migration(3, listOf("CREATE TABLE other_table (x INTEGER)"))))
            }
            assertTrue(
                "whisper-server: the message must start with the module prefix, was: ${failure.message}",
                failure.message.orEmpty().startsWith("whisper-server:"),
            )
            assertEquals("whisper-server: nothing may be applied", 0, TestDatabases.userVersion(connection))
            assertEquals("whisper-server: no table may be created", emptyList<String>(), TestDatabases.tableNames(connection))
        }
    }

    @Test
    fun `a list that does not start at version 1 is refused before the database is changed`() {
        withMemoryConnection { connection ->
            TestDatabases.expectFailure<MigrationException>("list starting at 2") {
                Migrations.migrate(connection, listOf(Migration(2, listOf("CREATE TABLE extra_table (x INTEGER)"))))
            }
            assertEquals("whisper-server: nothing may be applied", 0, TestDatabases.userVersion(connection))
            assertEquals("whisper-server: no table may be created", emptyList<String>(), TestDatabases.tableNames(connection))
        }
    }

    @Test
    fun `a repeated or reordered version is refused before the database is changed`() {
        withMemoryConnection { connection ->
            val cases = mapOf(
                "versions 1 and 1" to listOf(logV1(), Migration(1, listOf("SELECT 1"))),
                "versions 2 and 1" to listOf(logV2(), logV1()),
            )
            for ((name, steps) in cases) {
                TestDatabases.expectFailure<MigrationException>(name) { Migrations.migrate(connection, steps) }
            }
            assertEquals("whisper-server: nothing may be applied", 0, TestDatabases.userVersion(connection))
            assertEquals("whisper-server: no table may be created", emptyList<String>(), TestDatabases.tableNames(connection))
        }
    }
}
