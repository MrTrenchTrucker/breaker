package dev.breaker.server.syncapi.tokens

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The change-password rule: the account's other sessions die, its agent tokens and other accounts never. */
internal class TokenStoreRevokeOthersTest : TempDatabaseTest() {

    private val clock = MutableClock(TokenFixtures.START)

    /** Account A (admin) with three sessions and two agent tokens; account B (user) with one of each. */
    private class World(
        val temp: TempDatabase,
        val store: TokenStore,
        val accountA: Long,
        val s1: IssuedToken,
        val s2: IssuedToken,
        val s3: IssuedToken,
        val a1: IssuedToken,
        val a2: IssuedToken,
        val sb1: IssuedToken,
        val ab1: IssuedToken,
    )

    private fun world(): World {
        val temp = openTemp()
        val store = TokenFixtures.tokenStore(temp, clock)
        val a = TokenFixtures.register(temp, "alice", 1)
        val b = TokenFixtures.register(temp, "bob", 2)
        return World(
            temp = temp,
            store = store,
            accountA = a.accountId,
            s1 = session(store, a.accountId),
            s2 = session(store, a.accountId),
            s3 = session(store, a.accountId),
            a1 = agent(store, a.accountId, AgentScope.TRANSCRIBE),
            a2 = agent(store, a.accountId, AgentScope.ADMIN),
            sb1 = session(store, b.accountId),
            ab1 = agent(store, b.accountId, AgentScope.TRANSCRIBE),
        )
    }

    private fun session(store: TokenStore, accountId: Long): IssuedToken =
        TokenFixtures.issuedOf(runBlocking { store.issueSession(accountId) })

    private fun agent(store: TokenStore, accountId: Long, scope: AgentScope): IssuedToken =
        TokenFixtures.issuedOf(runBlocking { store.issueAgent(accountId, scope, "test-agent", null) })

    private fun World.live(token: IssuedToken): Boolean = runBlocking { store.resolve(token.secret.reveal()) } != null

    private fun World.revokeOthers(accountId: Long, keepTokenId: Long): Int =
        runBlocking { store.revokeOtherSessions(accountId, keepTokenId) }

    @Test
    fun `keeping one session revokes the other two and touches no agent token or other account`() {
        val w = world()

        assertEquals("sync-api: the two other sessions are revoked", 2, w.revokeOthers(w.accountA, w.s2.tokenId))

        assertTrue("sync-api: the kept session must resolve", w.live(w.s2))
        assertFalse("sync-api: the first session must be dead", w.live(w.s1))
        assertFalse("sync-api: the third session must be dead", w.live(w.s3))
        assertTrue("sync-api: the transcribe agent token must resolve", w.live(w.a1))
        assertTrue("sync-api: the admin agent token must resolve", w.live(w.a2))
        assertTrue("sync-api: another account's session must resolve", w.live(w.sb1))
        assertTrue("sync-api: another account's agent token must resolve", w.live(w.ab1))
    }

    @Test
    fun `a second identical call returns zero and changes nothing`() {
        val w = world()
        assertEquals("sync-api: the first call revokes two", 2, w.revokeOthers(w.accountA, w.s2.tokenId))
        val s1Time = TokenFixtures.revokedAtMs(w.temp, w.s1.tokenId)
        clock.advance(Duration.ofMinutes(1))

        assertEquals("sync-api: the second call has nothing left to revoke", 0, w.revokeOthers(w.accountA, w.s2.tokenId))

        assertEquals("sync-api: an earlier revoke keeps its time", s1Time, TokenFixtures.revokedAtMs(w.temp, w.s1.tokenId))
        assertTrue("sync-api: the kept session must still resolve", w.live(w.s2))
        assertEquals("sync-api: one session and both agent tokens stay live", 3L, TokenFixtures.liveTokenCount(w.temp, w.accountA))
    }

    @Test
    fun `an agent token id as the keep id spares nothing and the agent token stays live`() {
        val w = world()

        assertEquals("sync-api: all three sessions are revoked", 3, w.revokeOthers(w.accountA, w.a1.tokenId))

        assertFalse("sync-api: the first session must be dead", w.live(w.s1))
        assertFalse("sync-api: the second session must be dead", w.live(w.s2))
        assertFalse("sync-api: the third session must be dead", w.live(w.s3))
        assertTrue("sync-api: the named agent token must be untouched", w.live(w.a1))
        assertTrue("sync-api: the other agent token must be untouched", w.live(w.a2))
    }

    @Test
    fun `another account's session as the keep id spares nothing of this account and stays live`() {
        val w = world()

        assertEquals("sync-api: all three sessions are revoked", 3, w.revokeOthers(w.accountA, w.sb1.tokenId))

        assertFalse("sync-api: the first session must be dead", w.live(w.s1))
        assertFalse("sync-api: the second session must be dead", w.live(w.s2))
        assertFalse("sync-api: the third session must be dead", w.live(w.s3))
        assertTrue("sync-api: the other account's session must be untouched", w.live(w.sb1))
        assertNull("sync-api: it must have no revocation time", TokenFixtures.revokedAtMs(w.temp, w.sb1.tokenId))
    }

    @Test
    fun `an already revoked session as the keep id spares nothing`() {
        val w = world()
        assertEquals("sync-api: setup revokes two", 2, w.revokeOthers(w.accountA, w.s2.tokenId))

        assertEquals("sync-api: the dead keep id spares the last live session", 1, w.revokeOthers(w.accountA, w.s1.tokenId))

        assertFalse("sync-api: the former keeper must now be dead", w.live(w.s2))
        assertTrue("sync-api: agent tokens must still resolve", w.live(w.a1) && w.live(w.a2))
    }

    @Test
    fun `an unknown keep id revokes every session of the account`() {
        val w = world()

        assertEquals("sync-api: all three sessions are revoked", 3, w.revokeOthers(w.accountA, 999_999L))

        assertFalse("sync-api: the first session must be dead", w.live(w.s1))
        assertFalse("sync-api: the second session must be dead", w.live(w.s2))
        assertFalse("sync-api: the third session must be dead", w.live(w.s3))
        assertTrue("sync-api: agent tokens must still resolve", w.live(w.a1) && w.live(w.a2))
        assertTrue("sync-api: the other account must still resolve", w.live(w.sb1) && w.live(w.ab1))
    }

    @Test
    fun `revoked sessions get the clock of the call and the others get no time`() {
        val w = world()
        clock.advance(Duration.ofMinutes(7))

        assertEquals("sync-api: two sessions are revoked", 2, w.revokeOthers(w.accountA, w.s2.tokenId))

        val now = clock.millis()
        assertEquals("sync-api: first session time", now, TokenFixtures.revokedAtMs(w.temp, w.s1.tokenId))
        assertEquals("sync-api: third session time", now, TokenFixtures.revokedAtMs(w.temp, w.s3.tokenId))
        assertNull("sync-api: the kept session has no time", TokenFixtures.revokedAtMs(w.temp, w.s2.tokenId))
        assertNull("sync-api: an agent token has no time", TokenFixtures.revokedAtMs(w.temp, w.a1.tokenId))
    }
}
