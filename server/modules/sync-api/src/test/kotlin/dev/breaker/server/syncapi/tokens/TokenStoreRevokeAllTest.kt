package dev.breaker.server.syncapi.tokens

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The account reset rule: every token of the account dies, nobody else's does. */
internal class TokenStoreRevokeAllTest : TempDatabaseTest() {

    private val clock = MutableClock(TokenFixtures.START)

    private fun newStore(temp: TempDatabase): TokenStore = TokenFixtures.tokenStore(temp, clock)

    private fun session(store: TokenStore, accountId: Long): IssuedToken =
        TokenFixtures.issuedOf(runBlocking { store.issueSession(accountId) })

    private fun agent(store: TokenStore, accountId: Long, scope: AgentScope): IssuedToken =
        TokenFixtures.issuedOf(runBlocking { store.issueAgent(accountId, scope, "test-agent", null) })

    private fun resolves(store: TokenStore, token: IssuedToken): Boolean =
        runBlocking { store.resolve(token.secret.reveal()) } != null

    private fun revokeAll(store: TokenStore, accountId: Long): Int = runBlocking { store.revokeAllForAccount(accountId) }

    @Test
    fun `revoking all of an account returns the row count and kills both kinds and both scopes`() {
        val temp = openTemp()
        val store = newStore(temp)
        val owner = TokenFixtures.register(temp, "alice", 1)
        val first = session(store, owner.accountId)
        val second = session(store, owner.accountId)
        val transcribe = agent(store, owner.accountId, AgentScope.TRANSCRIBE)
        val admin = agent(store, owner.accountId, AgentScope.ADMIN)

        assertEquals("sync-api: all four live rows must be counted", 4, revokeAll(store, owner.accountId))

        assertFalse("sync-api: the first session must be dead", resolves(store, first))
        assertFalse("sync-api: the second session must be dead", resolves(store, second))
        assertFalse("sync-api: the transcribe agent token must be dead", resolves(store, transcribe))
        assertFalse("sync-api: the admin agent token must be dead", resolves(store, admin))
        assertEquals("sync-api: no live row may remain", 0L, TokenFixtures.liveTokenCount(temp, owner.accountId))
    }

    @Test
    fun `revoking all of the middle account leaves the accounts on either side untouched`() {
        val temp = openTemp()
        val store = newStore(temp)
        val low = TokenFixtures.register(temp, "alice", 1)
        val target = TokenFixtures.register(temp, "bob", 2)
        val high = TokenFixtures.register(temp, "carol", 3)
        val lowSession = session(store, low.accountId)
        val lowAgent = agent(store, low.accountId, AgentScope.ADMIN)
        val targetSession = session(store, target.accountId)
        val targetAgent = agent(store, target.accountId, AgentScope.TRANSCRIBE)
        val highSession = session(store, high.accountId)
        val highAgent = agent(store, high.accountId, AgentScope.TRANSCRIBE)

        assertEquals("sync-api: only the two rows of the target account", 2, revokeAll(store, target.accountId))

        assertFalse("sync-api: the target session must be dead", resolves(store, targetSession))
        assertFalse("sync-api: the target agent token must be dead", resolves(store, targetAgent))
        assertTrue("sync-api: a lower account's session must resolve", resolves(store, lowSession))
        assertTrue("sync-api: a lower account's agent token must resolve", resolves(store, lowAgent))
        assertTrue("sync-api: a higher account's session must resolve", resolves(store, highSession))
        assertTrue("sync-api: a higher account's agent token must resolve", resolves(store, highAgent))
    }

    @Test
    fun `the count leaves out a row revoked earlier and that row keeps its time`() {
        val temp = openTemp()
        val store = newStore(temp)
        val owner = TokenFixtures.register(temp, "alice", 1)
        val sessionToken = session(store, owner.accountId)
        val early = agent(store, owner.accountId, AgentScope.TRANSCRIBE)
        val late = agent(store, owner.accountId, AgentScope.ADMIN)
        clock.advance(Duration.ofMinutes(1))
        assertTrue("sync-api: the early revoke must succeed", runBlocking { store.revokeAgentToken(early.tokenId) })
        val earlyTime = TokenFixtures.revokedAtMs(temp, early.tokenId)
        clock.advance(Duration.ofMinutes(1))

        assertEquals("sync-api: only the two still-live rows are counted", 2, revokeAll(store, owner.accountId))

        assertEquals(
            "sync-api: the early row must keep its own revocation time",
            earlyTime,
            TokenFixtures.revokedAtMs(temp, early.tokenId),
        )
        val now = clock.millis()
        assertEquals("sync-api: the session gets the clock of the call", now, TokenFixtures.revokedAtMs(temp, sessionToken.tokenId))
        assertEquals("sync-api: the late agent token gets the clock of the call", now, TokenFixtures.revokedAtMs(temp, late.tokenId))
    }

    @Test
    fun `a second revoke of all returns zero and moves no time`() {
        val temp = openTemp()
        val store = newStore(temp)
        val owner = TokenFixtures.register(temp, "alice", 1)
        val sessionToken = session(store, owner.accountId)
        val token = agent(store, owner.accountId, AgentScope.TRANSCRIBE)
        assertEquals("sync-api: the first call counts both rows", 2, revokeAll(store, owner.accountId))
        val sessionTime = TokenFixtures.revokedAtMs(temp, sessionToken.tokenId)
        val agentTime = TokenFixtures.revokedAtMs(temp, token.tokenId)
        clock.advance(Duration.ofMinutes(1))

        assertEquals("sync-api: nothing is live, so the second call counts zero", 0, revokeAll(store, owner.accountId))

        assertEquals("sync-api: the session time must not move", sessionTime, TokenFixtures.revokedAtMs(temp, sessionToken.tokenId))
        assertEquals("sync-api: the agent time must not move", agentTime, TokenFixtures.revokedAtMs(temp, token.tokenId))
    }

    @Test
    fun `an unknown account id returns zero and revokes nothing`() {
        val temp = openTemp()
        val store = newStore(temp)
        val owner = TokenFixtures.register(temp, "alice", 1)
        val sessionToken = session(store, owner.accountId)
        val token = agent(store, owner.accountId, AgentScope.TRANSCRIBE)

        assertEquals("sync-api: an unknown account has no rows", 0, revokeAll(store, 999_999L))

        assertTrue("sync-api: an existing session must be untouched", resolves(store, sessionToken))
        assertTrue("sync-api: an existing agent token must be untouched", resolves(store, token))
    }
}
