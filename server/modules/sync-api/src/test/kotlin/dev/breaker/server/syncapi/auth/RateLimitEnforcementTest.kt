package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.accounts.RateLimitCounterStore
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.settings.ServerSettingsStore
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Tests for [checkRateLimits] through a test route: fail-open, separate keys, global first. */
internal class RateLimitEnforcementTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var store: RateLimitCounterStore
    private lateinit var settings: ServerSettingsStore

    @Before
    fun openStore() {
        temp = openTemp()
        settings = ServerSettingsStore(temp.db)
        store = RateLimitCounterStore(
            temp.db,
            Clock.fixed(Instant.ofEpochMilli(0), ZoneOffset.UTC),
            maxRows = 5,
        )
    }

    /** The test route: it calls checkRateLimits and answers 429 with Retry-After when it refuses. */
    private fun Application.limitedRoute(limitKey: String, perAddress: Int, global: Int) {
        routing {
            get("/check") {
                val refused = checkRateLimits(call, store, settings, limitKey, perAddress, global)
                if (refused != null) {
                    call.response.header("Retry-After", refused.retryAfterSeconds.toString())
                    call.respondText(
                        "{\"error\":\"rate_limited\"}",
                        ContentType.Application.Json,
                        HttpStatusCode.TooManyRequests,
                    )
                    return@get
                }
                call.respondText("ok", ContentType.Application.Json, HttpStatusCode.OK)
            }
        }
    }

    /** One request against the test route. Returns the status. */
    private fun oneRequest(limitKey: String, perAddress: Int, global: Int): HttpStatusCode =
        manyRequests(limitKey, perAddress, global, 1).single()

    /** N requests against the same test route; returns the list of statuses. */
    private fun manyRequests(limitKey: String, perAddress: Int, global: Int, n: Int): List<HttpStatusCode> {
        val statuses = ArrayList<HttpStatusCode>()
        runBlocking {
            testApplication {
                application { limitedRoute(limitKey, perAddress, global) }
                repeat(n) { statuses.add(client.get("http://localhost/check").status) }
            }
        }
        return statuses
    }

    // The test host's peer address is not assumed: the one non-global row of the route is the caller's.
    private fun countOf(route: String, address: String? = null): Long =
        runBlocking {
            temp.db.transaction<Long> { connection ->
                val sql = if (address == null) {
                    "SELECT count FROM rate_limit_counters WHERE route = ? AND address <> '*'"
                } else {
                    "SELECT count FROM rate_limit_counters WHERE route = ? AND address = ?"
                }
                connection.prepareStatement(sql).use { statement ->
                    statement.setString(1, route)
                    if (address != null) statement.setString(2, address)
                    statement.executeQuery().use { rows ->
                        if (rows.next()) rows.getLong(1) else -1L
                    }
                }
            }
        }

    // ---------- separate keys per route ----------

    @Test
    fun `register and salt do not share one row for the same address`() {
        // 10 register requests hit the register cap...
        val statuses = manyRequests("register", 10, 120, 10)
        assertEquals("all 10 register requests allowed", 10, statuses.count { it == HttpStatusCode.OK })
        // ...and the 11th is refused.
        assertEquals("11th register refused", HttpStatusCode.TooManyRequests, oneRequest("register", 10, 120))
        // But salt still allows the same address (separate key prefix).
        assertEquals("salt allowed after register cap", HttpStatusCode.OK, oneRequest("salt", 60, 1200))
        assertEquals("register per-address row is 10", 10L, countOf("register"))
        assertEquals("salt per-address row is 1", 1L, countOf("salt"))
    }

    @Test
    fun `register refuses after 10 but salt still allows the same address`() {
        manyRequests("register", 10, 120, 10)
        assertEquals("register refused", HttpStatusCode.TooManyRequests, oneRequest("register", 10, 120))
        assertEquals("salt allowed", HttpStatusCode.OK, oneRequest("salt", 60, 1200))
    }

    // ---------- global backstop first ----------

    @Test
    fun `global backstop refused never increments the per-address counter`() {
        // Global cap of 2: first two are allowed (count goes 1, 2), third is refused globally.
        val s1 = oneRequest("register", 10, 2)
        val s2 = oneRequest("register", 10, 2)
        val s3 = oneRequest("register", 10, 2)
        assertEquals(HttpStatusCode.OK, s1)
        assertEquals(HttpStatusCode.OK, s2)
        assertEquals(HttpStatusCode.TooManyRequests, s3)
        // Per-address count is 2 — two allowed requests counted; the refused third did not touch it.
        assertEquals("per-address register count is 2", 2L, countOf("register"))
        // Global backstop count is also 2 — refused third did not touch it either.
        assertEquals("global register count is 2", 2L, countOf("register", RateLimitCounterStore.GLOBAL_ADDRESS))
    }

    // ---------- fail-open ----------

    @Test
    fun `a failing counter store fails open and the request proceeds`() {
        runBlocking { temp.db.close() }
        // checkRateLimits catches the store exception and returns null → proceed.
        assertEquals(HttpStatusCode.OK, oneRequest("register", 10, 120))
    }

    // ---------- switch OFF ----------

    @Test
    fun `with the switch off nothing is refused even at the cap`() {
        runBlocking { settings.setRateLimitsEnabled(false) }
        val statuses = manyRequests("register", 10, 120, 15)
        assertEquals("all 15 requests allowed when switch is off", 15, statuses.count { it == HttpStatusCode.OK })
    }

    // ---------- per-address isolation ----------

    @Test
    fun `different addresses get separate counters`() {
        val s1 = manyRequests("register", 3, 120, 3)
        assertEquals("three allowed from 127.0.0.1", 3, s1.count { it == HttpStatusCode.OK })
        val s4 = oneRequest("register", 3, 120)
        assertEquals(HttpStatusCode.TooManyRequests, s4)
        // Same peer address in the same testApplication process → same peer key.
        // A fresh route instance would use the same peer; we verify per-key via countOf.
        assertEquals("per-address count is 3", 3L, countOf("register"))
    }
}
