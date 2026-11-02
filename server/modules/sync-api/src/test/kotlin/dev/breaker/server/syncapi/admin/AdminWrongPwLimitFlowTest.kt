package dev.breaker.server.syncapi.admin

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.accounts.AccountStore
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.handoff.HandoffFixtures
import dev.breaker.server.syncapi.handoff.HandoffStore
import dev.breaker.server.syncapi.syncApiModule
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport
import dev.breaker.server.syncapi.transcriptions.TranscriptionStore
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

/**
 * Tests the wrong-password limit switch through the real application: the login route and
 * the admin route are both installed by syncApiModule, so the wiring is covered too.
 *
 * The first registered account (alice) is the admin; bob is a plain user and takes the
 * wrong tries. The admin acts with its session token, never with its password.
 */
internal class AdminWrongPwLimitFlowTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var accountStore: AccountStore
    private lateinit var tokenStore: TokenStore
    private lateinit var transcriptionStore: TranscriptionStore
    private lateinit var handoffStore: HandoffStore
    private lateinit var adminAuth: String

    @Before
    fun setUp() {
        temp = openTemp()
        accountStore = AccountFixtures.newStore(temp)
        tokenStore = TokenFixtures.tokenStore(temp, AccountFixtures.fixedClock)
        transcriptionStore = SyncRouteTestSupport.newStore(temp)
        handoffStore = HandoffFixtures.handoffStore(temp, tokenStore, AccountFixtures.fixedClock)
        val admin = TokenFixtures.register(temp, "alice", 1)
        TokenFixtures.register(temp, "bob", 2)
        val session = TokenFixtures.issuedOf(runBlocking { tokenStore.issueSession(admin.accountId) })
        adminAuth = "Bearer " + session.secret.reveal()
    }

    // ---------- helpers ----------

    private fun loginBody(username: String, seed: Int): String {
        val verifierText = Base64.getEncoder().encodeToString(AccountFixtures.verifierBytes(seed))
        return """{"username":"$username","auth_verifier":"$verifierText"}"""
    }

    /** Runs the block against the whole application. */
    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) {
        testApplication {
            application { syncApiModule(accountStore, tokenStore, transcriptionStore, handoffStore) }
            block()
        }
    }

    private suspend fun ApplicationTestBuilder.login(username: String, seed: Int): HttpResponse =
        client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(loginBody(username, seed))
        }

    private suspend fun ApplicationTestBuilder.putSwitch(enabled: Boolean): HttpResponse =
        client.put("/v1/admin/wrong-pw-limit") {
            header("Authorization", adminAuth)
            contentType(ContentType.Application.Json)
            setBody("""{"enabled":$enabled}""")
        }

    // ---------- the route is wired into the application ----------

    @Test
    fun `the application answers the admin route and refuses it without a token`() {
        withApp {
            val refused = client.get("/v1/admin/wrong-pw-limit")
            assertEquals("no token status", HttpStatusCode.Unauthorized, refused.status)
            assertEquals("no token body", UNAUTHORIZED_BODY, refused.bodyAsText())
            val allowed = client.get("/v1/admin/wrong-pw-limit") { header("Authorization", adminAuth) }
            assertEquals("admin status", HttpStatusCode.OK, allowed.status)
            assertEquals("admin body", """{"enabled":true}""", allowed.bodyAsText())
        }
    }

    // ---------- limit on, then off, then on again ----------

    @Test
    fun `turning the limit off lets a locked name in and turning it on locks it again`() {
        withApp {
            repeat(5) {
                val wrong = login("bob", 3)
                assertEquals("wrong try number ${it + 1} is the plain 401", HttpStatusCode.Unauthorized, wrong.status)
            }
            val sixth = login("bob", 3)
            assertEquals("the sixth try inside the wait", HttpStatusCode.Locked, sixth.status)
            assertTrue("the sixth body says locked", sixth.bodyAsText().contains("\"error\":\"locked\""))

            val off = putSwitch(false)
            assertEquals("off status", HttpStatusCode.OK, off.status)
            assertEquals("off body", """{"enabled":false}""", off.bodyAsText())

            val right = login("bob", 2)
            assertEquals("right password with the limit off", HttpStatusCode.OK, right.status)
            assertTrue("right password body has a token", right.bodyAsText().contains("\"token\":\""))
            val kept = accountStore.attempts.attemptOf("bob")
            assertNotNull("the attempt row is kept", kept)
            assertEquals("the count is kept", 5L, kept!!.wrongCount)

            val on = putSwitch(true)
            assertEquals("on status", HttpStatusCode.OK, on.status)
            assertEquals("on body", """{"enabled":true}""", on.bodyAsText())

            val again = login("bob", 2)
            assertEquals("right password with the limit back on", HttpStatusCode.Locked, again.status)
        }
    }

    // ---------- limit off: no row is written ----------

    @Test
    fun `with the limit off five wrong tries leave no attempt row`() {
        withApp {
            val off = putSwitch(false)
            assertEquals("off status", HttpStatusCode.OK, off.status)
            repeat(5) {
                val wrong = login("bob", 3)
                assertEquals("wrong try number ${it + 1}", HttpStatusCode.Unauthorized, wrong.status)
            }
            val sixth = login("bob", 3)
            assertEquals("the sixth try is still the plain 401", HttpStatusCode.Unauthorized, sixth.status)
            assertNull("no attempt row with the limit off", accountStore.attempts.attemptOf("bob"))
        }
    }
}
