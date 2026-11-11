package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.accounts.AccountStore
import dev.breaker.server.syncapi.accounts.RateLimitCounterStore
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.handoff.HandoffFixtures
import dev.breaker.server.syncapi.syncApiModule
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// The limits of POST /v1/auth/register and POST /v1/auth/salt, through the real routes. The source address is
// played by the test header X-Test-Peer (the seam of the counter store); the default seam is the socket peer.
internal class RateLimitRoutesTest : TempDatabaseTest() {

    private class StepClock(var nowMs: Long) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(nowMs)
    }

    private lateinit var temp: TempDatabase
    private lateinit var accounts: AccountStore
    private val clock = StepClock(0)

    private val refusalBody = """{"error":"rate_limited","message":"Too many requests."}"""

    @Before
    fun setUp() {
        temp = openTemp()
        accounts = AccountFixtures.newStore(temp)
    }

    private fun playedPeers(): RateLimitCounterStore =
        RateLimitCounterStore(temp.db, clock, peerOf = { call -> call.request.headers["X-Test-Peer"].orEmpty() })

    private fun socketPeer(): RateLimitCounterStore = RateLimitCounterStore(temp.db, clock)

    private fun withRoutes(counters: RateLimitCounterStore, block: suspend ApplicationTestBuilder.() -> Unit) {
        runBlocking {
            testApplication {
                application {
                    routing { authRegisterRoute(accounts, counters) }
                    authSaltRoute(accounts, counters)
                }
                block()
            }
        }
    }

    private suspend fun ApplicationTestBuilder.hit(path: String, peer: String?, forwarded: String? = null): HttpResponse =
        client.post(path) {
            contentType(ContentType.Application.Json)
            setBody(if (path.endsWith("register")) "{}" else """{"username":"nobody"}""")
            if (peer != null) header("X-Test-Peer", peer)
            if (forwarded != null) header("X-Forwarded-For", forwarded)
        }

    private fun counterOf(route: String, address: String): Long = runBlocking {
        temp.db.transaction<Long> { connection ->
            connection.prepareStatement("SELECT count FROM rate_limit_counters WHERE route = ? AND address = ?").use { statement ->
                statement.setString(1, route)
                statement.setString(2, address)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else -1L }
            }
        }
    }

    private suspend fun assertRefusedAsLimit(claim: String, reply: HttpResponse) {
        assertEquals("sync-api: $claim status", HttpStatusCode.TooManyRequests, reply.status)
        assertEquals("sync-api: $claim body", refusalBody, reply.bodyAsText())
        val wait = reply.headers["Retry-After"]?.toIntOrNull()
        assertTrue("sync-api: $claim Retry-After is ${reply.headers["Retry-After"]}", wait != null && wait in 1..3600)
        assertTrue("sync-api: $claim content type", reply.headers["Content-Type"].orEmpty().startsWith("application/json"))
    }

    @Test
    fun `the eleventh register from one address answers 429 with the fixed body and a bounded wait`() {
        withRoutes(playedPeers()) {
            repeat(10) { assertTrue("sync-api: register $it passes", hit("/v1/auth/register", "a").status != HttpStatusCode.TooManyRequests) }
            assertRefusedAsLimit("the eleventh register", hit("/v1/auth/register", "a"))
        }
    }

    @Test
    fun `the sixty first salt lookup from one address answers 429 with the fixed body and a bounded wait`() {
        withRoutes(playedPeers()) {
            repeat(60) { assertTrue("sync-api: salt $it passes", hit("/v1/auth/salt", "a").status != HttpStatusCode.TooManyRequests) }
            assertRefusedAsLimit("the sixty first salt", hit("/v1/auth/salt", "a"))
        }
    }

    @Test
    fun `the window rolls and the next request passes`() {
        withRoutes(playedPeers()) {
            repeat(10) { hit("/v1/auth/register", "a") }
            assertEquals("sync-api: refused inside the window", HttpStatusCode.TooManyRequests, hit("/v1/auth/register", "a").status)
            clock.nowMs = 59_999
            assertEquals("sync-api: still refused one millisecond before the end", HttpStatusCode.TooManyRequests, hit("/v1/auth/register", "a").status)
            clock.nowMs = 60_000
            assertTrue("sync-api: a new window passes", hit("/v1/auth/register", "a").status != HttpStatusCode.TooManyRequests)
        }
    }

    @Test
    fun `one address flooding salt does not stop another address on salt or on register`() {
        withRoutes(playedPeers()) {
            repeat(60) { hit("/v1/auth/salt", "a") }
            assertEquals("sync-api: a is refused on salt", HttpStatusCode.TooManyRequests, hit("/v1/auth/salt", "a").status)
            assertEquals("sync-api: b still gets its salt answer", HttpStatusCode.NotFound, hit("/v1/auth/salt", "b").status)
            assertTrue("sync-api: b can still register", hit("/v1/auth/register", "b").status != HttpStatusCode.TooManyRequests)
            assertTrue("sync-api: a can still register", hit("/v1/auth/register", "a").status != HttpStatusCode.TooManyRequests)
        }
    }

    @Test
    fun `one address flooding register does not stop another address or its own salt`() {
        withRoutes(playedPeers()) {
            repeat(10) { hit("/v1/auth/register", "a") }
            assertEquals("sync-api: a is refused on register", HttpStatusCode.TooManyRequests, hit("/v1/auth/register", "a").status)
            assertTrue("sync-api: b can still register", hit("/v1/auth/register", "b").status != HttpStatusCode.TooManyRequests)
            assertEquals("sync-api: a still gets its salt answer", HttpStatusCode.NotFound, hit("/v1/auth/salt", "a").status)
        }
    }

    @Test
    fun `the global backstop refuses many addresses together and the refusal counts nothing`() {
        withRoutes(playedPeers()) {
            for (index in 1..120) {
                assertTrue("sync-api: peer $index passes", hit("/v1/auth/register", "p$index").status != HttpStatusCode.TooManyRequests)
            }
            assertRefusedAsLimit("the 121st address", hit("/v1/auth/register", "p121"))
        }
        assertEquals("sync-api: the global row holds 120", 120L, counterOf("register", RateLimitCounterStore.GLOBAL_ADDRESS))
        assertEquals("sync-api: the refused address got no row", -1L, counterOf("register", "p121"))
        assertEquals("sync-api: an earlier address kept its count", 1L, counterOf("register", "p1"))
    }

    @Test
    fun `a forged X-Forwarded-For does not give the same peer a fresh key`() {
        withRoutes(socketPeer()) {
            repeat(10) { hit("/v1/auth/register", null) }
            assertRefusedAsLimit("the eleventh register with a forged header", hit("/v1/auth/register", null, forwarded = "203.0.113.9"))
        }
    }

    @Test
    fun `a forged X-Forwarded-For on every request of one peer does not stop another peer`() {
        withRoutes(playedPeers()) {
            repeat(10) { hit("/v1/auth/register", "a", forwarded = "203.0.113.9") }
            assertEquals("sync-api: a is refused", HttpStatusCode.TooManyRequests, hit("/v1/auth/register", "a", forwarded = "203.0.113.9").status)
            assertTrue("sync-api: b with the same header is not refused", hit("/v1/auth/register", "b", forwarded = "203.0.113.9").status != HttpStatusCode.TooManyRequests)
        }
    }

    @Test
    fun `the real application module applies the register limit`() {
        val tokenStore = TokenFixtures.tokenStore(temp, AccountFixtures.fixedClock)
        runBlocking {
            testApplication {
                application {
                    syncApiModule(
                        accounts,
                        tokenStore,
                        SyncRouteTestSupport.newStore(temp),
                        HandoffFixtures.handoffStore(temp, tokenStore, AccountFixtures.fixedClock),
                    )
                }
                repeat(10) { hit("/v1/auth/register", null) }
                assertRefusedAsLimit("the eleventh register through the module", hit("/v1/auth/register", null))
            }
        }
    }
}
