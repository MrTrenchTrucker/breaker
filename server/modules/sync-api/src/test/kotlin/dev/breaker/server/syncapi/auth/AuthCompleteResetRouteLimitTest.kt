package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.accounts.AccountStore
import dev.breaker.server.syncapi.accounts.RateLimitCounterStore
import dev.breaker.server.syncapi.syncApiModule
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The limit of POST /v1/auth/complete-reset through the real route: only FAILED attempts count, per source
// address and in total, and a right code is never counted or refused. The source address is played by the test
// header X-Test-Peer (the seam of the counter store); the default seam is the socket peer.
internal class AuthCompleteResetRouteLimitTest : ResetRouteTestBase() {

    private class StepClock(var nowMs: Long) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(nowMs)
    }

    // A clock that reads the real one and, once [live], throws a CancellationException from its [cancelFrom]th reading.
    private class CancellingClock(private val inner: Clock, private val cancelFrom: Int) : Clock() {
        var live = false
        var readings = 0
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant {
            if (live) {
                readings++
                if (readings >= cancelFrom) throw CancellationException("sync-api test: cancelled")
            }
            return inner.instant()
        }
    }

    private val counterClock = StepClock(0)
    private val wrongCode = "A".repeat(43)
    private val limitBody = """{"error":"rate_limited","message":"Too many requests."}"""

    private fun playedPeers(): RateLimitCounterStore =
        RateLimitCounterStore(temp.db, counterClock, peerOf = { call -> call.request.headers["X-Test-Peer"].orEmpty() })

    private fun socketPeer(): RateLimitCounterStore = RateLimitCounterStore(temp.db, counterClock)

    private fun withRoute(counters: RateLimitCounterStore, block: suspend ApplicationTestBuilder.() -> Unit) {
        runBlocking {
            testApplication {
                application { authCompleteResetRoute(accountStore, tokenStore, counters) }
                block()
            }
        }
    }

    private suspend fun ApplicationTestBuilder.hit(
        peer: String?,
        body: String,
        forwarded: String? = null,
        bearer: String? = null,
    ): HttpResponse =
        client.post(completePath) {
            contentType(ContentType.Application.Json)
            setBody(body)
            if (peer != null) header("X-Test-Peer", peer)
            if (forwarded != null) header("X-Forwarded-For", forwarded)
            if (bearer != null) header(HttpHeaders.Authorization, "Bearer $bearer")
        }

    private fun counterOf(address: String): Long = runBlocking {
        temp.db.transaction<Long> { connection ->
            connection.prepareStatement(
                "SELECT count FROM rate_limit_counters WHERE route = 'complete-reset' AND address = ?",
            ).use { statement ->
                statement.setString(1, address)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else -1L }
            }
        }
    }

    private suspend fun assertLimited(claim: String, reply: HttpResponse) {
        assertEquals("sync-api: $claim status", HttpStatusCode.TooManyRequests, reply.status)
        assertEquals("sync-api: $claim body", limitBody, reply.bodyAsText())
        val wait = reply.headers["Retry-After"]?.toIntOrNull()
        assertTrue("sync-api: $claim Retry-After is ${reply.headers["Retry-After"]}", wait != null && wait in 1..3600)
        assertTrue("sync-api: $claim content type", reply.headers["Content-Type"].orEmpty().startsWith("application/json"))
    }

    private suspend fun assertRefusedAsBefore(claim: String, reply: HttpResponse) {
        assertEquals("sync-api: $claim status", HttpStatusCode.Unauthorized, reply.status)
        assertEquals("sync-api: $claim body", refusedBody, reply.bodyAsText())
    }

    /** The admin, a pending Alice, and her reset code. */
    private fun aliceWithCode(): String = pendingAccount(startAdmin(), "Alice", 2)

    @Test
    fun `the eleventh wrong code from one address answers 429 and is not counted`() {
        aliceWithCode()
        withRoute(playedPeers()) {
            repeat(10) { assertRefusedAsBefore("wrong code $it", hit("a", completeBody("Alice", wrongCode))) }
            assertEquals("sync-api: ten failures counted", 10L, counterOf("a"))
            assertLimited("the eleventh wrong code", hit("a", completeBody("Alice", wrongCode)))
            assertEquals("sync-api: a refused request is not counted", 10L, counterOf("a"))
            assertEquals("sync-api: the global row holds the ten failures", 10L, counterOf("*"))
        }
    }

    @Test
    fun `the tenth wrong code is still answered 401 and the boundary is exact`() {
        aliceWithCode()
        withRoute(playedPeers()) {
            repeat(9) { hit("a", completeBody("Alice", wrongCode)) }
            assertRefusedAsBefore("the tenth wrong code", hit("a", completeBody("Alice", wrongCode)))
            assertLimited("the eleventh", hit("a", completeBody("Alice", wrongCode)))
        }
    }

    @Test
    fun `a right code at the cap still completes and counts nothing`() {
        val code = aliceWithCode()
        withRoute(playedPeers()) {
            repeat(10) { hit("a", completeBody("Alice", wrongCode)) }
            val right = hit("a", completeBody("Alice", code))
            assertEquals("sync-api: a right code at the cap", HttpStatusCode.OK, right.status)
            assertEquals("sync-api: the success is not counted", 10L, counterOf("a"))
            assertEquals("sync-api: nor in the global row", 10L, counterOf("*"))
        }
    }

    @Test
    fun `right codes are never counted`() {
        val admin = startAdmin()
        val codes = listOf("Alice" to 2, "Carol" to 3, "Dave" to 4).map { (name, seed) -> name to pendingAccount(admin, name, seed) }
        withRoute(playedPeers()) {
            for ((name, code) in codes) {
                assertEquals("sync-api: right code of $name", HttpStatusCode.OK, hit("b", completeBody(name, code)).status)
            }
            assertEquals("sync-api: no row for the address", -1L, counterOf("b"))
            assertEquals("sync-api: no global row", -1L, counterOf("*"))
        }
    }

    @Test
    fun `one address at its cap does not stop another address, wrong or right`() {
        val code = aliceWithCode()
        withRoute(playedPeers()) {
            repeat(10) { hit("a", completeBody("Alice", wrongCode)) }
            assertLimited("address a", hit("a", completeBody("Alice", wrongCode)))
            assertRefusedAsBefore("a wrong code from address b", hit("b", completeBody("Alice", wrongCode)))
            assertEquals("sync-api: a right code from address b", HttpStatusCode.OK, hit("b", completeBody("Alice", code)).status)
        }
    }

    @Test
    fun `the global backstop refuses a wrong code from a new address and lets a right code through`() {
        val code = aliceWithCode()
        withRoute(playedPeers()) {
            for (i in 1..120) assertRefusedAsBefore("failure $i", hit("p$i", completeBody("Alice", wrongCode)))
            assertEquals("sync-api: the global row", 120L, counterOf("*"))
            assertLimited("a new address over the backstop", hit("new", completeBody("Alice", wrongCode)))
            assertEquals("sync-api: the refusal is not counted", 120L, counterOf("*"))
            assertEquals("sync-api: a right code still completes", HttpStatusCode.OK, hit("new", completeBody("Alice", code)).status)
        }
    }

    @Test
    fun `a forged X-Forwarded-For does not change the key`() {
        aliceWithCode()
        withRoute(socketPeer()) {
            repeat(10) { hit(null, completeBody("Alice", wrongCode), forwarded = "198.51.100.$it") }
            assertLimited("a different forwarded address", hit(null, completeBody("Alice", wrongCode), forwarded = "203.0.113.77"))
        }
    }

    @Test
    fun `the window rolls at 60000 ms and not at 59999`() {
        aliceWithCode()
        withRoute(playedPeers()) {
            repeat(10) { hit("a", completeBody("Alice", wrongCode)) }
            counterClock.nowMs = 59_999
            assertLimited("one millisecond before the end", hit("a", completeBody("Alice", wrongCode)))
            counterClock.nowMs = 60_000
            assertRefusedAsBefore("the first request of the new window", hit("a", completeBody("Alice", wrongCode)))
            assertEquals("sync-api: a new window starts at one", 1L, counterOf("a"))
        }
    }

    @Test
    fun `bad bodies and unknown names count as failures`() {
        aliceWithCode()
        withRoute(playedPeers()) {
            repeat(5) { assertEquals("sync-api: bad body $it", HttpStatusCode.BadRequest, hit("a", "{ not json").status) }
            repeat(5) { assertRefusedAsBefore("unknown name $it", hit("a", completeBody("Nobody", wrongCode))) }
            assertEquals("sync-api: ten failures of two kinds", 10L, counterOf("a"))
            assertLimited("the eleventh failure", hit("a", "{ not json"))
        }
    }

    @Test
    fun `a body over the cap and an agent token never count`() {
        val code = aliceWithCode()
        val agent = agentText(register("Bob", 3))
        withRoute(playedPeers()) {
            repeat(11) {
                assertEquals("sync-api: oversized $it", HttpStatusCode.PayloadTooLarge, hit("a", padTo(completeBody("Alice", code), 5000)).status)
            }
            repeat(11) {
                assertEquals("sync-api: agent $it", HttpStatusCode.Forbidden, hit("a", completeBody("Alice", code), bearer = agent).status)
            }
            assertEquals("sync-api: nothing was counted", -1L, counterOf("a"))
            assertEquals("sync-api: a right code still completes", HttpStatusCode.OK, hit("a", completeBody("Alice", code)).status)
        }
    }

    @Test
    fun `a bad salt after a right code is a 400 and does not count`() {
        val code = aliceWithCode()
        withRoute(playedPeers()) {
            repeat(11) {
                val reply = hit("a", completeWith("Alice", code, "salt", quoted("AAAA")))
                assertEquals("sync-api: salt of the wrong length $it", HttpStatusCode.BadRequest, reply.status)
            }
            assertEquals("sync-api: nothing was counted", -1L, counterOf("a"))
        }
    }

    @Test
    fun `with the switch off nothing is refused and nothing is counted`() {
        aliceWithCode()
        runBlocking { accountStore.settings.setRateLimitsEnabled(false) }
        withRoute(playedPeers()) {
            repeat(15) { assertRefusedAsBefore("wrong code $it", hit("a", completeBody("Alice", wrongCode))) }
            assertEquals("sync-api: nothing was counted", -1L, counterOf("a"))
        }
    }

    // A BEFORE DELETE trigger that skips the delete: the code check still matches, the spend deletes no row.
    private val ignoreCodeDelete =
        "CREATE TRIGGER ignored_code_delete BEFORE DELETE ON reset_codes BEGIN SELECT RAISE(IGNORE); END"

    @Test
    fun `an invalid username over the cap answers 429 and from a fresh address counts one failure`() {
        aliceWithCode()
        withRoute(playedPeers()) {
            repeat(10) { hit("a", completeBody("Alice", wrongCode)) }
            assertLimited("an invalid username over the cap", hit("a", completeBody("Al ice", wrongCode)))
            assertEquals("sync-api: the refusal is not counted", 10L, counterOf("a"))
            assertRefusedAsBefore("an invalid username from a fresh address", hit("b", completeBody("Al ice", wrongCode)))
            assertEquals("sync-api: it counts as one failure", 1L, counterOf("b"))
        }
    }

    @Test
    fun `a right code whose spend is refused counts a failure and at the cap answers 429`() {
        val code = aliceWithCode()
        runSql(temp, ignoreCodeDelete)
        withRoute(playedPeers()) {
            repeat(10) { assertRefusedAsBefore("refused spend $it", hit("b", completeBody("Alice", code))) }
            assertEquals("sync-api: each refused spend counts one failure", 10L, counterOf("b"))
            assertLimited("the eleventh refused spend", hit("b", completeBody("Alice", code)))
            assertEquals("sync-api: the refusal is not counted", 10L, counterOf("b"))
        }
    }

    @Test
    fun `a store failure answers 503 and the counters do not move`() {
        aliceWithCode()
        withRoute(playedPeers()) {
            repeat(3) { assertRefusedAsBefore("wrong code $it", hit("a", completeBody("Alice", wrongCode))) }
            runSql(temp, "DROP TABLE reset_codes")
            repeat(11) {
                assertEquals("sync-api: store failure $it", HttpStatusCode.ServiceUnavailable, hit("a", completeBody("Alice", wrongCode)).status)
            }
            assertEquals("sync-api: the address row did not move", 3L, counterOf("a"))
            assertEquals("sync-api: the global row did not move", 3L, counterOf("*"))
        }
    }

    // The status of the answer, or null when the call ended with an exception and no answer came back. A rethrown
    // CancellationException is answered 500 by Ktor 3.6 (the module installs no StatusPages), never by the route.
    private fun answerOf(accounts: AccountStore, tokens: TokenStore, body: String, bearer: String?): HttpStatusCode? {
        var status: HttpStatusCode? = null
        try {
            runBlocking {
                testApplication {
                    application { authCompleteResetRoute(accounts, tokens, playedPeers()) }
                    try {
                        status = hit("a", body, bearer = bearer).status
                    } catch (_: Exception) {
                        // The cancellation reached the caller: there is no answer.
                    }
                }
            }
        } catch (_: CancellationException) {
            // Same as above, seen one level up.
        }
        return status
    }

    @Test
    fun `a cancelled session peek is rethrown and never answered with a 503`() {
        val admin = startAdmin()
        pendingAccount(admin, "Alice", 2)
        val cancelling = CancellingClock(clock, 1)
        val tokens = TokenFixtures.tokenStore(temp, cancelling)
        cancelling.live = true
        assertEquals("sync-api: rethrown, so Ktor answers 500 and the route never answers 503", HttpStatusCode.InternalServerError, answerOf(accountStore, tokens, completeBody("Alice", wrongCode), admin))
        assertTrue("sync-api: the peek read the clock", cancelling.readings >= 1)
    }

    @Test
    fun `a cancelled code check is rethrown and never answered with a 503`() {
        aliceWithCode()
        val cancelling = CancellingClock(clock, 1)
        val accounts = AccountFixtures.newStore(temp, AccountFixtures.fastHasher(), cancelling)
        cancelling.live = true
        assertEquals("sync-api: rethrown, so Ktor answers 500 and the route never answers 503", HttpStatusCode.InternalServerError, answerOf(accounts, tokenStore, completeBody("Alice", wrongCode), null))
        assertTrue("sync-api: the code check read the clock", cancelling.readings >= 1)
    }

    @Test
    fun `a cancelled spend of a right code is rethrown and never answered with a 503`() {
        val code = aliceWithCode()
        val cancelling = CancellingClock(clock, 2)
        val accounts = AccountFixtures.newStore(temp, AccountFixtures.fastHasher(), cancelling)
        cancelling.live = true
        assertEquals("sync-api: rethrown, so Ktor answers 500 and the route never answers 503", HttpStatusCode.InternalServerError, answerOf(accounts, tokenStore, completeBody("Alice", code), null))
        assertTrue("sync-api: the check and the spend both read the clock", cancelling.readings >= 2)
    }

    @Test
    fun `through the real module the eleventh wrong code from one peer answers 429`() {
        aliceWithCode()
        runBlocking {
            testApplication {
                application { syncApiModule(accountStore, tokenStore, transcriptionStore, handoffStore) }
                repeat(10) {
                    val reply = client.post(completePath) {
                        contentType(ContentType.Application.Json)
                        setBody(completeBody("Alice", wrongCode))
                    }
                    assertRefusedAsBefore("wrong code $it through the module", reply)
                }
                val eleventh = client.post(completePath) {
                    contentType(ContentType.Application.Json)
                    setBody(completeBody("Alice", wrongCode))
                }
                assertLimited("the eleventh wrong code through the module", eleventh)
            }
        }
    }

    // A failure of the SPEND (store.complete) of a right code, not of the code check: an UPDATE trigger on accounts
    // aborts the write. The route answers the fixed 503 and counts nothing, however often it is asked.
    @Test
    fun `a right code whose spend fails in the store answers 503 and the counters do not move`() {
        val code = aliceWithCode()
        withRoute(playedPeers()) {
            repeat(3) { assertRefusedAsBefore("wrong code $it", hit("a", completeBody("Alice", wrongCode))) }
            runSql(temp, "CREATE TRIGGER forced_account_failure BEFORE UPDATE ON accounts BEGIN SELECT RAISE(ABORT, 'forced'); END")
            repeat(11) {
                assertEquals("sync-api: spend failure $it", HttpStatusCode.ServiceUnavailable, hit("a", completeBody("Alice", code)).status)
            }
            assertEquals("sync-api: the address row did not move", 3L, counterOf("a"))
            assertEquals("sync-api: the global row did not move", 3L, counterOf("*"))
        }
    }

    // The hash step of a right code (accountStore.hashVerifier) cannot be made to throw from a test: its hasher is a
    // final class. So the rule that every store step of the route rethrows a cancellation is pinned by the source:
    // exactly four catch arms (session peek, code check, hash, spend), each of which only rethrows.
    @Test
    fun `every guarded step of the route rethrows a cancellation and the route has four of them`() {
        val file = File("src/main/kotlin/dev/breaker/server/syncapi/auth/AuthCompleteResetRoute.kt")
        assertTrue("sync-api: the source file must exist: ${file.absolutePath}", file.isFile)
        val arms = Regex("""catch \(cancellation: CancellationException\) \{\s*throw cancellation\s*\}""").findAll(file.readText()).count()
        assertEquals("sync-api: the rethrowing catch arms", 4, arms)
    }
}
