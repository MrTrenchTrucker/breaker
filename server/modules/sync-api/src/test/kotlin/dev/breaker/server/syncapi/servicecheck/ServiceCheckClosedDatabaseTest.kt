package dev.breaker.server.syncapi.servicecheck

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.MutableClock
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenFixtures.issuedOf
import dev.breaker.server.syncapi.tokens.TokenStore
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

// Over a store whose database is CLOSED (close it first), a request gets
// 503 {"error":"unavailable"}, a wrong key on the same setup still gets 403 (key check stays
// first), a normal live token on a healthy store still gets 200 (control), and the exception
// text ("the database is closed") appears in no response body.
internal class ServiceCheckClosedDatabaseTest : TempDatabaseTest() {

    private class Reply(val status: HttpStatusCode, val body: String)

    private lateinit var temp: TempDatabase
    private lateinit var clock: MutableClock
    private lateinit var store: TokenStore
    private var userId: Long = 0L
    private var tokenText: String = ""

    @Before
    fun openStoreAndIssue() {
        temp = openTemp()
        clock = MutableClock(TokenFixtures.START)
        store = TokenFixtures.tokenStore(temp, clock)
        TokenFixtures.register(temp, "admin", 1)
        val member = TokenFixtures.register(temp, "member", 2)
        userId = member.accountId
        tokenText = issuedOf(runBlocking { store.issueSession(userId) }).secret.reveal()
    }

    private fun call(path: String, key: String?, token: String?, dbStore: TokenStore): Reply {
        var reply: Reply? = null
        runBlocking {
            testApplication {
                application { routing { serviceCheckRoutes(dbStore, "test-key") } }
                val response = client.post(path) {
                    if (key != null) header("X-Service-Key", key)
                    if (token != null) header("X-Presented-Token", token)
                }
                reply = Reply(response.status, response.bodyAsText())
            }
        }
        return checkNotNull(reply)
    }

    @Test
    fun `closed database answers 503 with the fixed body and no exception text`() {
        temp.db.close()
        val reply = call("/internal/v1/token/use", "test-key", tokenText, store)
        assertEquals("sync-api: closed db status", HttpStatusCode.ServiceUnavailable, reply.status)
        assertEquals("sync-api: closed db body", """{"error":"unavailable"}""", reply.body)
        assertEquals("sync-api: no exception text in body",
            false, reply.body.contains("the database is closed"))
    }

    @Test
    fun `wrong key on a closed database is still 403`() {
        temp.db.close()
        val reply = call("/internal/v1/token/use", "wrong-key", tokenText, store)
        assertEquals("sync-api: wrong key on closed db status", HttpStatusCode.Forbidden, reply.status)
        assertEquals("sync-api: wrong key on closed db body", """{"error":"bad_service_key"}""", reply.body)
    }

    @Test
    fun `healthy store still answers 200 for a live token`() {
        val reply = call("/internal/v1/token/use", "test-key", tokenText, store)
        assertEquals("sync-api: healthy status", HttpStatusCode.OK, reply.status)
        assertEquals("sync-api: healthy body",
            """{"account_id":$userId,"kind":"session","role":"user"}""", reply.body)
    }
}
