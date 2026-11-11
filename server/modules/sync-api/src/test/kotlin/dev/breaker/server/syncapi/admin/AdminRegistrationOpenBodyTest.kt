package dev.breaker.server.syncapi.admin

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.accounts.AccountStore
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
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
import org.junit.Before
import org.junit.Test

/**
 * Tests that every bad PUT body answers 400 with the exact Error body and leaves the
 * registration-open switch unchanged, and that the shapes the contract allows are accepted.
 *
 * The first registered account (alice) is the admin.
 */
internal class AdminRegistrationOpenBodyTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var accountStore: AccountStore

    private val badRequestBody = """{"error":"bad_request","message":"enabled must be true or false"}"""

    @Before
    fun setUp() {
        temp = openTemp()
        accountStore = AccountFixtures.newStore(temp)
    }

    // ---------- helpers ----------

    private fun adminAuth(): String {
        val admin = TokenFixtures.register(temp, "alice", 1)
        val session = TokenFixtures.issuedOf(runBlocking { TokenFixtures.tokenStore(temp, AccountFixtures.fixedClock).issueSession(admin.accountId) })
        return "Bearer " + session.secret.reveal()
    }

    /** Runs the block against an application that has only the route under test installed. */
    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) {
        testApplication {
            val store = AccountFixtures.newStore(temp)
            val tokens = TokenFixtures.tokenStore(temp, AccountFixtures.fixedClock)
            application { adminRegistrationOpenRoute(tokens, store.settings) }
            block()
        }
    }

    private suspend fun ApplicationTestBuilder.putBody(auth: String, body: String): HttpResponse =
        client.put("/v1/admin/registration-open") {
            header("Authorization", auth)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private val badBodies: List<String> = listOf(
        "",
        "not json",
        "[1,2,3]",
        "\"true\"",
        "{}",
        "1",
        "null",
        """{"enabled":true,"extra":1}""",
        """{"extra":1,"enabled":true}""",
        """{"extra":true}""",
        """{"enabled":true,"enabled":false}""",
        """{"enabled":true}garbage""",
        """{"enabled": True}""",
        """{"enabled":"true"}""",
        """{"enabled":1}""",
        """{"enabled":null}""",
        """{"enabled":true,}""",
        """{"enabled":truefalse}""",
        """{"\uZZZZ":true}""",
        "\u00A0{\"enabled\":true}\u00A0",
    )

    // ---------- bad bodies all answer 400 with the exact Error body ----------

    @Test
    fun `every bad body answers 400 with the exact Error body and leaves the switch alone`() {
        val auth = adminAuth()
        withApp {
            for (initial in listOf(false, true)) {
                runBlocking { accountStore.settings.setRegistrationOpenEnabled(initial) }
                for (body in badBodies) {
                    val response = putBody(auth, body)
                    assertEquals("status for body '$body'", HttpStatusCode.BadRequest, response.status)
                    assertEquals("body for '$body'", badRequestBody, response.bodyAsText())
                    assertEquals(
                        "content type for '$body'",
                        ContentType.Application.Json,
                        response.contentType()?.withoutParameters(),
                    )
                }
                assertEquals("switch unchanged from $initial", initial, accountStore.settings.registrationOpen())
            }
        }
    }

    // ---------- shapes the contract allows ----------

    @Test
    fun `whitespace between tokens is accepted and the value is stored`() {
        val auth = adminAuth()
        withApp {
            val spaced = putBody(auth, "{ \"enabled\" : false }")
            assertEquals("spaced status", HttpStatusCode.OK, spaced.status)
            assertEquals("spaced body", """{"enabled":false}""", spaced.bodyAsText())
            assertEquals("spaced stored", false, accountStore.settings.registrationOpen())
            val padded = putBody(auth, "  \r\n{\t\"enabled\":true}  \n")
            assertEquals("padded status", HttpStatusCode.OK, padded.status)
            assertEquals("padded body", """{"enabled":true}""", padded.bodyAsText())
            assertEquals("padded stored", true, accountStore.settings.registrationOpen())
        }
    }

    @Test
    fun `an escaped spelling of the member name is the same member`() {
        val auth = adminAuth()
        withApp {
            val response = putBody(auth, """{"\u0065nabled":false}""")
            assertEquals("status", HttpStatusCode.OK, response.status)
            assertEquals("body", """{"enabled":false}""", response.bodyAsText())
        }
    }

    // ---------- the one-member rule ----------

    @Test
    fun `a repeated enabled member is refused`() {
        val auth = adminAuth()
        withApp {
            val response = putBody(auth, """{"enabled":true,"enabled":false}""")
            assertEquals("status", HttpStatusCode.BadRequest, response.status)
            assertEquals("body", badRequestBody, response.bodyAsText())
        }
    }

    @Test
    fun `trailing text after the object is refused`() {
        val auth = adminAuth()
        withApp {
            val response = putBody(auth, """{"enabled":true}extra""")
            assertEquals("status", HttpStatusCode.BadRequest, response.status)
            assertEquals("body", badRequestBody, response.bodyAsText())
        }
    }
}
