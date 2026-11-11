package dev.breaker.server.syncapi.db

import java.sql.Connection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class SchemaV7MigrationTest : TempDatabaseTest() {

    @Test
    fun `migration 7 applies to a fresh database and creates server_settings`() {
        val temp = openTemp()
        inTransaction(temp) { connection ->
            assertEquals("sync-api: fresh database user_version", 11, TestDatabases.userVersion(connection))
            val tables = TestDatabases.tableNames(connection)
            assertTrue("sync-api: server_settings table is missing: $tables", "server_settings" in tables)
            val columns = names(connection, "SELECT name FROM pragma_table_info('server_settings')")
            assertTrue("sync-api: setting_key column missing: $columns", "setting_key" in columns)
            assertTrue("sync-api: int_value column missing: $columns", "int_value" in columns)
        }
    }

    @Test
    fun `server_settings checks refuse an empty key and a 65-character key`() {
        val temp = openTemp()
        val longKey = "k".repeat(65)
        assertRefused(temp, "an empty key", "CHECK constraint failed") { connection ->
            connection.prepareStatement(
                "INSERT INTO server_settings (setting_key, int_value) VALUES ('', 1)",
            ).use { statement -> statement.executeUpdate() }
        }
        assertRefused(temp, "a 65-character key", "CHECK constraint failed") { connection ->
            connection.prepareStatement(
                "INSERT INTO server_settings (setting_key, int_value) VALUES ('$longKey', 1)",
            ).use { statement -> statement.executeUpdate() }
        }
    }

    @Test
    fun `server_settings refuses a null int_value`() {
        val temp = openTemp()
        assertRefused(temp, "a null int_value", "NOT NULL constraint failed") { connection ->
            connection.prepareStatement(
                "INSERT INTO server_settings (setting_key, int_value) VALUES ('wrong_pw_limit', NULL)",
            ).use { statement -> statement.executeUpdate() }
        }
    }

    @Test
    fun `an upgrade from version 6 keeps every row and adds an empty server_settings table`() {
        val first = openTemp(listOf(SchemaV1.MIGRATION, SchemaV2.MIGRATION, SchemaV3.MIGRATION, SchemaV4.MIGRATION, SchemaV5.MIGRATION, SchemaV6.MIGRATION))
        val aliceId = inTransaction(first) { connection ->
            TestDatabases.insertAccountRow(
                connection,
                TestDatabases.validAccountRow(mapOf("username" to "alice", "username_lower" to "alice")),
            )
            TestDatabases.longs(connection, "SELECT id FROM accounts WHERE username_lower = 'alice'").single()
        }
        val second = reopen(first)

        inTransaction(second) { connection ->
            assertEquals("sync-api: user_version after the upgrade", 11, TestDatabases.userVersion(connection))
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
                "sync-api: server_settings table exists after upgrade",
                "server_settings" in TestDatabases.tableNames(connection),
            )
            assertEquals(
                "sync-api: server_settings is empty after the upgrade",
                listOf(0L),
                TestDatabases.longs(connection, "SELECT count(*) FROM server_settings"),
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
