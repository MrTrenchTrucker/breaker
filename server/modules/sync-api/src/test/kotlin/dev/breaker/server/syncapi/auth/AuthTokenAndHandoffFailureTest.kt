package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.handoff.HandoffFixtures
import dev.breaker.server.syncapi.handoff.HandoffStore
import dev.breaker.server.syncapi.tokens.MutableClock
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.UNAVAILABLE_BODY
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** A failing token or hand-off store answers the fixed 503 on logout, hand-off start and exchange. */
internal class AuthTokenAndHandoffFailureTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var tokenStore: TokenStore
    private lateinit var openHandoffStore: HandoffStore
    private lateinit var token: String
    private val clock = MutableClock(TokenFixtures.START)

    private companion object {
        const val FAIL_REVOKE_SQL =
            "CREATE TRIGGER forced_revoke_failure BEFORE UPDATE OF revoked_at_ms ON tokens " +
                "BEGIN SELECT RAISE(ABORT, 'forced'); END"
        const val DROP_REVOKE_SQL = "DROP TRIGGER forced_revoke_failure"
        const val FAIL_HANDOFF_SQL =
            "CREATE TRIGGER forced_handoff_failure BEFORE INSERT ON signin_handoff " +
                "BEGIN SELECT RAISE(ABORT, 'forced'); END"
        const val DROP_HANDOFF_SQL = "DROP TRIGGER forced_handoff_failure"
    }

    @Before
    fun setUp() {
        temp = openTemp()
        tokenStore = TokenFixtures.tokenStore(temp, clock)
        openHandoffStore = HandoffFixtures.handoffStore(temp, tokenStore, clock)
        val accountId = TokenFixtures.register(temp, "alice", 1).accountId
        token = SyncRouteTestSupport.sessionToken(tokenStore, accountId)
    }

    private fun closedHandoffStore(): HandoffStore {
        val closed = openTemp()
        val store = HandoffFixtures.handoffStore(closed, tokenStore, clock)
        closed.db.close()
        return store
    }

    private fun runSql(sql: String) {
        inTransaction(temp) { connection -> connection.createStatement().use { it.execute(sql) } }
    }

    private fun post(handoffStore: HandoffStore, path: String, bearer: String?, requestBody: String): Pair<HttpStatusCode, String> =
        runBlocking {
            var status = HttpStatusCode.OK
            var text = ""
            testApplication {
                application {
                    authLogoutRoute(tokenStore)
                    authHandoffRoutes(handoffStore, tokenStore)
                }
                val response = client.post(path) {
                    if (bearer != null) header(HttpHeaders.Authorization, "Bearer " + bearer)
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }
                status = response.status
                text = response.bodyAsText()
            }
            Pair(status, text)
        }

    private fun logout(bearer: String): Pair<HttpStatusCode, String> = post(openHandoffStore, "/v1/auth/logout", bearer, "")

    private fun startBody(): String =
        "{\"challenge\":\"" + HandoffFixtures.CHALLENGE_FOR_SECRET(HandoffFixtures.SECRET) + "\"," +
            "\"sealed_package\":\"" + HandoffFixtures.SEALED_PACKAGE_TEXT + "\"}"

    private fun exchangeBody(): String =
        "{\"code\":\"" + "A".repeat(43) + "\",\"secret\":\"" + HandoffFixtures.SECRET + "\"}"

    private fun anotherSession(): String =
        SyncRouteTestSupport.sessionToken(tokenStore, TokenFixtures.register(temp, "bob", 2).accountId)

    @Test
    fun `a logout whose token check fails in the store answers the fixed 503`() {
        assertEquals(HttpStatusCode.NoContent, logout(anotherSession()).first)
        temp.db.close()
        val reply = logout(token)
        assertEquals(HttpStatusCode.ServiceUnavailable, reply.first)
        assertEquals(UNAVAILABLE_BODY, reply.second)
    }

    @Test
    fun `a logout whose revoke fails in the store answers the fixed 503 and keeps the session`() {
        runSql(FAIL_REVOKE_SQL)
        val reply = logout(token)
        assertEquals(HttpStatusCode.ServiceUnavailable, reply.first)
        assertEquals(UNAVAILABLE_BODY, reply.second)
        runSql(DROP_REVOKE_SQL)
        assertEquals(HttpStatusCode.NoContent, logout(token).first)
    }

    @Test
    fun `a hand-off start whose store is closed answers the fixed 503`() {
        assertEquals(HttpStatusCode.Created, post(openHandoffStore, "/v1/auth/handoff/start", token, startBody()).first)
        val reply = post(closedHandoffStore(), "/v1/auth/handoff/start", token, startBody())
        assertEquals(HttpStatusCode.ServiceUnavailable, reply.first)
        assertEquals(UNAVAILABLE_BODY, reply.second)
    }

    @Test
    fun `a hand-off start that fails inside the insert answers the fixed 503`() {
        runSql(FAIL_HANDOFF_SQL)
        val reply = post(openHandoffStore, "/v1/auth/handoff/start", token, startBody())
        assertEquals(HttpStatusCode.ServiceUnavailable, reply.first)
        assertEquals(UNAVAILABLE_BODY, reply.second)
        runSql(DROP_HANDOFF_SQL)
        assertEquals(HttpStatusCode.Created, post(openHandoffStore, "/v1/auth/handoff/start", token, startBody()).first)
    }

    @Test
    fun `a hand-off exchange whose store is closed answers the fixed 503`() {
        assertEquals(HttpStatusCode.Unauthorized, post(openHandoffStore, "/v1/auth/handoff/exchange", null, exchangeBody()).first)
        val reply = post(closedHandoffStore(), "/v1/auth/handoff/exchange", null, exchangeBody())
        assertEquals(HttpStatusCode.ServiceUnavailable, reply.first)
        assertEquals(UNAVAILABLE_BODY, reply.second)
    }
}
