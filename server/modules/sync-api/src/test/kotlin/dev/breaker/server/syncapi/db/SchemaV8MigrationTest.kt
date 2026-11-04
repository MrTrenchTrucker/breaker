package dev.breaker.server.syncapi.db

import dev.breaker.server.syncapi.accounts.AccountFixtures
import java.sql.Connection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class SchemaV8MigrationTest : TempDatabaseTest() {

    private val keyColumns = listOf(
        "wrapped_dek", "wrapped_box_privkey", "box_pubkey", "prev_wrapped_dek", "rekeyed_at", "reset_at",
    )

    // SQL expressions for the three key columns that satisfy every rule.
    private val goodDek = "zeroblob(60)"
    private val goodPrivkey = "zeroblob(60)"
    private val goodPubkey = "zeroblob(32)"

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

    private fun assertRefused(temp: TempDatabase, case: String, block: (Connection) -> Unit) {
        try {
            inTransaction(temp, block)
        } catch (failure: Exception) {
            assertNotNull("sync-api: the database accepted $case", failure)
            assertTrue(
                "sync-api: $case was refused, but not by a CHECK: ${failure.message}",
                failure.message.orEmpty().contains("CHECK constraint failed", ignoreCase = true),
            )
            return
        }
        throw AssertionError("sync-api: the database accepted $case but was expected to refuse it")
    }

    /** Sets the three key columns from SQL expressions; [column] gets [expression] and the other two stay valid. */
    private fun setKeysWith(connection: Connection, accountId: Long, column: String, expression: String) {
        val values = linkedMapOf("wrapped_dek" to goodDek, "wrapped_box_privkey" to goodPrivkey, "box_pubkey" to goodPubkey)
        require(values.containsKey(column)) { "sync-api test helper: unknown key column '$column'" }
        values[column] = expression
        connection.executeStatement(
            "UPDATE accounts SET wrapped_dek = ${values.getValue("wrapped_dek")}, " +
                "wrapped_box_privkey = ${values.getValue("wrapped_box_privkey")}, " +
                "box_pubkey = ${values.getValue("box_pubkey")} WHERE id = $accountId",
        )
    }

    private fun setPrevious(connection: Connection, accountId: Long, column: String, expression: String) {
        require(column == "prev_wrapped_dek") { "sync-api test helper: unexpected column '$column'" }
        connection.executeStatement("UPDATE accounts SET $column = $expression WHERE id = $accountId")
    }

    /** A column accepts [size] bytes and refuses one byte less, one byte more, an empty blob and text of [size] characters. */
    private fun assertSizeRule(column: String, size: Int, assign: (Connection, Long, String) -> Unit) {
        val temp = openTemp()
        val id = addAccount(temp, "alice")
        assertRefused(temp, "a $column of ${size - 1} bytes") { connection -> assign(connection, id, "zeroblob(${size - 1})") }
        assertRefused(temp, "a $column of ${size + 1} bytes") { connection -> assign(connection, id, "zeroblob(${size + 1})") }
        assertRefused(temp, "an empty $column") { connection -> assign(connection, id, "x''") }
        assertEquals(
            "sync-api: a refused $column write left a value",
            0L,
            count(temp, "SELECT count(*) FROM accounts WHERE $column IS NOT NULL"),
        )
        inTransaction(temp) { connection -> assign(connection, id, "zeroblob($size)") }
        assertEquals(
            "sync-api: the exact $column size was not stored",
            1L,
            count(temp, "SELECT count(*) FROM accounts WHERE $column IS NOT NULL AND length($column) = $size"),
        )
    }

    /** A TEXT value of the right length is refused because the column must hold a blob. */
    private fun assertTextRefused(column: String, size: Int, assign: (Connection, Long, String) -> Unit) {
        val temp = openTemp()
        val id = addAccount(temp, "alice")
        assertRefused(temp, "a $column of $size characters of text") { connection ->
            assign(connection, id, "'" + "a".repeat(size) + "'")
        }
        assertEquals(
            "sync-api: the refused text write left a value in $column",
            0L,
            count(temp, "SELECT count(*) FROM accounts WHERE $column IS NOT NULL"),
        )
    }

    private fun partialUpdate(accountId: Long, combo: List<Boolean>): String {
        val dek = if (combo[0]) goodDek else "NULL"
        val privkey = if (combo[1]) goodPrivkey else "NULL"
        val pubkey = if (combo[2]) goodPubkey else "NULL"
        return "UPDATE accounts SET wrapped_dek = $dek, wrapped_box_privkey = $privkey, box_pubkey = $pubkey WHERE id = $accountId"
    }

    @Test
    fun `migration 8 applies to a fresh database and ends at user_version 8 with the six key columns`() {
        val temp = openTemp()
        inTransaction(temp) { connection ->
            assertEquals("sync-api: fresh database user_version", 8, TestDatabases.userVersion(connection))
            val columns = TestDatabases.strings(connection, "SELECT name FROM pragma_table_info('accounts')")
            for (name in keyColumns) {
                assertTrue("sync-api: accounts column $name is missing: $columns", name in columns)
            }
        }
    }

    @Test
    fun `a new account row has all six new columns NULL and key_version 0`() {
        val temp = openTemp()
        addAccount(temp, "alice")
        assertEquals(
            "sync-api: a new row must be keyless with key_version 0",
            1L,
            count(
                temp,
                "SELECT count(*) FROM accounts WHERE wrapped_dek IS NULL AND wrapped_box_privkey IS NULL " +
                    "AND box_pubkey IS NULL AND prev_wrapped_dek IS NULL AND rekeyed_at IS NULL " +
                    "AND reset_at IS NULL AND key_version = 0",
            ),
        )
    }

    @Test
    fun `an upgrade from version 7 keeps the account and a token row and gives NULL key columns`() {
        val first = openTemp(
            listOf(
                SchemaV1.MIGRATION, SchemaV2.MIGRATION, SchemaV3.MIGRATION, SchemaV4.MIGRATION,
                SchemaV5.MIGRATION, SchemaV6.MIGRATION, SchemaV7.MIGRATION,
            ),
        )
        val aliceId = addAccount(first, "alice")
        inTransaction(first) { connection ->
            assertEquals("sync-api: setup database user_version", 7, TestDatabases.userVersion(connection))
            connection.prepareStatement(
                "INSERT INTO tokens (account_id, kind, token_hash, created_at_ms, expires_at_ms) " +
                    "VALUES (?, 'session', ?, 1, ?)",
            ).use { statement ->
                statement.setLong(1, aliceId)
                statement.setBytes(2, ByteArray(32) { 9 })
                statement.setLong(3, AccountFixtures.FIXED_MILLIS)
                statement.executeUpdate()
            }
        }
        val second = reopen(first)

        inTransaction(second) { connection ->
            assertEquals("sync-api: user_version after the upgrade", 8, TestDatabases.userVersion(connection))
            assertEquals(
                "sync-api: the account row is kept with its id",
                listOf(aliceId),
                TestDatabases.longs(connection, "SELECT id FROM accounts WHERE username_lower = 'alice'"),
            )
            assertEquals(
                "sync-api: the token row must survive the upgrade",
                listOf(1L),
                TestDatabases.longs(connection, "SELECT count(*) FROM tokens WHERE account_id = $aliceId"),
            )
        }
        assertEquals(
            "sync-api: the upgraded row must be keyless with key_version 0",
            1L,
            count(
                second,
                "SELECT count(*) FROM accounts WHERE wrapped_dek IS NULL AND wrapped_box_privkey IS NULL " +
                    "AND box_pubkey IS NULL AND prev_wrapped_dek IS NULL AND rekeyed_at IS NULL " +
                    "AND reset_at IS NULL AND key_version = 0",
            ),
        )
    }

    @Test
    fun `wrapped_dek refuses 59 bytes 61 bytes and an empty blob and accepts 60 bytes`() {
        assertSizeRule("wrapped_dek", 60) { connection, id, expression -> setKeysWith(connection, id, "wrapped_dek", expression) }
    }

    @Test
    fun `wrapped_box_privkey refuses 59 bytes 61 bytes and an empty blob and accepts 60 bytes`() {
        assertSizeRule("wrapped_box_privkey", 60) { connection, id, expression ->
            setKeysWith(connection, id, "wrapped_box_privkey", expression)
        }
    }

    @Test
    fun `box_pubkey refuses 31 bytes 33 bytes and an empty blob and accepts 32 bytes`() {
        assertSizeRule("box_pubkey", 32) { connection, id, expression -> setKeysWith(connection, id, "box_pubkey", expression) }
    }

    @Test
    fun `prev_wrapped_dek refuses 59 bytes 61 bytes and an empty blob and accepts 60 bytes`() {
        assertSizeRule("prev_wrapped_dek", 60) { connection, id, expression ->
            setPrevious(connection, id, "prev_wrapped_dek", expression)
        }
    }

    @Test
    fun `wrapped_dek refuses text of the right length`() {
        assertTextRefused("wrapped_dek", 60) { connection, id, expression -> setKeysWith(connection, id, "wrapped_dek", expression) }
    }

    @Test
    fun `wrapped_box_privkey refuses text of the right length`() {
        assertTextRefused("wrapped_box_privkey", 60) { connection, id, expression ->
            setKeysWith(connection, id, "wrapped_box_privkey", expression)
        }
    }

    @Test
    fun `box_pubkey refuses text of the right length`() {
        assertTextRefused("box_pubkey", 32) { connection, id, expression -> setKeysWith(connection, id, "box_pubkey", expression) }
    }

    @Test
    fun `prev_wrapped_dek refuses text of the right length`() {
        assertTextRefused("prev_wrapped_dek", 60) { connection, id, expression ->
            setPrevious(connection, id, "prev_wrapped_dek", expression)
        }
    }

    @Test
    fun `the three key columns refuse each of the six partial combinations and accept all set and all NULL`() {
        val temp = openTemp()
        val id = addAccount(temp, "alice")
        val partial = listOf(
            listOf(true, false, false), listOf(false, true, false), listOf(false, false, true),
            listOf(true, true, false), listOf(true, false, true), listOf(false, true, true),
        )
        for (combo in partial) {
            assertRefused(temp, "the key columns set as $combo on a keyless row") { connection ->
                connection.executeStatement(partialUpdate(id, combo))
            }
        }
        assertEquals(
            "sync-api: a refused combination left a value",
            0L,
            count(temp, "SELECT count(*) FROM accounts WHERE wrapped_dek IS NOT NULL OR wrapped_box_privkey IS NOT NULL OR box_pubkey IS NOT NULL"),
        )
        inTransaction(temp) { connection -> connection.executeStatement(partialUpdate(id, listOf(true, true, true))) }
        assertEquals(
            "sync-api: all three set must be accepted",
            1L,
            count(temp, "SELECT count(*) FROM accounts WHERE wrapped_dek IS NOT NULL AND wrapped_box_privkey IS NOT NULL AND box_pubkey IS NOT NULL"),
        )
        for (combo in partial) {
            assertRefused(temp, "the key columns set as $combo on a keyed row") { connection ->
                connection.executeStatement(partialUpdate(id, combo))
            }
        }
        assertEquals(
            "sync-api: a refused combination changed the keyed row",
            1L,
            count(temp, "SELECT count(*) FROM accounts WHERE wrapped_dek IS NOT NULL AND wrapped_box_privkey IS NOT NULL AND box_pubkey IS NOT NULL"),
        )
        inTransaction(temp) { connection -> connection.executeStatement(partialUpdate(id, listOf(false, false, false))) }
        assertEquals(
            "sync-api: all three NULL must be accepted",
            1L,
            count(temp, "SELECT count(*) FROM accounts WHERE wrapped_dek IS NULL AND wrapped_box_privkey IS NULL AND box_pubkey IS NULL"),
        )
    }

    @Test
    fun `rekeyed_at and reset_at refuse minus 1 and accept 0 and a positive value`() {
        val temp = openTemp()
        val id = addAccount(temp, "alice")
        for (column in listOf("rekeyed_at", "reset_at")) {
            assertRefused(temp, "a $column of minus 1") { connection ->
                connection.executeStatement("UPDATE accounts SET $column = -1 WHERE id = $id")
            }
            assertEquals(
                "sync-api: a refused $column write left a value",
                0L,
                count(temp, "SELECT count(*) FROM accounts WHERE $column IS NOT NULL"),
            )
            inTransaction(temp) { connection -> connection.executeStatement("UPDATE accounts SET $column = 0 WHERE id = $id") }
            assertEquals("sync-api: $column of 0 was not stored", 1L, count(temp, "SELECT count(*) FROM accounts WHERE $column = 0"))
            val positive = AccountFixtures.FIXED_MILLIS
            inTransaction(temp) { connection -> connection.executeStatement("UPDATE accounts SET $column = $positive WHERE id = $id") }
            assertEquals(
                "sync-api: a positive $column was not stored",
                1L,
                count(temp, "SELECT count(*) FROM accounts WHERE $column = $positive"),
            )
        }
    }
}
