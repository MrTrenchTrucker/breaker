package dev.breaker.server.syncapi.admin

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.accounts.Role
import dev.breaker.server.syncapi.db.SqliteDatabase
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.AgentScope
import dev.breaker.server.syncapi.tokens.AgentTokenRow
import dev.breaker.server.syncapi.tokens.IssuedToken
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

internal class AdminListAgentTokensRouteTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var tokenStore: TokenStore

    @Before
    fun setUp() {
        temp = openTemp()
        tokenStore = TokenFixtures.tokenStore(temp, AccountFixtures.fixedClock)
    }

    // ---------- helpers ----------

    private fun register(name: String, seed: Int) = TokenFixtures.register(temp, name, seed)

    private fun session(accountId: Long): IssuedToken =
        TokenFixtures.issuedOf(runBlocking { tokenStore.issueSession(accountId) })

    private fun bearer(text: String): String = "Bearer $text"

    private fun mint(accountId: Long, label: String, scope: AgentScope = AgentScope.TRANSCRIBE, expiresAt: Instant? = null): IssuedToken =
        TokenFixtures.issuedOf(runBlocking { tokenStore.issueAgent(accountId, scope, label, expiresAt) })

    // A store whose listAgentTokens throws: the route must answer 503.
    private class ExplodingTokenStore(db: SqliteDatabase, clock: Clock) : TokenStore(db, clock) {
        override suspend fun listAgentTokens(): List<AgentTokenRow> =
            throw RuntimeException("the store is broken")
    }

    // ---------- happy path ----------

    @Test
    fun `an empty store lists nothing`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)

        testApplication {
            application { adminListAgentTokensRoute(tokenStore) }
            val response = client.get("/v1/admin/agent-tokens") {
                header("Authorization", bearer(adminSession.secret.reveal()))
            }
            assertEquals("status", HttpStatusCode.OK, response.status)
            assertEquals("body", "[]", response.bodyAsText())
        }
    }

    @Test
    fun `the list covers every account, oldest first`() = runBlocking<Unit> {
        val alice = register("alice", 1) // first account: admin
        val aliceSession = session(alice.accountId)
        val bob = register("bob", 2) // second account: user
        TokenFixtures.setRole(temp, bob.accountId, Role.ADMIN)

        val aliceToken = mint(alice.accountId, "alice-first", AgentScope.TRANSCRIBE, null)
        val bobToken = mint(bob.accountId, "bob-only", AgentScope.ADMIN, Instant.parse("2026-07-01T00:00:00.000Z"))
        val aliceSecond = mint(alice.accountId, "alice-second", AgentScope.TRANSCRIBE, null)

        testApplication {
            application { adminListAgentTokensRoute(tokenStore) }
            val response = client.get("/v1/admin/agent-tokens") {
                header("Authorization", bearer(aliceSession.secret.reveal()))
            }
            assertEquals("status", HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue("alice's first token appears", body.contains("\"name\":\"alice-first\""))
            assertTrue("another account's token appears", body.contains("\"name\":\"bob-only\""))
            assertTrue("alice's second token appears", body.contains("\"name\":\"alice-second\""))
            assertTrue("id ascending", body.indexOf("\"name\":\"alice-first\"") < body.indexOf("\"name\":\"alice-second\""))
            assertTrue("bob's entry carries admin scope and an expiry", body.contains("\"name\":\"bob-only\"") && body.contains("\"scope\":\"admin\"") && body.contains("\"expires_at\":\"2026-07-01T00:00:00.000Z\""))
            assertTrue("a null expiry is null", body.contains("\"name\":\"alice-first\"") && body.contains("\"scope\":\"transcribe\"") && body.contains("\"expires_at\":null"))
            assertTrue("alice's id appears", body.contains("\"id\":${aliceToken.tokenId},"))
            assertTrue("bob's id appears", body.contains("\"id\":${bobToken.tokenId},"))
        }
    }

    @Test
    fun `a revoked token does not appear`() = runBlocking<Unit> {
        val alice = register("alice", 1)
        val aliceSession = session(alice.accountId)
        val live = mint(alice.accountId, "live-one", AgentScope.TRANSCRIBE, null)
        val dead = mint(alice.accountId, "dead-one", AgentScope.TRANSCRIBE, null)
        runBlocking { tokenStore.revokeAgentToken(dead.tokenId) }

        testApplication {
            application { adminListAgentTokensRoute(tokenStore) }
            val response = client.get("/v1/admin/agent-tokens") {
                header("Authorization", bearer(aliceSession.secret.reveal()))
            }
            assertEquals("status", HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue("the live token appears", body.contains("\"name\":\"live-one\""))
            assertFalse("the revoked token does not appear", body.contains("\"name\":\"dead-one\""))
        }
    }

    @Test
    fun `entries never carry the token value or its hash`() = runBlocking<Unit> {
        val alice = register("alice", 1)
        val aliceSession = session(alice.accountId)
        val issued = mint(alice.accountId, "secret-one", AgentScope.TRANSCRIBE, null)
        val secret = issued.secret.reveal()

        testApplication {
            application { adminListAgentTokensRoute(tokenStore) }
            val response = client.get("/v1/admin/agent-tokens") {
                header("Authorization", bearer(aliceSession.secret.reveal()))
            }
            assertEquals("status", HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertFalse("no token key", body.contains("\"token\""))
            assertFalse("no token value", body.contains(secret))
        }
    }

    // ---------- refusals ----------

    @Test
    fun `no token returns 401`() = runBlocking<Unit> {
        val alice = register("alice", 1)
        mint(alice.accountId, "x")

        testApplication {
            application { adminListAgentTokensRoute(tokenStore) }
            val response = client.get("/v1/admin/agent-tokens")
            assertEquals("status", HttpStatusCode.Unauthorized, response.status)
            assertEquals("body", UNAUTHORIZED_BODY, response.bodyAsText())
        }
    }

    @Test
    fun `a non-admin caller returns 403`() = runBlocking<Unit> {
        register("alice", 1) // first account: admin
        val bob = register("bob", 2) // second account: user
        val bobSession = session(bob.accountId)
        mint(bob.accountId, "x")

        testApplication {
            application { adminListAgentTokensRoute(tokenStore) }
            val response = client.get("/v1/admin/agent-tokens") {
                header("Authorization", bearer(bobSession.secret.reveal()))
            }
            assertEquals("status", HttpStatusCode.Forbidden, response.status)
            assertEquals("body", FORBIDDEN_BODY, response.bodyAsText())
        }
    }

    @Test
    fun `a forced store failure returns 503`() = runBlocking<Unit> {
        val alice = register("alice", 1)
        val aliceSession = session(alice.accountId)
        val exploding = ExplodingTokenStore(temp.db, AccountFixtures.fixedClock)

        testApplication {
            application { adminListAgentTokensRoute(exploding) }
            val response = client.get("/v1/admin/agent-tokens") {
                header("Authorization", bearer(aliceSession.secret.reveal()))
            }
            assertEquals("status", HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("body", SERVICE_UNAVAILABLE_BODY, response.bodyAsText())
        }
    }

    // ---------- wiring ----------

    @Test
    fun `a token created through the create route shows up in the list`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)

        testApplication {
            application {
                adminCreateAgentTokenRoute(tokenStore)
                adminListAgentTokensRoute(tokenStore)
            }
            client.post("/v1/admin/agent-tokens") {
                header("Authorization", bearer(adminSession.secret.reveal()))
                contentType(ContentType.Application.Json)
                setBody("""{"name":"wired-token","owner_username":"alice"}""")
            }
            val response = client.get("/v1/admin/agent-tokens") {
                header("Authorization", bearer(adminSession.secret.reveal()))
            }
            assertEquals("status", HttpStatusCode.OK, response.status)
            assertTrue("created token is listed", response.bodyAsText().contains("\"name\":\"wired-token\""))
            assertFalse("no value in the list", response.bodyAsText().contains("\"token\""))
        }
    }
}
