package dev.breaker.server.syncapi.db

import java.sql.Connection
import java.sql.SQLException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class SchemaV5MigrationTest : TempDatabaseTest() {

    private fun addAccount(temp: TempDatabase, name: String): Long =
        inTransaction(temp) { connection ->
            TestDatabases.insertAccountRow(
                connection,
                TestDatabases.validAccountRow(mapOf("username" to name, "username_lower" to name)),
            )
            TestDatabases.longs(connection, "SELECT id FROM accounts WHERE username_lower = '$name'").single()
        }

    // A client id is 36 characters: 35 letters and one digit.
    private fun clientId(n: Int): String = "c".repeat(35) + n

    // changeNo null = the column list of version 4, which has no change number yet.
    private fun insertRow(connection: Connection, accountId: Long, n: Int, changeNo: Long?) {
        val sql = if (changeNo == null) {
            "INSERT INTO transcriptions (account_id, client_id, key_version, encrypted, stored_at_ms) " +
                "VALUES (?, ?, 0, 'aGVsbG8=', 1)"
        } else {
            "INSERT INTO transcriptions (account_id, client_id, key_version, encrypted, stored_at_ms, change_no) " +
                "VALUES (?, ?, 0, 'aGVsbG8=', 1, ?)"
        }
        connection.prepareStatement(sql).use { statement ->
            statement.setLong(1, accountId)
            statement.setString(2, clientId(n))
            if (changeNo != null) statement.setLong(3, changeNo)
            statement.executeUpdate()
        }
    }

    private fun insertTokens(connection: Connection, accountId: Long) {
        connection.prepareStatement(
            "INSERT INTO tokens (account_id, kind, token_hash, created_at_ms, expires_at_ms) " +
                "VALUES (?, 'session', ?, 1, 5000)",
        ).use { statement ->
            statement.setLong(1, accountId)
            statement.setBytes(2, ByteArray(32) { 9 })
            statement.executeUpdate()
        }
        connection.prepareStatement(
            "INSERT INTO tokens (account_id, kind, scope, label, token_hash, created_at_ms) " +
                "VALUES (?, 'agent', 'transcribe', 'ci', ?, 2)",
        ).use { statement ->
            statement.setLong(1, accountId)
            statement.setBytes(2, ByteArray(32) { 10 })
            statement.executeUpdate()
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

    // Every case runs in its own transaction: a nested one would wait for the open one for ever.
    private fun refusalOf(temp: TempDatabase, block: (Connection) -> Unit): SQLException? =
        try {
            inTransaction(temp, block)
            null
        } catch (failure: SQLException) {
            failure
        }

    private fun assertRefused(temp: TempDatabase, case: String, expected: String, block: (Connection) -> Unit) {
        val refusal = refusalOf(temp, block)
        assertNotNull("sync-api: the database accepted $case", refusal)
        assertTrue(
            "sync-api: $case was refused, but not with '$expected': ${refusal?.message}",
            refusal?.message.orEmpty().contains(expected, ignoreCase = true),
        )
    }

    private fun assertAccepted(temp: TempDatabase, case: String, block: (Connection) -> Unit) {
        val refusal = refusalOf(temp, block)
        assertNull("sync-api: the database refused $case: ${refusal?.message}", refusal)
    }

    private fun update(connection: Connection, sql: String) {
        connection.prepareStatement(sql).use { statement -> statement.executeUpdate() }
    }

    @Test
    fun `a fresh database ends at the latest version with the change columns, the delete flag, the pulled change column and the unique index`() {
        val temp = openTemp()
        inTransaction(temp) { connection ->
            assertEquals("sync-api: fresh database user_version", 5, TestDatabases.userVersion(connection))
            val accountColumns = names(connection, "SELECT name FROM pragma_table_info('accounts')")
            assertTrue("sync-api: accounts.change_seq is missing: $accountColumns", "change_seq" in accountColumns)
            assertTrue("sync-api: accounts.purged_through is missing: $accountColumns", "purged_through" in accountColumns)
            val rowColumns = names(connection, "SELECT name FROM pragma_table_info('transcriptions')")
            assertTrue("sync-api: transcriptions.change_no is missing: $rowColumns", "change_no" in rowColumns)
            assertTrue("sync-api: transcriptions.deleted is missing: $rowColumns", "deleted" in rowColumns)
            val tokenColumns = names(connection, "SELECT name FROM pragma_table_info('tokens')")
            assertTrue("sync-api: tokens.last_pulled_change is missing: $tokenColumns", "last_pulled_change" in tokenColumns)
            assertEquals(
                "sync-api: the unique index must cover account_id then change_no",
                listOf("account_id", "change_no"),
                names(connection, "SELECT name FROM pragma_index_info('transcriptions_account_change_no') ORDER BY seqno"),
            )
            assertEquals(
                "sync-api: the index on (account_id, change_no) must be UNIQUE",
                listOf(1L),
                TestDatabases.longs(
                    connection,
                    "SELECT \"unique\" FROM pragma_index_list('transcriptions') WHERE name = 'transcriptions_account_change_no'",
                ),
            )
        }
    }

    @Test
    fun `the new columns refuse a negative counter, a negative purge mark and a delete flag of 2`() {
        val temp = openTemp()
        val alice = addAccount(temp, "alice")
        inTransaction(temp) { connection -> insertRow(connection, alice, 1, 1L) }
        assertRefused(temp, "a change_seq of -1", "CHECK constraint failed") { connection ->
            update(connection, "UPDATE accounts SET change_seq = -1 WHERE id = $alice")
        }
        assertRefused(temp, "a purged_through of -1", "CHECK constraint failed") { connection ->
            update(connection, "UPDATE accounts SET purged_through = -1 WHERE id = $alice")
        }
        assertRefused(temp, "a delete flag of 2", "CHECK constraint failed") { connection ->
            update(connection, "UPDATE transcriptions SET deleted = 2 WHERE account_id = $alice")
        }
        assertAccepted(temp, "a counter of 5 and a purge mark of 3") { connection ->
            update(connection, "UPDATE accounts SET change_seq = 5, purged_through = 3 WHERE id = $alice")
        }
        assertAccepted(temp, "a delete flag of 1 with the placeholder body") { connection ->
            update(connection, "UPDATE transcriptions SET deleted = 1, encrypted = '-' WHERE account_id = $alice")
        }
    }

    @Test
    fun `the unique index refuses a repeated change number of one account and accepts it on another account`() {
        val temp = openTemp()
        val alice = addAccount(temp, "alice")
        val bob = addAccount(temp, "bob")
        inTransaction(temp) { connection -> insertRow(connection, alice, 1, 1L) }
        assertRefused(temp, "a second row of one account with the same change number", "UNIQUE constraint failed") { connection ->
            insertRow(connection, alice, 2, 1L)
        }
        assertAccepted(temp, "the same change number on another account") { connection ->
            insertRow(connection, bob, 1, 1L)
        }
        assertAccepted(temp, "the next change number on the first account") { connection ->
            insertRow(connection, alice, 2, 2L)
        }
    }

    @Test
    fun `an upgrade from version 4 keeps every row, numbers each account from 1 in id order and sets each counter`() {
        val first = openTemp(listOf(SchemaV1.MIGRATION, SchemaV2.MIGRATION, SchemaV3.MIGRATION, SchemaV4.MIGRATION))
        val alice = addAccount(first, "alice")
        val bob = addAccount(first, "bob")
        inTransaction(first) { connection ->
            assertEquals("sync-api: setup database user_version", 4, TestDatabases.userVersion(connection))
            // Row ids run 1 to 5 and the accounts are interleaved: alice, bob, alice, bob, alice.
            insertRow(connection, alice, 1, null)
            insertRow(connection, bob, 1, null)
            insertRow(connection, alice, 2, null)
            insertRow(connection, bob, 2, null)
            insertRow(connection, alice, 3, null)
            insertTokens(connection, alice)
        }

        val second = reopen(first)

        inTransaction(second) { connection ->
            assertEquals("sync-api: user_version after the upgrade", 5, TestDatabases.userVersion(connection))
            assertEquals(
                "sync-api: a transcription row was lost or duplicated by the upgrade",
                listOf(5L),
                TestDatabases.longs(connection, "SELECT count(*) FROM transcriptions"),
            )
            assertEquals(
                "sync-api: every body must be kept",
                listOf(5L),
                TestDatabases.longs(connection, "SELECT count(*) FROM transcriptions WHERE encrypted = 'aGVsbG8='"),
            )
            assertEquals(
                "sync-api: the first account's numbers in id order",
                listOf(1L, 2L, 3L),
                TestDatabases.longs(connection, "SELECT change_no FROM transcriptions WHERE account_id = $alice ORDER BY id"),
            )
            assertEquals(
                "sync-api: the second account's numbers in id order",
                listOf(1L, 2L),
                TestDatabases.longs(connection, "SELECT change_no FROM transcriptions WHERE account_id = $bob ORDER BY id"),
            )
            assertEquals(
                "sync-api: each account's counter equals its row count",
                listOf(3L, 2L),
                TestDatabases.longs(connection, "SELECT change_seq FROM accounts ORDER BY id"),
            )
            assertEquals(
                "sync-api: no purge mark may be set by the upgrade",
                listOf(0L),
                TestDatabases.longs(connection, "SELECT count(*) FROM accounts WHERE purged_through <> 0"),
            )
            assertEquals(
                "sync-api: no row may be marked deleted by the upgrade",
                listOf(0L),
                TestDatabases.longs(connection, "SELECT count(*) FROM transcriptions WHERE deleted <> 0"),
            )
            assertEquals(
                "sync-api: both token rows must be kept",
                listOf(2L),
                TestDatabases.longs(connection, "SELECT count(*) FROM tokens"),
            )
            assertEquals(
                "sync-api: no token has a pulled change number after the upgrade",
                listOf(0L),
                TestDatabases.longs(connection, "SELECT count(*) FROM tokens WHERE last_pulled_change IS NOT NULL"),
            )
        }

        val carol = addAccount(second, "carol")
        assertAccepted(second, "change number 1 on a third account") { connection ->
            insertRow(connection, carol, 1, 1L)
        }
        assertRefused(second, "change number 3 again on the first account", "UNIQUE constraint failed") { connection ->
            insertRow(connection, alice, 4, 3L)
        }
    }
}
