package dev.breaker.server.syncapi.tokens

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.transcriptions.PushResult
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport
import dev.breaker.server.syncapi.transcriptions.TranscriptionStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

// setPulledChangeOn stores the smaller of since and the account head on a session of that account only.
// The head is the account's change_seq, which the real pushes below raise to 8 for alice.
internal class TokenStorePullNumberTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var clock: MutableClock
    private lateinit var store: TokenStore
    private lateinit var records: TranscriptionStore
    private var alice: Long = 0L
    private var bob: Long = 0L

    @Before
    fun openWorld() {
        temp = openTemp()
        clock = MutableClock(TokenFixtures.START)
        store = TokenFixtures.tokenStore(temp, clock)
        records = SyncRouteTestSupport.newStore(temp)
        alice = TokenFixtures.register(temp, "alice", 1).accountId
        bob = TokenFixtures.register(temp, "bob", 2).accountId
        for (n in 1..8) push(alice, n)
        for (n in 101..103) push(bob, n)
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

    @Test
    fun `a session stores the since number when since is below the head`() {
        val session = newSession(alice)

        assertEquals("sync-api: the returned number", 3L, setNumber(session.tokenId, alice, 3L))
        assertEquals("sync-api: the stored number", 3L, storedNumber(session.tokenId))
    }

    @Test
    fun `a since above the head stores the head`() {
        val session = newSession(alice)

        assertEquals("sync-api: the returned number is the head", 8L, setNumber(session.tokenId, alice, 99L))
        assertEquals("sync-api: the stored number is the head", 8L, storedNumber(session.tokenId))
    }

    @Test
    fun `a later smaller since lowers the stored number`() {
        val session = newSession(alice)
        assertEquals("sync-api: first set", 6L, setNumber(session.tokenId, alice, 6L))

        assertEquals("sync-api: second set", 2L, setNumber(session.tokenId, alice, 2L))
        assertEquals("sync-api: the stored number is the smaller one", 2L, storedNumber(session.tokenId))
    }

    @Test
    fun `an agent token of the same account stores nothing and keeps its column null`() {
        val agent = newAgent(alice)

        assertNull("sync-api: an agent token gets no number", setNumber(agent.tokenId, alice, 3L))
        assertNull("sync-api: the agent column stays null", storedNumber(agent.tokenId))
        val session = newSession(alice)
        val control = setNumber(session.tokenId, alice, 3L)
        assertEquals("sync-api: control, a session of the account stores", 3L, control)
    }

    @Test
    fun `a session of another account stores nothing and keeps its column null`() {
        val bobSession = newSession(bob)

        val refused = setNumber(bobSession.tokenId, alice, 3L)
        assertNull("sync-api: a session of bob gets no number for alice", refused)
        assertNull("sync-api: the column of bob stays null", storedNumber(bobSession.tokenId))
        val control = setNumber(bobSession.tokenId, bob, 3L)
        assertEquals("sync-api: control, bob stores with his own account", 3L, control)
    }

    @Test
    fun `the right session of the right account stores and the other session is untouched`() {
        val first = newSession(alice)
        val second = newSession(alice)
        assertNull("sync-api: before the set the first column is null", storedNumber(first.tokenId))
        assertNull("sync-api: before the set the second column is null", storedNumber(second.tokenId))

        assertEquals("sync-api: the returned number", 3L, setNumber(first.tokenId, alice, 3L))
        assertEquals("sync-api: the first session stores", 3L, storedNumber(first.tokenId))
        assertNull("sync-api: the second session is untouched", storedNumber(second.tokenId))
    }
}
