package dev.breaker.server.syncapi.db

import java.sql.Connection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class SchemaV6MigrationTest : TempDatabaseTest() {

    @Test
    fun `migration 6 applies to a fresh database and ends at user_version 6 with signin_attempts`() {
        val temp = openTemp()
        inTransaction(temp) { connection ->
            assertEquals("sync-api: fresh database user_version", 6, TestDatabases.userVersion(connection))
            val tables = TestDatabases.tableNames(connection)
            assertTrue("sync-api: signin_attempts table is missing: $tables", "signin_attempts" in tables)
            val columns = names(connection, "SELECT name FROM pragma_table_info('signin_attempts')")
            assertTrue("sync-api: username_lower column missing: $columns", "username_lower" in columns)
            assertTrue("sync-api: wrong_count column missing: $columns", "wrong_count" in columns)
            assertTrue("sync-api: locked_until_ms column missing: $columns", "locked_until_ms" in columns)
            assertTrue("sync-api: last_attempt_ms column missing: $columns", "last_attempt_ms" in columns)
        }
    }

    @Test
    fun `signin_attempts checks refuse a negative count, a negative lock and a negative last attempt`() {
        val temp = openTemp()
        assertRefused(temp, "a negative wrong_count", "CHECK constraint failed") { connection ->
            connection.prepareStatement(
                "INSERT INTO signin_attempts (username_lower, wrong_count, locked_until_ms, last_attempt_ms) VALUES ('alice', -1, 0, 1)",
            ).use { statement -> statement.executeUpdate() }
        }
        assertRefused(temp, "a negative locked_until_ms", "CHECK constraint failed") { connection ->
            connection.prepareStatement(
                "INSERT INTO signin_attempts (username_lower, wrong_count, locked_until_ms, last_attempt_ms) VALUES ('alice', 0, -1, 1)",
            ).use { statement -> statement.executeUpdate() }
        }
        assertRefused(temp, "a negative last_attempt_ms", "CHECK constraint failed") { connection ->
            connection.prepareStatement(
                "INSERT INTO signin_attempts (username_lower, wrong_count, locked_until_ms, last_attempt_ms) VALUES ('alice', 0, 0, -1)",
            ).use { statement -> statement.executeUpdate() }
        }
    }

    @Test
    fun `signin_attempts refuses an upper-case name, an empty name and a 65-character name`() {
        val temp = openTemp()
        val longName = "a".repeat(65)
        assertRefused(temp, "an upper-case name", "CHECK constraint failed") { connection ->
            connection.prepareStatement(
                "INSERT INTO signin_attempts (username_lower, wrong_count, locked_until_ms, last_attempt_ms) VALUES ('ALICE', 0, 0, 1)",
            ).use { statement -> statement.executeUpdate() }
        }
        assertRefused(temp, "an empty name", "CHECK constraint failed") { connection ->
            connection.prepareStatement(
                "INSERT INTO signin_attempts (username_lower, wrong_count, locked_until_ms, last_attempt_ms) VALUES ('', 0, 0, 1)",
            ).use { statement -> statement.executeUpdate() }
        }
        assertRefused(temp, "a 65-character name", "CHECK constraint failed") { connection ->
            connection.prepareStatement(
                "INSERT INTO signin_attempts (username_lower, wrong_count, locked_until_ms, last_attempt_ms) VALUES ('$longName', 0, 0, 1)",
            ).use { statement -> statement.executeUpdate() }
        }
    }

    @Test
    fun `an upgrade from version 5 keeps every row and adds an empty signin_attempts table`() {
        val first = openTemp(listOf(SchemaV1.MIGRATION, SchemaV2.MIGRATION, SchemaV3.MIGRATION, SchemaV4.MIGRATION, SchemaV5.MIGRATION))
        val aliceId = inTransaction(first) { connection ->
            TestDatabases.insertAccountRow(
                connection,
                TestDatabases.validAccountRow(mapOf("username" to "alice", "username_lower" to "alice")),
            )
            TestDatabases.longs(connection, "SELECT id FROM accounts WHERE username_lower = 'alice'").single()
        }
        val second = reopen(first)

        inTransaction(second) { connection ->
            assertEquals("sync-api: user_version after the upgrade", 6, TestDatabases.userVersion(connection))
            assertEquals(
                "sync-api: every account row is kept",
                listOf(1L),
                TestDatabases.longs(connection, "SELECT count(*) FROM accounts"),
            )
            assertEquals(
                "sync-api: alice's row still has its id",
                listOf(aliceId),
                TestDatabases.longs(connection, "SELECT id FROM accounts WHERE username_lower = 'alice'"),
            )
            assertTrue(
                "sync-api: signin_attempts table exists after upgrade",
                "signin_attempts" in TestDatabases.tableNames(connection),
            )
            assertEquals(
                "sync-api: signin_attempts is empty after the upgrade",
                listOf(0L),
                TestDatabases.longs(connection, "SELECT count(*) FROM signin_attempts"),
            )
        }
    }

    private fun names(connection: Connection, sql: String): List<String> =
        connection.prepareStatement(sql).use { statement ->
            statement.executeQuery().use { rows ->
                val found = ArrayList<String>()
                while (rows.next()) found.add(rows.getString(1))
                found
            }
        }

    private fun assertRefused(temp: TempDatabase, case: String, expected: String, block: (Connection) -> Unit) {
        try {
            inTransaction(temp, block)
        } catch (failure: Exception) {
            assertNotNull("sync-api: the database accepted $case", failure)
            assertTrue(
                "sync-api: $case was refused, but not with '$expected': ${failure.message}",
                failure.message.orEmpty().contains(expected, ignoreCase = true),
            )
            return
        }
        throw AssertionError("sync-api: the database accepted $case but was expected to refuse it")
    }
}
