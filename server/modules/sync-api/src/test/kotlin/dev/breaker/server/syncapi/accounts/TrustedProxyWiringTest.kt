package dev.breaker.server.syncapi.accounts

import dev.breaker.server.syncapi.db.TempDatabaseTest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

// How the trusted-proxy list reaches the rate-limit key. The behaviour of the key rule is in XffKeyTest; main() and the
// module need a socket, so their wiring is pinned by source text, the same way SyncApiMainWiringTest pins main.
internal class TrustedProxyWiringTest : TempDatabaseTest() {

    // Gradle runs tests with the module folder as the working directory.
    private val sources = File("src/main/kotlin/dev/breaker/server/syncapi")

    private fun count(file: String, text: String): Int = Regex(Regex.escape(text)).findAll(File(sources, file).readText()).count()

    @Test
    fun `the default key seam hands the peer, every header line and the list to the key rule`() {
        assertEquals(
            "the seam calls xffKey with the configured list",
            1,
            count("accounts/RateLimitCounterStore.kt", "xffKey(call.request.local.remoteAddress, forwardedFor(call), trustedProxies)"),
        )
        assertEquals("every header line is read", 1, count("accounts/RateLimitCounterStore.kt", "getAll(HttpHeaders.XForwardedFor)"))
        assertEquals("no other forwarding header is read", 0, count("accounts/RateLimitCounterStore.kt", "HttpHeaders.Forwarded"))
        assertEquals("no other forwarding header is read", 0, count("accounts/RateLimitCounterStore.kt", "XRealIp"))
    }

    @Test
    fun `the application module builds its counter store with the configured list`() {
        assertEquals(
            "the module passes the list on",
            1,
            count("SyncApiApplication.kt", "RateLimitCounterStore(accountStore.db, accountStore.clock, trustedProxies = trustedProxies)"),
        )
    }

    @Test
    fun `a forged X-Forwarded-For does not move a peer that is not listed, even with a list set`() {
        val temp = openTemp()
        val counters = RateLimitCounterStore(
            temp.db,
            Clock.fixed(Instant.ofEpochMilli(0), ZoneOffset.UTC),
            trustedProxies = parseTrustedProxies("203.0.113.77"),
        )
        val seen = ArrayList<String>()
        runBlocking {
            testApplication {
                application {
                    routing {
                        get("/peer") { call.respondText(counters.peerAddress(call) + "|" + call.request.local.remoteAddress) }
                    }
                }
                seen.add(client.get("/peer") { header("X-Forwarded-For", "1.2.3.4") }.bodyAsText())
            }
        }
        val parts = seen.single().split("|")
        assertEquals("sync-api: the key stays the socket peer", parts[1], parts[0])
    }

    // Register and salt must share the ONE counter store the module builds with the trusted list: a store built per
    // route would not carry the list, so a forwarded client would be keyed by the proxy on that route.
    @Test
    fun `the register route and the salt route are built with the shared counter store`() {
        assertEquals("one counter store is built", 1, count("SyncApiApplication.kt", "RateLimitCounterStore("))
        assertEquals("the register route gets it", 1, count("SyncApiApplication.kt", "authRegisterRoute(accountStore, counterStore)"))
        assertEquals("the salt route gets it", 1, count("SyncApiApplication.kt", "authSaltRoute(accountStore, counterStore)"))
    }
}
