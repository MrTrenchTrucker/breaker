package dev.breaker.server.syncapi.status

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.admin.SERVICE_UNAVAILABLE_BODY
import dev.breaker.server.syncapi.admin.UNAUTHORIZED_BODY
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.AgentScope
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** `GET /v1/status` through the route: who may read it, the exact body, and no 500 from a failing probe. */
internal class StatusRouteTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var tokens: TokenStore
    private val calls = AtomicInteger(0)

    // A clock that reads the real one and, once [live], throws a CancellationException from its first reading.
    private class CancellingClock(private val inner: Clock) : Clock() {
        var live = false
        var readings = 0
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant {
            if (live) {
                readings++
                throw CancellationException("sync-api test: cancelled")
            }
            return inner.instant()
        }
    }

    @Before
    fun setUp() {
        temp = openTemp()
        tokens = TokenFixtures.tokenStore(temp, AccountFixtures.fixedClock)
    }

    private fun whisper(probe: suspend (String) -> Boolean): StatusProbe =
        StatusProbe("http://whisper.test/health", { url -> calls.incrementAndGet(); probe(url) }, Clock.systemUTC())

    private fun withRoute(probe: StatusProbe?, block: suspend ApplicationTestBuilder.() -> Unit) {
        testApplication {
            application { routing { statusRoute(tokens, probe) } }
            block()
        }
    }

    private fun sessionFor(name: String, seed: Int): String {
        val account = TokenFixtures.register(temp, name, seed)
        return "Bearer " + TokenFixtures.issuedOf(runBlocking { tokens.issueSession(account.accountId) }).secret.reveal()
    }

    @Test
    fun `a request without a token is 401 with the plain body`() {
        withRoute(null) {
            val response = client.get("/v1/status")
            assertEquals("status", HttpStatusCode.Unauthorized, response.status)
            assertEquals("body", UNAUTHORIZED_BODY, response.bodyAsText())
        }
    }

    @Test
    fun `an unknown token is 401`() {
        withRoute(null) {
            val response = client.get("/v1/status") { header("Authorization", "Bearer not-a-real-token") }
            assertEquals("status", HttpStatusCode.Unauthorized, response.status)
            assertEquals("body", UNAUTHORIZED_BODY, response.bodyAsText())
        }
    }

    @Test
    fun `an admin session reads the status`() {
        val auth = sessionFor("alice", 1)
        withRoute(null) {
            val response = client.get("/v1/status") { header("Authorization", auth) }
            assertEquals("status", HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun `a plain user session reads the status`() {
        sessionFor("alice", 1)
        val auth = sessionFor("bob", 2)
        withRoute(null) {
            val response = client.get("/v1/status") { header("Authorization", auth) }
            assertEquals("status", HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun `an agent token reads the status`() {
        val account = TokenFixtures.register(temp, "alice", 1)
        val agent = TokenFixtures.issuedOf(runBlocking { tokens.issueAgent(account.accountId, AgentScope.TRANSCRIBE, "agent-label", null) })
        withRoute(null) {
            val response = client.get("/v1/status") { header("Authorization", "Bearer " + agent.secret.reveal()) }
            assertEquals("status", HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun `without a whisper probe only sync-api is listed`() {
        val auth = sessionFor("alice", 1)
        withRoute(null) {
            val response = client.get("/v1/status") { header("Authorization", auth) }
            assertEquals("body", """{"services":[{"name":"sync-api","up":true}]}""", response.bodyAsText())
        }
    }

    @Test
    fun `whisper-server up is listed after sync-api`() {
        val auth = sessionFor("alice", 1)
        withRoute(whisper { true }) {
            val response = client.get("/v1/status") { header("Authorization", auth) }
            assertEquals(
                "body",
                """{"services":[{"name":"sync-api","up":true},{"name":"whisper-server","up":true}]}""",
                response.bodyAsText(),
            )
        }
    }

    @Test
    fun `whisper-server down is listed as down and the answer is still 200`() {
        val auth = sessionFor("alice", 1)
        withRoute(whisper { false }) {
            val response = client.get("/v1/status") { header("Authorization", auth) }
            assertEquals("status", HttpStatusCode.OK, response.status)
            assertEquals(
                "body",
                """{"services":[{"name":"sync-api","up":true},{"name":"whisper-server","up":false}]}""",
                response.bodyAsText(),
            )
        }
    }

    @Test
    fun `a probe that throws is down and never a 500`() {
        val auth = sessionFor("alice", 1)
        withRoute(whisper { throw IllegalStateException("whisper exploded with a secret path") }) {
            val response = client.get("/v1/status") { header("Authorization", auth) }
            assertEquals("status", HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue("the whisper entry is down: $body", body.contains("""{"name":"whisper-server","up":false}"""))
            assertTrue("no exception text in the body: $body", !body.contains("secret"))
        }
    }

    @Test
    fun `the body is the content type JSON`() {
        val auth = sessionFor("alice", 1)
        withRoute(null) {
            val response = client.get("/v1/status") { header("Authorization", auth) }
            assertTrue("content type", response.headers["Content-Type"].orEmpty().startsWith("application/json"))
        }
    }

    @Test
    fun `two asks inside the window probe whisper-server once`() {
        val auth = sessionFor("alice", 1)
        withRoute(whisper { true }) {
            client.get("/v1/status") { header("Authorization", auth) }
            client.get("/v1/status") { header("Authorization", auth) }
        }
        assertEquals("sync-api: one probe for two requests", 1, calls.get())
    }

    @Test
    fun `a failing token check is the fixed 503`() {
        val auth = sessionFor("alice", 1)
        temp.closeKeepingFiles()
        withRoute(whisper { true }) {
            val response = client.get("/v1/status") { header("Authorization", auth) }
            assertEquals("status", HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("body", SERVICE_UNAVAILABLE_BODY, response.bodyAsText())
        }
        assertEquals("sync-api: no probe ran for a caller that was never checked", 0, calls.get())
    }

    @Test(timeout = 20_000)
    fun `a cancelled token check is rethrown and Ktor answers 500, not the route`() {
        val auth = sessionFor("alice", 1)
        val cancelling = CancellingClock(AccountFixtures.fixedClock)
        tokens = TokenFixtures.tokenStore(temp, cancelling)
        cancelling.live = true
        var status: HttpStatusCode? = null
        try {
            withRoute(whisper { true }) {
                try {
                    status = client.get("/v1/status") { header("Authorization", auth) }.status
                } catch (_: Exception) {
                    // The cancellation reached the caller: there is no answer.
                }
            }
        } catch (_: CancellationException) {
            // Same, seen one level up.
        }
        assertEquals("sync-api: rethrown, so Ktor answers 500 and the route never answers 503 or 200", HttpStatusCode.InternalServerError, status)
        assertTrue("sync-api: the token check read the clock", cancelling.readings >= 1)
        assertEquals("sync-api: no probe ran for a cancelled call", 0, calls.get())
    }
}
