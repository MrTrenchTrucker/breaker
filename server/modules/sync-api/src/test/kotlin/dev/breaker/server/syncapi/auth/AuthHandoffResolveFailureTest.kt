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

/** The token check of a hand-off start answers the documented 503 body when the store fails. */
internal class AuthHandoffResolveFailureTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var tokenStore: TokenStore
    private lateinit var handoffStore: HandoffStore
    private lateinit var token: String

    @Before
    fun setUp() {
        temp = openTemp()
        val clock = MutableClock(TokenFixtures.START)
        tokenStore = TokenFixtures.tokenStore(temp, clock)
        handoffStore = HandoffFixtures.handoffStore(temp, tokenStore, clock)
        val accountId = TokenFixtures.register(temp, "alice", 1).accountId
        token = SyncRouteTestSupport.sessionToken(tokenStore, accountId)
    }

    private fun startBody(): String =
        "{\"challenge\":\"" + HandoffFixtures.CHALLENGE_FOR_SECRET(HandoffFixtures.SECRET) + "\"," +
            "\"sealed_package\":\"" + HandoffFixtures.SEALED_PACKAGE_TEXT + "\"}"

    private fun postStart(): Pair<HttpStatusCode, String> = runBlocking {
        var status = HttpStatusCode.OK
        var text = ""
        testApplication {
            application { authHandoffRoutes(handoffStore, tokenStore) }
            val response = client.post("/v1/auth/handoff/start") {
                header(HttpHeaders.Authorization, "Bearer " + token)
                contentType(ContentType.Application.Json)
                setBody(startBody())
            }
            status = response.status
            text = response.bodyAsText()
        }
        Pair(status, text)
    }

    @Test
    fun `a hand-off start whose token check fails answers the documented 503 body`() {
        assertEquals(HttpStatusCode.Created, postStart().first)
        temp.db.close()
        val reply = postStart()
        assertEquals(HttpStatusCode.ServiceUnavailable, reply.first)
        assertEquals(UNAVAILABLE_BODY, reply.second)
    }
}
