package dev.breaker.server.syncapi.db

import java.sql.Connection
import java.sql.SQLException
import java.sql.Types
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

internal class SchemaV2Test : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private var accountId: Long = 0L

    @Before
    fun openDatabaseWithAccount() {
        temp = openTemp()
        accountId = insertAccount("alice")
    }

    private fun insertAccount(name: String): Long =
        inTransaction(temp) { connection ->
            TestDatabases.insertAccountRow(
                connection,
                TestDatabases.validAccountRow(mapOf("username" to name, "username_lower" to name)),
            )
            TestDatabases.longs(connection, "SELECT id FROM accounts WHERE username_lower = '$name'").single()
        }

    // A valid session row. The hash bytes are all [seed], so rows with different
    // seeds never collide on the unique hash by accident.
    private fun validToken(owner: Long, overrides: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        val row = LinkedHashMap<String, Any?>()
        row["account_id"] = owner
        row["kind"] = "session"
        row["scope"] = null
        row["label"] = null
        row["token_hash"] = ByteArray(32) { 1 }
        row["created_at_ms"] = 1000L
        row["expires_at_ms"] = null
        row["revoked_at_ms"] = null
        // A misspelt column would otherwise add a column and make the caller's
        // single-defect row fail for the wrong reason.
        for (key in overrides.keys) {
            require(row.containsKey(key)) { "sync-api test helper: unknown tokens column '$key'" }
        }
        row.putAll(overrides)
        return row
    }

    private fun validAgentToken(owner: Long, overrides: Map<String, Any?> = emptyMap()): Map<String, Any?> =
        validToken(owner, mapOf<String, Any?>("kind" to "agent", "scope" to "transcribe", "label" to "ci") + overrides)

    private fun insertToken(connection: Connection, row: Map<String, Any?>) {
        val columns = row.keys.toList()
        val sql = "INSERT INTO tokens (" + columns.joinToString(", ") + ") VALUES (" +
            columns.joinToString(", ") { "?" } + ")"
        connection.prepareStatement(sql).use { statement ->
            for ((index, column) in columns.withIndex()) {
                val value: Any? = row[column]
                val position = index + 1
                when (value) {
                    null -> statement.setNull(position, Types.NULL)
                    is ByteArray -> statement.setBytes(position, value)
                    is String -> statement.setString(position, value)
                    is Int -> statement.setLong(position, value.toLong())
                    is Long -> statement.setLong(position, value)
                    else -> throw IllegalArgumentException("sync-api test helper: unsupported value type for '$column'")
                }
            }
            statement.executeUpdate()
        }
    }

    private fun tokenCount(): Long =
        inTransaction(temp) { connection -> TestDatabases.longs(connection, "SELECT count(*) FROM tokens").single() }

    /**
     * The database must refuse [defective] with a constraint error naming [kindOfConstraint],
     * and must then accept [control], the same row with the defect repaired. The control
     * runs second, so a refusal that came from the fixture rather than from the defect
     * would show up as a refused control.
     */
    private fun assertRefusedThenControlAccepted(
        case: String,
        kindOfConstraint: String,
        defective: Map<String, Any?>,
        control: Map<String, Any?>,
    ) {
        val before = tokenCount()
        val refusal: SQLException? = try {
            inTransaction(temp) { connection -> insertToken(connection, defective) }
            null
        } catch (failure: SQLException) {
            failure
        }
        assertNotNull("sync-api: the database accepted a token row with $case", refusal)
        assertTrue(
            "sync-api: $case was refused, but not by a $kindOfConstraint constraint: ${refusal?.message}",
            refusal?.message.orEmpty().contains(kindOfConstraint, ignoreCase = true),
        )
        assertEquals("sync-api: a refused token row with $case was stored", before, tokenCount())
        try {
            inTransaction(temp) { connection -> insertToken(connection, control) }
        } catch (failure: SQLException) {
            fail("sync-api: the database refused the valid control row for $case: ${failure.message}")
        }
        assertEquals("sync-api: the valid control row for $case was not stored", before + 1, tokenCount())
    }

    @Test
    fun `a kind outside session and agent is refused`() {
        assertRefusedThenControlAccepted(
            "kind 'device'", "check",
            validToken(accountId, mapOf("kind" to "device")),
            validToken(accountId),
        )
    }

    @Test
    fun `an agent row without a scope is refused`() {
        assertRefusedThenControlAccepted(
            "an agent token without a scope", "check",
            validAgentToken(accountId, mapOf("scope" to null)),
            validAgentToken(accountId),
        )
    }

    @Test
    fun `an agent row without a label is refused`() {
        assertRefusedThenControlAccepted(
            "an agent token without a label", "check",
            validAgentToken(accountId, mapOf("label" to null)),
            validAgentToken(accountId),
        )
    }

    @Test
    fun `a session row with a scope is refused`() {
        assertRefusedThenControlAccepted(
            "a session token with a scope", "check",
            validToken(accountId, mapOf("scope" to "transcribe")),
            validToken(accountId),
        )
    }

    @Test
    fun `a session row with a label is refused`() {
        assertRefusedThenControlAccepted(
            "a session token with a label", "check",
            validToken(accountId, mapOf("label" to "ci")),
            validToken(accountId),
        )
    }

    @Test
    fun `a scope outside transcribe and admin is refused`() {
        assertRefusedThenControlAccepted(
            "scope 'root'", "check",
            validAgentToken(accountId, mapOf("scope" to "root")),
            validAgentToken(accountId, mapOf("scope" to "admin")),
        )
    }

    @Test
    fun `an empty label is refused`() {
        assertRefusedThenControlAccepted(
            "an empty label", "check",
            validAgentToken(accountId, mapOf("label" to "")),
            validAgentToken(accountId, mapOf("label" to "x")),
        )
    }

    @Test
    fun `a label of 65 characters is refused and 64 are accepted`() {
        assertRefusedThenControlAccepted(
            "a 65 character label", "check",
            validAgentToken(accountId, mapOf("label" to "l".repeat(65))),
            validAgentToken(accountId, mapOf("label" to "l".repeat(64))),
        )
    }

    @Test
    fun `a token hash of 31 bytes is refused and 32 are accepted`() {
        assertRefusedThenControlAccepted(
            "a token_hash of 31 bytes", "check",
            validToken(accountId, mapOf("token_hash" to ByteArray(31) { 1 })),
            validToken(accountId),
        )
    }

    @Test
    fun `a token hash of 33 bytes is refused and 32 are accepted`() {
        assertRefusedThenControlAccepted(
            "a token_hash of 33 bytes", "check",
            validToken(accountId, mapOf("token_hash" to ByteArray(33) { 1 })),
            validToken(accountId),
        )
    }

    @Test
    fun `a second row with the same token hash is refused and a different hash is accepted`() {
        inTransaction(temp) { connection -> insertToken(connection, validToken(accountId)) }
        assertRefusedThenControlAccepted(
            "a token_hash already stored", "unique",
            validToken(accountId),
            validToken(accountId, mapOf("token_hash" to ByteArray(32) { 2 })),
        )
    }

    @Test
    fun `a token of an account that does not exist is refused and one of an existing account is accepted`() {
        assertRefusedThenControlAccepted(
            "an account_id with no account", "foreign key",
            validToken(accountId + 1000L),
            validToken(accountId),
        )
    }

    @Test
    fun `a session row with an expiry is accepted so session expiry needs no schema change`() {
        inTransaction(temp) { connection ->
            insertToken(connection, validToken(accountId, mapOf("expires_at_ms" to 5000L)))
        }
        assertEquals(
            "sync-api: the session row with an expiry was not stored with its value",
            listOf(5000L),
            inTransaction(temp) { connection ->
                TestDatabases.longs(connection, "SELECT expires_at_ms FROM tokens WHERE kind = 'session'")
            },
        )
    }

    @Test
    fun `an agent row is accepted with and without an expiry`() {
        inTransaction(temp) { connection ->
            insertToken(connection, validAgentToken(accountId, mapOf("token_hash" to ByteArray(32) { 1 })))
            insertToken(
                connection,
                validAgentToken(accountId, mapOf("token_hash" to ByteArray(32) { 2 }, "expires_at_ms" to 9000L)),
            )
        }
        assertEquals(
            "sync-api: expected one agent row without an expiry and one with an expiry",
            listOf(0L, 1L),
            inTransaction(temp) { connection ->
                TestDatabases.longs(
                    connection,
                    "SELECT expires_at_ms IS NOT NULL FROM tokens WHERE kind = 'agent' ORDER BY token_hash",
                )
            },
        )
    }

    @Test
    fun `deleting an account deletes its tokens and leaves the tokens of other accounts`() {
        val bob = insertAccount("bob")
        inTransaction(temp) { connection ->
            insertToken(connection, validToken(accountId, mapOf("token_hash" to ByteArray(32) { 1 })))
            insertToken(connection, validAgentToken(accountId, mapOf("token_hash" to ByteArray(32) { 2 })))
            insertToken(connection, validToken(bob, mapOf("token_hash" to ByteArray(32) { 3 })))
        }
        assertEquals("sync-api: setup should have stored three tokens", 3L, tokenCount())
        inTransaction(temp) { connection -> connection.executeStatement("DELETE FROM accounts WHERE id = $accountId") }
        assertEquals(
            "sync-api: the tokens of the deleted account must go with it and no other token",
            listOf(bob),
            inTransaction(temp) { connection -> TestDatabases.longs(connection, "SELECT account_id FROM tokens") },
        )
    }

    @Test
    fun `a fresh file database is at version 2 and has the account and kind index`() {
        inTransaction(temp) { connection ->
            assertEquals("sync-api: fresh database user_version", 2, TestDatabases.userVersion(connection))
            assertEquals(
                "sync-api: index tokens_account_kind missing",
                listOf("tokens_account_kind"),
                TestDatabases.strings(
                    connection,
                    "SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'tokens_account_kind'",
                ),
            )
            assertEquals(
                "sync-api: tokens_account_kind must cover account_id then kind",
                listOf("account_id", "kind"),
                TestDatabases.strings(
                    connection,
                    "SELECT name FROM pragma_index_info('tokens_account_kind') ORDER BY seqno",
                ),
            )
        }
    }
}
