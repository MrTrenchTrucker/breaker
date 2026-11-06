package dev.breaker.server.syncapi.tokens

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.transcriptions.PushResult
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport
import dev.breaker.server.syncapi.transcriptions.TranscriptionStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// The pin: pullFloorOn must agree with the login rule, isSessionLiveOn, on the same rows. Six sessions of
// alice sit in six states. For each one in turn, every session is set to 100 and that one to 0 (the head
// is 100 after real pushes). Both functions run in one transaction on the same rows. The floor is 0 exactly
// when the login rule says the session is live, and 100 otherwise, because the live control at 100 stays.
internal class TokenStorePullFloorPinTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var clock: MutableClock
    private lateinit var store: TokenStore
    private lateinit var records: TranscriptionStore
    private var alice: Long = 0L
    private var liveId: Long = 0L
    private var revokedId: Long = 0L
    private var expiredId: Long = 0L
    private var noExpiryId: Long = 0L
    private var cappedId: Long = 0L
    private var controlId: Long = 0L
    private var fixtureIds: List<Long> = emptyList()

    private val dayMs: Long = 24L * 60 * 60 * 1000

    @Before
    fun openFixture() {
        temp = openTemp()
        clock = MutableClock(TokenFixtures.START)
        store = TokenFixtures.tokenStore(temp, clock)
        records = SyncRouteTestSupport.newStore(temp)
        alice = TokenFixtures.register(temp, "alice", 1).accountId
        for (n in 1..100) push(alice, n)

        liveId = newSession(alice)
        revokedId = newSession(alice)
        expiredId = newSession(alice)
        noExpiryId = newSession(alice)
        cappedId = newSession(alice)
        controlId = newSession(alice)
        assertTrue("sync-api: setup revoke", runBlocking { store.revokeSession(revokedId) })
        TokenFixtures.expireSession(temp, expiredId)
        clearExpiry(noExpiryId)
        setExpiry(cappedId, TokenFixtures.START.toEpochMilli() + 181L * dayMs)
        fixtureIds = listOf(liveId, revokedId, expiredId, noExpiryId, cappedId, controlId)
    }

    private fun clientId(n: Int): String = "0f9c" + "4d1e-6a2b-4c3d-9e8f-" + n.toString().padStart(12, '0')

    private fun push(accountId: Long, n: Int) {
        val result = runBlocking { records.push(accountId, clientId(n), 0, SyncRouteTestSupport.ENCRYPTED) }
        assertEquals("sync-api: setup push $n", PushResult.Stored, result)
    }

    private fun newSession(accountId: Long): Long =
        TokenFixtures.issuedOf(runBlocking { store.issueSession(accountId) }).tokenId

    private fun setNumber(tokenId: Long, accountId: Long, since: Long): Long? =
        inTransaction<Long?>(temp) { connection ->
            store.setPulledChangeOn(connection, tokenId, accountId, since)
        }

    private fun liveOn(tokenId: Long): Boolean =
        inTransaction<Boolean>(temp) { connection -> store.isSessionLiveOn(connection, tokenId, alice) }

    // Raw SQL only for the expiry shapes the store never writes: an expiry set to a day, and a NULL expiry.
    private fun setExpiry(tokenId: Long, expiresAtMs: Long) {
        inTransaction<Unit>(temp) { connection ->
            connection.prepareStatement("UPDATE tokens SET expires_at_ms = ? WHERE id = ?").use { statement ->
                statement.setLong(1, expiresAtMs)
                statement.setLong(2, tokenId)
                statement.executeUpdate()
            }
        }
    }

    private fun clearExpiry(tokenId: Long) {
        inTransaction<Unit>(temp) { connection ->
            val sql = "UPDATE tokens SET expires_at_ms = NULL WHERE id = ?"
            connection.prepareStatement(sql).use { statement ->
                statement.setLong(1, tokenId)
                statement.executeUpdate()
            }
        }
    }

    @Test
    fun `the floor and the login rule agree for every fixture session`() {
        for (target in fixtureIds) {
            for (id in fixtureIds) {
                assertEquals("sync-api: reset of session $id to 100", 100L, setNumber(id, alice, 100L))
            }
            assertEquals("sync-api: setup of session $target to 0", 0L, setNumber(target, alice, 0L))

            val both: Pair<Boolean, Long?> = inTransaction(temp) { connection ->
                Pair(store.isSessionLiveOn(connection, target, alice), store.pullFloorOn(connection, alice))
            }
            val expected: Long = if (both.first) 0L else 100L
            val message = "sync-api: the floor for session $target when the login rule says ${both.first}"
            assertEquals(message, expected, both.second)
        }
    }

    @Test
    fun `the fixture sessions are in the states this test names`() {
        assertTrue("sync-api: the live session is live", liveOn(liveId))
        assertFalse("sync-api: the revoked session is not live", liveOn(revokedId))
        assertFalse("sync-api: the expired session is not live", liveOn(expiredId))
        assertTrue("sync-api: the session with no expiry is live", liveOn(noExpiryId))
        assertFalse("sync-api: the session past the 180 day cap is not live", liveOn(cappedId))
        assertTrue("sync-api: the control session is live", liveOn(controlId))
    }
}
