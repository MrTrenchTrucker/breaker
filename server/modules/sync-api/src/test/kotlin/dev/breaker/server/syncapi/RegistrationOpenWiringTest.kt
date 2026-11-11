package dev.breaker.server.syncapi

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.handoff.HandoffFixtures
import dev.breaker.server.syncapi.tokens.MutableClock
import dev.breaker.server.syncapi.tokens.TokenFixtures
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
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

// The public module must install the registration-open admin route: through syncApiModule itself,
// the admin can read the switch, turn it on, and read it back.
internal class RegistrationOpenWiringTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase

    @Before
    fun setUp() {
        temp = openTemp()
    }

    @Test
    fun `the public module installs GET and PUT on the registration-open route`() {
        val tokens = TokenFixtures.tokenStore(temp, MutableClock(TokenFixtures.START))
        val admin = TokenFixtures.register(temp, "alice", 1)
        val session = TokenFixtures.issuedOf(runBlocking { tokens.issueSession(admin.accountId) })
        val auth = "Bearer " + session.secret.reveal()
        val seen = ArrayList<String>()
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
                val first = client.get("/v1/admin/registration-open") { header("Authorization", auth) }
                seen.add(first.status.toString() + " " + first.bodyAsText())
                val put = client.put("/v1/admin/registration-open") {
                    header("Authorization", auth)
                    contentType(ContentType.Application.Json)
                    setBody("""{"enabled":true}""")
                }
                seen.add(put.status.toString() + " " + put.bodyAsText())
                val second = client.get("/v1/admin/registration-open") { header("Authorization", auth) }
                seen.add(second.status.toString() + " " + second.bodyAsText())
            }
        }
        assertEquals("sync-api: GET on a fresh server", HttpStatusCode.OK.toString() + " " + """{"enabled":false}""", seen[0])
        assertEquals("sync-api: PUT true", HttpStatusCode.OK.toString() + " " + """{"enabled":true}""", seen[1])
        assertEquals("sync-api: GET after the PUT", HttpStatusCode.OK.toString() + " " + """{"enabled":true}""", seen[2])
    }
}
