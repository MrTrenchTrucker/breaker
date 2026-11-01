package dev.breaker.server.syncapi.tokens

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.TokenFixtures.issuedOf
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

// A session token lives 30 days after its last use; each authenticated use moves its expiry to now + 30 days.
// An agent token keeps its own optional expiry and is never moved by a use.
internal class TokenStoreSessionExpiryTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var clock: MutableClock
    private lateinit var store: TokenStore
    private var userId: Long = 0L

    @Before
    fun openStore() {
        temp = openTemp()
        clock = MutableClock(TokenFixtures.START)
        store = TokenFixtures.tokenStore(temp, clock)
        userId = TokenFixtures.register(temp, "member", 2).accountId
    }

    private fun session(): IssuedToken = issuedOf(runBlocking { store.issueSession(userId) })

    private fun agent(expiresAt: Instant?): IssuedToken =
        issuedOf(runBlocking { store.issueAgent(userId, AgentScope.TRANSCRIBE, "bot", expiresAt) })

    private fun resolve(text: String): ResolvedToken? = runBlocking { store.resolve(text) }

    private fun advanceDays(days: Long) = clock.advance(Duration.ofDays(days))

    private fun expiresAt(id: Long): Long? =
        inTransaction<Long?>(temp) { connection ->
            connection.prepareStatement("SELECT expires_at_ms FROM tokens WHERE id = ?").use { statement ->
                statement.setLong(1, id)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "sync-api test: no token row with id $id" }
                    val value = rows.getLong(1)
                    if (rows.wasNull()) null else value
                }
            }
        }

    private fun clearExpiry(id: Long) {
        runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement("UPDATE tokens SET expires_at_ms = NULL WHERE id = ?").use { statement ->
                    statement.setLong(1, id)
                    statement.executeUpdate()
                }
            }
        }
    }

    @Test
    fun `a new session expires thirty days after it was issued`() {
        val issued = session()
        assertEquals("sync-api: the stored expiry", clock.millis() + 2_592_000_000L, expiresAt(issued.tokenId))
    }

    @Test
    fun `a session used on day 29 stays valid and its expiry moves forward`() {
        val issued = session()
        advanceDays(29)
        assertNotNull("sync-api: a session on day 29 resolves", resolve(issued.secret.reveal()))
        assertEquals("sync-api: the expiry moved to now + 30 days", clock.millis() + 2_592_000_000L, expiresAt(issued.tokenId))
    }

    @Test
    fun `a session unused for 30 days is refused`() {
        val text = session().secret.reveal()
        advanceDays(30)
        assertNull("sync-api: a session on day 30 is refused", resolve(text))
    }

    @Test
    fun `a session is still valid one second before the thirty days`() {
        val text = session().secret.reveal()
        clock.advance(Duration.ofDays(30).minusSeconds(1))
        assertNotNull("sync-api: a session one second before day 30 resolves", resolve(text))
    }

    @Test
    fun `an expired session stays refused after a later try`() {
        val issued = session()
        advanceDays(31)
        assertNull("sync-api: a session on day 31 is refused", resolve(issued.secret.reveal()))
        val before = expiresAt(issued.tokenId)
        advanceDays(1)
        assertNull("sync-api: a session on day 32 is still refused", resolve(issued.secret.reveal()))
        assertEquals("sync-api: a refused try must not move the expiry", before, expiresAt(issued.tokenId))
    }

    @Test
    fun `each use pushes the date out again`() {
        val text = session().secret.reveal()
        advanceDays(20)
        assertNotNull("sync-api: day 20 resolves", resolve(text))
        advanceDays(20)
        assertNotNull("sync-api: day 40 resolves", resolve(text))
        advanceDays(20)
        assertNotNull("sync-api: day 60 resolves", resolve(text))
        advanceDays(31)
        assertNull("sync-api: day 91, 31 days after the last use, is refused", resolve(text))
    }

    @Test
    fun `a session issued with no expiry gets one at its next use`() {
        val issued = session()
        clearExpiry(issued.tokenId)
        assertNull("sync-api: the old shape has no expiry", expiresAt(issued.tokenId))
        advanceDays(3)
        assertNotNull("sync-api: the old shape still resolves", resolve(issued.secret.reveal()))
        assertEquals("sync-api: its next use sets now + 30 days", clock.millis() + 2_592_000_000L, expiresAt(issued.tokenId))
    }

    @Test
    fun `an agent token is not moved by a use`() {
        val issued = agent(TokenFixtures.START.plus(Duration.ofDays(60)))
        val before = expiresAt(issued.tokenId)
        advanceDays(10)
        assertNotNull("sync-api: an agent token on day 10 resolves", resolve(issued.secret.reveal()))
        assertEquals("sync-api: the agent expiry did not move", before, expiresAt(issued.tokenId))
    }

    @Test
    fun `an agent token without expiry stays without expiry after a use`() {
        val issued = agent(null)
        advanceDays(10)
        assertNotNull("sync-api: an agent token without expiry resolves", resolve(issued.secret.reveal()))
        assertNull("sync-api: it still has no expiry", expiresAt(issued.tokenId))
    }
}
