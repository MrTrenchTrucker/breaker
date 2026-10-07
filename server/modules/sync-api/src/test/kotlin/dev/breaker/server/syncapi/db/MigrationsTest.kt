package dev.breaker.server.syncapi.db

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

    private fun <T> withMemoryConnection(block: (Connection) -> T): T =
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection -> block(connection) }

    private fun logRows(connection: Connection): List<Long> =
        TestDatabases.longs(connection, "SELECT n FROM log ORDER BY rowid")

    @Test
    fun `a fresh database ends at version 1 with the accounts table`() {
        val temp = openTemp()
        inTransaction(temp) { connection ->
            assertEquals("sync-api: fresh database user_version", 1, TestDatabases.userVersion(connection))
            assertEquals(
                "sync-api: accounts table missing after the first migration",
                listOf("accounts"),
                TestDatabases.strings(
                    connection,
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'accounts'",
                ),
            )
        }
    }

    @Test
    fun `reopening a migrated file changes nothing and keeps stored rows`() {
        val first = openTemp()
        inTransaction(first) { connection ->
            TestDatabases.insertAccountRow(connection, TestDatabases.validAccountRow())
        }
        val second = reopen(first)
        inTransaction(second) { connection ->
            assertEquals("sync-api: user_version after reopen", 1, TestDatabases.userVersion(connection))
            assertEquals(
                "sync-api: stored account lost or duplicated by a second migrate",
                listOf(1L),
                TestDatabases.longs(connection, "SELECT count(*) FROM accounts"),
            )
        }
    }

    @Test
    fun `a database with a newer version than the code knows is refused untouched`() {
        withMemoryConnection { connection ->
            connection.executeStatement("PRAGMA user_version = 2")
            val failure = TestDatabases.expectFailure<MigrationException>("newer schema") {
                Migrations.migrate(connection, Migrations.ALL)
            }
            assertTrue(
                "sync-api: message should say the schema is newer, was: ${failure.message}",
                failure.message.orEmpty().contains("newer than this code knows"),
            )
            assertEquals(
                "sync-api: a refused database must not be modified",
                emptyList<String>(),
                TestDatabases.tableNames(connection),
            )
            assertEquals("sync-api: user_version must stay as found", 2, TestDatabases.userVersion(connection))
        }
    }

    @Test
    fun `steps run exactly once and in order`() {
        withMemoryConnection { connection ->
            val version = Migrations.migrate(connection, listOf(logV1(), logV2()))
            assertEquals("sync-api: returned version", 2, version)
            assertEquals("sync-api: log rows show the order the steps ran in", listOf(1L, 2L), logRows(connection))
        }
    }

    @Test
    fun `migrating twice with the same steps applies nothing the second time`() {
        withMemoryConnection { connection ->
            Migrations.migrate(connection, listOf(logV1(), logV2()))
            val version = Migrations.migrate(connection, listOf(logV1(), logV2()))
            assertEquals("sync-api: returned version", 2, version)
            assertEquals("sync-api: a step ran again", listOf(1L, 2L), logRows(connection))
        }
    }

    @Test
    fun `upgrading a version 1 file with steps 1 and 2 applies only step 2`() {
        val first = openTemp(listOf(logV1()))
        val second = reopen(first, listOf(logV1(), logV2()))
        inTransaction(second) { connection ->
            assertEquals("sync-api: user_version after the upgrade", 2, TestDatabases.userVersion(connection))
            assertEquals("sync-api: step 1 must not run again", listOf(1L, 2L), logRows(connection))
        }
    }

    @Test
    fun `a failing step is rolled back including its tables and keeps the earlier version`() {
        withMemoryConnection { connection ->
            Migrations.migrate(connection, listOf(logV1()))
            val failing = Migration(2, listOf("CREATE TABLE extra_table (x INTEGER)", "THIS IS NOT SQL"))
            val failure = TestDatabases.expectFailure<MigrationException>("failing step") {
                Migrations.migrate(connection, listOf(logV1(), failing))
            }
            assertNotNull("sync-api: the original SQL error must be attached as the cause", failure.cause)
            assertEquals("sync-api: user_version after a failed step", 1, TestDatabases.userVersion(connection))
            assertEquals(
                "sync-api: the table of a failed step survived",
                emptyList<String>(),
                TestDatabases.strings(connection, "SELECT name FROM sqlite_master WHERE name = 'extra_table'"),
            )
            // Proves the failed transaction was really ended on this connection.
            connection.executeStatement("BEGIN IMMEDIATE")
            connection.executeStatement("ROLLBACK")
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
                "sync-api: user_version must roll back with the failed step",
                1,
                TestDatabases.userVersion(connection),
            )
        }
    }

    @Test
    fun `a step that failed can be applied once it is fixed`() {
        withMemoryConnection { connection ->
            Migrations.migrate(connection, listOf(logV1()))
            val failing = Migration(2, listOf("CREATE TABLE extra_table (x INTEGER)", "THIS IS NOT SQL"))
            TestDatabases.expectFailure<MigrationException>("failing step") {
                Migrations.migrate(connection, listOf(logV1(), failing))
            }
            val fixed = Migration(2, listOf("CREATE TABLE extra_table (x INTEGER)"))
            val version = Migrations.migrate(connection, listOf(logV1(), fixed))
            assertEquals("sync-api: version after the fixed step", 2, version)
            assertEquals(
                "sync-api: fixed step should have created extra_table",
                listOf("extra_table"),
                TestDatabases.strings(connection, "SELECT name FROM sqlite_master WHERE name = 'extra_table'"),
            )
        }
    }

    @Test
    fun `versions with a gap are refused before anything is applied`() {
        withMemoryConnection { connection ->
            TestDatabases.expectFailure<MigrationException>("versions 1 and 3") {
                Migrations.migrate(connection, listOf(logV1(), Migration(3, listOf("CREATE TABLE other_table (x INTEGER)"))))
            }
            assertEquals("sync-api: nothing may be applied", 0, TestDatabases.userVersion(connection))
            assertEquals("sync-api: no table may be created", emptyList<String>(), TestDatabases.tableNames(connection))
        }
    }

    @Test
    fun `a list that does not start at version 1 is refused`() {
        withMemoryConnection { connection ->
            TestDatabases.expectFailure<MigrationException>("list starting at 2") {
                Migrations.migrate(connection, listOf(Migration(2, listOf("CREATE TABLE extra_table (x INTEGER)"))))
            }
            assertEquals("sync-api: nothing may be applied", 0, TestDatabases.userVersion(connection))
        }
    }

    @Test
    fun `a repeated or reordered version is refused`() {
        withMemoryConnection { connection ->
            val cases = mapOf(
                "versions 1 and 1" to listOf(logV1(), Migration(1, listOf("SELECT 1"))),
                "versions 2 and 1" to listOf(logV2(), logV1()),
            )
            for ((name, steps) in cases) {
                TestDatabases.expectFailure<MigrationException>(name) { Migrations.migrate(connection, steps) }
            }
            assertEquals("sync-api: nothing may be applied", 0, TestDatabases.userVersion(connection))
        }
    }
}
