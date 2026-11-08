package dev.breaker.server.syncapi.tokens

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.Types
import java.time.Duration
import java.time.Instant

internal class TokenStoreSignOutTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var store: TokenStore
    private val clock = MutableClock(TokenFixtures.START)

    @Before
    fun setUp() {
        temp = openTemp()
        store = TokenFixtures.tokenStore(temp, clock)
    }

    private fun account(): Long = TokenFixtures.register(temp, "alice", 1).accountId

    private fun issuedSession(accountId: Long): IssuedToken =
        TokenFixtures.issuedOf(runBlocking { store.issueSession(accountId) })

    private fun freshSession(accountId: Long): Long = issuedSession(accountId).tokenId

    private fun insertRow(
        accountId: Long,
        createdMs: Long,
        expiresMs: Long?,
        name: String?,
        seed: Byte = 0,
    ): Long = runBlocking {
        temp.db.transaction { connection ->
            connection.prepareStatement(
                "INSERT INTO tokens (account_id, kind, scope, label, token_hash, " +
                    "created_at_ms, expires_at_ms, device_name) " +
                    "VALUES (?, 'session', NULL, NULL, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setLong(1, accountId)
                statement.setBytes(2, ByteArray(32) { seed })
                statement.setLong(3, createdMs)
                if (expiresMs == null) statement.setNull(4, Types.BIGINT) else statement.setLong(4, expiresMs)
                if (name == null) statement.setNull(5, Types.VARCHAR) else statement.setString(5, name)
                statement.executeUpdate()
                connection.createStatement().use { query ->
                    query.executeQuery("SELECT last_insert_rowid()").use { rows ->
                        check(rows.next()) { "sync-api test: the row id could not be read back" }
                        rows.getLong(1)
                    }
                }
            }
        }
    }

    private fun revoked(tokenId: Long): Long? = runBlocking { TokenFixtures.revokedAtMs(temp, tokenId) }

    @Test
    fun `a live session of the caller's account is revoked`() {
        val accountId = account()
        val first = issuedSession(accountId)
        val second = issuedSession(accountId)
        assertTrue("revokeDeviceOf returns true", runBlocking { store.revokeDeviceOf(accountId, first.tokenId) })
        assertEquals("the revoke time is the clock's millis", TokenFixtures.START.toEpochMilli(), revoked(first.tokenId))
        assertNotNull("the other session still resolves", runBlocking { store.resolve(second.secret.reveal()) })
    }

    @Test
    fun `an unknown id changes nothing`() {
        val accountId = account()
        issuedSession(accountId)
        val before = TokenFixtures.liveTokenCount(temp, accountId)
        assertFalse("an unknown id revokes nothing", runBlocking { store.revokeDeviceOf(accountId, 999999999L) })
        assertEquals("the live token count is unchanged", before, TokenFixtures.liveTokenCount(temp, accountId))
    }

    @Test
    fun `another account's session is not revoked`() {
        val alice = account()
        val bob = TokenFixtures.register(temp, "bob", 2).accountId
        val bobSession = issuedSession(bob)
        val aliceSession = issuedSession(alice)
        assertFalse("another account's session is not revoked", runBlocking { store.revokeDeviceOf(alice, bobSession.tokenId) })
        assertNotNull("bob's secret still resolves", runBlocking { store.resolve(bobSession.secret.reveal()) })
        assertNull("bob's revoked time stays null", revoked(bobSession.tokenId))
        assertNotNull("alice's own session still resolves", runBlocking { store.resolve(aliceSession.secret.reveal()) })
    }

    @Test
    fun `an agent token of the caller's account is not revoked`() {
        val accountId = account()
        val agent = TokenFixtures.issuedOf(runBlocking { store.issueAgent(accountId, AgentScope.TRANSCRIBE, "agent", null) })
        assertFalse("an agent token is not revoked", runBlocking { store.revokeDeviceOf(accountId, agent.tokenId) })
        assertNotNull("the agent secret still resolves", runBlocking { store.resolve(agent.secret.reveal()) })
        assertNull("the agent revoked time stays null", revoked(agent.tokenId))
    }

    @Test
    fun `an already revoked session changes nothing`() {
        val accountId = account()
        val id = freshSession(accountId)
        runBlocking { store.revokeSession(id) }
        val firstTime = revoked(id)
        assertEquals("the first revoke time stands", TokenFixtures.START.toEpochMilli(), firstTime)
        assertFalse("revoking again changes nothing", runBlocking { store.revokeDeviceOf(accountId, id) })
        assertEquals("revoked_at_ms is still the first revoke time", firstTime, revoked(id))
    }

    @Test
    fun `an expired session is not revoked`() {
        val accountId = account()
        val id = freshSession(accountId)
        TokenFixtures.expireSession(temp, id)
        assertFalse("an expired session is not revoked", runBlocking { store.revokeDeviceOf(accountId, id) })
        assertNull("revoked_at_ms stays null", revoked(id))
    }

    @Test
    fun `a session past the 180 day cap is not revoked`() {
        val accountId = account()
        val start = TokenFixtures.START.toEpochMilli()
        val id = insertRow(accountId, start, start + Duration.ofDays(180).toMillis() + 1, null, 1)
        assertFalse("a session past the cap is not revoked", runBlocking { store.revokeDeviceOf(accountId, id) })
        assertNull("revoked_at_ms stays null", revoked(id))
    }

    @Test
    fun `a session with no expiry is revoked`() {
        val accountId = account()
        val start = TokenFixtures.START.toEpochMilli()
        val id = insertRow(accountId, start, null, null, 2)
        assertTrue("a session without an expiry is revoked", runBlocking { store.revokeDeviceOf(accountId, id) })
        assertEquals("revoked_at_ms is set", start, revoked(id))
    }

    @Test
    fun `the account's hand-off codes are left alone`() {
        val accountId = account()
        val deviceId = freshSession(accountId)
        inTransaction(temp) { connection ->
            insertHandoff(connection, accountId, ByteArray(32) { 9 }, "c".repeat(43), ByteArray(113) { 7 })
        }
        assertEquals("one hand-off row before", 1L, handoffCount(accountId))
        assertTrue("the device is revoked", runBlocking { store.revokeDeviceOf(accountId, deviceId) })
        assertEquals("one hand-off row after", 1L, handoffCount(accountId))
    }

    @Test
    fun `a second call on the same id changes nothing`() {
        val accountId = account()
        val id = freshSession(accountId)
        assertTrue("the first call revokes", runBlocking { store.revokeDeviceOf(accountId, id) })
        assertFalse("the second call changes nothing", runBlocking { store.revokeDeviceOf(accountId, id) })
    }

    @Test
    fun `the revoke time is the clock's millis`() {
        val accountId = account()
        val id = freshSession(accountId)
        val instant = Instant.ofEpochMilli(TokenFixtures.START.toEpochMilli() + Duration.ofDays(7).toMillis())
        clock.set(instant)
        assertTrue("the revoke lands", runBlocking { store.revokeDeviceOf(accountId, id) })
        assertEquals("revoked_at_ms equals the clock's millis", instant.toEpochMilli(), revoked(id))
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

    private fun handoffCount(accountId: Long): Long = runBlocking {
        temp.db.transaction { connection ->
            connection.prepareStatement("SELECT count(*) FROM signin_handoff WHERE account_id = ?").use { statement ->
                statement.setLong(1, accountId)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "sync-api test: the hand-off count returned no row" }
                    rows.getLong(1)
                }
            }
        }
    }
}
