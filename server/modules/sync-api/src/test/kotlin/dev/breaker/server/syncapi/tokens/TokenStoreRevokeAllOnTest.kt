package dev.breaker.server.syncapi.tokens

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.db.TestDatabases
import dev.breaker.server.syncapi.handoff.HandoffFixtures
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The same account reset rule on the caller's connection: the work is part of the caller's transaction. */
internal class TokenStoreRevokeAllOnTest : TempDatabaseTest() {

    private val clock = MutableClock(TokenFixtures.START)
    private lateinit var temp: TempDatabase
    private lateinit var store: TokenStore
    private var low: Long = 0L
    private var target: Long = 0L
    private lateinit var lowSession: IssuedToken
    private lateinit var targetSession: IssuedToken
    private lateinit var targetOther: IssuedToken
    private lateinit var targetAgent: IssuedToken

    // low (id 1) has a session; target (id 2) has two sessions and an agent token and two hand-off codes.
    @Before
    fun openWorld() {
        temp = openTemp()
        store = TokenFixtures.tokenStore(temp, clock)
        low = TokenFixtures.register(temp, "alice", 1).accountId
        target = TokenFixtures.register(temp, "bob", 2).accountId
        lowSession = TokenFixtures.issuedOf(runBlocking { store.issueSession(low) })
        targetSession = TokenFixtures.issuedOf(runBlocking { store.issueSession(target) })
        targetOther = TokenFixtures.issuedOf(runBlocking { store.issueSession(target) })
        targetAgent = TokenFixtures.issuedOf(runBlocking { store.issueAgent(target, AgentScope.TRANSCRIBE, "test-agent", null) })
        HandoffFixtures.insertRawRow(temp, ByteArray(32) { 1 }, target, 0L, clock.millis() + 100_000L)
        HandoffFixtures.insertRawRow(temp, ByteArray(32) { 2 }, target, 0L, clock.millis() + 100_000L)
        HandoffFixtures.insertRawRow(temp, ByteArray(32) { 3 }, low, 0L, clock.millis() + 100_000L)
    }

    private fun revokeAllOn(accountId: Long): Int =
        inTransaction(temp) { connection -> store.revokeAllForAccountOn(connection, accountId) }

    private fun live(issued: IssuedToken): Boolean = runBlocking { store.peek(issued.secret.reveal()) } != null

    @Test
    fun `revokeAllForAccountOn returns the revoked count and ends both kinds`() {
        assertEquals("sync-api: the three live rows are counted", 3, revokeAllOn(target))
        assertFalse("sync-api: first session", live(targetSession))
        assertFalse("sync-api: second session", live(targetOther))
        assertFalse("sync-api: agent token", live(targetAgent))
    }

    @Test
    fun `revokeAllForAccountOn leaves the tokens of another account`() {
        revokeAllOn(target)
        assertTrue("sync-api: the other account session", live(lowSession))
        assertEquals("sync-api: live rows of the other account", 1L, TokenFixtures.liveTokenCount(temp, low))
    }

    @Test
    fun `revokeAllForAccountOn deletes the hand-off codes of the account only`() {
        revokeAllOn(target)
        assertEquals("sync-api: codes of the account", 0L, HandoffFixtures.rowCountFor(temp, target))
        assertEquals("sync-api: codes of the other account", 1L, HandoffFixtures.rowCountFor(temp, low))
    }

    @Test
    fun `revokeAllForAccountOn stamps the clock and a second call counts zero`() {
        clock.advance(Duration.ofMinutes(2))
        revokeAllOn(target)
        assertEquals("sync-api: revoked at", clock.millis(), TokenFixtures.revokedAtMs(temp, targetSession.tokenId))
        clock.advance(Duration.ofMinutes(2))
        assertEquals("sync-api: second call", 0, revokeAllOn(target))
    }

    @Test
    fun `a failure after revokeAllForAccountOn in the same transaction undoes it`() {
        TestDatabases.expectFailure<IllegalStateException>("a failure after the revoke") {
            inTransaction<Int>(temp) { connection ->
                store.revokeAllForAccountOn(connection, target)
                throw IllegalStateException("forced")
            }
        }
        assertTrue("sync-api: the session was revoked although the transaction failed", live(targetSession))
        assertTrue("sync-api: the agent token was revoked although the transaction failed", live(targetAgent))
        assertEquals("sync-api: the codes were deleted although the transaction failed", 2L, HandoffFixtures.rowCountFor(temp, target))
    }

    @Test
    fun `revokeAllForAccountOn for an id with no account returns 0 and touches nothing`() {
        assertEquals("sync-api: the count", 0, revokeAllOn(target + 1000L))
        assertTrue("sync-api: a session was revoked", live(targetSession))
    }
}
