package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.MutableClock
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration

internal class AuthSignOutAllRouteTest : TempDatabaseTest() {
    private lateinit var temp: TempDatabase
    private lateinit var tokenStore: TokenStore
    private val clock = MutableClock(TokenFixtures.START)

    @Before
    fun setUp() {
        temp = openTemp()
        tokenStore = TokenFixtures.tokenStore(temp, clock)
    }

    private fun account(): Long = TokenFixtures.register(temp, "alice", 1).accountId

    private fun bobAccount(): Long = TokenFixtures.register(temp, "bob", 2).accountId

    private fun bearer(text: String): String = "Bearer $text"

    private fun signOutAll(authorization: String?): Pair<HttpStatusCode, String> = runBlocking {
        var status = HttpStatusCode.OK
        var text = ""
        testApplication {
            application { authDevicesRoute(tokenStore) }
            val response = client.post("/v1/auth/devices/sign-out-all") {
                if (authorization != null) header(HttpHeaders.Authorization, authorization)
            }
            status = response.status
            text = response.bodyAsText()
        }
        Pair(status, text)
    }

    private fun resolves(secret: String): Boolean = runBlocking {
        tokenStore.resolve(secret) != null
    }

    // One sign-in hand-off row written by raw SQL, for a state the store itself never writes here.
    private fun insertHandoff(accountId: Long) = runBlocking {
        temp.db.transaction { connection ->
            connection.prepareStatement(
                "INSERT INTO signin_handoff (code_hash, account_id, challenge, package, created_at_ms, expires_at_ms) " +
                    "VALUES (?, ?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setBytes(1, ByteArray(32) { 11 })
                statement.setLong(2, accountId)
                statement.setString(3, "C".repeat(43))
                statement.setBytes(4, ByteArray(113) { 13 })
                statement.setLong(5, TokenFixtures.START.toEpochMilli())
                statement.setLong(6, TokenFixtures.START.plus(Duration.ofDays(1)).toEpochMilli())
                statement.executeUpdate()
            }
        }
    }

    private fun handoffCount(accountId: Long): Long = runBlocking {
        temp.db.transaction { connection ->
            connection.prepareStatement("SELECT count(*) FROM signin_handoff WHERE account_id = ?").use { statement ->
                statement.setLong(1, accountId)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "sync-api test: count returned no row" }
                    rows.getLong(1)
                }
            }
        }
    }

    @Test
    fun `sign out all revokes every session of the account and the caller's`() {
        val accountId = account()
        clock.set(TokenFixtures.START)
        val s1 = SyncRouteTestSupport.issuedSession(tokenStore, accountId)
        val s2 = SyncRouteTestSupport.issuedSession(tokenStore, accountId)
        val s3 = SyncRouteTestSupport.issuedSession(tokenStore, accountId)
        val (status, body) = signOutAll(bearer(s1.secret.reveal()))
        assertEquals("status", HttpStatusCode.NoContent, status)
        assertEquals("body", "", body)
        assertFalse("s1 no longer resolves", resolves(s1.secret.reveal()))
        assertFalse("s2 no longer resolves", resolves(s2.secret.reveal()))
        assertFalse("s3 no longer resolves", resolves(s3.secret.reveal()))
    }

    @Test
    fun `sign out all leaves the agent token of the account live`() {
        val accountId = account()
        clock.set(TokenFixtures.START)
        val agent = SyncRouteTestSupport.issuedAgent(tokenStore, accountId)
        val s1 = SyncRouteTestSupport.issuedSession(tokenStore, accountId)
        val s2 = SyncRouteTestSupport.issuedSession(tokenStore, accountId)
        val (status, body) = signOutAll(bearer(s1.secret.reveal()))
        assertEquals("status", HttpStatusCode.NoContent, status)
        assertEquals("body", "", body)
        assertFalse("s1 no longer resolves", resolves(s1.secret.reveal()))
        assertFalse("s2 no longer resolves", resolves(s2.secret.reveal()))
        assertTrue("the agent token still resolves", resolves(agent.secret.reveal()))
        assertNull("the agent row keeps no revocation time", TokenFixtures.revokedAtMs(temp, agent.tokenId))
    }

    @Test
    fun `sign out all deletes the account's hand-off codes`() {
        val accountId = account()
        clock.set(TokenFixtures.START)
        val session = SyncRouteTestSupport.issuedSession(tokenStore, accountId)
        insertHandoff(accountId)
        assertEquals("the row was planted", 1L, handoffCount(accountId))
        val (status, body) = signOutAll(bearer(session.secret.reveal()))
        assertEquals("status", HttpStatusCode.NoContent, status)
        assertEquals("body", "", body)
        assertEquals("handoff count", 0L, handoffCount(accountId))
    }

    @Test
    fun `sign out all leaves another account's sessions alone`() {
        val alice = account()
        val bob = bobAccount()
        clock.set(TokenFixtures.START)
        val bobSession = SyncRouteTestSupport.issuedSession(tokenStore, bob)
        val (status, body) = signOutAll(bearer(SyncRouteTestSupport.sessionToken(tokenStore, alice)))
        assertEquals("status", HttpStatusCode.NoContent, status)
        assertEquals("body", "", body)
        assertTrue("bob's session still resolves", resolves(bobSession.secret.reveal()))
        assertNull("bob's row keeps no revocation time", TokenFixtures.revokedAtMs(temp, bobSession.tokenId))
    }

    @Test
    fun `sign out all with a dead token gets 401`() {
        val accountId = account()
        clock.set(TokenFixtures.START)
        val session = SyncRouteTestSupport.issuedSession(tokenStore, accountId)
        assertTrue("the revoke must land", runBlocking { tokenStore.revokeSession(session.tokenId) })
        val (status, body) = signOutAll(bearer(session.secret.reveal()))
        assertEquals("status", HttpStatusCode.Unauthorized, status)
        assertEquals("body", SyncRouteTestSupport.UNAUTHORIZED_BODY, body)
    }

    @Test
    fun `sign out all with no header gets 401`() {
        val accountId = account()
        clock.set(TokenFixtures.START)
        val session = SyncRouteTestSupport.issuedSession(tokenStore, accountId)
        val (status, body) = signOutAll(null)
        assertEquals("status", HttpStatusCode.Unauthorized, status)
        assertEquals("body", SyncRouteTestSupport.UNAUTHORIZED_BODY, body)
    }

    @Test
    fun `sign out all with an agent token gets 403`() {
        val accountId = account()
        clock.set(TokenFixtures.START)
        val session = SyncRouteTestSupport.issuedSession(tokenStore, accountId)
        val agent = SyncRouteTestSupport.issuedAgent(tokenStore, accountId)
        val (status, body) = signOutAll(bearer(agent.secret.reveal()))
        assertEquals("status", HttpStatusCode.Forbidden, status)
        assertEquals("body", SyncRouteTestSupport.FORBIDDEN_BODY, body)
        assertTrue("no session was revoked", resolves(session.secret.reveal()))
    }

    @Test
    fun `sign out all store failure answers the fixed 503`() {
        val accountId = account()
        clock.set(TokenFixtures.START)
        val session = SyncRouteTestSupport.issuedSession(tokenStore, accountId)
        temp.db.close()
        val (status, body) = signOutAll(bearer(session.secret.reveal()))
        assertEquals("status", HttpStatusCode.ServiceUnavailable, status)
        assertEquals("body", SyncRouteTestSupport.UNAVAILABLE_BODY, body)
    }
}
