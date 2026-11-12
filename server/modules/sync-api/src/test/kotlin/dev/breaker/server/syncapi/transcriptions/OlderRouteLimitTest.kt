package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.accounts.RateLimitCounterStore
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.handoff.HandoffFixtures
import dev.breaker.server.syncapi.settings.ServerSettingsStore
import dev.breaker.server.syncapi.syncApiModule
import dev.breaker.server.syncapi.tokens.MutableClock
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// The per-account limit of GET /v1/transcriptions/older (ADR-041): 30 reads in a fixed 60 s window for the
// key acct:<account id>, route older, no global counter, the same admin switch as the other limits. The
// counter clock is stepped by the test; the sessions run on a clock of their own.
internal class OlderRouteLimitTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var tokenStore: TokenStore
    private lateinit var store: TranscriptionStore
    private lateinit var settings: ServerSettingsStore
    private lateinit var counters: RateLimitCounterStore
    private val counterClock = MutableClock(TokenFixtures.START)
    private var aliceId = 0L
    private var bobId = 0L
    private var alice = ""
    private var bob = ""
    private val bound = "${TokenFixtures.START.toEpochMilli() + 1_000L}"

    private val refusalBody = """{"error":"rate_limited","message":"Too many requests."}"""

    @Before
    fun setUp() {
        temp = openTemp()
        val clock = MutableClock(TokenFixtures.START)
        tokenStore = TokenFixtures.tokenStore(temp, clock)
        store = SyncRouteTestSupport.newStore(temp, clock)
        settings = ServerSettingsStore(temp.db)
        counters = RateLimitCounterStore(temp.db, counterClock)
        aliceId = TokenFixtures.register(temp, "alice", 1).accountId
        bobId = TokenFixtures.register(temp, "bob", 2).accountId
        alice = SyncRouteTestSupport.sessionToken(tokenStore, aliceId)
        bob = SyncRouteTestSupport.sessionToken(tokenStore, bobId)
    }

    private fun withOlder(
        counterStore: RateLimitCounterStore? = counters,
        settingsStore: ServerSettingsStore? = settings,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) {
        runBlocking {
            testApplication {
                application { syncRoutes(tokenStore, store, counterStore, settingsStore) }
                block()
            }
        }
    }

    // One read with the bound in the URL and the token in the Authorization header, or no header when null.
    private suspend fun ApplicationTestBuilder.older(token: String?, value: String = bound): HttpResponse =
        client.get("/v1/transcriptions/older?older_than=$value") {
            if (token != null) header("Authorization", "Bearer $token")
        }

    // Makes [times] reads and asserts that none of them is limited.
    private suspend fun ApplicationTestBuilder.readUnlimited(token: String, times: Int) {
        repeat(times) {
            assertEquals("sync-api: read ${it + 1} is served", HttpStatusCode.OK, older(token).status)
        }
    }

    // A refusal is 429 with the fixed body, a Retry-After from 1 to 3600 (exactly [exactWait] when given),
    // and a JSON content type.
    private suspend fun assertRefusedAsLimit(claim: String, reply: HttpResponse, exactWait: String? = null) {
        assertEquals("sync-api: $claim status", HttpStatusCode.TooManyRequests, reply.status)
        assertEquals("sync-api: $claim body", refusalBody, reply.bodyAsText())
        val wait = reply.headers["Retry-After"]?.toIntOrNull()
        assertTrue("sync-api: $claim Retry-After is ${reply.headers["Retry-After"]}", wait != null && wait in 1..3600)
        if (exactWait != null) assertEquals("sync-api: $claim exact wait", exactWait, reply.headers["Retry-After"])
        assertTrue("sync-api: $claim content type", reply.headers["Content-Type"].orEmpty().startsWith("application/json"))
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

    // The number of counter rows of the whole table, or of the rows that match [condition] (test text, never input).
    private fun tableRows(condition: String = "1 = 1"): Long = runBlocking {
        temp.db.transaction<Long> { connection ->
            connection.prepareStatement("SELECT count(*) FROM rate_limit_counters WHERE $condition").use { statement ->
                statement.executeQuery().use { rows ->
                    rows.next()
                    rows.getLong(1)
                }
            }
        }
    }

    // Fails when the cap is not thirty: the thirty first read would be served, or the thirtieth refused.
    @Test
    fun `the thirty first read of one account in a window answers 429 with the fixed body and a sixty second wait`() {
        withOlder {
            readUnlimited(alice, 30)
            assertRefusedAsLimit("the 31st read", older(alice), "60")
        }
    }

    // Fails when a refused read is counted again: the counter would then pass thirty.
    @Test
    fun `a refused read is not counted again`() {
        withOlder {
            readUnlimited(alice, 30)
            repeat(3) { assertRefusedAsLimit("refused read ${it + 1}", older(alice), "60") }
        }
        assertEquals("sync-api: the counter stays at the cap", 30L, counterOf("older", "acct:$aliceId"))
    }

    // Fails when the key is not acct and the account id under route older: the row is then missing or under another key.
    @Test
    fun `the counter key is acct and the account id under route older with no global row`() {
        withOlder {
            assertEquals("sync-api: one read served", HttpStatusCode.OK, older(alice).status)
        }
        assertEquals("sync-api: one read counted under the account key", 1L, counterOf("older", "acct:$aliceId"))
        assertEquals("sync-api: one row in the table", 1L, tableRows())
        assertEquals("sync-api: no global row", 0L, tableRows("address = '*'"))
        assertEquals("sync-api: no row under the sync route", 0L, tableRows("route = 'sync'"))
    }

    // Fails when the account key is shared: bob would be refused on the count of alice.
    @Test
    fun `an account over its cap does not stop another account`() {
        withOlder {
            readUnlimited(alice, 30)
            assertRefusedAsLimit("alice over the cap", older(alice), "60")
            assertEquals("sync-api: bob's read is served", HttpStatusCode.OK, older(bob).status)
        }
        assertEquals("sync-api: bob has his own counter", 1L, counterOf("older", "acct:$bobId"))
    }

    // Fails when the window is not sixty thousand milliseconds: a read at 59999 would pass, or one at 60000 be refused.
    @Test
    fun `the window rolls at 60000 ms and the next read passes and counts from one`() {
        withOlder {
            readUnlimited(alice, 30)
            assertRefusedAsLimit("inside the window", older(alice), "60")
            counterClock.set(TokenFixtures.START.plusMillis(59_999L))
            assertRefusedAsLimit("one millisecond before the end", older(alice), "1")
            counterClock.set(TokenFixtures.START.plusMillis(60_000L))
            assertEquals("sync-api: a new window opens", HttpStatusCode.OK, older(alice).status)
        }
        assertEquals("sync-api: the new window counts from one", 1L, counterOf("older", "acct:$aliceId"))
    }

    // Fails when a malformed bound is not counted: the thirty malformed reads would leave the counter at zero.
    @Test
    fun `a malformed bound counts toward the limit`() {
        withOlder {
            repeat(30) {
                assertEquals("sync-api: malformed read ${it + 1} status", HttpStatusCode.BadRequest, older(alice, "x").status)
            }
            assertRefusedAsLimit("the read after thirty malformed ones", older(alice), "60")
        }
        assertEquals("sync-api: the malformed reads are counted", 30L, counterOf("older", "acct:$aliceId"))
    }

    // Fails when a 401 or a 403 is counted: the table would then hold a row for the refused requests.
    @Test
    fun `a request with no token or with an agent token counts nothing`() {
        val agent = SyncRouteTestSupport.agentToken(tokenStore, aliceId)
        withOlder {
            repeat(31) { assertEquals("sync-api: no token status", HttpStatusCode.Unauthorized, older(null).status) }
            repeat(31) { assertEquals("sync-api: agent token status", HttpStatusCode.Forbidden, older(agent).status) }
        }
        assertEquals("sync-api: nothing counted", 0L, tableRows())
    }

    // Fails when the switch is ignored: with the switch off the thirty first read would be refused and a row written.
    @Test
    fun `with the switch off no read is limited and no row is written`() {
        runBlocking { settings.setRateLimitsEnabled(false) }
        withOlder { readUnlimited(alice, 31) }
        assertEquals("sync-api: nothing counted", 0L, tableRows())
    }

    // Fails when a missing counter store or missing settings still limits: the route has no limit then.
    @Test
    fun `without a counter store or settings the route has no limit`() {
        withOlder(counterStore = null) { readUnlimited(alice, 31) }
        withOlder(settingsStore = null) { readUnlimited(alice, 31) }
        assertEquals("sync-api: nothing counted", 0L, tableRows())
    }

    // Fails when a failed counter refuses the read: a store error lets the read through and counts nothing.
    @Test
    fun `a failed counter lets the read through and counts nothing`() {
        val closed = openTemp()
        closed.db.close()
        val reply = OlderRouteTestSupport.getOlderLimited(
            tokenStore, store, RateLimitCounterStore(closed.db, counterClock), settings, "Bearer $alice", bound,
        )
        assertEquals("sync-api: failed counter read is served", HttpStatusCode.OK, reply.status)
        assertEquals("sync-api: nothing counted", 0L, tableRows())
    }

    // Fails when a failed switch read refuses the read: a store error lets the read through and counts nothing.
    @Test
    fun `a failed switch read lets the read through and counts nothing`() {
        val closed = openTemp()
        closed.db.close()
        withOlder(settingsStore = ServerSettingsStore(closed.db)) {
            assertEquals("sync-api: failed switch read is served", HttpStatusCode.OK, older(alice).status)
        }
        assertEquals("sync-api: nothing counted", 0L, tableRows())
    }

    // Fails when the module does not install the older limit: the thirty first read through the module is served.
    @Test
    fun `the module installs the older limit`() {
        val accounts = AccountFixtures.newStore(temp)
        runBlocking {
            testApplication {
                application {
                    syncApiModule(
                        accounts,
                        tokenStore,
                        store,
                        HandoffFixtures.handoffStore(temp, tokenStore, AccountFixtures.fixedClock),
                    )
                }
                readUnlimited(alice, 30)
                assertRefusedAsLimit("the 31st read through the module", older(alice))
            }
        }
    }
}
