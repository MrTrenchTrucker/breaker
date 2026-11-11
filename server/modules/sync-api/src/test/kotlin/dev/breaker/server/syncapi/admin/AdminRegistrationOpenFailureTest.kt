package dev.breaker.server.syncapi.admin

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.accounts.AccountStore
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.TokenFixtures
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The failure answers and the body edge cases of `GET` and `PUT /v1/admin/registration-open`:
 * a store failure is the fixed 503 and never a 500 or a 200, an oversize body is one 413,
 * and the literal, the member name and the escapes follow the strict body rule.
 */
internal class AdminRegistrationOpenFailureTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var accountStore: AccountStore
    private val badRequestBody = """{"error":"bad_request","message":"enabled must be true or false"}"""
    private val tooLargeBody = """{"error":"payload_too_large","message":"Request body exceeds maximum allowed size"}"""

    @Before
    fun setUp() {
        temp = openTemp()
        accountStore = AccountFixtures.newStore(temp)
    }

    private fun adminAuth(): String {
        val admin = TokenFixtures.register(temp, "alice", 1)
        val tokens = TokenFixtures.tokenStore(temp, AccountFixtures.fixedClock)
        val session = TokenFixtures.issuedOf(runBlocking { tokens.issueSession(admin.accountId) })
        return "Bearer " + session.secret.reveal()
    }

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) {
        testApplication {
            val tokens = TokenFixtures.tokenStore(temp, AccountFixtures.fixedClock)
            application { adminRegistrationOpenRoute(tokens, accountStore.settings) }
            block()
        }
    }

    private suspend fun ApplicationTestBuilder.putBody(auth: String, body: String): HttpResponse =
        client.put("/v1/admin/registration-open") {
            header("Authorization", auth)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun dropSettingsTable() {
        runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement("DROP TABLE server_settings").use { statement -> statement.executeUpdate() }
            }
        }
    }

    private fun switchIsOpen(): Boolean = runBlocking { accountStore.settings.registrationOpen() }

    // ---------- store failures answer the fixed 503 ----------

    @Test
    fun `a failing settings read on GET answers the fixed 503 and not a 200`() {
        val auth = adminAuth()
        dropSettingsTable()
        withApp {
            val response = client.get("/v1/admin/registration-open") { header("Authorization", auth) }
            assertEquals("status", HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("body", SERVICE_UNAVAILABLE_BODY, response.bodyAsText())
        }
    }

    @Test
    fun `a failing settings write on PUT answers the fixed 503`() {
        val auth = adminAuth()
        dropSettingsTable()
        withApp {
            val response = putBody(auth, """{"enabled":true}""")
            assertEquals("status", HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("body", SERVICE_UNAVAILABLE_BODY, response.bodyAsText())
        }
    }

    @Test
    fun `a failing token check answers the fixed 503 on GET and on PUT`() {
        val auth = adminAuth()
        temp.closeKeepingFiles()
        withApp {
            val put = putBody(auth, """{"enabled":true}""")
            assertEquals("PUT status", HttpStatusCode.ServiceUnavailable, put.status)
            assertEquals("PUT body", SERVICE_UNAVAILABLE_BODY, put.bodyAsText())
            val get = client.get("/v1/admin/registration-open") { header("Authorization", auth) }
            assertEquals("GET status", HttpStatusCode.ServiceUnavailable, get.status)
            assertEquals("GET body", SERVICE_UNAVAILABLE_BODY, get.bodyAsText())
        }
    }

    // ---------- the body size cap ----------

    @Test
    fun `a PUT body over 4096 bytes answers one 413 and leaves the switch alone`() {
        val auth = adminAuth()
        withApp {
            val body = """{"enabled":true}""" + " ".repeat(4097 - """{"enabled":true}""".length)
            val response = putBody(auth, body)
            assertEquals("status", HttpStatusCode.PayloadTooLarge, response.status)
            assertEquals("body", tooLargeBody, response.bodyAsText())
        }
        assertFalse("the switch is unchanged", switchIsOpen())
    }

    // ---------- literal, member name and escapes ----------

    @Test
    fun `a literal in mixed case is refused`() {
        val auth = adminAuth()
        withApp {
            for (body in listOf("""{"enabled":tRUE}""", """{"enabled":trUe}""", """{"enabled":fALSE}""", """{"enabled":faLse}""")) {
                val response = putBody(auth, body)
                assertEquals("status of " + body, HttpStatusCode.BadRequest, response.status)
                assertEquals("body of " + body, badRequestBody, response.bodyAsText())
            }
        }
        assertFalse("the switch is unchanged", switchIsOpen())
    }

    @Test
    fun `a member name in another case is refused`() {
        val auth = adminAuth()
        withApp {
            for (body in listOf("""{"ENABLED":true}""", """{"Enabled":true}""", """{"eNabled":true}""")) {
                val response = putBody(auth, body)
                assertEquals("status of " + body, HttpStatusCode.BadRequest, response.status)
                assertEquals("body of " + body, badRequestBody, response.bodyAsText())
            }
        }
        assertFalse("the switch is unchanged", switchIsOpen())
    }

    @Test
    fun `a unicode escape with an upper-case hex digit is accepted`() {
        val auth = adminAuth()
        withApp {
            // \u006E is the letter n: the member name spells enabled.
            val response = putBody(auth, """{"e\u006Eabled":true}""")
            assertEquals("status", HttpStatusCode.OK, response.status)
            assertEquals("body", """{"enabled":true}""", response.bodyAsText())
        }
        assertTrue("the switch is on", switchIsOpen())
    }

    @Test
    fun `a body that ends inside a unicode escape answers 400 and not 500`() {
        val auth = adminAuth()
        withApp {
            for (body in listOf("""{"\u006}""", """{"\u00""", """{"\u""", """{"\""")) {
                val response = putBody(auth, body)
                assertEquals("status of " + body, HttpStatusCode.BadRequest, response.status)
                assertEquals("body of " + body, badRequestBody, response.bodyAsText())
            }
        }
        assertFalse("the switch is unchanged", switchIsOpen())
    }
}
