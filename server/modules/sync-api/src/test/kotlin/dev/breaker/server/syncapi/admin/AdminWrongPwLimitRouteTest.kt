package dev.breaker.server.syncapi.admin

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.accounts.AccountStore
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.AgentScope
import dev.breaker.server.syncapi.tokens.IssuedToken
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
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
 * Tests for [adminWrongPwLimitRoute]: `GET` and `PUT /v1/admin/wrong-pw-limit`.
 *
 * The first registered account is the admin; the second is a plain user.
 */
internal class AdminWrongPwLimitRouteTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var tokenStore: TokenStore
    private lateinit var accountStore: AccountStore

    @Before
    fun setUp() {
        temp = openTemp()
        tokenStore = TokenFixtures.tokenStore(temp, AccountFixtures.fixedClock)
        accountStore = AccountFixtures.newStore(temp)
    }

    // ---------- helpers ----------

    private fun register(name: String, seed: Int) = TokenFixtures.register(temp, name, seed)

    private fun session(accountId: Long): IssuedToken =
        TokenFixtures.issuedOf(runBlocking { tokenStore.issueSession(accountId) })

    private fun agent(accountId: Long, scope: AgentScope): IssuedToken =
        TokenFixtures.issuedOf(runBlocking { tokenStore.issueAgent(accountId, scope, "agent-label", null) })

    private fun bearer(token: IssuedToken): String = "Bearer " + token.secret.reveal()

    /** Runs the block against an application that has only the route under test installed. */
    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) {
        testApplication {
            application { adminWrongPwLimitRoute(tokenStore, accountStore.settings) }
            block()
        }
    }

    // ---------- GET refusals ----------

    @Test
    fun `GET without Authorization returns 401`() {
        register("alice", 1)
        withApp {
            val response = client.get("/v1/admin/wrong-pw-limit")
            assertEquals("status", HttpStatusCode.Unauthorized, response.status)
            assertEquals("body", UNAUTHORIZED_BODY, response.bodyAsText())
        }
    }

    @Test
    fun `GET with a malformed header returns 401`() {
        register("alice", 1)
        withApp {
            val response = client.get("/v1/admin/wrong-pw-limit") { header("Authorization", "not a bearer header") }
            assertEquals("status", HttpStatusCode.Unauthorized, response.status)
            assertEquals("body", UNAUTHORIZED_BODY, response.bodyAsText())
        }
    }

    @Test
    fun `GET with an unknown token returns 401 and the same body`() {
        register("alice", 1)
        val unknown = "Bearer " + "A".repeat(43)
        withApp {
            val response = client.get("/v1/admin/wrong-pw-limit") { header("Authorization", unknown) }
            assertEquals("status", HttpStatusCode.Unauthorized, response.status)
            assertEquals("body", UNAUTHORIZED_BODY, response.bodyAsText())
        }
    }

    @Test
    fun `GET with a plain user session token returns 403`() {
        register("alice", 1)
        val bob = register("bob", 2)
        val auth = bearer(session(bob.accountId))
        withApp {
            val response = client.get("/v1/admin/wrong-pw-limit") { header("Authorization", auth) }
            assertEquals("status", HttpStatusCode.Forbidden, response.status)
            assertEquals("body", FORBIDDEN_BODY, response.bodyAsText())
        }
    }

    @Test
    fun `GET with an agent token whose scope is not admin returns 403`() {
        val admin = register("alice", 1)
        val auth = bearer(agent(admin.accountId, AgentScope.TRANSCRIBE))
        withApp {
            val response = client.get("/v1/admin/wrong-pw-limit") { header("Authorization", auth) }
            assertEquals("status", HttpStatusCode.Forbidden, response.status)
            assertEquals("body", FORBIDDEN_BODY, response.bodyAsText())
        }
    }

    // ---------- GET success ----------

    @Test
    fun `GET with the admin session token returns 200 and enabled true on a fresh database`() {
        val admin = register("alice", 1)
        val auth = bearer(session(admin.accountId))
        withApp {
            val response = client.get("/v1/admin/wrong-pw-limit") { header("Authorization", auth) }
            assertEquals("status", HttpStatusCode.OK, response.status)
            assertEquals("body", """{"enabled":true}""", response.bodyAsText())
            assertEquals("content type", ContentType.Application.Json, response.contentType()?.withoutParameters())
        }
    }

    // ---------- PUT refusals ----------

    @Test
    fun `PUT without a token returns 401`() {
        register("alice", 1)
        withApp {
            val response = client.put("/v1/admin/wrong-pw-limit") {
                contentType(ContentType.Application.Json)
                setBody("""{"enabled":false}""")
            }
            assertEquals("status", HttpStatusCode.Unauthorized, response.status)
            assertEquals("body", UNAUTHORIZED_BODY, response.bodyAsText())
        }
    }

    @Test
    fun `PUT by a plain user returns 403 even with a bad body`() {
        register("alice", 1)
        val bob = register("bob", 2)
        val auth = bearer(session(bob.accountId))
        withApp {
            val response = client.put("/v1/admin/wrong-pw-limit") {
                header("Authorization", auth)
                contentType(ContentType.Application.Json)
                setBody("""{"enabled":notjson}""")
            }
            assertEquals("status", HttpStatusCode.Forbidden, response.status)
            assertEquals("body", FORBIDDEN_BODY, response.bodyAsText())
        }
    }

    // ---------- PUT success ----------

    @Test
    fun `PUT false then GET says false`() {
        val admin = register("alice", 1)
        val auth = bearer(session(admin.accountId))
        withApp {
            val put = client.put("/v1/admin/wrong-pw-limit") {
                header("Authorization", auth)
                contentType(ContentType.Application.Json)
                setBody("""{"enabled":false}""")
            }
            assertEquals("PUT status", HttpStatusCode.OK, put.status)
            assertEquals("PUT body", """{"enabled":false}""", put.bodyAsText())
            val get = client.get("/v1/admin/wrong-pw-limit") { header("Authorization", auth) }
            assertEquals("GET status", HttpStatusCode.OK, get.status)
            assertEquals("GET body", """{"enabled":false}""", get.bodyAsText())
        }
    }

    @Test
    fun `PUT true then GET says true`() {
        val admin = register("alice", 1)
        val auth = bearer(session(admin.accountId))
        runBlocking { accountStore.settings.setWrongPwLimitEnabled(false) }
        withApp {
            val put = client.put("/v1/admin/wrong-pw-limit") {
                header("Authorization", auth)
                contentType(ContentType.Application.Json)
                setBody("""{"enabled":true}""")
            }
            assertEquals("PUT status", HttpStatusCode.OK, put.status)
            assertEquals("PUT body", """{"enabled":true}""", put.bodyAsText())
            val get = client.get("/v1/admin/wrong-pw-limit") { header("Authorization", auth) }
            assertEquals("GET body", """{"enabled":true}""", get.bodyAsText())
        }
    }

    @Test
    fun `PUT writes the switch into the store`() {
        val admin = register("alice", 1)
        val auth = bearer(session(admin.accountId))
        withApp {
            client.put("/v1/admin/wrong-pw-limit") {
                header("Authorization", auth)
                contentType(ContentType.Application.Json)
                setBody("""{"enabled":false}""")
            }
        }
        assertEquals("stored switch", false, runBlocking { accountStore.settings.wrongPwLimitEnabled() })
    }

    @Test
    fun `PUT content type is JSON`() {
        val admin = register("alice", 1)
        val auth = bearer(session(admin.accountId))
        withApp {
            val response = client.put("/v1/admin/wrong-pw-limit") {
                header("Authorization", auth)
                contentType(ContentType.Application.Json)
                setBody("""{"enabled":false}""")
            }
            assertEquals("content type", ContentType.Application.Json, response.contentType()?.withoutParameters())
        }
    }
}
