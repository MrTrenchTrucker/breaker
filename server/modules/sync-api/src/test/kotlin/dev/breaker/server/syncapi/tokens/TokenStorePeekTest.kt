package dev.breaker.server.syncapi.tokens

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.db.TestDatabases
import dev.breaker.server.syncapi.tokens.TokenFixtures.issuedOf
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

// TokenStore.peek mirrors resolve's live rule but is read-only: no expiry slide, no write.
internal class TokenStorePeekTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var clock: MutableClock
    private lateinit var store: TokenStore
    private var userId: Long = 0L

    @Before
    fun openStore() {
        temp = openTemp()
        clock = MutableClock(TokenFixtures.START)
        store = TokenFixtures.tokenStore(temp, clock)
        userId = TokenFixtures.register(temp, "member", 2).accountId
    }

    private fun session() = issuedOf(runBlocking { store.issueSession(userId) })

    private fun agent(expiresAt: Instant?): IssuedToken =
        issuedOf(runBlocking { store.issueAgent(userId, AgentScope.TRANSCRIBE, "bot", expiresAt) })

    private fun resolve(text: String): ResolvedToken? = runBlocking { store.resolve(text) }

    private fun peek(text: String): ResolvedToken? = runBlocking { store.peek(text) }

    private fun expiresAt(id: Long): Long? =
        inTransaction<Long?>(temp) { connection ->
            connection.prepareStatement("SELECT expires_at_ms FROM tokens WHERE id = ?").use { statement ->
                statement.setLong(1, id)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "sync-api test: no token row with id $id" }
                    val value = rows.getLong(1)
                    if (rows.wasNull()) null else value
                }
            }
        }

    // One text line per row with every column, so any write by peek changes the list.
    private fun snapshot(): List<String> = inTransaction<List<String>>(temp) { connection ->
        TestDatabases.strings(
            connection,
            "SELECT id || '|' || account_id || '|' || kind || '|' || ifnull(scope, '-') || '|' || " +
                "ifnull(label, '-') || '|' || hex(token_hash) || '|' || created_at_ms || '|' || " +
                "ifnull(expires_at_ms, '-') || '|' || ifnull(revoked_at_ms, '-') FROM tokens ORDER BY id",
        )
    }

    @Test
    fun `peek gives the same answer as resolve for a live session token`() {
        val issued = session()
        assertEquals(
            "sync-api: peek and resolve agree on a live session token",
            resolve(issued.secret.reveal()),
            peek(issued.secret.reveal()),
        )
    }

    @Test
    fun `peek never moves the session expiry`() {
        val issued = session()
        val before = expiresAt(issued.tokenId)
        assertNotNull("sync-api: fresh session has an expiry", before)
        clock.advance(Duration.ofDays(10))
        peek(issued.secret.reveal())
        assertEquals("sync-api: peek did not move the expiry", before, expiresAt(issued.tokenId))
    }

    @Test
    fun `peek refuses an expired session token`() {
        val issued = session()
        clock.advance(Duration.ofDays(31))
        assertNull("sync-api: peek refuses an expired session", peek(issued.secret.reveal()))
    }

    @Test
    fun `peek refuses a revoked agent token`() {
        val issued = agent(TokenFixtures.START.plus(Duration.ofDays(60)))
        assertEquals("sync-api: the agent token was revoked", true, runBlocking { store.revokeAgentToken(issued.tokenId) })
        assertNull("sync-api: peek refuses a revoked agent token", peek(issued.secret.reveal()))
    }

    @Test
    fun `peek refuses an unknown and a malformed token`() {
        assertNull("sync-api: peek refuses a well-formed token that was never issued", peek(TokenSecret.generate(TokenFixtures.fakeRandom(77)).reveal()))
        assertNull("sync-api: peek refuses a malformed token", peek("not-a-token"))
    }

    @Test
    fun `peek never writes for an agent token`() {
        val issued = agent(TokenFixtures.START.plus(Duration.ofDays(60)))
        session()
        val before = snapshot()
        clock.advance(Duration.ofDays(10))
        assertNotNull("sync-api: peek finds the agent token", peek(issued.secret.reveal()))
        assertEquals("sync-api: peek changed the table", before, snapshot())
    }
}
