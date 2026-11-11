package dev.breaker.server.syncapi

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.handoff.HandoffFixtures
import dev.breaker.server.syncapi.tokens.MutableClock
import dev.breaker.server.syncapi.tokens.TokenFixtures
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

// The public module must wire the counter store into the salt route: through syncApiModule itself,
// the 61st lookup from one socket address in a window answers the fixed 429.
internal class SaltLimitWiringTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase

    @Before
    fun setUp() {
        temp = openTemp()
    }

    @Test
    fun `the public module limits the salt route per address`() {
        val statuses = ArrayList<HttpStatusCode>()
        val bodies = ArrayList<String>()
        val tokens = TokenFixtures.tokenStore(temp, MutableClock(TokenFixtures.START))
        runBlocking<Unit> {
            testApplication {
                application {
                    syncApiModule(
                        AccountFixtures.newStore(temp),
                        tokens,
                        dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.newStore(temp),
                        HandoffFixtures.handoffStore(temp, tokens, AccountFixtures.fixedClock),
                    )
                }
                repeat(61) {
                    val response = client.post("/v1/auth/salt") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"username":"nobody"}""")
                    }
                    statuses.add(response.status)
                    bodies.add(response.bodyAsText())
                }
            }
        }
        assertEquals("sync-api: the first 60 lookups are not limited", 0, statuses.take(60).count { it == HttpStatusCode.TooManyRequests })
        assertEquals("sync-api: the 61st lookup is limited", HttpStatusCode.TooManyRequests, statuses[60])
        assertEquals(
            "sync-api: the fixed body",
            """{"error":"rate_limited","message":"Too many requests."}""",
            bodies[60],
        )
    }
}
