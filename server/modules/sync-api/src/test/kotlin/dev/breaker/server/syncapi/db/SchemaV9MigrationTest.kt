package dev.breaker.server.syncapi.db

import java.sql.Connection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class SchemaV9MigrationTest : TempDatabaseTest() {

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

    private fun insertCode(connection: Connection, accountId: Long, hashExpression: String, issued: Long, expires: Long) {
        connection.executeStatement(
            "INSERT INTO reset_codes (account_id, code_hash, issued_at_ms, expires_at_ms) " +
                "VALUES ($accountId, $hashExpression, $issued, $expires)",
        )
    }

    /** The statement fails with a message holding [expected], and it leaves nothing behind. */
    private fun assertRefused(temp: TempDatabase, case: String, expected: String, block: (Connection) -> Unit) {
        try {
            inTransaction(temp, block)
        } catch (failure: Exception) {
            assertTrue(
                "sync-api: $case was refused, but not with '$expected': ${failure.message}",
                failure.message.orEmpty().contains(expected, ignoreCase = true),
            )
            return
        }
        throw AssertionError("sync-api: the database accepted $case but was expected to refuse it")
    }

    @Test
    fun `migration 9 applies to a fresh database and adds reset_pending reset_codes and its index`() {
        val temp = openTemp()
        inTransaction(temp) { connection ->
            assertEquals("sync-api: fresh database user_version", 10, TestDatabases.userVersion(connection))
            val columns = TestDatabases.strings(connection, "SELECT name FROM pragma_table_info('accounts')")
            assertTrue("sync-api: accounts column reset_pending is missing: $columns", "reset_pending" in columns)
            val codeColumns = TestDatabases.strings(connection, "SELECT name FROM pragma_table_info('reset_codes')")
            assertEquals(
                "sync-api: reset_codes columns",
                listOf("account_id", "code_hash", "issued_at_ms", "expires_at_ms"),
                codeColumns,
            )
            assertEquals(
                "sync-api: the expiry index",
                listOf("reset_codes_expires"),
                TestDatabases.strings(connection, "SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'reset_codes_expires'"),
            )
        }
    }

    @Test
    fun `a new account row has reset_pending 0`() {
        val temp = openTemp()
        addAccount(temp, "alice")
        assertEquals("sync-api: a new row must not be pending", 1L, count(temp, "SELECT count(*) FROM accounts WHERE reset_pending = 0"))
    }

    @Test
    fun `an upgrade from version 8 keeps the account and gives reset_pending 0 and an empty reset_codes table`() {
        val first = openTemp(
            listOf(
                SchemaV1.MIGRATION, SchemaV2.MIGRATION, SchemaV3.MIGRATION, SchemaV4.MIGRATION,
                SchemaV5.MIGRATION, SchemaV6.MIGRATION, SchemaV7.MIGRATION, SchemaV8.MIGRATION,
            ),
        )
        val aliceId = addAccount(first, "alice")
        inTransaction(first) { connection ->
            assertEquals("sync-api: setup database user_version", 8, TestDatabases.userVersion(connection))
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
        assertEquals("sync-api: the kept row is not pending", 1L, count(second, "SELECT count(*) FROM accounts WHERE reset_pending = 0"))
        assertEquals("sync-api: reset_codes starts empty", 0L, count(second, "SELECT count(*) FROM reset_codes"))
    }

    @Test
    fun `reset_pending refuses 2 and minus 1 and accepts 1 and 0`() {
        val temp = openTemp()
        val id = addAccount(temp, "alice")
        for (value in listOf(2, -1)) {
            assertRefused(temp, "reset_pending $value", "CHECK constraint failed") { connection ->
                connection.executeStatement("UPDATE accounts SET reset_pending = $value WHERE id = $id")
            }
        }
        assertEquals("sync-api: a refused write left a value", 0L, count(temp, "SELECT count(*) FROM accounts WHERE reset_pending <> 0"))
        inTransaction(temp) { connection -> connection.executeStatement("UPDATE accounts SET reset_pending = 1 WHERE id = $id") }
        assertEquals("sync-api: 1 was not stored", 1L, count(temp, "SELECT count(*) FROM accounts WHERE reset_pending = 1"))
        inTransaction(temp) { connection -> connection.executeStatement("UPDATE accounts SET reset_pending = 0 WHERE id = $id") }
        assertEquals("sync-api: 0 was not stored", 1L, count(temp, "SELECT count(*) FROM accounts WHERE reset_pending = 0"))
    }

    @Test
    fun `code_hash refuses 31 bytes 33 bytes and an empty blob and accepts 32 bytes`() {
        val temp = openTemp()
        val id = addAccount(temp, "alice")
        for (expression in listOf("zeroblob(31)", "zeroblob(33)", "x''")) {
            assertRefused(temp, "a code_hash of $expression", "CHECK constraint failed") { connection ->
                insertCode(connection, id, expression, 1L, 2L)
            }
        }
        assertEquals("sync-api: a refused write left a row", 0L, count(temp, "SELECT count(*) FROM reset_codes"))
        inTransaction(temp) { connection -> insertCode(connection, id, "zeroblob(32)", 1L, 2L) }
        assertEquals("sync-api: 32 bytes were not stored", 1L, count(temp, "SELECT count(*) FROM reset_codes WHERE length(code_hash) = 32"))
    }

    @Test
    fun `code_hash refuses text of 32 characters`() {
        val temp = openTemp()
        val id = addAccount(temp, "alice")
        assertRefused(temp, "a code_hash of 32 characters of text", "CHECK constraint failed") { connection ->
            insertCode(connection, id, "'" + "a".repeat(32) + "'", 1L, 2L)
        }
        assertEquals("sync-api: the refused text left a row", 0L, count(temp, "SELECT count(*) FROM reset_codes"))
    }

    @Test
    fun `expires_at_ms must be above issued_at_ms and issued_at_ms must not be negative`() {
        val temp = openTemp()
        val id = addAccount(temp, "alice")
        assertRefused(temp, "expiry equal to issue", "CHECK constraint failed") { connection ->
            insertCode(connection, id, "zeroblob(32)", 5L, 5L)
        }
        assertRefused(temp, "expiry below issue", "CHECK constraint failed") { connection ->
            insertCode(connection, id, "zeroblob(32)", 5L, 4L)
        }
        assertRefused(temp, "a negative issue", "CHECK constraint failed") { connection ->
            insertCode(connection, id, "zeroblob(32)", -1L, 4L)
        }
        assertEquals("sync-api: a refused write left a row", 0L, count(temp, "SELECT count(*) FROM reset_codes"))
        inTransaction(temp) { connection -> insertCode(connection, id, "zeroblob(32)", 5L, 6L) }
        assertEquals("sync-api: expiry one above issue was not stored", 1L, count(temp, "SELECT count(*) FROM reset_codes"))
    }

    @Test
    fun `an account can hold one code row only`() {
        val temp = openTemp()
        val id = addAccount(temp, "alice")
        inTransaction(temp) { connection -> insertCode(connection, id, "zeroblob(32)", 1L, 2L) }
        assertRefused(temp, "a second code row for one account", "UNIQUE constraint failed") { connection ->
            insertCode(connection, id, "zeroblob(32)", 3L, 4L)
        }
        assertEquals("sync-api: the first row must stay", 1L, count(temp, "SELECT count(*) FROM reset_codes"))
    }

    @Test
    fun `deleting an account deletes its code row and only its own`() {
        val temp = openTemp()
        val alice = addAccount(temp, "alice")
        val bob = addAccount(temp, "bob")
        inTransaction(temp) { connection ->
            insertCode(connection, alice, "zeroblob(32)", 1L, 2L)
            insertCode(connection, bob, "zeroblob(32)", 1L, 2L)
        }
        inTransaction(temp) { connection -> connection.executeStatement("DELETE FROM accounts WHERE id = $alice") }
        assertEquals("sync-api: the code row of the deleted account", 0L, count(temp, "SELECT count(*) FROM reset_codes WHERE account_id = $alice"))
        assertEquals("sync-api: the code row of the other account", 1L, count(temp, "SELECT count(*) FROM reset_codes WHERE account_id = $bob"))
    }
}
