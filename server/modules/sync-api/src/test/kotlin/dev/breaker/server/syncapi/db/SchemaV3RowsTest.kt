package dev.breaker.server.syncapi.db

import dev.breaker.server.syncapi.transcriptions.TranscriptionFixtures
import java.sql.Connection
import java.sql.SQLException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

internal class SchemaV3RowsTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private var accountId: Long = 0L

    @Before
    fun openDatabaseWithAccount() {
        temp = openTemp()
        accountId = TranscriptionFixtures.accountId(temp, "alice")
    }

    // A valid row: a 36 character client id, key version 0 and a non-empty text.
    // A misspelt column would otherwise add a column and make a defect fail for the wrong reason.
    private fun validRow(owner: Long, overrides: Map<String, Any> = emptyMap()): Map<String, Any> {
        val row = mapOf<String, Any>(
            "account_id" to owner,
            "client_id" to TranscriptionFixtures.clientId(1),
            "key_version" to 0,
            "encrypted" to "ZW5j",
            "stored_at_ms" to 1000L,
        )
        for (key in overrides.keys) {
            require(row.containsKey(key)) { "sync-api test helper: unknown transcriptions column '$key'" }
        }
        return row + overrides
    }

    private fun insertRow(connection: Connection, row: Map<String, Any>) {
        val columns = row.keys.toList()
        val sql = "INSERT INTO transcriptions (" + columns.joinToString(", ") + ") VALUES (" +
            columns.joinToString(", ") { "?" } + ")"
        connection.prepareStatement(sql).use { statement ->
            for ((index, column) in columns.withIndex()) {
                val value: Any = row.getValue(column)
                val position = index + 1
                when (value) {
                    is String -> statement.setString(position, value)
                    is Int -> statement.setInt(position, value)
                    is Long -> statement.setLong(position, value)
                    else -> throw IllegalArgumentException("sync-api test helper: unsupported value type for '$column'")
                }
            }
            statement.executeUpdate()
        }
    }

    private fun rowCount(): Long =
        inTransaction(temp) { connection -> TestDatabases.longs(connection, "SELECT count(*) FROM transcriptions").single() }

    // The defective row must be refused by a constraint whose message names [kindOfConstraint];
    // the control row, the same row with the defect repaired, must then be accepted.
    private fun assertRefusedThenControlAccepted(
        case: String,
        kindOfConstraint: String,
        defective: Map<String, Any>,
        control: Map<String, Any>,
    ) {
        val before = rowCount()
        val refusal: SQLException? = try {
            inTransaction(temp) { connection -> insertRow(connection, defective) }
            null
        } catch (failure: SQLException) {
            failure
        }
        assertNotNull("sync-api: the database accepted a row with $case", refusal)
        assertTrue(
            "sync-api: $case was refused, but not by a $kindOfConstraint constraint: ${refusal?.message}",
            refusal?.message.orEmpty().contains(kindOfConstraint, ignoreCase = true),
        )
        assertEquals("sync-api: a refused row with $case was stored", before, rowCount())
        try {
            inTransaction(temp) { connection -> insertRow(connection, control) }
        } catch (failure: SQLException) {
            fail("sync-api: the database refused the valid control row for $case: ${failure.message}")
        }
        assertEquals("sync-api: the valid control row for $case was not stored", before + 1, rowCount())
    }

    @Test
    fun `a client id of 35 characters is refused and one of 36 is accepted`() {
        assertRefusedThenControlAccepted(
            "a client id of 35 characters",
            "check",
            validRow(accountId, mapOf("client_id" to "x".repeat(35))),
            validRow(accountId),
        )
    }

    @Test
    fun `a client id of 37 characters is refused and one of 36 is accepted`() {
        assertRefusedThenControlAccepted(
            "a client id of 37 characters",
            "check",
            validRow(accountId, mapOf("client_id" to "x".repeat(37))),
            validRow(accountId),
        )
    }

    @Test
    fun `a negative key version is refused and key version zero is accepted`() {
        assertRefusedThenControlAccepted(
            "a key version of -1",
            "check",
            validRow(accountId, mapOf("key_version" to -1)),
            validRow(accountId),
        )
    }

    @Test
    fun `an empty encrypted text is refused and a non empty one is accepted`() {
        assertRefusedThenControlAccepted(
            "an empty encrypted text",
            "check",
            validRow(accountId, mapOf("encrypted" to "")),
            validRow(accountId),
        )
    }

    @Test
    fun `a second row for the same account and client id is refused`() {
        inTransaction(temp) { connection -> insertRow(connection, validRow(accountId)) }
        assertRefusedThenControlAccepted(
            "a second row for the same account and client id",
            "unique",
            validRow(accountId),
            validRow(accountId, mapOf("client_id" to TranscriptionFixtures.clientId(2))),
        )
    }

    @Test
    fun `the same client id may be held by two different accounts`() {
        val bob = TranscriptionFixtures.accountId(temp, "bob")
        inTransaction(temp) { connection -> insertRow(connection, validRow(accountId)) }
        try {
            inTransaction(temp) { connection -> insertRow(connection, validRow(bob)) }
        } catch (failure: SQLException) {
            fail("sync-api: the second account's row was refused: ${failure.message}")
        }
        assertEquals("sync-api: one row per account", 2L, rowCount())
    }

    @Test
    fun `a row for an account that does not exist is refused`() {
        assertRefusedThenControlAccepted(
            "an account that does not exist",
            "foreign key",
            validRow(accountId + 1000L),
            validRow(accountId),
        )
    }

    @Test
    fun `deleting an account deletes its transcriptions and leaves other accounts rows`() {
        val bob = TranscriptionFixtures.accountId(temp, "bob")
        inTransaction(temp) { connection ->
            insertRow(connection, validRow(accountId))
            insertRow(connection, validRow(bob, mapOf("client_id" to TranscriptionFixtures.clientId(2))))
        }
        inTransaction(temp) { connection ->
            connection.executeStatement("DELETE FROM accounts WHERE id = $accountId")
        }
        assertEquals(
            "sync-api: only the other account's row must remain",
            listOf(bob),
            inTransaction(temp) { connection ->
                TestDatabases.longs(connection, "SELECT account_id FROM transcriptions")
            },
        )
    }
}
