package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.accounts.RateLimitCounterStore
import dev.breaker.server.syncapi.accounts.RateLimitResult
import dev.breaker.server.syncapi.db.TempDatabaseTest
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 429 answer's Retry-After bounds, and the default source address of the counter store. */
internal class RateLimitRespondTest : TempDatabaseTest() {

    private fun waitFor(seconds: Int): String? {
        val seen = ArrayList<String?>()
        runBlocking {
            testApplication {
                application {
                    routing { get("/limited") { respondRateLimited(call, RateLimitResult.Refused(seconds)) } }
                }
                seen.add(client.get("/limited").headers["Retry-After"])
            }
        }
        return seen.single()
    }

    @Test
    fun `Retry-After never goes above 3600 and never below 1`() {
        assertEquals("sync-api: far above", "3600", waitFor(9999))
        assertEquals("sync-api: one above", "3600", waitFor(3601))
        assertEquals("sync-api: the bound", "3600", waitFor(3600))
        assertEquals("sync-api: below the bound", "3599", waitFor(3599))
        assertEquals("sync-api: a plain value", "30", waitFor(30))
        assertEquals("sync-api: the floor", "1", waitFor(1))
        assertEquals("sync-api: zero", "1", waitFor(0))
        assertEquals("sync-api: negative", "1", waitFor(-5))
    }

    @Test
    fun `a counter store built without a peer seam reads the connection's remote address`() {
        val temp = openTemp()
        val counters = RateLimitCounterStore(temp.db, Clock.fixed(Instant.ofEpochMilli(0), ZoneOffset.UTC))
        val seen = ArrayList<String>()
        runBlocking {
            testApplication {
                application {
                    routing {
                        get("/peer") { call.respondText(counters.peerAddress(call) + "|" + call.request.local.remoteAddress) }
                    }
                }
                seen.add(client.get("/peer").bodyAsText())
            }
        }
        val parts = seen.single().split("|")
        assertEquals("sync-api: the default seam is the socket peer", parts[1], parts[0])
        assertTrue("sync-api: the address is not empty", parts[0].isNotEmpty())
    }
}
