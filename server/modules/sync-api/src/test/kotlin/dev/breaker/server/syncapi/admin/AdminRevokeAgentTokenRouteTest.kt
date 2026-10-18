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
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
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
 * Covers the requirements: the error body shape (malformed body / missing field /
 * wrong type / extra field all reduce to the same `{error, message}` object here,
 * because this route has no JSON request body — a bearer token and a path parameter
 * only), Authorization header parsing, no token or verifier in a response body, and
 * the wiring test.
 */
internal class AdminRevokeAgentTokenRouteTest : TempDatabaseTest() {

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

    // ---------- happy path ----------

    @Test
    fun `an admin revokes an agent token with 204 and the token stops resolving`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)
        val target = agent(admin.accountId)

        testApplication {
            application { adminRevokeAgentTokenRoute(tokenStore) }
            val response = client.delete("/v1/admin/agent-tokens/${target.tokenId}") {
                header("Authorization", bearer(adminSession.secret.reveal()))
            }
            assertEquals("status", HttpStatusCode.NoContent, response.status)
        }
        assertFalse("the revoked token must no longer resolve", resolves(target))
    }

    @Test
    fun `revoking the same agent token twice answers 204 both times`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)
        val target = agent(admin.accountId)

        testApplication {
            application { adminRevokeAgentTokenRoute(tokenStore) }
            val auth = bearer(adminSession.secret.reveal())

            val first = client.delete("/v1/admin/agent-tokens/${target.tokenId}") { header("Authorization", auth) }
            val second = client.delete("/v1/admin/agent-tokens/${target.tokenId}") { header("Authorization", auth) }

            assertEquals("first revoke", HttpStatusCode.NoContent, first.status)
            assertEquals("second revoke", HttpStatusCode.NoContent, second.status)
        }
    }

    @Test
    fun `revoking one agent token leaves the owner's other tokens live`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)
        val other = agent(admin.accountId)
        val target = agent(admin.accountId)

        testApplication {
            application { adminRevokeAgentTokenRoute(tokenStore) }
            val response = client.delete("/v1/admin/agent-tokens/${target.tokenId}") {
                header("Authorization", bearer(adminSession.secret.reveal()))
            }
            assertEquals("status", HttpStatusCode.NoContent, response.status)
        }

        assertFalse("the revoked token must be dead", resolves(target))
        assertTrue("an untouched agent token must still resolve", resolves(other))
        assertTrue("the admin's session must still resolve", resolves(adminSession))
    }

    // ---------- refusals ----------

    @Test
    fun `no token returns 401`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val target = agent(admin.accountId)

        testApplication {
            application { adminRevokeAgentTokenRoute(tokenStore) }
            val response = client.delete("/v1/admin/agent-tokens/${target.tokenId}")
            assertEquals("status", HttpStatusCode.Unauthorized, response.status)
            assertEquals("body", UNAUTHORIZED_BODY, response.bodyAsText())
        }
    }

    @Test
    fun `an invalid token returns 401`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val target = agent(admin.accountId)

        testApplication {
            application { adminRevokeAgentTokenRoute(tokenStore) }
            val response = client.delete("/v1/admin/agent-tokens/${target.tokenId}") {
                header("Authorization", bearer("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))
            }
            assertEquals("status", HttpStatusCode.Unauthorized, response.status)
            assertEquals("body", UNAUTHORIZED_BODY, response.bodyAsText())
        }
    }

    @Test
    fun `a valid non-admin session token returns 403`() = runBlocking<Unit> {
        register("alice", 1) // first account: admin
        val bob = register("bob", 2) // second account: user
        val bobSession = session(bob.accountId)
        val target = agent(bob.accountId)

        testApplication {
            application { adminRevokeAgentTokenRoute(tokenStore) }
            val response = client.delete("/v1/admin/agent-tokens/${target.tokenId}") {
                header("Authorization", bearer(bobSession.secret.reveal()))
            }
            assertEquals("status", HttpStatusCode.Forbidden, response.status)
            assertEquals("body", FORBIDDEN_BODY, response.bodyAsText())
        }
        assertTrue("a refused revoke must not touch the token", resolves(target))
    }

    @Test
    fun `an unknown id returns 404`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)

        testApplication {
            application { adminRevokeAgentTokenRoute(tokenStore) }
            val response = client.delete("/v1/admin/agent-tokens/999999") {
                header("Authorization", bearer(adminSession.secret.reveal()))
            }
            assertEquals("status", HttpStatusCode.NotFound, response.status)
            assertEquals("body", NOT_FOUND_BODY, response.bodyAsText())
        }
    }

    @Test
    fun `a session token id is not revocable through the agent route and returns 404`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)
        val otherSession = session(admin.accountId)

        testApplication {
            application { adminRevokeAgentTokenRoute(tokenStore) }
            val response = client.delete("/v1/admin/agent-tokens/${otherSession.tokenId}") {
                header("Authorization", bearer(adminSession.secret.reveal()))
            }
            assertEquals("status", HttpStatusCode.NotFound, response.status)
        }
        assertTrue("the session token must survive", resolves(otherSession))
    }

    @Test
    fun `a non-numeric id returns 404`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)

        testApplication {
            application { adminRevokeAgentTokenRoute(tokenStore) }
            val response = client.delete("/v1/admin/agent-tokens/not-a-number") {
                header("Authorization", bearer(adminSession.secret.reveal()))
            }
            assertEquals("status", HttpStatusCode.NotFound, response.status)
            assertEquals("body", NOT_FOUND_BODY, response.bodyAsText())
        }
    }

    // ---------- Authorization header parsing ----------

    @Test
    fun `the bearer scheme is case insensitive`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)
        val target = agent(admin.accountId)
        val token = adminSession.secret.reveal()

        testApplication {
            application { adminRevokeAgentTokenRoute(tokenStore) }
            for (scheme in listOf("bearer", "Bearer", "BEARER", "BeArEr")) {
                val response = client.delete("/v1/admin/agent-tokens/${target.tokenId}") {
                    header("Authorization", "$scheme $token")
                }
                assertEquals("scheme '$scheme' must be accepted", HttpStatusCode.NoContent, response.status)
            }
        }
    }

    @Test
    fun `a header without the Bearer prefix is refused with 401`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)
        val target = agent(admin.accountId)
        val token = adminSession.secret.reveal()

        testApplication {
            application { adminRevokeAgentTokenRoute(tokenStore) }
            val response = client.delete("/v1/admin/agent-tokens/${target.tokenId}") {
                header("Authorization", token)
            }
            assertEquals("status", HttpStatusCode.Unauthorized, response.status)
        }
        assertTrue("a refused call must not revoke", resolves(target))
    }

    @Test
    fun `an empty token after Bearer is refused with 401`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val target = agent(admin.accountId)

        testApplication {
            application { adminRevokeAgentTokenRoute(tokenStore) }
            val response = client.delete("/v1/admin/agent-tokens/${target.tokenId}") {
                header("Authorization", "Bearer ")
            }
            assertEquals("status", HttpStatusCode.Unauthorized, response.status)
        }
        assertTrue("a refused call must not revoke", resolves(target))
    }

    @Test
    fun `a token with a space inside is refused with 401`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)
        val target = agent(admin.accountId)
        val token = adminSession.secret.reveal()

        testApplication {
            application { adminRevokeAgentTokenRoute(tokenStore) }
            val response = client.delete("/v1/admin/agent-tokens/${target.tokenId}") {
                header("Authorization", "Bearer ${token.substring(0, 20)} ${token.substring(20)}")
            }
            assertEquals("status", HttpStatusCode.Unauthorized, response.status)
        }
        assertTrue("a refused call must not revoke", resolves(target))
    }

    @Test
    fun `a header with no separator at all is refused with 401`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val target = agent(admin.accountId)

        testApplication {
            application { adminRevokeAgentTokenRoute(tokenStore) }
            val response = client.delete("/v1/admin/agent-tokens/${target.tokenId}") {
                header("Authorization", "Bearer")
            }
            assertEquals("status", HttpStatusCode.Unauthorized, response.status)
        }
        assertTrue("a refused call must not revoke", resolves(target))
    }

    @Test
    fun `a different scheme is refused with 401`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)
        val target = agent(admin.accountId)

        testApplication {
            application { adminRevokeAgentTokenRoute(tokenStore) }
            val response = client.delete("/v1/admin/agent-tokens/${target.tokenId}") {
                header("Authorization", "Basic ${adminSession.secret.reveal()}")
            }
            assertEquals("status", HttpStatusCode.Unauthorized, response.status)
        }
    }

    // ---------- wire names (the exact set) ----------

    @Test
    fun `the 401 body is exactly the error and message object`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val target = agent(admin.accountId)

        testApplication {
            application { adminRevokeAgentTokenRoute(tokenStore) }
            val response = client.delete("/v1/admin/agent-tokens/${target.tokenId}")
            assertEquals("the exact 401 body", UNAUTHORIZED_BODY, response.bodyAsText())
            assertEquals("the wire name set is {error, message}", setOf("error", "message"), errorWireNames(response.bodyAsText()))
        }
    }

    @Test
    fun `the 403 body is exactly the error and message object`() = runBlocking<Unit> {
        register("alice", 1)
        val bob = register("bob", 2)
        val bobSession = session(bob.accountId)
        val target = agent(bob.accountId)

        testApplication {
            application { adminRevokeAgentTokenRoute(tokenStore) }
            val response = client.delete("/v1/admin/agent-tokens/${target.tokenId}") {
                header("Authorization", bearer(bobSession.secret.reveal()))
            }
            assertEquals("the exact 403 body", FORBIDDEN_BODY, response.bodyAsText())
            assertEquals("the wire name set is {error, message}", setOf("error", "message"), errorWireNames(response.bodyAsText()))
        }
    }

    @Test
    fun `the 404 body is exactly the error and message object`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)

        testApplication {
            application { adminRevokeAgentTokenRoute(tokenStore) }
            val response = client.delete("/v1/admin/agent-tokens/999999") {
                header("Authorization", bearer(adminSession.secret.reveal()))
            }
            assertEquals("the exact 404 body", NOT_FOUND_BODY, response.bodyAsText())
            assertEquals("the wire name set is {error, message}", setOf("error", "message"), errorWireNames(response.bodyAsText()))
        }
    }

    @Test
    fun `no error body carries a wire name other than error and message`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)
        val target = agent(admin.accountId)

        testApplication {
            application { adminRevokeAgentTokenRoute(tokenStore) }
            val forbidden = client.delete("/v1/admin/agent-tokens/${target.tokenId}") {
                header("Authorization", bearer("BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"))
            }
            for (response in listOf(forbidden)) {
                val names = errorWireNames(response.bodyAsText())
                assertEquals("only error and message may appear", setOf("error", "message"), names)
                assertFalse("a token field must never appear", response.bodyAsText().contains("\"token\""))
                assertFalse("a verifier field must never appear", response.bodyAsText().contains("verifier"))
                assertFalse("an id field must never appear", response.bodyAsText().contains("\"id\""))
                assertFalse("a role field must never appear", response.bodyAsText().contains("\"role\""))
            }
        }
    }

    @Test
    fun `the 204 response carries no body at all`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)
        val target = agent(admin.accountId)

        testApplication {
            application { adminRevokeAgentTokenRoute(tokenStore) }
            val response = client.delete("/v1/admin/agent-tokens/${target.tokenId}") {
                header("Authorization", bearer(adminSession.secret.reveal()))
            }
            assertEquals("status", HttpStatusCode.NoContent, response.status)
            assertEquals("a 204 carries no content", "", response.bodyAsText())
        }
    }

    @Test
    fun `every error response is served as json`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)
        val target = agent(admin.accountId)

        testApplication {
            application { adminRevokeAgentTokenRoute(tokenStore) }
            val unauthorized = client.delete("/v1/admin/agent-tokens/${target.tokenId}")
            val notFound = client.delete("/v1/admin/agent-tokens/999999") {
                header("Authorization", bearer(adminSession.secret.reveal()))
            }
            for (response in listOf(unauthorized, notFound)) {
                assertEquals(
                    "content type",
                    ContentType.Application.Json,
                    response.contentType()?.withoutParameters(),
                )
            }
        }
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
