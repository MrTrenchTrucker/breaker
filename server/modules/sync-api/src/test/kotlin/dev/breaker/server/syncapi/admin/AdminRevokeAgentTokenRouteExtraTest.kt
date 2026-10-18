package dev.breaker.server.syncapi.admin

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.accounts.Role
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.AgentScope
import dev.breaker.server.syncapi.tokens.IssuedToken
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [adminRevokeAgentTokenRoute] — `DELETE /v1/admin/agent-tokens/{id}`.
 *
 * Continuation of [AdminRevokeAgentTokenRouteTest]: the body-shape refusals, the
 * no-token/verifier-leakage requirements, wiring, and the current role rule (ADR-009).
 *
 * The error body is the shared `Error` object with the snake_case wire names `error`
 * and `message`, exactly as openapi.yaml lines 292-320 give them; a second field
 * is not added casually.
 */
internal class AdminRevokeAgentTokenRouteExtraTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var tokenStore: TokenStore

    @Before
    fun setUp() {
        temp = openTemp()
        tokenStore = TokenFixtures.tokenStore(temp, AccountFixtures.fixedClock)
    }

    // ---------- helpers ----------

    private fun register(name: String, seed: Int) = TokenFixtures.register(temp, name, seed)

    private fun session(accountId: Long): IssuedToken = TokenFixtures.issuedOf(runBlocking { tokenStore.issueSession(accountId) })

    private fun agent(accountId: Long, scope: AgentScope = AgentScope.TRANSCRIBE): IssuedToken =
        TokenFixtures.issuedOf(runBlocking { tokenStore.issueAgent(accountId, scope, "agent-label", null) })

    private fun bearer(text: String): String = "Bearer $text"

    private fun resolves(token: IssuedToken): Boolean =
        runBlocking { tokenStore.resolve(token.secret.reveal()) } != null

    // ---------- body-shape refusals ----------

    @Test
    fun `a malformed Authorization header answers the shared error object`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val target = agent(admin.accountId)

        testApplication {
            application { adminRevokeAgentTokenRoute(tokenStore) }
            for (header in listOf("Bearer", "Bearer ", "", "not-bearer")) {
                val response = client.delete("/v1/admin/agent-tokens/${target.tokenId}") {
                    if (header.isNotEmpty()) this.header("Authorization", header)
                }
                assertEquals("header '$header' must be refused", HttpStatusCode.Unauthorized, response.status)
                assertEquals(
                    "header '$header' must answer the shared error object",
                    setOf("error", "message"),
                    errorWireNames(response.bodyAsText()),
                )
            }
        }
        assertTrue("no refused call may revoke", resolves(target))
    }

    // ---------- no token/verifier leakage ----------

    @Test
    fun `the presented token never appears in any response body`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)
        val target = agent(admin.accountId)
        val tokenText = adminSession.secret.reveal()
        val bogus = "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC"

        testApplication {
            application { adminRevokeAgentTokenRoute(tokenStore) }

            val unauthorized = client.delete("/v1/admin/agent-tokens/${target.tokenId}")
            assertFalse("the 401 body must not contain the token", unauthorized.bodyAsText().contains(tokenText))

            val bogusRefusal = client.delete("/v1/admin/agent-tokens/${target.tokenId}") {
                header("Authorization", bearer(bogus))
            }
            assertFalse("the 401 body must not contain the presented token", bogusRefusal.bodyAsText().contains(bogus))

            val notFound = client.delete("/v1/admin/agent-tokens/999999") {
                header("Authorization", bearer(tokenText))
            }
            assertFalse("the 404 body must not contain the token", notFound.bodyAsText().contains(tokenText))
        }
    }

    @Test
    fun `no response body names a token, verifier or the token text`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)
        val target = agent(admin.accountId)
        val tokenText = adminSession.secret.reveal()

        testApplication {
            application { adminRevokeAgentTokenRoute(tokenStore) }
            val bodies = listOf(
                client.delete("/v1/admin/agent-tokens/${target.tokenId}").bodyAsText(),
                client.delete("/v1/admin/agent-tokens/999999") {
                    header("Authorization", bearer(tokenText))
                }.bodyAsText(),
            )
            for (body in bodies) {
                assertFalse("no token text in the body", body.contains(tokenText))
                assertFalse("no \"token\" key in the body", body.contains("\"token\""))
                assertFalse("no \"verifier\" key in the body", body.contains("verifier"))
                assertFalse("no \"Authorization\" in the body", body.contains("Authorization"))
            }
        }
    }

    // ---------- wiring ----------

    @Test
    fun `the route answers at the admin agent-tokens path`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)
        val target = agent(admin.accountId)

        testApplication {
            application { adminRevokeAgentTokenRoute(tokenStore) }
            val response = client.delete("/v1/admin/agent-tokens/${target.tokenId}") {
                header("Authorization", bearer(adminSession.secret.reveal()))
            }
            assertEquals(
                "the route must answer at /v1/admin/agent-tokens/{id}, got ${response.status}",
                HttpStatusCode.NoContent,
                response.status,
            )
        }
    }

    @Test
    fun `an unknown admin path is answered 404`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)

        testApplication {
            application { adminRevokeAgentTokenRoute(tokenStore) }
            val response = client.delete("/v1/admin/unknown") {
                header("Authorization", bearer(adminSession.secret.reveal()))
            }
            assertEquals("unknown path must be 404", HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `GET on the revoke path is not the revoke route`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)

        testApplication {
            application { adminRevokeAgentTokenRoute(tokenStore) }
            val response = client.get("/v1/admin/agent-tokens/1") {
                header("Authorization", bearer(adminSession.secret.reveal()))
            }
            val code = response.status.value
            assertFalse("GET must not revoke, but was $code", code in 200..299)
            assertTrue("GET must be 404 or 405, but was $code", code == 404 || code == 405)
        }
    }

    // ---------- current role rule (ADR-009) ----------

    @Test
    fun `an admin-scoped agent token of a live admin can revoke`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)
        val adminAgent = agent(admin.accountId, AgentScope.ADMIN)
        val target = agent(admin.accountId)

        testApplication {
            application { adminRevokeAgentTokenRoute(tokenStore) }
            val response = client.delete("/v1/admin/agent-tokens/${target.tokenId}") {
                header("Authorization", bearer(adminAgent.secret.reveal()))
            }
            assertEquals("status", HttpStatusCode.NoContent, response.status)
        }
        assertTrue("the admin session must still resolve", resolves(adminSession))
    }

    @Test
    fun `a transcribe-scoped agent token is refused with 403`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminAgent = agent(admin.accountId, AgentScope.TRANSCRIBE)
        val target = agent(admin.accountId)

        testApplication {
            application { adminRevokeAgentTokenRoute(tokenStore) }
            val response = client.delete("/v1/admin/agent-tokens/${target.tokenId}") {
                header("Authorization", bearer(adminAgent.secret.reveal()))
            }
            assertEquals("status", HttpStatusCode.Forbidden, response.status)
            assertEquals("body", FORBIDDEN_BODY, response.bodyAsText())
        }
    }

    @Test
    fun `a demoted owner's admin-scoped agent token is refused with 403`() = runBlocking<Unit> {
        val alice = register("alice", 1) // admin
        val adminAgent = agent(alice.accountId, AgentScope.ADMIN)
        TokenFixtures.setRole(temp, alice.accountId, Role.USER) // demote after minting
        val bob = register("bob", 2)
        val target = agent(bob.accountId)

        testApplication {
            application { adminRevokeAgentTokenRoute(tokenStore) }
            val response = client.delete("/v1/admin/agent-tokens/${target.tokenId}") {
                header("Authorization", bearer(adminAgent.secret.reveal()))
            }
            assertEquals("status", HttpStatusCode.Forbidden, response.status)
            assertEquals("body", FORBIDDEN_BODY, response.bodyAsText())
        }
    }

    @Test
    fun `a demoted owner's session token is refused with 403`() = runBlocking<Unit> {
        val alice = register("alice", 1) // admin
        val aliceSession = session(alice.accountId)
        TokenFixtures.setRole(temp, alice.accountId, Role.USER)
        val bob = register("bob", 2)
        val target = agent(bob.accountId)

        testApplication {
            application { adminRevokeAgentTokenRoute(tokenStore) }
            val response = client.delete("/v1/admin/agent-tokens/${target.tokenId}") {
                header("Authorization", bearer(aliceSession.secret.reveal()))
            }
            assertEquals("status", HttpStatusCode.Forbidden, response.status)
            assertEquals("body", FORBIDDEN_BODY, response.bodyAsText())
        }
    }

    @Test
    fun `a revoked admin token is refused with 401`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)
        val target = agent(admin.accountId)
        assertTrue("the token must resolve before the revoke", resolves(adminSession))
        runBlocking { tokenStore.revokeSession(adminSession.tokenId) }

        testApplication {
            application { adminRevokeAgentTokenRoute(tokenStore) }
            val response = client.delete("/v1/admin/agent-tokens/${target.tokenId}") {
                header("Authorization", bearer(adminSession.secret.reveal()))
            }
            assertEquals("status", HttpStatusCode.Unauthorized, response.status)
        }
        assertTrue("the refused call must not revoke the target", resolves(target))
    }

    // ---------- helpers ----------

    /** The top-level names of a JSON object body, parsed by hand. */
    private fun errorWireNames(body: String): Set<String> {
        val trimmed = body.trim()
        assertTrue("the error body must be a JSON object: $body", trimmed.startsWith("{") && trimmed.endsWith("}"))
        val inner = trimmed.substring(1, trimmed.length - 1)
        val names = LinkedHashSet<String>()
        var pos = 0
        while (pos < inner.length && inner[pos] == '"') {
            val keyEnd = inner.indexOf('"', pos + 1)
            if (keyEnd < 0) break
            names.add(inner.substring(pos + 1, keyEnd))
            val colon = inner.indexOf(':', keyEnd)
            if (colon < 0) break
            val next = inner.indexOf(',', colon)
            pos = if (next < 0) inner.length else next + 1
        }
        return names
    }
}
