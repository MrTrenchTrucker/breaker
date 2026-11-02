package dev.breaker.server.syncapi.db

import java.sql.Connection
import java.sql.SQLException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * The table rules of migration 3, pinned by direct SQL on a fresh migrated database. The constraint
 * tests refuse a defective row with the constraint kind named, and accept a valid control row, so a
 * refusal that comes from the fixture cannot pass for a rule. The cascade test checks that deleting an
 * account removes its transcription rows and keeps the rows of the other accounts.
 */
internal class SchemaV3TableRulesTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private var alice: Long = 0L
    private var bob: Long = 0L

    @Before
    fun openFreshDatabase() {
        temp = openTemp()
        alice = insertAccount("alice")
        bob = insertAccount("bob")
    }

    @Test
    fun `a second row for the same account and client id is refused and another account may hold the id`() {
        assertAccepted("the first row") { connection ->
            insertTranscription(connection, alice, CLIENT_ID, 0, ENCRYPTED)
        }
        assertRefused("a second row with the same account and client id", "unique") { connection ->
            insertTranscription(connection, alice, CLIENT_ID, 0, ENCRYPTED)
        }
        assertAccepted("the same client id for another account") { connection ->
            insertTranscription(connection, bob, CLIENT_ID, 0, ENCRYPTED)
        }
        assertEquals("sync-api: alice's rows after the refusal", 1L, transcriptionCount(alice))
        assertEquals("sync-api: bob's row", 1L, transcriptionCount(bob))
    }

    @Test
    fun `a client id of 35 or 37 characters is refused and one of 36 characters is accepted`() {
        assertRefused("a 35 character client id", "check") { connection ->
            insertTranscription(connection, alice, "a".repeat(35), 0, ENCRYPTED)
        }
        assertRefused("a 37 character client id", "check") { connection ->
            insertTranscription(connection, alice, "a".repeat(37), 0, ENCRYPTED)
        }
        assertAccepted("a 36 character client id") { connection ->
            insertTranscription(connection, alice, CLIENT_ID, 0, ENCRYPTED)
        }
        assertEquals("sync-api: alice's rows after the refusals", 1L, transcriptionCount(alice))
    }

    @Test
    fun `a negative key version on a transcription is refused and key version zero is accepted`() {
        assertRefused("a transcription key version of minus one", "check") { connection ->
            insertTranscription(connection, alice, CLIENT_ID, -1, ENCRYPTED)
        }
        assertAccepted("a transcription key version of zero") { connection ->
            insertTranscription(connection, alice, CLIENT_ID, 0, ENCRYPTED)
        }
        assertEquals("sync-api: alice's rows after the refusal", 1L, transcriptionCount(alice))
    }

    @Test
    fun `an empty encrypted value is refused and a non empty one is accepted`() {
        assertRefused("an empty encrypted value", "check") { connection ->
            insertTranscription(connection, alice, CLIENT_ID, 0, "")
        }
        assertAccepted("a non empty encrypted value") { connection ->
            insertTranscription(connection, alice, CLIENT_ID, 0, ENCRYPTED)
        }
        assertEquals("sync-api: alice's rows after the refusal", 1L, transcriptionCount(alice))
    }

    @Test
    fun `a negative account key version is refused and a key version of one is accepted`() {
        assertRefused("an account key version of minus one", "check") { connection ->
            setAccountKeyVersion(connection, alice, -1)
        }
        assertEquals("sync-api: alice's key version after the refusal", listOf(0L), keyVersionOf(alice))
        assertAccepted("an account key version of one") { connection ->
            setAccountKeyVersion(connection, alice, 1)
        }
        assertEquals("sync-api: alice's key version after the control", listOf(1L), keyVersionOf(alice))
    }

    @Test
    fun `deleting an account removes its transcriptions and keeps the rows of other accounts`() {
        val foreignKeys = inTransaction(temp) { connection ->
            TestDatabases.longs(connection, "PRAGMA foreign_keys").single()
        }
        assertEquals("sync-api: foreign keys must be on for the connection", 1L, foreignKeys)
        assertAccepted("two rows of alice") { connection ->
            insertTranscription(connection, alice, CLIENT_ID, 0, ENCRYPTED, changeNo = 1)
            insertTranscription(connection, alice, CLIENT_OTHER, 0, ENCRYPTED, changeNo = 2)
        }
        assertAccepted("one row of bob") { connection ->
            insertTranscription(connection, bob, CLIENT_ID, 0, ENCRYPTED)
        }
        try {
            inTransaction(temp) { connection ->
                connection.prepareStatement("DELETE FROM accounts WHERE id = ?").use { statement ->
                    statement.setLong(1, alice)
                    statement.executeUpdate()
                }
            }
        } catch (failure: SQLException) {
            fail("sync-api: deleting an account with transcriptions was refused: ${failure.message}")
        }
        assertEquals("sync-api: alice's rows after her account is deleted", 0L, transcriptionCount(alice))
        assertEquals("sync-api: bob's row after alice's account is deleted", 1L, transcriptionCount(bob))
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

    private fun insertTranscription(
        connection: Connection,
        accountId: Long,
        clientId: String,
        keyVersion: Int,
        encrypted: String,
        changeNo: Int = 0,
    ) {
        connection.prepareStatement(
            "INSERT INTO transcriptions (account_id, client_id, key_version, encrypted, stored_at_ms, change_no) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.setLong(1, accountId)
            statement.setString(2, clientId)
            statement.setInt(3, keyVersion)
            statement.setString(4, encrypted)
            statement.setLong(5, STORED_AT_MS)
            statement.setInt(6, changeNo)
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

    private fun transcriptionCount(accountId: Long): Long =
        inTransaction(temp) { connection ->
            TestDatabases.longs(
                connection,
                "SELECT count(*) FROM transcriptions WHERE account_id = $accountId",
            ).single()
        }

    private fun transcriptionTotal(): Long =
        inTransaction(temp) { connection ->
            TestDatabases.longs(connection, "SELECT count(*) FROM transcriptions").single()
        }

    private companion object {
        // Both ids are 36 characters, the length the client_id CHECK requires.
        const val CLIENT_ID = "0f9c4d1e-6a2b-4c3d-9e8f-1a2b3c4d5e6f"
        const val CLIENT_OTHER = "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
        const val ENCRYPTED = "aGVsbG8="
        const val STORED_AT_MS = 0L
    }
}
