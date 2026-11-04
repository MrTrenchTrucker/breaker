package dev.breaker.server.syncapi.tokens

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** isSessionLiveOn is the live check inside the password change transaction: the live rule, read only. */
internal class TokenStoreSessionLiveTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var clock: MutableClock
    private lateinit var store: TokenStore
    private var alice: Long = 0L
    private var bob: Long = 0L

    @Before
    fun openStore() {
        temp = openTemp()
        clock = MutableClock(TokenFixtures.START)
        store = TokenFixtures.tokenStore(temp, clock)
        alice = TokenFixtures.register(temp, "alice", 1).accountId
        bob = TokenFixtures.register(temp, "bob", 2).accountId
    }

    private fun session(accountId: Long) = TokenFixtures.issuedOf(runBlocking { store.issueSession(accountId) })

    private fun live(tokenId: Long, accountId: Long): Boolean =
        inTransaction<Boolean>(temp) { connection -> store.isSessionLiveOn(connection, tokenId, accountId) }

    private fun expiresAt(tokenId: Long): Long? =
        inTransaction<Long?>(temp) { connection ->
            connection.prepareStatement("SELECT expires_at_ms FROM tokens WHERE id = ?").use { statement ->
                statement.setLong(1, tokenId)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "sync-api test: no token row with id $tokenId" }
                    val value = rows.getLong(1)
                    if (rows.wasNull()) null else value
                }
            }
        }

    @Test
    fun `a live session of the account is live`() {
        val issued = session(alice)

        assertTrue("sync-api: a fresh session must be live", live(issued.tokenId, alice))
    }

    @Test
    fun `a revoked session is not live`() {
        val issued = session(alice)
        assertTrue("sync-api: setup revoke", runBlocking { store.revokeSession(issued.tokenId) })

        assertFalse("sync-api: a revoked session must not be live", live(issued.tokenId, alice))
    }

    @Test
    fun `an expired session is not live`() {
        val issued = session(alice)
        TokenFixtures.expireSession(temp, issued.tokenId)

        assertFalse("sync-api: an expired session must not be live", live(issued.tokenId, alice))
    }

    @Test
    fun `a session is live one second before its expiry and not live at its expiry`() {
        val issued = session(alice)

        clock.advance(Duration.ofDays(30).minusSeconds(1))
        assertTrue("sync-api: one second before day 30 the session is live", live(issued.tokenId, alice))

        clock.advance(Duration.ofSeconds(1))
        assertFalse("sync-api: at day 30 the session is dead", live(issued.tokenId, alice))
    }

    @Test
    fun `an agent token id is not live`() {
        val agent = TokenFixtures.issuedOf(runBlocking { store.issueAgent(alice, AgentScope.TRANSCRIBE, "test-agent", null) })

        assertFalse("sync-api: an agent token must not count as a session", live(agent.tokenId, alice))
    }

    @Test
    fun `a session of another account and an unknown account are not live`() {
        val issued = session(alice)

        assertFalse("sync-api: the session of alice must not be live for bob", live(issued.tokenId, bob))
        assertFalse("sync-api: the session of alice must not be live for an unknown account", live(issued.tokenId, 999_999L))
        assertTrue("sync-api: it is live for its own account", live(issued.tokenId, alice))
    }

    @Test
    fun `the live check does not slide the expiry but a resolve does`() {
        val issued = session(alice)
        val first = expiresAt(issued.tokenId)
        clock.advance(Duration.ofDays(10))

        assertTrue("sync-api: the session is live on day 10", live(issued.tokenId, alice))
        assertEquals("sync-api: the live check must not move the expiry", first, expiresAt(issued.tokenId))

        assertTrue("sync-api: resolve of the session", runBlocking { store.resolve(issued.secret.reveal()) } != null)
        assertTrue("sync-api: a resolve must move the expiry", first != expiresAt(issued.tokenId))
    }
}
