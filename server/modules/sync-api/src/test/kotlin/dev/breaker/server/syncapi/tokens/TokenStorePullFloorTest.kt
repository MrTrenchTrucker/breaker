package dev.breaker.server.syncapi.tokens

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.transcriptions.PushResult
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport
import dev.breaker.server.syncapi.transcriptions.TranscriptionStore
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// pullFloorOn is the lowest stored number over the account's live user sessions. Each test below stores
// numbers with setPulledChangeOn after real pushes (alice head 9, bob head 1), so no number is set by SQL.
internal class TokenStorePullFloorTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var clock: MutableClock
    private lateinit var store: TokenStore
    private lateinit var records: TranscriptionStore
    private var alice: Long = 0L
    private var bob: Long = 0L

    private val dayMs: Long = 24L * 60 * 60 * 1000

    @Before
    fun openWorld() {
        temp = openTemp()
        clock = MutableClock(TokenFixtures.START)
        store = TokenFixtures.tokenStore(temp, clock)
        records = SyncRouteTestSupport.newStore(temp)
        alice = TokenFixtures.register(temp, "alice", 1).accountId
        bob = TokenFixtures.register(temp, "bob", 2).accountId
        for (n in 1..9) push(alice, n)
        push(bob, 101)
    }

    private fun clientId(n: Int): String = "0f9c" + "4d1e-6a2b-4c3d-9e8f-" + n.toString().padStart(12, '0')

    private fun push(accountId: Long, n: Int) {
        val result = runBlocking { records.push(accountId, clientId(n), 0, SyncRouteTestSupport.ENCRYPTED) }
        assertEquals("sync-api: setup push $n", PushResult.Stored, result)
    }

    private fun newSession(accountId: Long): IssuedToken =
        TokenFixtures.issuedOf(runBlocking { store.issueSession(accountId) })

    private fun newAgent(accountId: Long): IssuedToken =
        TokenFixtures.issuedOf(
            runBlocking { store.issueAgent(accountId, AgentScope.TRANSCRIBE, "pull-agent", null) },
        )

    private fun setNumber(tokenId: Long, accountId: Long, since: Long): Long? =
        inTransaction<Long?>(temp) { connection ->
            store.setPulledChangeOn(connection, tokenId, accountId, since)
        }

    private fun floor(accountId: Long): Long? =
        inTransaction<Long?>(temp) { connection -> store.pullFloorOn(connection, accountId) }

    private fun liveOn(tokenId: Long, accountId: Long): Boolean =
        inTransaction<Boolean>(temp) { connection -> store.isSessionLiveOn(connection, tokenId, accountId) }

    private fun resolve(text: String): ResolvedToken? = runBlocking { store.resolve(text) }

    private fun setExpiry(tokenId: Long, expiresAtMs: Long) {
        inTransaction<Unit>(temp) { connection ->
            connection.prepareStatement("UPDATE tokens SET expires_at_ms = ? WHERE id = ?").use { statement ->
                statement.setLong(1, expiresAtMs)
                statement.setLong(2, tokenId)
                statement.executeUpdate()
            }
        }
    }

    @Test
    fun `two live sessions give the lower of their numbers`() {
        val at9 = newSession(alice)
        val at5 = newSession(alice)
        assertEquals("sync-api: setup number 9", 9L, setNumber(at9.tokenId, alice, 9L))
        assertEquals("sync-api: setup number 5", 5L, setNumber(at5.tokenId, alice, 5L))

        assertEquals("sync-api: the floor is the lower number", 5L, floor(alice))
    }

    @Test
    fun `a live session with no number gives no floor and a number on it gives the floor back`() {
        val unset = newSession(alice)
        val at5 = newSession(alice)
        assertEquals("sync-api: setup number 5", 5L, setNumber(at5.tokenId, alice, 5L))

        assertNull("sync-api: a live session with no number gives no floor", floor(alice))
        val control = setNumber(unset.tokenId, alice, 7L)
        assertEquals("sync-api: control, the number set on the unset session", 7L, control)
        assertEquals("sync-api: control, the floor is back to the lower number", 5L, floor(alice))
    }

    @Test
    fun `a revoked session does not count`() {
        val revoked = newSession(alice)
        assertEquals("sync-api: setup number 1", 1L, setNumber(revoked.tokenId, alice, 1L))
        assertTrue("sync-api: setup revoke", runBlocking { store.revokeSession(revoked.tokenId) })
        val at5 = newSession(alice)
        assertEquals("sync-api: setup number 5", 5L, setNumber(at5.tokenId, alice, 5L))

        assertEquals("sync-api: the revoked session at 1 is not the floor", 5L, floor(alice))
    }

    @Test
    fun `an expired session does not count`() {
        val expired = newSession(alice)
        assertEquals("sync-api: setup number 1", 1L, setNumber(expired.tokenId, alice, 1L))
        val at5 = newSession(alice)
        assertEquals("sync-api: setup number 5", 5L, setNumber(at5.tokenId, alice, 5L))
        // The live session is used on day 29, so it slides to day 59 and the other one stays at day 30.
        clock.advance(Duration.ofDays(29))
        assertNotNull("sync-api: setup use of the live session", resolve(at5.secret.reveal()))
        clock.advance(Duration.ofDays(1))
        assertFalse("sync-api: setup, the other session is expired on day 30", liveOn(expired.tokenId, alice))
        assertTrue("sync-api: setup, the slid session is live on day 30", liveOn(at5.tokenId, alice))

        assertEquals("sync-api: the expired session at 1 is not the floor", 5L, floor(alice))
    }

    @Test
    fun `an agent token with no number does not count`() {
        val at5 = newSession(alice)
        assertEquals("sync-api: setup number 5", 5L, setNumber(at5.tokenId, alice, 5L))
        val agent = newAgent(alice)
        assertNull("sync-api: the agent token has no number", storedNumber(agent.tokenId))

        assertEquals("sync-api: the agent token is not counted", 5L, floor(alice))
    }

    @Test
    fun `a session past the 180 day cap does not count`() {
        val capped = newSession(alice)
        assertEquals("sync-api: setup number 1", 1L, setNumber(capped.tokenId, alice, 1L))
        // Created at START; an expiry at day 181 is later than now and later than the cap at day 180.
        setExpiry(capped.tokenId, TokenFixtures.START.toEpochMilli() + 181L * dayMs)
        assertFalse("sync-api: setup, the capped session is not live", liveOn(capped.tokenId, alice))
        val at5 = newSession(alice)
        assertEquals("sync-api: setup number 5", 5L, setNumber(at5.tokenId, alice, 5L))

        assertEquals("sync-api: the capped session at 1 is not the floor", 5L, floor(alice))
    }

    @Test
    fun `an account with no live session gives no floor and another account never counts`() {
        val bobAt1 = newSession(bob)
        assertEquals("sync-api: setup, bob number 1", 1L, setNumber(bobAt1.tokenId, bob, 1L))
        assertEquals("sync-api: control, bob sees its own number", 1L, floor(bob))

        assertNull("sync-api: alice has no live session, so no floor", floor(alice))
        val aliceAt4 = newSession(alice)
        assertEquals("sync-api: control, alice number 4", 4L, setNumber(aliceAt4.tokenId, alice, 4L))
        assertEquals("sync-api: alice floor is its own 4, not the 1 of bob", 4L, floor(alice))
    }

    // The raw read of a stored number, for the agent case; the store never returns it.
    private fun storedNumber(tokenId: Long): Long? =
        inTransaction<Long?>(temp) { connection ->
            val sql = "SELECT last_pulled_change FROM tokens WHERE id = ?"
            connection.prepareStatement(sql).use { statement ->
                statement.setLong(1, tokenId)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "sync-api test: no token row with id $tokenId" }
                    val value = rows.getLong(1)
                    if (rows.wasNull()) null else value
                }
            }
        }
}
