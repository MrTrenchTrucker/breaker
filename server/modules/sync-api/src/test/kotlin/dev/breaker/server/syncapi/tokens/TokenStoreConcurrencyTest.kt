package dev.breaker.server.syncapi.tokens

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Callers share one database through its single lane. The clock is fixed because the
 * calls run in parallel inside one process; nothing here depends on their order.
 */
internal class TokenStoreConcurrencyTest : TempDatabaseTest() {

    private val calls = 8
    private val timeoutMs = 30_000L

    private fun newStore(temp: TempDatabase): TokenStore = TokenFixtures.tokenStore(temp, AccountFixtures.fixedClock)

    /** Runs [block] [count] times at once inside one process; the index tells the calls apart. */
    private fun <T> concurrently(count: Int, block: suspend (Int) -> T): List<T> {
        require(count in 1..calls) { "sync-api test helper: at most $calls concurrent calls" }
        return runBlocking<List<T>> {
            withTimeout<List<T>>(timeoutMs) {
                coroutineScope<List<T>> {
                    val jobs: List<Deferred<T>> = (0 until count).map { index ->
                        async<T>(Dispatchers.Default) { block(index) }
                    }
                    jobs.awaitAll()
                }
            }
        }
    }

    private fun agent(store: TokenStore, accountId: Long): IssuedToken =
        TokenFixtures.issuedOf(runBlocking { store.issueAgent(accountId, AgentScope.TRANSCRIBE, "test-agent", null) })

    private fun resolveNow(store: TokenStore, token: IssuedToken): ResolvedToken? =
        runBlocking { store.resolve(token.secret.reveal()) }

    @Test
    fun `eight concurrent session issues give eight distinct ids, texts and rows that resolve to themselves`() {
        val temp = openTemp()
        val store = newStore(temp)
        val owner = TokenFixtures.register(temp, "alice", 1)

        val issued: List<IssuedToken> = concurrently<IssuedToken>(calls) {
            TokenFixtures.issuedOf(store.issueSession(owner.accountId))
        }

        assertEquals("sync-api: one id per call", calls, issued.map { token -> token.tokenId }.toSet().size)
        assertEquals("sync-api: one secret text per call", calls, issued.map { token -> token.secret.reveal() }.toSet().size)
        assertEquals("sync-api: one row per call", calls.toLong(), TokenFixtures.tokenRowCount(temp))
        for (token in issued) {
            val resolved = resolveNow(store, token)
            assertNotNull("sync-api: token ${token.tokenId} must resolve", resolved)
            assertEquals("sync-api: a token must resolve to its own id", token.tokenId, resolved!!.tokenId)
        }
    }

    @Test
    fun `eight concurrent resolves agree, and after a revoke eight concurrent resolves all miss`() {
        val temp = openTemp()
        val store = newStore(temp)
        val owner = TokenFixtures.register(temp, "alice", 1)
        val token = agent(store, owner.accountId)
        val text = token.secret.reveal()

        val before: List<ResolvedToken?> = concurrently<ResolvedToken?>(calls) { store.resolve(text) }

        for (resolved in before) {
            assertNotNull("sync-api: a live token must resolve in every concurrent call", resolved)
            assertEquals("sync-api: every call must see the same token", token.tokenId, resolved!!.tokenId)
        }
        assertTrue("sync-api: the revoke must succeed", runBlocking { store.revokeAgentToken(token.tokenId) })

        val after: List<ResolvedToken?> = concurrently<ResolvedToken?>(calls) { store.resolve(text) }

        for (resolved in after) {
            assertNull("sync-api: a revoked token must miss in every concurrent call", resolved)
        }
    }

    @Test
    fun `a revoke racing seven resolves ends with the token dead and no call failing`() {
        val temp = openTemp()
        val store = newStore(temp)
        val owner = TokenFixtures.register(temp, "alice", 1)
        val token = agent(store, owner.accountId)
        val text = token.secret.reveal()

        val results: List<Any?> = concurrently<Any?>(calls) { index ->
            if (index == 0) store.revokeAgentToken(token.tokenId) else store.resolve(text)
        }

        assertEquals("sync-api: the revoke must report true exactly once", true, results[0])
        for (other in results.drop(1)) {
            assertTrue(
                "sync-api: a racing resolve is either a miss or the right token, got $other",
                other == null || (other as ResolvedToken).tokenId == token.tokenId,
            )
        }
        assertNull("sync-api: the token must be dead afterwards", resolveNow(store, token))
        assertFalse("sync-api: the row is revoked, so a repeat revoke is false", runBlocking { store.revokeAgentToken(token.tokenId) })
    }

    @Test
    fun `eight concurrent keep-one calls revoke each of the other sessions exactly once`() {
        val temp = openTemp()
        val store = newStore(temp)
        val owner = TokenFixtures.register(temp, "alice", 1)
        val sessions: List<IssuedToken> = (1..5).map { TokenFixtures.issuedOf(runBlocking { store.issueSession(owner.accountId) }) }
        val kept = sessions[2]

        val counts: List<Int> = concurrently<Int>(calls) { store.revokeOtherSessions(owner.accountId, kept.tokenId) }

        assertEquals("sync-api: the four other sessions are revoked once in total", 4, counts.sum())
        assertNotNull("sync-api: the kept session must still resolve", resolveNow(store, kept))
        for (token in sessions.filter { session -> session.tokenId != kept.tokenId }) {
            assertNull("sync-api: session ${token.tokenId} must be dead", resolveNow(store, token))
        }
        assertEquals("sync-api: only the kept session is live", 1L, TokenFixtures.liveTokenCount(temp, owner.accountId))
    }
}
