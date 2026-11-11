package dev.breaker.server.syncapi.status

import com.sun.net.httpserver.HttpServer
import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.handoff.HandoffFixtures
import dev.breaker.server.syncapi.syncApiModule
import dev.breaker.server.syncapi.tokens.MutableClock
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

// Through syncApiModule itself: the status route is installed, and the health URL decides what it lists.
// The refused-connection case runs the real probe against a closed local port: no server, an instant refusal.
internal class StatusWiringTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase

    @Before
    fun setUp() {
        temp = openTemp()
    }

    private fun statusBody(whisperHealthUrl: String): Pair<HttpStatusCode, String> {
        val tokens = TokenFixtures.tokenStore(temp, MutableClock(TokenFixtures.START))
        val account = TokenFixtures.register(temp, "alice", 1)
        val auth = "Bearer " + TokenFixtures.issuedOf(runBlocking { tokens.issueSession(account.accountId) }).secret.reveal()
        val seen = ArrayList<Pair<HttpStatusCode, String>>()
        runBlocking<Unit> {
            testApplication {
                application {
                    syncApiModule(
                        AccountFixtures.newStore(temp),
                        tokens,
                        SyncRouteTestSupport.newStore(temp),
                        HandoffFixtures.handoffStore(temp, tokens, AccountFixtures.fixedClock),
                        whisperHealthUrl = whisperHealthUrl,
                    )
                }
                val response = client.get("/v1/status") { header("Authorization", auth) }
                seen.add(Pair(response.status, response.bodyAsText()))
            }
        }
        return seen.single()
    }

    @Test
    fun `the public module installs the status route and lists only sync-api when no url is set`() {
        val reply = statusBody("")
        assertEquals("sync-api: status", HttpStatusCode.OK, reply.first)
        assertEquals("sync-api: body", """{"services":[{"name":"sync-api","up":true}]}""", reply.second)
    }

    @Test
    fun `a configured url that refuses the connection lists whisper-server as down`() {
        val reply = statusBody("http://127.0.0.1:9/health")
        assertEquals("sync-api: status", HttpStatusCode.OK, reply.first)
        assertEquals(
            "sync-api: body",
            """{"services":[{"name":"sync-api","up":true},{"name":"whisper-server","up":false}]}""",
            reply.second,
        )
    }

    @Test
    fun `a url of spaces only is unset and lists only sync-api`() {
        val reply = statusBody("   ")
        assertEquals("sync-api: status", HttpStatusCode.OK, reply.first)
        assertEquals("sync-api: body", """{"services":[{"name":"sync-api","up":true}]}""", reply.second)
    }

    // The probe takes the account store clock, so a test clock decides when the cache window ends.
    @Test(timeout = 30_000)
    fun `two calls more than the window apart probe whisper-server again`() {
        val hits = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/health") { exchange ->
            hits.incrementAndGet()
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
        try {
            val clock = MutableClock(TokenFixtures.START)
            val tokens = TokenFixtures.tokenStore(temp, clock)
            val account = TokenFixtures.register(temp, "alice", 1)
            val auth = "Bearer " + TokenFixtures.issuedOf(runBlocking { tokens.issueSession(account.accountId) }).secret.reveal()
            val counts = ArrayList<Int>()
            runBlocking<Unit> {
                testApplication {
                    application {
                        syncApiModule(
                            AccountFixtures.newStore(temp, AccountFixtures.fastHasher(), clock),
                            tokens,
                            SyncRouteTestSupport.newStore(temp),
                            HandoffFixtures.handoffStore(temp, tokens, AccountFixtures.fixedClock),
                            whisperHealthUrl = "http://127.0.0.1:${server.address.port}/health",
                        )
                    }
                    client.get("/v1/status") { header("Authorization", auth) }
                    counts.add(hits.get())
                    client.get("/v1/status") { header("Authorization", auth) }
                    counts.add(hits.get())
                    clock.advance(Duration.ofSeconds(10))
                    client.get("/v1/status") { header("Authorization", auth) }
                    counts.add(hits.get())
                }
            }
            assertEquals("sync-api: one probe, one cached answer, one probe after the window", listOf(1, 1, 2), counts)
        } finally {
            server.stop(0)
        }
    }
}
