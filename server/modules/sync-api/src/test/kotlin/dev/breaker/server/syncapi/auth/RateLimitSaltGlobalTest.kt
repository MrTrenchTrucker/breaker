package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.accounts.AccountStore
import dev.breaker.server.syncapi.accounts.RateLimitCounterStore
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

/** The global backstop of the salt route: 1200 lookups in one window across addresses, the next one is refused. */
internal class RateLimitSaltGlobalTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var accounts: AccountStore

    @Before
    fun setUp() {
        temp = openTemp()
        accounts = AccountFixtures.newStore(temp)
    }

    private fun withSalt(block: suspend ApplicationTestBuilder.() -> Unit) {
        val counters = RateLimitCounterStore(
            temp.db,
            Clock.fixed(Instant.ofEpochMilli(0), ZoneOffset.UTC),
            peerOf = { call -> call.request.headers["X-Test-Peer"].orEmpty() },
        )
        runBlocking {
            testApplication {
                application { authSaltRoute(accounts, counters) }
                block()
            }
        }
    }

    private suspend fun ApplicationTestBuilder.salt(peer: String): HttpResponse =
        client.post("/v1/auth/salt") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"nobody"}""")
            header("X-Test-Peer", peer)
        }

    @Test
    fun `the twelve hundred first salt lookup of a window is refused whatever the address`() {
        withSalt {
            repeat(1200) {
                val status = salt("peer-" + it).status
                assertNotEquals("sync-api: lookup $it passes", HttpStatusCode.TooManyRequests, status)
            }
            assertEquals(
                "sync-api: the 1201st lookup from a new address is refused",
                HttpStatusCode.TooManyRequests,
                salt("peer-1200").status,
            )
        }
    }
}
