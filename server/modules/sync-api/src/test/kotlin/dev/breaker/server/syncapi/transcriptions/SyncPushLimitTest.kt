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
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.CLIENT_ID
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.ENCRYPTED
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// The per-account limit of POST /v1/sync (ADR-041): 600 pushes in a fixed 60 s window for the key acct:<account id>,
// route sync, no global counter, the same admin switch as the other limits. The counter clock is stepped by the test.
internal class SyncPushLimitTest : TempDatabaseTest() {

    private class StepClock(var nowMs: Long) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(nowMs)
    }

    private lateinit var temp: TempDatabase
    private lateinit var tokenStore: TokenStore
    private lateinit var store: TranscriptionStore
    private lateinit var settings: ServerSettingsStore
    private lateinit var counters: RateLimitCounterStore
    private val counterClock = StepClock(0)
    private var aliceId = 0L
    private var bobId = 0L
    private var alice = ""
    private var bob = ""

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

    private fun withSync(
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

    private suspend fun ApplicationTestBuilder.push(token: String?): HttpResponse =
        client.post("/v1/sync") {
            if (token != null) header("Authorization", "Bearer $token")
            setBody(SyncRouteTestSupport.pushBody(CLIENT_ID, "0", ENCRYPTED))
            contentType(ContentType.Application.Json)
        }

    // Pushes [times] times and asserts that none of them is limited.
    private suspend fun ApplicationTestBuilder.pushUnlimited(token: String, times: Int) {
        repeat(times) {
            assertNotEquals("sync-api: push ${it + 1} is not limited", HttpStatusCode.TooManyRequests, push(token).status)
        }
    }

    private suspend fun assertRefusedAsLimit(claim: String, reply: HttpResponse) {
        assertEquals("sync-api: $claim status", HttpStatusCode.TooManyRequests, reply.status)
        assertEquals("sync-api: $claim body", refusalBody, reply.bodyAsText())
        val wait = reply.headers["Retry-After"]?.toIntOrNull()
        assertTrue("sync-api: $claim Retry-After is ${reply.headers["Retry-After"]}", wait != null && wait in 1..3600)
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

    @Test
    fun `the 601st push of one account in a window answers 429 with the fixed body and a bounded wait`() {
        withSync {
            pushUnlimited(alice, 599)
            assertNotEquals("sync-api: the 600th push passes", HttpStatusCode.TooManyRequests, push(alice).status)
            assertRefusedAsLimit("the 601st push", push(alice))
        }
    }

    @Test
    fun `a push over the cap is refused before its body is read`() {
        withSync {
            pushUnlimited(alice, 600)
            val reply = client.post("/v1/sync") {
                header("Authorization", "Bearer $alice")
                setBody("this is not a push body")
                contentType(ContentType.Application.Json)
            }
            assertRefusedAsLimit("an unreadable body over the cap", reply)
        }
    }

    // The limit must be answered before the body is read: with the account at its cap, a body of the
    // smallest size the reader refuses as too large (CAP + 1, SyncBodyReader.kt line 17, the declared
    // length check) must answer the limit, not the body status.
    @Test
    fun `an over cap body at the cap is refused as a limit not as too large`() {
        withSync {
            pushUnlimited(alice, 600)
            val reply = client.post("/v1/sync") {
                header("Authorization", "Bearer $alice")
                setBody(SyncRouteTestSupport.bodyOfSize(SyncRouteTestSupport.CAP + 1))
                contentType(ContentType.Application.Json)
            }
            assertRefusedAsLimit("an over cap body at the limit cap", reply)
            assertEquals("sync-api: the refused body is not counted", 600L, counterOf("sync", "acct:$aliceId"))
        }
    }

    // A refusal must end the handler: with the account at its cap, a valid push for a NEW client id must
    // answer the limit and write nothing for that client id.
    @Test
    fun `a refused push at the cap writes no row for a new client id`() {
        withSync {
            pushUnlimited(alice, 600)
            val reply = client.post("/v1/sync") {
                header("Authorization", "Bearer $alice")
                setBody(SyncRouteTestSupport.pushBody(SyncRouteTestSupport.OTHER_CLIENT_ID, "0", ENCRYPTED))
                contentType(ContentType.Application.Json)
            }
            assertRefusedAsLimit("a refused push for a new client id", reply)
            assertEquals("sync-api: the refusal is not counted", 600L, counterOf("sync", "acct:$aliceId"))
            val rows = runBlocking { store.list(aliceId) }
            assertTrue(
                "sync-api: the store holds no row for the refused client id",
                rows.none { it.clientId == SyncRouteTestSupport.OTHER_CLIENT_ID },
            )
        }
    }

    @Test
    fun `an unreadable push under the cap answers 400 and still counts`() {
        withSync {
            val reply = client.post("/v1/sync") {
                header("Authorization", "Bearer $alice")
                setBody("this is not a push body")
                contentType(ContentType.Application.Json)
            }
            assertEquals("sync-api: an unreadable body status", HttpStatusCode.BadRequest, reply.status)
            assertEquals("sync-api: the request was counted", 1L, counterOf("sync", "acct:$aliceId"))
        }
    }

    @Test
    fun `a refused push is not counted again`() {
        withSync {
            pushUnlimited(alice, 600)
            repeat(3) { assertRefusedAsLimit("refused push ${it + 1}", push(alice)) }
            assertEquals("sync-api: the counter stays at the cap", 600L, counterOf("sync", "acct:$aliceId"))
        }
    }

    @Test
    fun `an account over its cap does not stop another account`() {
        withSync {
            pushUnlimited(alice, 600)
            assertRefusedAsLimit("alice over the cap", push(alice))
            assertEquals("sync-api: bob's push passes", HttpStatusCode.NoContent, push(bob).status)
            assertEquals("sync-api: bob has his own counter", 1L, counterOf("sync", "acct:$bobId"))
        }
    }

    @Test
    fun `the counter key is acct and the account id, one row, no global counter, nothing under another route`() {
        withSync {
            assertEquals(HttpStatusCode.NoContent, push(alice).status)
            assertEquals("sync-api: one push counted under the account key", 1L, counterOf("sync", "acct:$aliceId"))
            assertEquals("sync-api: nothing else was written", 1L, tableRows())
            assertEquals("sync-api: no global row", 0L, tableRows("address = '*'"))
        }
    }

    @Test
    fun `a push with no token answers 401 and writes no counter row`() {
        withSync {
            assertEquals(HttpStatusCode.Unauthorized, push(null).status)
            assertEquals("sync-api: nothing counted", 0L, tableRows())
        }
    }

    @Test
    fun `a push with an agent token answers 403 and writes no counter row`() {
        val agent = SyncRouteTestSupport.agentToken(tokenStore, aliceId)
        withSync {
            assertEquals(HttpStatusCode.Forbidden, push(agent).status)
            assertEquals("sync-api: nothing counted", 0L, tableRows())
        }
    }

    @Test
    fun `the window rolls at 60000 ms and the next push passes`() {
        withSync {
            pushUnlimited(alice, 600)
            assertRefusedAsLimit("inside the window", push(alice))
            counterClock.nowMs = 59_999
            assertRefusedAsLimit("one millisecond before the end", push(alice))
            counterClock.nowMs = 60_000
            assertNotEquals("sync-api: a new window opens", HttpStatusCode.TooManyRequests, push(alice).status)
            assertEquals("sync-api: the new window counts from one", 1L, counterOf("sync", "acct:$aliceId"))
        }
    }

    @Test
    fun `with the switch off no push is limited and no row is written`() {
        runBlocking { settings.setRateLimitsEnabled(false) }
        withSync {
            pushUnlimited(alice, 601)
            assertEquals("sync-api: nothing counted", 0L, tableRows())
        }
    }

    @Test
    fun `a failed switch read lets the push through and counts nothing`() {
        val closed = openTemp()
        closed.db.close()
        withSync(settingsStore = ServerSettingsStore(closed.db)) {
            assertEquals(HttpStatusCode.NoContent, push(alice).status)
            assertEquals("sync-api: nothing counted", 0L, tableRows())
        }
    }

    @Test
    fun `a failed counter lets the push through and counts nothing`() {
        val closed = openTemp()
        closed.db.close()
        withSync(counterStore = RateLimitCounterStore(closed.db, counterClock)) {
            assertEquals(HttpStatusCode.NoContent, push(alice).status)
            assertEquals("sync-api: nothing counted", 0L, tableRows())
        }
    }

    @Test
    fun `without a counter store or settings the route has no limit`() {
        withSync(counterStore = null) { pushUnlimited(alice, 601) }
        withSync(settingsStore = null) { pushUnlimited(alice, 601) }
        assertEquals("sync-api: nothing counted", 0L, tableRows())
    }

    @Test
    fun `the module installs the sync limit`() {
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
                pushUnlimited(alice, 600)
                assertRefusedAsLimit("the 601st push through the module", push(alice))
            }
        }
    }
}
