package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.MutableClock
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport
import io.ktor.client.request.delete
import io.ktor.client.request.header
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

// The one 404 body the route answers for every refused id. Top-level, because Kotlin allows a
// const val only at top level, in an object or in a companion object, never in a class body.
private const val NOT_FOUND_BODY = "{\"error\":\"not_found\",\"message\":\"No such device\"}"

internal class AuthSignOutDeviceRouteTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var tokenStore: TokenStore
    private val clock = MutableClock(TokenFixtures.START)
    private var hashSeed = 0

    @Before
    fun setUp() {
        temp = openTemp()
        tokenStore = TokenFixtures.tokenStore(temp, clock)
    }

    private fun account(): Long = TokenFixtures.register(temp, "alice", 1).accountId

    private fun bobAccount(): Long = TokenFixtures.register(temp, "bob", 2).accountId

    private fun bearer(text: String): String = "Bearer $text"

    // Every raw INSERT gets its own unique token_hash: token_hash is NOT NULL UNIQUE.
    private fun nextHash() = ByteArray(32) { (hashSeed++).toByte() }

    private fun insertRow(accountId: Long, createdMs: Long, expiresMs: Long?): Long = runBlocking {
        inTransaction(temp) { connection ->
            connection.prepareStatement(
                "INSERT INTO tokens (account_id, kind, scope, label, token_hash, " +
                    "created_at_ms, expires_at_ms, device_name) " +
                    "VALUES (?, 'session', NULL, NULL, ?, ?, ?, NULL)",
            ).use { statement ->
                statement.setLong(1, accountId)
                statement.setBytes(2, nextHash())
                statement.setLong(3, createdMs)
                if (expiresMs == null) statement.setNull(4, java.sql.Types.BIGINT)
                else statement.setLong(4, expiresMs)
                statement.executeUpdate()
                connection.createStatement().use { query ->
                    query.executeQuery("SELECT last_insert_rowid()").use { rows ->
                        check(rows.next()) { "sync-api test: row id missing" }
                        rows.getLong(1)
                    }
                }
            }
        }
    }

    private fun deviceDelete(id: Long, authorization: String?): Pair<HttpStatusCode, String> = runBlocking {
        var status = HttpStatusCode.OK
        var text = ""
        testApplication {
            application { authDevicesRoute(tokenStore) }
            val response = client.delete("/v1/auth/devices/$id") {
                if (authorization != null) header(HttpHeaders.Authorization, authorization)
            }
            status = response.status
            text = response.bodyAsText()
        }
        Pair(status, text)
    }

    // Sends the raw path segment verbatim: for ids the client would never build (a space, ...).
    private fun deviceDeleteRaw(id: String, authorization: String): Pair<HttpStatusCode, String> = runBlocking {
        var status = HttpStatusCode.OK
        var text = ""
        testApplication {
            application { authDevicesRoute(tokenStore) }
            val response = client.delete("/v1/auth/devices/$id") {
                header(HttpHeaders.Authorization, authorization)
            }
            status = response.status
            text = response.bodyAsText()
        }
        Pair(status, text)
    }

    private fun resolves(secret: String): Boolean = runBlocking {
        tokenStore.resolve(secret) != null
    }

    @Test
    fun `a session signs out its own device`() {
        val accountId = account()
        clock.set(TokenFixtures.START)
        val session = SyncRouteTestSupport.issuedSession(tokenStore, accountId)
        val secret = session.secret.reveal()
        val (status, body) = deviceDelete(session.tokenId, bearer(secret))
        assertEquals("status", HttpStatusCode.NoContent, status)
        assertEquals("body", "", body)
        assertFalse("the token no longer resolves", resolves(secret))
    }

    @Test
    fun `a bad id answers the same 404`() {
        val accountId = account()
        clock.set(TokenFixtures.START)
        val session = SyncRouteTestSupport.issuedSession(tokenStore, accountId)
        val secret = session.secret.reveal()
        // " 1" is omitted: the Ktor test client refuses a request path containing a space
        // (the plan's escape hatch for that case); the other eight bad shapes are all tested.
        val badIds = listOf("abc", "-1", "+1", "1.5", "12abc", "0x10", "1e5", "99999999999999999999")
        for (bad in badIds) {
            val (status, body) = deviceDeleteRaw(bad, bearer(secret))
            assertEquals("status for $bad", HttpStatusCode.NotFound, status)
            assertEquals("body for $bad", NOT_FOUND_BODY, body)
        }
        assertTrue("every live session still resolves", resolves(secret))
    }

    @Test
    fun `an unknown id answers the same 404`() {
        val accountId = account()
        clock.set(TokenFixtures.START)
        val session = SyncRouteTestSupport.issuedSession(tokenStore, accountId)
        val secret = session.secret.reveal()
        val (status, body) = deviceDelete(999999999, bearer(secret))
        assertEquals("status", HttpStatusCode.NotFound, status)
        assertEquals("body", NOT_FOUND_BODY, body)
        assertTrue("the own token still live", resolves(secret))
    }

    @Test
    fun `another account's id is not revoked`() {
        val alice = account()
        val bob = bobAccount()
        clock.set(TokenFixtures.START)
        val bobSession = SyncRouteTestSupport.issuedSession(tokenStore, bob)
        val aliceSecret = SyncRouteTestSupport.sessionToken(tokenStore, alice)
        val (status, body) = deviceDelete(bobSession.tokenId, bearer(aliceSecret))
        assertEquals("status", HttpStatusCode.NotFound, status)
        assertEquals("body", NOT_FOUND_BODY, body)
        assertTrue("bob's secret still resolves", resolves(bobSession.secret.reveal()))
        assertNull("bob's row keeps no revocation time", TokenFixtures.revokedAtMs(temp, bobSession.tokenId))
    }

    @Test
    fun `an agent token id is not revoked`() {
        val accountId = account()
        clock.set(TokenFixtures.START)
        val agent = SyncRouteTestSupport.issuedAgent(tokenStore, accountId)
        val session = SyncRouteTestSupport.issuedSession(tokenStore, accountId)
        val (status, body) = deviceDelete(agent.tokenId, bearer(session.secret.reveal()))
        assertEquals("status", HttpStatusCode.NotFound, status)
        assertEquals("body", NOT_FOUND_BODY, body)
        assertTrue("the agent secret still resolves", resolves(agent.secret.reveal()))
        assertNull("the agent row keeps no revocation time", TokenFixtures.revokedAtMs(temp, agent.tokenId))
    }

    @Test
    fun `an already revoked id answers 404`() {
        val accountId = account()
        clock.set(TokenFixtures.START)
        // The caller must be a SECOND live session: the target's own token is already dead, so a call
        // made with it would answer 401 before the id is ever looked at.
        val caller = SyncRouteTestSupport.issuedSession(tokenStore, accountId)
        val target = SyncRouteTestSupport.issuedSession(tokenStore, accountId)
        assertTrue("the revoke must land", runBlocking { tokenStore.revokeSession(target.tokenId) })
        val (status, body) = deviceDelete(target.tokenId, bearer(caller.secret.reveal()))
        assertEquals("status", HttpStatusCode.NotFound, status)
        assertEquals("body", NOT_FOUND_BODY, body)
        assertTrue("the caller's session stays live", resolves(caller.secret.reveal()))
    }

    @Test
    fun `an expired id answers 404`() {
        val accountId = account()
        clock.set(TokenFixtures.START)
        // The caller must be a SECOND live session: the target's own token is expired, so a call made
        // with it would answer 401 before the id is ever looked at.
        val caller = SyncRouteTestSupport.issuedSession(tokenStore, accountId)
        val target = SyncRouteTestSupport.issuedSession(tokenStore, accountId)
        TokenFixtures.expireSession(temp, target.tokenId)
        val (status, body) = deviceDelete(target.tokenId, bearer(caller.secret.reveal()))
        assertEquals("status", HttpStatusCode.NotFound, status)
        assertEquals("body", NOT_FOUND_BODY, body)
        assertNull("revoked_at_ms stays null", TokenFixtures.revokedAtMs(temp, target.tokenId))
        assertTrue("the caller's session stays live", resolves(caller.secret.reveal()))
    }

    @Test
    fun `an id past the 180 day cap answers 404`() {
        val accountId = account()
        clock.set(TokenFixtures.START)
        val session = SyncRouteTestSupport.issuedSession(tokenStore, accountId)
        val created = TokenFixtures.START.toEpochMilli()
        val tooLate = created + Duration.ofDays(180).toMillis() + 1
        val id = insertRow(accountId, created, tooLate)
        val (status, body) = deviceDelete(id, bearer(session.secret.reveal()))
        assertEquals("status", HttpStatusCode.NotFound, status)
        assertEquals("body", NOT_FOUND_BODY, body)
        assertNull("revoked_at_ms stays null", TokenFixtures.revokedAtMs(temp, id))
        assertTrue("the caller's session stays live", resolves(session.secret.reveal()))
    }

    @Test
    fun `a second DELETE of the same id answers 404`() {
        val accountId = account()
        clock.set(TokenFixtures.START)
        // The caller signs out ANOTHER device and keeps its own session live, so the second call is
        // authenticated and reaches the id: a call with the killed token would answer 401, not 404.
        val caller = SyncRouteTestSupport.issuedSession(tokenStore, accountId)
        val target = SyncRouteTestSupport.issuedSession(tokenStore, accountId)
        val callerSecret = caller.secret.reveal()
        val (firstStatus, firstBody) = deviceDelete(target.tokenId, bearer(callerSecret))
        assertEquals("first status", HttpStatusCode.NoContent, firstStatus)
        assertEquals("first body", "", firstBody)
        val (secondStatus, secondBody) = deviceDelete(target.tokenId, bearer(callerSecret))
        assertEquals("second status", HttpStatusCode.NotFound, secondStatus)
        assertEquals("second body", NOT_FOUND_BODY, secondBody)
        assertTrue("the caller's session stays live", resolves(callerSecret))
    }

    @Test
    fun `a dead token gets 401`() {
        val accountId = account()
        clock.set(TokenFixtures.START)
        val session = SyncRouteTestSupport.issuedSession(tokenStore, accountId)
        assertTrue("the revoke must land", runBlocking { tokenStore.revokeSession(session.tokenId) })
        val (status, body) = deviceDelete(session.tokenId, bearer(session.secret.reveal()))
        assertEquals("status", HttpStatusCode.Unauthorized, status)
        assertEquals("body", SyncRouteTestSupport.UNAUTHORIZED_BODY, body)
    }

    @Test
    fun `no header gets 401`() {
        val accountId = account()
        clock.set(TokenFixtures.START)
        val session = SyncRouteTestSupport.issuedSession(tokenStore, accountId)
        val (status, body) = deviceDelete(session.tokenId, null)
        assertEquals("status", HttpStatusCode.Unauthorized, status)
        assertEquals("body", SyncRouteTestSupport.UNAUTHORIZED_BODY, body)
    }

    @Test
    fun `an agent token gets 403 and revokes nothing`() {
        val accountId = account()
        clock.set(TokenFixtures.START)
        val session = SyncRouteTestSupport.issuedSession(tokenStore, accountId)
        val agent = SyncRouteTestSupport.issuedAgent(tokenStore, accountId)
        val (status, body) = deviceDelete(session.tokenId, bearer(agent.secret.reveal()))
        assertEquals("status", HttpStatusCode.Forbidden, status)
        assertEquals("body", SyncRouteTestSupport.FORBIDDEN_BODY, body)
        assertTrue("the target session still resolves", resolves(session.secret.reveal()))
    }

    @Test
    fun `a store failure answers the fixed 503`() {
        val accountId = account()
        clock.set(TokenFixtures.START)
        val session = SyncRouteTestSupport.issuedSession(tokenStore, accountId)
        temp.db.close()
        val (status, body) = deviceDelete(session.tokenId, bearer(session.secret.reveal()))
        assertEquals("status", HttpStatusCode.ServiceUnavailable, status)
        assertEquals("body", SyncRouteTestSupport.UNAVAILABLE_BODY, body)
    }
}
