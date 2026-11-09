package dev.breaker.server.syncapi.tokens

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import java.sql.Connection
import java.sql.Types
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for `TokenStore.revokeSessionById(tokenId)` — `DELETE /v1/admin/sessions/{id}` at store level.
 *
 * One transaction: read the live row's account_id, then run REVOKE_DEVICE_SQL bound to that
 * account id and the token id. Returns true only when a live session row was actually revoked.
 * Agent-token rows, unknown ids, already-revoked rows, expired rows and rows past the 180-day
 * cap all return false and change nothing.
 */
internal class TokenStoreEndSessionTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var store: TokenStore
    private val clock = MutableClock(TokenFixtures.START)
    private var hashSeed = 0

    @Before
    fun setUp() {
        temp = openTemp()
        store = TokenFixtures.tokenStore(temp, clock)
    }

    // The victim account is the SECOND one registered (id 2), not the first (id 1). A mutant that
    // reads the first row's account instead of the named row's would bind account 1 and match
    // nothing, so the tests below only kill it if the victim's account id is not 1.
    private fun account(): Long {
        TokenFixtures.register(temp, "alice", 1)
        return TokenFixtures.register(temp, "bob", 2).accountId
    }

    private fun issuedSession(accountId: Long): IssuedToken =
        TokenFixtures.issuedOf(runBlocking { store.issueSession(accountId) })

    private fun issuedAgent(accountId: Long): IssuedToken =
        TokenFixtures.issuedOf(runBlocking { store.issueAgent(accountId, AgentScope.TRANSCRIBE, "agent-label", null) })

    private fun revoked(tokenId: Long): Long? = runBlocking { TokenFixtures.revokedAtMs(temp, tokenId) }

    // Every raw INSERT gets its own unique token_hash: token_hash is NOT NULL UNIQUE.
    private fun nextHash() = ByteArray(32) { (hashSeed++).toByte() }

    private fun insertRow(accountId: Long, createdMs: Long, expiresMs: Long?): Long = runBlocking {
        temp.db.transaction { connection ->
            connection.prepareStatement(
                "INSERT INTO tokens (account_id, kind, scope, label, token_hash, " +
                    "created_at_ms, expires_at_ms, device_name) " +
                    "VALUES (?, 'session', NULL, NULL, ?, ?, ?, NULL)",
            ).use { statement ->
                statement.setLong(1, accountId)
                statement.setBytes(2, nextHash())
                statement.setLong(3, createdMs)
                if (expiresMs == null) statement.setNull(4, Types.BIGINT) else statement.setLong(4, expiresMs)
                statement.executeUpdate()
                connection.createStatement().use { query ->
                    query.executeQuery("SELECT last_insert_rowid()").use { rows ->
                        check(rows.next()) { "sync-api test: row id missing" }
                        rows.getLong(1)
                    }
                }
            }
        }
    }

    // ---------- revokeSessionById ----------

    @Test
    fun `revokeSessionById revokes a live session and returns true`() {
        val accountId = account() // bob: id 2, the victim's account (not 1)
        // Issue carol's session FIRST so her row is the first row of `tokens`. A SELECT that drops
        // its id filter binds her account (3), and REVOKE_DEVICE_SQL then refuses the mismatch,
        // so this assertTrue now fails and the mutant is killed.
        issuedSession(TokenFixtures.register(temp, "carol", 3).accountId)
        val session = issuedSession(accountId)
        assertTrue("revokeSessionById returns true for a live session", runBlocking { store.revokeSessionById(session.tokenId) })
        assertFalse("the session no longer resolves", runBlocking { store.resolve(session.secret.reveal()) != null })
        assertNotNull("revoked_at_ms is set", revoked(session.tokenId))
    }

    @Test
    fun `an unknown id returns false`() {
        val accountId = account()
        issuedSession(accountId)
        val before = TokenFixtures.liveTokenCount(temp, accountId)
        assertFalse("an unknown id revokes nothing", runBlocking { store.revokeSessionById(999999999L) })
        assertEquals("the live token count is unchanged", before, TokenFixtures.liveTokenCount(temp, accountId))
    }

    @Test
    fun `an agent token row returns false and stays live`() {
        val accountId = account()
        val agent = issuedAgent(accountId)
        assertFalse("an agent token is not revoked by revokeSessionById", runBlocking { store.revokeSessionById(agent.tokenId) })
        assertNotNull("the agent secret still resolves", runBlocking { store.resolve(agent.secret.reveal()) })
        assertNull("the agent revoked time stays null", revoked(agent.tokenId))
    }

    @Test
    fun `an already revoked row returns false`() {
        val accountId = account()
        val id = issuedSession(accountId).tokenId
        assertTrue("the first revoke revokes", runBlocking { store.revokeSessionById(id) })
        val firstTime = revoked(id)
        assertFalse("revoking again returns false", runBlocking { store.revokeSessionById(id) })
        assertEquals("revoked_at_ms is still the first revoke time", firstTime, revoked(id))
    }

    @Test
    fun `an expired row returns false`() {
        val accountId = account()
        val id = issuedSession(accountId).tokenId
        TokenFixtures.expireSession(temp, id)
        assertFalse("an expired session is not revoked", runBlocking { store.revokeSessionById(id) })
        assertNull("revoked_at_ms stays null", revoked(id))
    }

    @Test
    fun `a row past the 180-day cap returns false`() {
        val accountId = account()
        val start = TokenFixtures.START.toEpochMilli()
        val id = insertRow(accountId, start, start + Duration.ofDays(180).toMillis() + 1)
        assertFalse("a session past the cap is not revoked", runBlocking { store.revokeSessionById(id) })
        assertNull("revoked_at_ms stays null", revoked(id))
    }

    @Test
    fun `the account's hand-off codes are left alone`() {
        val accountId = account()
        val deviceId = issuedSession(accountId).tokenId
        inTransaction(temp) { connection ->
            insertHandoff(connection, accountId, ByteArray(32) { 9 }, "c".repeat(43), ByteArray(113) { 7 })
        }
        assertEquals("one hand-off row before", 1L, handoffCount(accountId))
        assertTrue("the session is revoked", runBlocking { store.revokeSessionById(deviceId) })
        assertEquals("one hand-off row after", 1L, handoffCount(accountId))
    }

    @Test
    fun `an agent token row of the same account is not touched`() {
        val accountId = account()
        val session = issuedSession(accountId)
        val agent = issuedAgent(accountId)
        assertTrue("the session is revoked", runBlocking { store.revokeSessionById(session.tokenId) })
        assertFalse("the session no longer resolves", runBlocking { store.resolve(session.secret.reveal()) != null })
        assertNotNull("the agent token of the same account still resolves", runBlocking { store.resolve(agent.secret.reveal()) })
        assertNull("the agent revoked time stays null", revoked(agent.tokenId))
    }

    // ---------- helpers ----------

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
