package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.accounts.Role
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.syncApiModule
import dev.breaker.server.syncapi.tokens.MutableClock
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.CLIENT_ID
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.ENCRYPTED
import io.ktor.client.request.get
import io.ktor.client.request.header
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

// The public module built by syncApiModule, through testApplication. The sync routes are mounted there,
// so a push and a list through the module reach the same handlers that the route tests call directly.
internal class SyncApiModuleRoutesTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var clock: MutableClock
    private lateinit var tokenStore: TokenStore
    private lateinit var store: TranscriptionStore
    private var token: String = ""

    @Before
    fun setUp() {
        temp = openTemp()
        clock = MutableClock(TokenFixtures.START)
        tokenStore = TokenFixtures.tokenStore(temp, clock)
        store = SyncRouteTestSupport.newStore(temp, clock)
        val alice = TokenFixtures.register(temp, "alice", 1)
        assertEquals("sync-api: the first account must be the admin", Role.ADMIN, alice.role)
        token = SyncRouteTestSupport.sessionToken(tokenStore, alice.accountId)
    }

    @Test
    fun `the public module mounts the push and the list so a pushed row is listed`() = runBlocking<Unit> {
        testApplication {
            application { syncApiModule(AccountFixtures.newStore(temp), tokenStore, store) }
            val pushed = client.post("/v1/sync") {
                header("Authorization", "Bearer $token")
                setBody(SyncRouteTestSupport.pushBody(CLIENT_ID, "0", ENCRYPTED))
                contentType(ContentType.Application.Json)
            }
            assertEquals("sync-api: module push status", HttpStatusCode.NoContent, pushed.status)
            val listed = client.get("/v1/transcriptions") {
                header("Authorization", "Bearer $token")
            }
            assertEquals("sync-api: module list status", HttpStatusCode.OK, listed.status)
            assertEquals(
                "sync-api: module list body",
                "[" + SyncRouteTestSupport.rowJson(CLIENT_ID, 0, ENCRYPTED, "2023-11-14T22:13:20Z") + "]",
                listed.bodyAsText(),
            )
        }
    }
}
