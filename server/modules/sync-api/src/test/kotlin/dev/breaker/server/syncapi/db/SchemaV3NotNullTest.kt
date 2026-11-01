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

/**
 * The NOT NULL rules of migration 3, pinned by direct SQL on a fresh migrated database. A CHECK
 * constraint does not refuse a NULL value, so each NOT NULL column needs its own refusal. Each test
 * refuses a row with NULL in one column, naming the constraint kind, and accepts a valid control row,
 * so a refusal that comes from the fixture cannot pass for a rule. The row id is not tested here: it
 * is the INTEGER PRIMARY KEY of transcriptions, which SQLite assigns itself, so a NULL id is not a
 * refusal case.
 */
internal class SchemaV3NotNullTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private var alice: Long = 0L

    @Before
    fun openFreshDatabase() {
        temp = openTemp()
        alice = insertAccount("alice")
    }

    @Test
    fun `a null account id is refused and a valid row is accepted`() {
        assertRefused("a transcription with a null account id", "not null") { connection ->
            insertRow(connection, validRow(alice, mapOf("account_id" to null)))
        }
        assertAccepted("a valid transcription row") { connection -> insertRow(connection, validRow(alice)) }
        assertEquals("sync-api: the rows after the refusal and the control", 1L, transcriptionTotal())
    }

    @Test
    fun `a null client id is refused and a valid row is accepted`() {
        assertRefused("a transcription with a null client id", "not null") { connection ->
            insertRow(connection, validRow(alice, mapOf("client_id" to null)))
        }
        assertAccepted("a valid transcription row") { connection -> insertRow(connection, validRow(alice)) }
        assertEquals("sync-api: the rows after the refusal and the control", 1L, transcriptionTotal())
    }

    @Test
    fun `a null key version on a transcription is refused and a valid row is accepted`() {
        assertRefused("a transcription with a null key version", "not null") { connection ->
            insertRow(connection, validRow(alice, mapOf("key_version" to null)))
        }
        assertAccepted("a valid transcription row") { connection -> insertRow(connection, validRow(alice)) }
        assertEquals("sync-api: the rows after the refusal and the control", 1L, transcriptionTotal())
    }

    @Test
    fun `a null encrypted value is refused and a valid row is accepted`() {
        assertRefused("a transcription with a null encrypted value", "not null") { connection ->
            insertRow(connection, validRow(alice, mapOf("encrypted" to null)))
        }
        assertAccepted("a valid transcription row") { connection -> insertRow(connection, validRow(alice)) }
        assertEquals("sync-api: the rows after the refusal and the control", 1L, transcriptionTotal())
    }

    @Test
    fun `a null stored time is refused and a valid row is accepted`() {
        assertRefused("a transcription with a null stored time", "not null") { connection ->
            insertRow(connection, validRow(alice, mapOf("stored_at_ms" to null)))
        }
        assertAccepted("a valid transcription row") { connection -> insertRow(connection, validRow(alice)) }
        assertEquals("sync-api: the rows after the refusal and the control", 1L, transcriptionTotal())
    }

    @Test
    fun `a null account key version is refused and a key version of one is accepted`() {
        assertRefused("an account key version of null", "not null") { connection ->
            connection.prepareStatement("UPDATE accounts SET key_version = NULL WHERE id = ?").use { statement ->
                statement.setLong(1, alice)
                statement.executeUpdate()
            }
        }
        assertEquals("sync-api: alice's key version after the refusal", listOf(0L), keyVersionOf(alice))
        assertAccepted("an account key version of one") { connection ->
            setAccountKeyVersion(connection, alice, 1)
        }
        assertEquals("sync-api: alice's key version after the control", listOf(1L), keyVersionOf(alice))
    }

    /**
     * The database must refuse [defective], and the refusal must name [kindOfConstraint]. A refused
     * row must not be stored: the transcription count is unchanged.
     */
    private fun assertRefused(case: String, kindOfConstraint: String, defective: (Connection) -> Unit) {
        val before = transcriptionTotal()
        val refusal: SQLException? = try {
            inTransaction(temp, defective)
            null
        } catch (failure: SQLException) {
            failure
        }
        assertNotNull("sync-api: the database accepted $case", refusal)
        assertTrue(
            "sync-api: $case was refused, but not by a $kindOfConstraint constraint: ${refusal?.message}",
            refusal?.message.orEmpty().contains(kindOfConstraint, ignoreCase = true),
        )
        assertEquals("sync-api: a refused row for $case was stored", before, transcriptionTotal())
    }

    private fun assertAccepted(case: String, valid: (Connection) -> Unit) {
        try {
            inTransaction(temp, valid)
        } catch (failure: SQLException) {
            fail("sync-api: the database refused the valid row for $case: ${failure.message}")
        }
    }

    private fun insertAccount(name: String): Long {
        inTransaction(temp) { connection ->
            TestDatabases.insertAccountRow(
                connection,
                TestDatabases.validAccountRow(mapOf("username" to name, "username_lower" to name)),
            )
        }
        return inTransaction(temp) { connection ->
            TestDatabases.longs(connection, "SELECT max(id) FROM accounts").single()
        }
    }

    /** A complete valid row for `transcriptions`. [overrides] replace single columns; a null value stores NULL. */
    private fun validRow(accountId: Long, overrides: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        val row = LinkedHashMap<String, Any?>()
        row["account_id"] = accountId
        row["client_id"] = CLIENT_ID
        row["key_version"] = 0
        row["encrypted"] = ENCRYPTED
        row["stored_at_ms"] = STORED_AT_MS
        // A misspelt column would otherwise add a column and make the caller's
        // single-defect row fail for the wrong reason.
        for (key in overrides.keys) {
            require(row.containsKey(key)) { "sync-api test helper: unknown transcriptions column '$key'" }
        }
        row.putAll(overrides)
        return row
    }

    private fun insertRow(connection: Connection, row: Map<String, Any?>) {
        val columns = row.keys.toList()
        val sql = "INSERT INTO transcriptions (" + columns.joinToString(", ") + ") VALUES (" +
            columns.joinToString(", ") { "?" } + ")"
        connection.prepareStatement(sql).use { statement ->
            for ((index, column) in columns.withIndex()) {
                val value: Any? = row[column]
                val position = index + 1
                when (value) {
                    null -> statement.setNull(position, Types.NULL)
                    is Int -> statement.setInt(position, value)
                    is Long -> statement.setLong(position, value)
                    is String -> statement.setString(position, value)
                    else -> throw IllegalArgumentException("sync-api test helper: unsupported value for '$column'")
                }
            }
            statement.executeUpdate()
        }
    }

    private fun setAccountKeyVersion(connection: Connection, accountId: Long, keyVersion: Int) {
        connection.prepareStatement("UPDATE accounts SET key_version = ? WHERE id = ?").use { statement ->
            statement.setInt(1, keyVersion)
            statement.setLong(2, accountId)
            statement.executeUpdate()
        }
    }

    private fun keyVersionOf(accountId: Long): List<Long> =
        inTransaction(temp) { connection ->
            TestDatabases.longs(connection, "SELECT key_version FROM accounts WHERE id = $accountId")
        }

    private fun transcriptionTotal(): Long =
        inTransaction(temp) { connection ->
            TestDatabases.longs(connection, "SELECT count(*) FROM transcriptions").single()
        }

    private companion object {
        // 36 characters, the length the client_id CHECK requires.
        const val CLIENT_ID = "0f9c4d1e-6a2b-4c3d-9e8f-1a2b3c4d5e6f"
        const val ENCRYPTED = "aGVsbG8="
        const val STORED_AT_MS = 0L
    }
}
