package dev.breaker.server.syncapi.db

import dev.breaker.server.syncapi.accounts.AccountFixtures
import java.sql.Connection
import java.sql.SQLException
import java.sql.Types
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class SchemaV4MigrationTest : TempDatabaseTest() {

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

    private fun insertToken(connection: Connection, accountId: Long, kind: String, seed: Int, deviceName: String?) {
        val isAgent = kind == "agent"
        connection.prepareStatement(
            "INSERT INTO tokens (account_id, kind, scope, label, token_hash, created_at_ms, device_name) " +
                "VALUES (?, ?, ?, ?, ?, 1, ?)",
        ).use { statement ->
            statement.setLong(1, accountId)
            statement.setString(2, kind)
            if (isAgent) statement.setString(3, "transcribe") else statement.setNull(3, Types.VARCHAR)
            if (isAgent) statement.setString(4, "ci") else statement.setNull(4, Types.VARCHAR)
            statement.setBytes(5, ByteArray(32) { seed.toByte() })
            if (deviceName == null) statement.setNull(6, Types.VARCHAR) else statement.setString(6, deviceName)
            statement.executeUpdate()
        }
    }

    private fun insertHandoff(
        connection: Connection,
        accountId: Long,
        codeHash: ByteArray,
        challenge: String,
        sealedPackage: ByteArray,
    ) {
        connection.prepareStatement(
            "INSERT INTO signin_handoff (code_hash, account_id, challenge, package, created_at_ms, expires_at_ms) " +
                "VALUES (?, ?, ?, ?, 1, 2)",
        ).use { statement ->
            statement.setBytes(1, codeHash)
            statement.setLong(2, accountId)
            statement.setString(3, challenge)
            statement.setBytes(4, sealedPackage)
            statement.executeUpdate()
        }
    }

    private fun hashOf(size: Int, seed: Int): ByteArray = ByteArray(size) { seed.toByte() }

    private fun challengeOf(size: Int): String = "c".repeat(size)

    private fun packageOf(size: Int): ByteArray = ByteArray(size) { 7 }

    @Test
    fun `a fresh database ends at the latest version with the hand-off table, its index, the device name column and both triggers`() {
        val temp = openTemp()
        inTransaction(temp) { connection ->
            assertEquals("sync-api: fresh database user_version", 5, TestDatabases.userVersion(connection))
            assertEquals(
                "sync-api: the signin_handoff table is missing after the migrations",
                listOf("signin_handoff"),
                TestDatabases.strings(
                    connection,
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'signin_handoff'",
                ),
            )
            assertEquals(
                "sync-api: the index signin_handoff_account_created must sit on the hand-off table",
                listOf("signin_handoff"),
                TestDatabases.strings(
                    connection,
                    "SELECT tbl_name FROM sqlite_master WHERE type = 'index' AND name = 'signin_handoff_account_created'",
                ),
            )
            assertEquals(
                "sync-api: signin_handoff_account_created must cover account_id then created_at_ms",
                listOf("account_id", "created_at_ms"),
                TestDatabases.strings(
                    connection,
                    "SELECT name FROM pragma_index_info('signin_handoff_account_created') ORDER BY seqno",
                ),
            )
            assertEquals(
                "sync-api: the tokens table must have a device_name column",
                listOf("device_name"),
                TestDatabases.strings(
                    connection,
                    "SELECT name FROM pragma_table_info('tokens') WHERE name = 'device_name'",
                ),
            )
            assertEquals(
                "sync-api: both device name triggers must exist",
                listOf("tokens_device_name_insert", "tokens_device_name_update"),
                TestDatabases.strings(
                    connection,
                    "SELECT name FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'tokens_device_name%' ORDER BY name",
                ),
            )
            assertEquals(
                "sync-api: the new hand-off table must start empty",
                listOf(0L),
                TestDatabases.longs(connection, "SELECT count(*) FROM signin_handoff"),
            )
        }
    }

    @Test
    fun `a version 3 database with an account, a session, an agent token and a transcription upgrades to the latest version and keeps every row`() {
        val first = openTemp(listOf(SchemaV1.MIGRATION, SchemaV2.MIGRATION, SchemaV3.MIGRATION))
        val createdAt = AccountFixtures.FIXED_MILLIS + 789L
        val sessionExpiry = AccountFixtures.FIXED_MILLIS + 5_000L
        val sessionHash = ByteArray(32) { 9 }
        val agentHash = ByteArray(32) { 10 }
        val clientId = "c".repeat(36)
        inTransaction(first) { connection ->
            assertEquals("sync-api: setup database user_version", 3, TestDatabases.userVersion(connection))
            TestDatabases.insertAccountRow(
                connection,
                TestDatabases.validAccountRow(
                    mapOf(
                        "username" to "Dora.Old",
                        "username_lower" to "dora.old",
                        "role" to "admin",
                        "created_at_ms" to createdAt,
                    ),
                ),
            )
            val owner: Long = TestDatabases.longs(connection, "SELECT id FROM accounts").single()
            connection.prepareStatement(
                "INSERT INTO tokens (account_id, kind, token_hash, created_at_ms, expires_at_ms) " +
                    "VALUES (?, 'session', ?, 1, ?)",
            ).use { statement ->
                statement.setLong(1, owner)
                statement.setBytes(2, sessionHash)
                statement.setLong(3, sessionExpiry)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO tokens (account_id, kind, scope, label, token_hash, created_at_ms) " +
                    "VALUES (?, 'agent', 'transcribe', 'ci', ?, 2)",
            ).use { statement ->
                statement.setLong(1, owner)
                statement.setBytes(2, agentHash)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO transcriptions (account_id, client_id, key_version, encrypted, stored_at_ms) " +
                    "VALUES (?, ?, 0, 'aGVsbG8=', 77)",
            ).use { statement ->
                statement.setLong(1, owner)
                statement.setString(2, clientId)
                statement.executeUpdate()
            }
        }

        val second = reopen(first)

        inTransaction(second) { connection ->
            assertEquals("sync-api: user_version after the upgrade", 5, TestDatabases.userVersion(connection))
            assertEquals(
                "sync-api: the account was lost or duplicated by the upgrade",
                listOf(1L),
                TestDatabases.longs(connection, "SELECT count(*) FROM accounts"),
            )
            assertEquals(
                "sync-api: a token row was lost or duplicated by the upgrade",
                listOf(2L),
                TestDatabases.longs(connection, "SELECT count(*) FROM tokens"),
            )
            assertEquals(
                "sync-api: the transcription was lost or duplicated by the upgrade",
                listOf(1L),
                TestDatabases.longs(connection, "SELECT count(*) FROM transcriptions"),
            )
            assertEquals(
                "sync-api: the old token rows must have no device name",
                listOf(2L),
                TestDatabases.longs(connection, "SELECT count(*) FROM tokens WHERE device_name IS NULL"),
            )
            assertEquals(
                "sync-api: the new hand-off table must start empty",
                listOf(0L),
                TestDatabases.longs(connection, "SELECT count(*) FROM signin_handoff"),
            )
            connection.prepareStatement("SELECT username, username_lower, role, created_at_ms FROM accounts").use { statement ->
                statement.executeQuery().use { rows ->
                    assertTrue("sync-api: the upgraded database lost the account row", rows.next())
                    assertEquals("sync-api: username changed by the upgrade", "Dora.Old", rows.getString("username"))
                    assertEquals("sync-api: username_lower changed by the upgrade", "dora.old", rows.getString("username_lower"))
                    assertEquals("sync-api: role changed by the upgrade", "admin", rows.getString("role"))
                    assertEquals("sync-api: created_at_ms changed by the upgrade", createdAt, rows.getLong("created_at_ms"))
                }
            }
            connection.prepareStatement("SELECT kind, scope, label, token_hash, created_at_ms, expires_at_ms, device_name FROM tokens ORDER BY id").use { statement ->
                statement.executeQuery().use { rows ->
                    assertTrue("sync-api: the upgraded database lost the session row", rows.next())
                    assertEquals("sync-api: session kind changed by the upgrade", "session", rows.getString("kind"))
                    assertNull("sync-api: a session row gained a scope", rows.getString("scope"))
                    assertNull("sync-api: a session row gained a label", rows.getString("label"))
                    assertArrayEquals("sync-api: session token_hash changed by the upgrade", sessionHash, rows.getBytes("token_hash"))
                    assertEquals("sync-api: session created_at_ms changed by the upgrade", 1L, rows.getLong("created_at_ms"))
                    assertEquals("sync-api: session expires_at_ms changed by the upgrade", sessionExpiry, rows.getLong("expires_at_ms"))
                    assertNull("sync-api: the old session row must have no device name", rows.getString("device_name"))
                    assertTrue("sync-api: the upgraded database lost the agent row", rows.next())
                    assertEquals("sync-api: agent kind changed by the upgrade", "agent", rows.getString("kind"))
                    assertEquals("sync-api: agent scope changed by the upgrade", "transcribe", rows.getString("scope"))
                    assertEquals("sync-api: agent label changed by the upgrade", "ci", rows.getString("label"))
                    assertArrayEquals("sync-api: agent token_hash changed by the upgrade", agentHash, rows.getBytes("token_hash"))
                    assertEquals("sync-api: agent created_at_ms changed by the upgrade", 2L, rows.getLong("created_at_ms"))
                    assertNull("sync-api: the old agent row must have no device name", rows.getString("device_name"))
                }
            }
            connection.prepareStatement("SELECT client_id, key_version, encrypted, stored_at_ms FROM transcriptions").use { statement ->
                statement.executeQuery().use { rows ->
                    assertTrue("sync-api: the upgraded database lost the transcription row", rows.next())
                    assertEquals("sync-api: client_id changed by the upgrade", clientId, rows.getString("client_id"))
                    assertEquals("sync-api: key_version of the transcription changed by the upgrade", 0, rows.getInt("key_version"))
                    assertEquals("sync-api: encrypted changed by the upgrade", "aGVsbG8=", rows.getString("encrypted"))
                    assertEquals("sync-api: stored_at_ms changed by the upgrade", 77L, rows.getLong("stored_at_ms"))
                }
            }
        }

        val owner = count(second, "SELECT id FROM accounts")
        assertAccepted(second, "a session row with a device name in an upgraded database") { connection ->
            insertToken(connection, owner, "session", 11, "Pixel 8")
        }
        assertEquals(
            "sync-api: the named session in the upgraded database was not stored",
            1L,
            count(second, "SELECT count(*) FROM tokens WHERE device_name = 'Pixel 8'"),
        )
    }

    @Test
    fun `a device name of 0 characters is refused and a name of 1 character is accepted`() {
        val temp = openTemp()
        val owner = addAccount(temp, "alice")
        assertRefused(temp, "an empty device name", "check") { connection ->
            insertToken(connection, owner, "session", 1, "")
        }
        assertEquals("sync-api: a refused session row was stored", 0L, count(temp, "SELECT count(*) FROM tokens"))
        assertAccepted(temp, "a device name of 1 character") { connection ->
            insertToken(connection, owner, "session", 2, "x")
        }
        assertEquals("sync-api: the valid session row was not stored", 1L, count(temp, "SELECT count(*) FROM tokens"))
    }

    @Test
    fun `a device name of 65 characters is refused and a name of 64 characters is accepted`() {
        val temp = openTemp()
        val owner = addAccount(temp, "alice")
        assertRefused(temp, "a 65 character device name", "check") { connection ->
            insertToken(connection, owner, "session", 1, "n".repeat(65))
        }
        assertEquals("sync-api: a refused session row was stored", 0L, count(temp, "SELECT count(*) FROM tokens"))
        assertAccepted(temp, "a 64 character device name") { connection ->
            insertToken(connection, owner, "session", 2, "n".repeat(64))
        }
        assertEquals("sync-api: the valid session row was not stored", 1L, count(temp, "SELECT count(*) FROM tokens"))
    }

    @Test
    fun `a session row without a device name is accepted and keeps the column empty`() {
        val temp = openTemp()
        val owner = addAccount(temp, "alice")
        assertAccepted(temp, "a session row with no device name") { connection ->
            insertToken(connection, owner, "session", 1, null)
        }
        assertEquals(
            "sync-api: the device name of a nameless session must be NULL",
            1L,
            count(temp, "SELECT count(*) FROM tokens WHERE kind = 'session' AND device_name IS NULL"),
        )
    }

    @Test
    fun `a hand-off code hash of 31 bytes is refused and 32 bytes are accepted`() {
        assertSizeRefusedThenExactAccepted("a code_hash of 31 bytes", 31, 43, 113)
    }

    @Test
    fun `a hand-off code hash of 33 bytes is refused and 32 bytes are accepted`() {
        assertSizeRefusedThenExactAccepted("a code_hash of 33 bytes", 33, 43, 113)
    }

    @Test
    fun `a hand-off challenge of 42 characters is refused and 43 are accepted`() {
        assertSizeRefusedThenExactAccepted("a challenge of 42 characters", 32, 42, 113)
    }

    @Test
    fun `a hand-off challenge of 44 characters is refused and 43 are accepted`() {
        assertSizeRefusedThenExactAccepted("a challenge of 44 characters", 32, 44, 113)
    }

    @Test
    fun `a hand-off package of 112 bytes is refused and 113 are accepted`() {
        assertSizeRefusedThenExactAccepted("a package of 112 bytes", 32, 43, 112)
    }

    @Test
    fun `a hand-off package of 114 bytes is refused and 113 are accepted`() {
        assertSizeRefusedThenExactAccepted("a package of 114 bytes", 32, 43, 114)
    }

    private fun assertSizeRefusedThenExactAccepted(case: String, hashSize: Int, challengeSize: Int, packageSize: Int) {
        val temp = openTemp()
        val owner = addAccount(temp, "alice")
        assertRefused(temp, case, "check") { connection ->
            insertHandoff(connection, owner, hashOf(hashSize, 1), challengeOf(challengeSize), packageOf(packageSize))
        }
        assertEquals(
            "sync-api: a refused write with $case was stored",
            0L,
            count(temp, "SELECT count(*) FROM signin_handoff"),
        )
        assertAccepted(temp, "the exact sizes next to $case") { connection ->
            insertHandoff(connection, owner, hashOf(32, 1), challengeOf(43), packageOf(113))
        }
        assertEquals(
            "sync-api: the exact sizes next to $case were not stored",
            1L,
            count(temp, "SELECT count(*) FROM signin_handoff"),
        )
    }

    @Test
    fun `a second hand-off row with the same code hash is refused and a different hash is accepted`() {
        val temp = openTemp()
        val owner = addAccount(temp, "alice")
        inTransaction(temp) { connection ->
            insertHandoff(connection, owner, hashOf(32, 1), challengeOf(43), packageOf(113))
        }
        assertRefused(temp, "a code_hash already stored", "unique") { connection ->
            insertHandoff(connection, owner, hashOf(32, 1), challengeOf(43), packageOf(113))
        }
        assertEquals("sync-api: a refused duplicate was stored", 1L, count(temp, "SELECT count(*) FROM signin_handoff"))
        assertAccepted(temp, "a different code_hash") { connection ->
            insertHandoff(connection, owner, hashOf(32, 2), challengeOf(43), packageOf(113))
        }
        assertEquals("sync-api: the second code was not stored", 2L, count(temp, "SELECT count(*) FROM signin_handoff"))
    }

    @Test
    fun `a hand-off row of an account that does not exist is refused and one of an existing account is accepted`() {
        val temp = openTemp()
        val owner = addAccount(temp, "alice")
        assertRefused(temp, "an account_id with no account", "foreign key") { connection ->
            insertHandoff(connection, owner + 1000L, hashOf(32, 1), challengeOf(43), packageOf(113))
        }
        assertEquals("sync-api: a refused row was stored", 0L, count(temp, "SELECT count(*) FROM signin_handoff"))
        assertAccepted(temp, "an account_id of an existing account") { connection ->
            insertHandoff(connection, owner, hashOf(32, 1), challengeOf(43), packageOf(113))
        }
        assertEquals("sync-api: the valid row was not stored", 1L, count(temp, "SELECT count(*) FROM signin_handoff"))
    }

    @Test
    fun `deleting an account deletes its hand-off rows and leaves the rows of other accounts`() {
        val temp = openTemp()
        val alice = addAccount(temp, "alice")
        val bob = addAccount(temp, "bob")
        inTransaction(temp) { connection ->
            insertHandoff(connection, alice, hashOf(32, 1), challengeOf(43), packageOf(113))
            insertHandoff(connection, alice, hashOf(32, 2), challengeOf(43), packageOf(113))
            insertHandoff(connection, bob, hashOf(32, 3), challengeOf(43), packageOf(113))
        }
        assertEquals("sync-api: setup should have stored three rows", 3L, count(temp, "SELECT count(*) FROM signin_handoff"))
        inTransaction(temp) { connection -> connection.executeStatement("DELETE FROM accounts WHERE id = $alice") }
        assertEquals(
            "sync-api: the rows of the deleted account must go with it and no other row",
            listOf(bob),
            inTransaction(temp) { connection -> TestDatabases.longs(connection, "SELECT account_id FROM signin_handoff") },
        )
    }

    @Test
    fun `the insert trigger refuses a device name on an agent row`() {
        val temp = openTemp()
        val owner = addAccount(temp, "alice")
        assertRefused(temp, "an agent row with a device name", "session rows only") { connection ->
            insertToken(connection, owner, "agent", 1, "Pixel 8")
        }
        assertEquals("sync-api: a refused agent row was stored", 0L, count(temp, "SELECT count(*) FROM tokens"))
    }

    @Test
    fun `the update trigger refuses a device name set on an agent row`() {
        val temp = openTemp()
        val owner = addAccount(temp, "alice")
        inTransaction(temp) { connection ->
            insertToken(connection, owner, "agent", 1, null)
        }
        assertRefused(temp, "a device name set on an agent row", "session rows only") { connection ->
            connection.executeStatement("UPDATE tokens SET device_name = 'Pixel 9' WHERE kind = 'agent'")
        }
        assertEquals(
            "sync-api: the agent row must still have no device name",
            0L,
            count(temp, "SELECT count(*) FROM tokens WHERE kind = 'agent' AND device_name IS NOT NULL"),
        )
    }

    @Test
    fun `the update trigger refuses a named session turned into an agent row`() {
        val temp = openTemp()
        val owner = addAccount(temp, "alice")
        inTransaction(temp) { connection -> insertToken(connection, owner, "session", 1, "Pixel 8") }
        assertRefused(temp, "a named session turned into an agent row", "session rows only") { connection ->
            connection.executeStatement(
                "UPDATE tokens SET kind = 'agent', scope = 'transcribe', label = 'ci' WHERE kind = 'session'",
            )
        }
        assertEquals(
            "sync-api: the named session must still be a session with its name",
            1L,
            count(temp, "SELECT count(*) FROM tokens WHERE kind = 'session' AND device_name = 'Pixel 8'"),
        )
    }

    @Test
    fun `a plain agent insert without a device name passes`() {
        val temp = openTemp()
        val owner = addAccount(temp, "alice")
        assertAccepted(temp, "an agent row without a device name") { connection ->
            insertToken(connection, owner, "agent", 2, null)
        }
        assertEquals("sync-api: the plain agent row was not stored", 1L, count(temp, "SELECT count(*) FROM tokens"))
    }
}
