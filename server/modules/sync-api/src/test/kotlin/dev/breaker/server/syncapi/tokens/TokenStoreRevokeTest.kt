package dev.breaker.server.syncapi.tokens

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class TokenStoreRevokeTest : TempDatabaseTest() {

    private val clock = MutableClock(TokenFixtures.START)

    private fun newStore(temp: TempDatabase): TokenStore = TokenFixtures.tokenStore(temp, clock)

    private fun session(store: TokenStore, accountId: Long): IssuedToken =
        TokenFixtures.issuedOf(runBlocking { store.issueSession(accountId) })

    private fun agent(store: TokenStore, accountId: Long, expiresAt: Instant? = null): IssuedToken =
        TokenFixtures.issuedOf(runBlocking { store.issueAgent(accountId, AgentScope.TRANSCRIBE, "test-agent", expiresAt) })

    private fun resolves(store: TokenStore, token: IssuedToken): Boolean =
        runBlocking { store.resolve(token.secret.reveal()) } != null

    private fun revoke(store: TokenStore, tokenId: Long): Boolean = runBlocking { store.revokeAgentToken(tokenId) }

    @Test
    fun `revoking a live agent token returns true, stops it resolving and a second call returns false`() {
        val temp = openTemp()
        val store = newStore(temp)
        val owner = TokenFixtures.register(temp, "alice", 1)
        val token = agent(store, owner.accountId)
        assertTrue("sync-api: a fresh agent token must resolve before the revoke", resolves(store, token))

        assertTrue("sync-api: revoking a live agent token must return true", revoke(store, token.tokenId))
        assertFalse("sync-api: a revoked agent token must not resolve", resolves(store, token))
        assertFalse("sync-api: a second revoke of the same token must return false", revoke(store, token.tokenId))
    }

    @Test
    fun `revoking one agent token leaves the older and newer tokens of the owner alone`() {
        val temp = openTemp()
        val store = newStore(temp)
        val owner = TokenFixtures.register(temp, "alice", 1)
        val sessionToken = session(store, owner.accountId)
        val older = agent(store, owner.accountId)
        val target = agent(store, owner.accountId)
        val newer = agent(store, owner.accountId)

        assertTrue("sync-api: the middle agent token must be revoked", revoke(store, target.tokenId))

        assertFalse("sync-api: the revoked token must not resolve", resolves(store, target))
        assertTrue("sync-api: the owner's session token must still resolve", resolves(store, sessionToken))
        assertTrue("sync-api: an older agent token of the owner must still resolve", resolves(store, older))
        assertTrue("sync-api: a newer agent token of the owner must still resolve", resolves(store, newer))
    }

    @Test
    fun `a session token id is not revoked through the agent call`() {
        val temp = openTemp()
        val store = newStore(temp)
        val owner = TokenFixtures.register(temp, "alice", 1)
        val sessionToken = session(store, owner.accountId)

        assertFalse("sync-api: a session token id must give false", revoke(store, sessionToken.tokenId))
        assertTrue("sync-api: the session must still resolve", resolves(store, sessionToken))
        assertNull(
            "sync-api: the session row must not get a revocation time",
            TokenFixtures.revokedAtMs(temp, sessionToken.tokenId),
        )
    }

    @Test
    fun `an unknown token id gives false and changes nothing`() {
        val temp = openTemp()
        val store = newStore(temp)
        val owner = TokenFixtures.register(temp, "alice", 1)
        val token = agent(store, owner.accountId)

        assertFalse("sync-api: an unknown id must give false", revoke(store, 999_999L))
        assertTrue("sync-api: an existing token must be untouched by an unknown id", resolves(store, token))
    }

    @Test
    fun `an agent token that has expired but was never revoked can still be revoked`() {
        val temp = openTemp()
        val store = newStore(temp)
        val owner = TokenFixtures.register(temp, "alice", 1)
        val token = agent(store, owner.accountId, expiresAt = TokenFixtures.START.plus(Duration.ofHours(1)))
        clock.advance(Duration.ofHours(2))
        assertFalse("sync-api: the token must be dead by expiry before the revoke", resolves(store, token))
        assertNull("sync-api: expiry alone must not set a revocation time", TokenFixtures.revokedAtMs(temp, token.tokenId))

        assertTrue("sync-api: an expired but unrevoked agent token must be revocable", revoke(store, token.tokenId))
        assertNotNull(
            "sync-api: the revoke must record a revocation time",
            TokenFixtures.revokedAtMs(temp, token.tokenId),
        )
    }

    @Test
    fun `the revocation time is the clock time of the call`() {
        val temp = openTemp()
        val store = newStore(temp)
        val owner = TokenFixtures.register(temp, "alice", 1)
        val token = agent(store, owner.accountId)
        clock.advance(Duration.ofMinutes(5))

        assertTrue("sync-api: the revoke must succeed", revoke(store, token.tokenId))

        val stored = TokenFixtures.revokedAtMs(temp, token.tokenId)
        assertEquals("sync-api: revoked_at_ms must equal the clock at the call", clock.millis(), stored)
        assertNotEquals("sync-api: revoked_at_ms must not be the issue time", TokenFixtures.START.toEpochMilli(), stored)
    }

    @Test
    fun `a second revoke after the clock moved keeps the first revocation time`() {
        val temp = openTemp()
        val store = newStore(temp)
        val owner = TokenFixtures.register(temp, "alice", 1)
        val token = agent(store, owner.accountId)
        clock.advance(Duration.ofMinutes(5))
        assertTrue("sync-api: the first revoke must succeed", revoke(store, token.tokenId))
        val first = TokenFixtures.revokedAtMs(temp, token.tokenId)
        clock.advance(Duration.ofMinutes(5))

        assertFalse("sync-api: the second revoke must return false", revoke(store, token.tokenId))

        assertEquals(
            "sync-api: the second revoke must not overwrite revoked_at_ms",
            first,
            TokenFixtures.revokedAtMs(temp, token.tokenId),
        )
        assertEquals(
            "sync-api: the kept time must be the clock of the first call",
            TokenFixtures.START.toEpochMilli() + 300_000L,
            first,
        )
    }
}
