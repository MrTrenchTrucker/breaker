package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.AgentScope
import dev.breaker.server.syncapi.tokens.MutableClock
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class ReaderTranscriptionStorePullTest : TempDatabaseTest() {

    private val clock = MutableClock(TokenFixtures.START)

    private fun clientId(n: Int): String = SyncRouteTestSupport.CLIENT_ID.dropLast(1) + n.toString()

    private fun newStore(temp: TempDatabase): TranscriptionStore =
        SyncRouteTestSupport.newStore(temp, clock)

    private fun newTokenStore(temp: TempDatabase): TokenStore =
        TokenFixtures.tokenStore(temp, clock)

    private fun aliceId(temp: TempDatabase): Long =
        TokenFixtures.register(temp, "alice", 1).accountId

    private fun sessionIdOf(store: TokenStore, accountId: Long): Long =
        TokenFixtures.issuedOf(runBlocking { store.issueSession(accountId) }).tokenId

    private fun lastPulled(temp: TempDatabase, tokenId: Long): Long? =
        inTransaction(temp) { connection ->
            connection.prepareStatement("SELECT last_pulled_change FROM tokens WHERE id = ?").use { s ->
                s.setLong(1, tokenId)
                s.executeQuery().use { rows ->
                    if (!rows.next()) null else {
                        val value = rows.getLong(1)
                        if (rows.wasNull()) null else value
                    }
                }
            }
        }

    private fun head(temp: TempDatabase, accountId: Long): Long =
        inTransaction(temp) { connection ->
            connection.prepareStatement("SELECT change_seq FROM accounts WHERE id = ?").use { s ->
                s.setLong(1, accountId)
                s.executeQuery().use { rows ->
                    check(rows.next()) { "no account" }
                    rows.getLong(1)
                }
            }
        }

    @Test
    fun `pull sets the session number to min since head not max`() {
        val temp = openTemp()
        val accountId = aliceId(temp)
        val store = newStore(temp)
        val tokenStore = newTokenStore(temp)
        val sessionId = sessionIdOf(tokenStore, accountId)
        // Push two rows: head goes 0 -> 1 -> 2. Pull at since 5: number = min(5, 2) = 2.
        runBlocking {
            store.push(accountId, clientId(1), 0, SyncRouteTestSupport.ENCRYPTED)
            store.push(accountId, clientId(2), 0, SyncRouteTestSupport.ENCRYPTED)
        }
        assertEquals("sync-api: head is 2 after two pushes", 2L, head(temp, accountId))
        val page = runBlocking { store.pull(tokenStore, accountId, sessionId, 5L) }
        assertEquals("sync-api: clamped page carries next = head 2", 2L,
            (page as PageResult.Page).next)
        assertEquals("sync-api: last_pulled_change = min(5, head=2) = 2", 2L,
            lastPulled(temp, sessionId)!!
        )
    }

    @Test
    fun `a full list sets nothing`() {
        val temp = openTemp()
        val accountId = aliceId(temp)
        val store = newStore(temp)
        val tokenStore = newTokenStore(temp)
        val sessionId = sessionIdOf(tokenStore, accountId)
        runBlocking { store.push(accountId, clientId(1), 0, SyncRouteTestSupport.ENCRYPTED) }
        // Pull without since: a full list sets nothing.
        runBlocking { store.pull(tokenStore, accountId, sessionId, null) }
        assertNull("sync-api: full list leaves last_pulled_change NULL",
            lastPulled(temp, sessionId))
    }

    @Test
    fun `a session stores only its own number`() {
        val temp = openTemp()
        val accountId = aliceId(temp)
        val store = newStore(temp)
        val tokenStore = newTokenStore(temp)
        val a = sessionIdOf(tokenStore, accountId)
        val b = sessionIdOf(tokenStore, accountId)
        runBlocking { store.push(accountId, clientId(1), 0, SyncRouteTestSupport.ENCRYPTED) }
        // Session a pulls at since 1: sets a's number only.
        runBlocking { store.pull(tokenStore, accountId, a, 1L) }
        assertEquals("sync-api: session a's number is 1", 1L, lastPulled(temp, a)!!)
        assertNull("sync-api: session b's number is untouched (NULL)", lastPulled(temp, b))
    }

    @Test
    fun `another account's session is untouched by this account's pull`() {
        val temp = openTemp()
        val accountId = aliceId(temp)
        val bobId = TokenFixtures.register(temp, "bob", 2).accountId
        val store = newStore(temp)
        val tokenStore = newTokenStore(temp)
        val a = sessionIdOf(tokenStore, accountId)
        val bob = sessionIdOf(tokenStore, bobId)
        runBlocking { store.push(accountId, clientId(1), 0, SyncRouteTestSupport.ENCRYPTED) }
        runBlocking { store.pull(tokenStore, accountId, a, 1L) }
        assertEquals("sync-api: alice's session number is 1", 1L, lastPulled(temp, a)!!)
        assertNull("sync-api: bob's session of another account stays NULL", lastPulled(temp, bob))
    }

    @Test
    fun `pull at since below purged through answers Gone without setting the number`() {
        val temp = openTemp()
        val accountId = aliceId(temp)
        val store = newStore(temp)
        val tokenStore = newTokenStore(temp)
        val sessionId = sessionIdOf(tokenStore, accountId)
        // Push c1 (change 1), delete c1 (change 2). Head = 2.
        runBlocking {
            store.push(accountId, clientId(1), 0, SyncRouteTestSupport.ENCRYPTED)
            store.delete(accountId, clientId(1))
        }
        // Pull at since 2: number set to 2, floor = 2, purge removes delete record at change 2,
        // purged_through becomes 2.
        val page2 = runBlocking { store.pull(tokenStore, accountId, sessionId, 2L) }
        assertTrue("sync-api: pull at since 2 answers page", page2 is PageResult.Page)
        assertEquals("sync-api: page carries next = head 2", 2L,
            (page2 as PageResult.Page).next)
        val numberBeforeGone = lastPulled(temp, sessionId)!!
        assertEquals("sync-api: number before the Gone pull is 2", 2L, numberBeforeGone)

        // Pull at since 0 (below purged_through 2): Gone, and the number stays at 2
        // (a mutant that wrote the number before returning Gone would store 0).
        val page = runBlocking { store.pull(tokenStore, accountId, sessionId, 0L) }
        assertTrue("sync-api: since below purged_through answers Gone",
            page is PageResult.Gone)
        assertEquals("sync-api: number stays at 2 after the Gone pull", 2L,
            lastPulled(temp, sessionId))
    }

    @Test
    fun `an agent token id matches no session row so no number is stored`() {
        val temp = openTemp()
        val accountId = aliceId(temp)
        val store = newStore(temp)
        val tokenStore = newTokenStore(temp)
        val agentId = TokenFixtures.issuedOf(
            runBlocking { tokenStore.issueAgent(accountId, AgentScope.TRANSCRIBE, "agent", null) }).tokenId
        // store.pull takes a session token id. An agent token id is not a session row,
        // so setPulledChangeOn matches nothing and returns null.
        runBlocking { store.pull(tokenStore, accountId, agentId, 1L) }
        assertNull("sync-api: agent token id matches no session row, number stays unset",
            lastPulled(temp, agentId))
    }
}
