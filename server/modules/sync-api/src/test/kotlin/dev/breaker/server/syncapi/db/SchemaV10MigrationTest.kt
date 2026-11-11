package dev.breaker.server.syncapi.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class SchemaV10MigrationTest : TempDatabaseTest() {

    private val versionsOneToNine = listOf(
        SchemaV1.MIGRATION, SchemaV2.MIGRATION, SchemaV3.MIGRATION, SchemaV4.MIGRATION, SchemaV5.MIGRATION,
        SchemaV6.MIGRATION, SchemaV7.MIGRATION, SchemaV8.MIGRATION, SchemaV9.MIGRATION,
    )

    private fun addAccount(temp: TempDatabase, name: String): Long =
        inTransaction(temp) { connection ->
            TestDatabases.insertAccountRow(
                connection,
                TestDatabases.validAccountRow(mapOf("username" to name, "username_lower" to name)),
            )
            TestDatabases.longs(connection, "SELECT id FROM accounts WHERE username_lower = '$name'").single()
        }

    private fun count(temp: TempDatabase, sql: String): Long =
        inTransaction(temp) { connection -> TestDatabases.longs(connection, sql).single() }

    private fun definitionOf(temp: TempDatabase, name: String): List<String> =
        inTransaction(temp) { connection ->
            TestDatabases.strings(connection, "SELECT sql FROM sqlite_master WHERE name = '$name'")
        }

    @Test
    fun `migration 10 applies to a fresh database with the pending_sealed columns and its index`() {
        val temp = openTemp()
        inTransaction(temp) { connection ->
            assertEquals("sync-api: fresh database user_version", 10, TestDatabases.userVersion(connection))
            assertEquals(
                "sync-api: pending_sealed columns",
                listOf("id", "account_id", "sealed_box", "created_at"),
                TestDatabases.strings(connection, "SELECT name FROM pragma_table_info('pending_sealed')"),
            )
            assertEquals(
                "sync-api: the account index",
                listOf("pending_sealed_account"),
                TestDatabases.strings(connection, "SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'pending_sealed_account'"),
            )
        }
    }

    @Test
    fun `an upgrade from version 9 keeps the account and starts with an empty pending_sealed table`() {
        val first = openTemp(versionsOneToNine)
        val aliceId = addAccount(first, "alice")
        inTransaction(first) { connection ->
            assertEquals("sync-api: setup database user_version", 9, TestDatabases.userVersion(connection))
        }
        val second = reopen(first)
        inTransaction(second) { connection ->
            assertEquals("sync-api: user_version after the upgrade", 10, TestDatabases.userVersion(connection))
            assertEquals(
                "sync-api: the account row is kept with its id",
                listOf(aliceId),
                TestDatabases.longs(connection, "SELECT id FROM accounts WHERE username_lower = 'alice'"),
            )
        }
        assertEquals("sync-api: pending_sealed starts empty", 0L, count(second, "SELECT count(*) FROM pending_sealed"))
    }

    @Test
    fun `a migrated database and a fresh database hold the same pending_sealed definitions`() {
        val fresh = openTemp()
        val migrated = reopen(openTemp(versionsOneToNine))
        assertEquals("sync-api: table definition", definitionOf(fresh, "pending_sealed"), definitionOf(migrated, "pending_sealed"))
        assertEquals("sync-api: index definition", definitionOf(fresh, "pending_sealed_account"), definitionOf(migrated, "pending_sealed_account"))
        assertEquals("sync-api: the definition exists", 1, definitionOf(fresh, "pending_sealed").size)
    }

    @Test
    fun `a row goes with its account and a row of an unknown account is refused`() {
        val temp = openTemp()
        val aliceId = addAccount(temp, "alice")
        inTransaction(temp) { connection ->
            connection.executeStatement("INSERT INTO pending_sealed (id, account_id, sealed_box, created_at) VALUES ('r1', $aliceId, 'YQ==', '2026-10-11T12:00:00Z')")
        }
        assertEquals("sync-api: the row is stored", 1L, count(temp, "SELECT count(*) FROM pending_sealed"))
        inTransaction(temp) { connection -> connection.executeStatement("DELETE FROM accounts WHERE id = $aliceId") }
        assertEquals("sync-api: the row went with the account", 0L, count(temp, "SELECT count(*) FROM pending_sealed"))
        try {
            inTransaction(temp) { connection ->
                connection.executeStatement("INSERT INTO pending_sealed (id, account_id, sealed_box, created_at) VALUES ('r2', 999999, 'YQ==', '2026-10-11T12:00:00Z')")
            }
            throw AssertionError("sync-api: the database accepted a row of an unknown account")
        } catch (failure: Exception) {
            assertTrue(
                "sync-api: refused, but not by the foreign key: ${failure.message}",
                failure.message.orEmpty().contains("FOREIGN KEY", ignoreCase = true),
            )
        }
    }

    @Test
    fun `a second row with the same id is refused`() {
        val temp = openTemp()
        val aliceId = addAccount(temp, "alice")
        inTransaction(temp) { connection ->
            connection.executeStatement("INSERT INTO pending_sealed (id, account_id, sealed_box, created_at) VALUES ('r1', $aliceId, 'YQ==', '2026-10-11T12:00:00Z')")
        }
        try {
            inTransaction(temp) { connection ->
                connection.executeStatement("INSERT INTO pending_sealed (id, account_id, sealed_box, created_at) VALUES ('r1', $aliceId, 'Yg==', '2026-10-11T12:00:01Z')")
            }
            throw AssertionError("sync-api: the database accepted a repeated id")
        } catch (failure: Exception) {
            assertTrue(
                "sync-api: refused, but not as a repeated key: ${failure.message}",
                failure.message.orEmpty().contains("UNIQUE", ignoreCase = true) || failure.message.orEmpty().contains("PRIMARY", ignoreCase = true),
            )
        }
    }
    private val accountColumnIndex = "pending_sealed_account"

    private fun refusesNull(column: String, insertSql: (accountId: Long) -> String) {
        val temp = openTemp()
        val aliceId = addAccount(temp, "alice")
        try {
            inTransaction(temp) { connection -> connection.executeStatement(insertSql(aliceId)) }
            throw AssertionError("sync-api: the database accepted a NULL $column")
        } catch (failure: Exception) {
            val message = failure.message.orEmpty()
            assertTrue("sync-api: refused, but not as NOT NULL on $column: $message", message.contains("NOT NULL") && message.contains(column))
        }
        assertEquals("sync-api: no row was stored for the NULL $column", 0L, count(temp, "SELECT count(*) FROM pending_sealed"))
    }

    @Test
    fun `the pending_sealed index covers the account_id column and nothing else`() {
        val temp = openTemp()
        inTransaction(temp) { connection ->
            assertEquals(
                "sync-api: indexed columns",
                listOf("account_id"),
                TestDatabases.strings(connection, "SELECT name FROM pragma_index_info('$accountColumnIndex')"),
            )
        }
    }

    @Test
    fun `a NULL account_id is refused`() {
        refusesNull("account_id") { _ ->
            "INSERT INTO pending_sealed (id, account_id, sealed_box, created_at) VALUES ('n1', NULL, 'YQ==', '2026-10-11T12:00:00Z')"
        }
    }

    @Test
    fun `a NULL sealed_box is refused`() {
        refusesNull("sealed_box") { accountId ->
            "INSERT INTO pending_sealed (id, account_id, sealed_box, created_at) VALUES ('n2', $accountId, NULL, '2026-10-11T12:00:00Z')"
        }
    }

    @Test
    fun `a NULL created_at is refused`() {
        refusesNull("created_at") { accountId ->
            "INSERT INTO pending_sealed (id, account_id, sealed_box, created_at) VALUES ('n3', $accountId, 'YQ==', NULL)"
        }
    }

    @Test
    fun `the sealed_box column is declared TEXT and a stored value reads back as text`() {
        val temp = openTemp()
        val aliceId = addAccount(temp, "alice")
        inTransaction(temp) { connection ->
            connection.executeStatement("INSERT INTO pending_sealed (id, account_id, sealed_box, created_at) VALUES ('t1', $aliceId, 'YQ==', '2026-10-11T12:00:00Z')")
        }
        inTransaction(temp) { connection ->
            assertEquals(
                "sync-api: declared type",
                listOf("TEXT"),
                TestDatabases.strings(connection, "SELECT type FROM pragma_table_info('pending_sealed') WHERE name = 'sealed_box'"),
            )
            assertEquals(
                "sync-api: stored type",
                listOf("text"),
                TestDatabases.strings(connection, "SELECT typeof(sealed_box) FROM pending_sealed WHERE id = 't1'"),
            )
        }
    }

    @Test
    fun `migration 10 adds exactly one table and removes none`() {
        val before = inTransaction(openTemp(versionsOneToNine)) { connection -> TestDatabases.tableNames(connection) }
        val after = inTransaction(openTemp()) { connection -> TestDatabases.tableNames(connection) }
        assertEquals("sync-api: the added tables", listOf("pending_sealed"), after.filter { it !in before })
        assertEquals("sync-api: the removed tables", emptyList<String>(), before.filter { it !in after })
    }
}
