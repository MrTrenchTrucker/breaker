package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.AgentScope
import dev.breaker.server.syncapi.tokens.IssuedToken
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [authLogoutRoute] — `POST /v1/auth/logout`.
 *
 * The route takes no request body: the presented bearer token is the whole request,
 * so the body-shape cases (malformed JSON, missing field, wrong type, extra field)
 * do not apply here and are not faked. What is covered instead, per the plan:
 *
 * - 204 on a live session token, with an empty body, and the token stops resolving.
 * - 401 for a missing, unknown, already-revoked, expired or agent token — always the
 *   same fixed body, so nothing about the refusal is disclosed.
 * - the 401 body is exactly `{"error","message"}` (the exact wire-name set).
 * - Authorization header parsing: scheme case, missing scheme, empty token, a token
 *   carrying whitespace, a different scheme.
 * - the presented token never appears in a response body or the captured output.
 * - wiring: the route answers at `/v1/auth/logout` and an unknown path is 404.
 */
internal class AuthLogoutRouteTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var tokenStore: TokenStore

    @Before
    fun setUp() {
        temp = openTemp()
        tokenStore = TokenFixtures.tokenStore(temp, AccountFixtures.fixedClock)
    }

    // ---------- helpers ----------

    private fun register(name: String = "alice", seed: Int = 1) =
        TokenFixtures.register(temp, name, seed)

    private fun session(accountId: Long): IssuedToken = TokenFixtures.issuedOf(runBlocking { tokenStore.issueSession(accountId) })

    private fun agent(accountId: Long): IssuedToken =
        TokenFixtures.issuedOf(runBlocking { tokenStore.issueAgent(accountId, AgentScope.TRANSCRIBE, "agent-label", null) })

    private fun bearer(text: String): String = "Bearer $text"

    private fun resolves(token: IssuedToken): Boolean =
        runBlocking { tokenStore.resolve(token.secret.reveal()) } != null

    private fun resolvesSecret(secret: String): Boolean = runBlocking { tokenStore.resolve(secret) } != null

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

    // ---------- happy path (204 "Logged out", no content) ----------

    @Test
    fun `a live session token logs out with 204 and an empty body`() = runBlocking<Unit> {
        val account = register()
        val token = session(account.accountId)
        assertTrue("a fresh session token must resolve before logout", resolves(token))

        testApplication {
            application { authLogoutRoute(tokenStore) }
            val response = client.post("/v1/auth/logout") {
                header("Authorization", bearer(token.secret.reveal()))
            }
            assertEquals("status", HttpStatusCode.NoContent, response.status)
            assertEquals("a 204 carries no content", "", response.bodyAsText())
        }

        assertFalse("the presented token must not resolve after logout", resolves(token))
    }

    @Test
    fun `the same token refused after logout returns 401`() = runBlocking<Unit> {
        val account = register()
        val token = session(account.accountId)

        testApplication {
            application { authLogoutRoute(tokenStore) }
            val auth = bearer(token.secret.reveal())
            val first = client.post("/v1/auth/logout") { header("Authorization", auth) }
            val second = client.post("/v1/auth/logout") { header("Authorization", auth) }

            assertEquals("the first logout", HttpStatusCode.NoContent, first.status)
            assertEquals("a reused token", HttpStatusCode.Unauthorized, second.status)
            assertEquals("the reused token body", UNAUTHORIZED_BODY, second.bodyAsText())
        }
    }

    @Test
    fun `logout revokes only the presented session token`() = runBlocking<Unit> {
        val account = register()
        val presented = session(account.accountId)
        val other = session(account.accountId)
        val agentToken = agent(account.accountId)

        testApplication {
            application { authLogoutRoute(tokenStore) }
            val response = client.post("/v1/auth/logout") {
                header("Authorization", bearer(presented.secret.reveal()))
            }
            assertEquals("status", HttpStatusCode.NoContent, response.status)
        }

        assertFalse("the presented session token must be revoked", resolves(presented))
        assertTrue("another session of the same account must stay live", resolves(other))
        assertTrue("logout never touches agent tokens", resolves(agentToken))
    }

    // ---------- refusal (401 "Unauthorized") ----------

    @Test
    fun `no Authorization header returns 401`() = runBlocking<Unit> {
        val account = register()

        testApplication {
            application { authLogoutRoute(tokenStore) }
            val response = client.post("/v1/auth/logout")
            assertEquals("status", HttpStatusCode.Unauthorized, response.status)
            assertEquals("body", UNAUTHORIZED_BODY, response.bodyAsText())
        }
    }

    @Test
    fun `an unknown token returns 401`() = runBlocking<Unit> {
        register()

        testApplication {
            application { authLogoutRoute(tokenStore) }
            val response = client.post("/v1/auth/logout") {
                header("Authorization", bearer(UNKNOWN_TOKEN))
            }
            assertEquals("status", HttpStatusCode.Unauthorized, response.status)
            assertEquals("body", UNAUTHORIZED_BODY, response.bodyAsText())
        }
    }

    @Test
    fun `an already-revoked token returns 401`() = runBlocking<Unit> {
        val account = register()
        val token = session(account.accountId)
        assertTrue("the revoke must land", runBlocking { tokenStore.revokeSession(token.tokenId) })

        testApplication {
            application { authLogoutRoute(tokenStore) }
            val response = client.post("/v1/auth/logout") {
                header("Authorization", bearer(token.secret.reveal()))
            }
            assertEquals("status", HttpStatusCode.Unauthorized, response.status)
        }
    }

    @Test
    fun `an expired session token returns 401`() = runBlocking<Unit> {
        val account = register()
        val token = session(account.accountId)
        TokenFixtures.expireSession(temp, token.tokenId)

        testApplication {
            application { authLogoutRoute(tokenStore) }
            val response = client.post("/v1/auth/logout") {
                header("Authorization", bearer(token.secret.reveal()))
            }
            assertEquals("status", HttpStatusCode.Unauthorized, response.status)
        }
    }

    @Test
    fun `an agent token returns 401 and stays live`() = runBlocking<Unit> {
        val account = register()
        val token = agent(account.accountId)
        assertTrue("a fresh agent token must resolve", resolves(token))

        testApplication {
            application { authLogoutRoute(tokenStore) }
            val response = client.post("/v1/auth/logout") {
                header("Authorization", bearer(token.secret.reveal()))
            }
            assertEquals("an agent token is not a session", HttpStatusCode.Unauthorized, response.status)
        }

        assertTrue("an agent token must survive a logout", resolves(token))
    }

    // ---------- Authorization header parsing ----------

    @Test
    fun `the bearer scheme is case insensitive`() = runBlocking<Unit> {
        val account = register()

        for (scheme in listOf("Bearer", "bearer", "BEARER", "BeArEr")) {
            val token = session(account.accountId)
            testApplication {
                application { authLogoutRoute(tokenStore) }
                val response = client.post("/v1/auth/logout") {
                    header("Authorization", "$scheme ${token.secret.reveal()}")
                }
                assertEquals("scheme '$scheme' must be accepted", HttpStatusCode.NoContent, response.status)
            }
            assertFalse("the token of scheme '$scheme' must be revoked", resolves(token))
        }
    }

    @Test
    fun `a header without the Bearer prefix is refused with 401`() = runBlocking<Unit> {
        val account = register()
        val token = session(account.accountId)

        testApplication {
            application { authLogoutRoute(tokenStore) }
            val response = client.post("/v1/auth/logout") {
                header("Authorization", token.secret.reveal())
            }
            assertEquals("a bare token must be refused", HttpStatusCode.Unauthorized, response.status)
            assertEquals("body", UNAUTHORIZED_BODY, response.bodyAsText())
        }

        assertTrue("a refused call must not revoke", resolves(token))
    }

    @Test
    fun `a different scheme is refused with 401`() = runBlocking<Unit> {
        val account = register()
        val token = session(account.accountId)

        testApplication {
            application { authLogoutRoute(tokenStore) }
            val response = client.post("/v1/auth/logout") {
                header("Authorization", "Basic ${token.secret.reveal()}")
            }
            assertEquals("status", HttpStatusCode.Unauthorized, response.status)
        }

        assertTrue("a refused call must not revoke", resolves(token))
    }

    @Test
    fun `an empty token after Bearer is refused with 401`() = runBlocking<Unit> {
        register()

        for (value in listOf("Bearer", "Bearer ", "Bearer  ")) {
            testApplication {
                application { authLogoutRoute(tokenStore) }
                val response = client.post("/v1/auth/logout") {
                    header("Authorization", value)
                }
                assertEquals("header '$value' must be refused", HttpStatusCode.Unauthorized, response.status)
            }
        }
    }

    @Test
    fun `a token containing spaces is refused with 401`() = runBlocking<Unit> {
        val account = register()
        val token = session(account.accountId)
        val text = token.secret.reveal()

        testApplication {
            application { authLogoutRoute(tokenStore) }
            val response = client.post("/v1/auth/logout") {
                header("Authorization", "Bearer ${text.substring(0, 20)} ${text.substring(20)}")
            }
            assertEquals("a token with whitespace must be refused", HttpStatusCode.Unauthorized, response.status)
        }

        assertTrue("a refused call must not revoke", resolves(token))
    }

    // ---------- wire names (exactly {error, message}) ----------

    @Test
    fun `the 401 body is exactly the spec error object`() = runBlocking<Unit> {
        register()

        testApplication {
            application { authLogoutRoute(tokenStore) }
            val response = client.post("/v1/auth/logout")
            assertEquals("status", HttpStatusCode.Unauthorized, response.status)
            assertEquals("the exact 401 body", UNAUTHORIZED_BODY, response.bodyAsText())
            assertEquals("the wire name set is {error, message}", setOf("error", "message"), errorWireNames(response.bodyAsText()))
        }
    }

    @Test
    fun `no 401 body carries a wire name other than error and message`() = runBlocking<Unit> {
        val account = register()
        val live = session(account.accountId)
        val dead = session(account.accountId)
        assertTrue("the dead token must be revoked first", runBlocking { tokenStore.revokeSession(dead.tokenId) })

        testApplication {
            application { authLogoutRoute(tokenStore) }
            for (response in listOf(
                client.post("/v1/auth/logout"),
                client.post("/v1/auth/logout") { header("Authorization", bearer(dead.secret.reveal())) },
                client.post("/v1/auth/logout") { header("Authorization", bearer(UNKNOWN_TOKEN)) },
            )) {
                assertEquals("a refusal must be 401", HttpStatusCode.Unauthorized, response.status)
                val body = response.bodyAsText()
                assertEquals("only error and message may appear", setOf("error", "message"), errorWireNames(body))
                assertFalse("a token field must never appear", body.contains("\"token\""))
                assertFalse("a verifier field must never appear", body.contains("verifier"))
                assertFalse("an id field must never appear", body.contains("\"id\""))
                assertFalse("a role field must never appear", body.contains("\"role\""))
                assertFalse("the presented token must never appear", body.contains(live.secret.reveal()))
            }

            val loggedOut = client.post("/v1/auth/logout") { header("Authorization", bearer(live.secret.reveal())) }
            assertEquals("a live token logs out", HttpStatusCode.NoContent, loggedOut.status)
            assertEquals("a 204 carries no body", "", loggedOut.bodyAsText())
        }
    }

    // ---------- no token/verifier leakage ----------

    @Test
    fun `the presented token never appears in a response body`() = runBlocking<Unit> {
        val account = register()
        val token = session(account.accountId)
        val text = token.secret.reveal()

        testApplication {
            application { authLogoutRoute(tokenStore) }

            // A live token answers 204: the body is empty, so nothing is echoed.
            val loggedOut = client.post("/v1/auth/logout") { header("Authorization", bearer(text)) }
            assertEquals("a live token logs out", HttpStatusCode.NoContent, loggedOut.status)
            assertEquals("a 204 carries no body", "", loggedOut.bodyAsText())
            assertFalse("a 204 body must not contain the token", loggedOut.bodyAsText().contains(text))

            // Now the token is revoked: the refusals must not echo it either.
            val unauthorized = client.post("/v1/auth/logout") { header("Authorization", bearer(text)) }
            assertEquals("a revoked token is refused", HttpStatusCode.Unauthorized, unauthorized.status)
            assertFalse("a 401 body must not contain the token", unauthorized.bodyAsText().contains(text))

            val refused = client.post("/v1/auth/logout") { header("Authorization", bearer("$text-extra")) }
            assertEquals("an unknown token is refused", HttpStatusCode.Unauthorized, refused.status)
            assertFalse("a 401 body must not contain the presented value", refused.bodyAsText().contains("$text-extra"))
        }
    }

    @Test
    fun `the presented token is never written to the captured output`() = runBlocking<Unit> {
        val account = register()
        val token = session(account.accountId)
        val text = token.secret.reveal()

        val captured = ByteArrayOutputStream()
        val originalOut = System.out
        val originalErr = System.err
        try {
            val sink = PrintStream(captured, true, Charsets.UTF_8)
            System.setOut(sink)
            System.setErr(sink)
            testApplication {
                application { authLogoutRoute(tokenStore) }
                client.post("/v1/auth/logout") { header("Authorization", bearer(text)) }
                client.post("/v1/auth/logout") { header("Authorization", bearer(text)) }
            }
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }

        val output = String(captured.toByteArray(), Charsets.UTF_8)
        assertFalse("the presented token must never reach the captured output", output.contains(text))
    }

    // ---------- content type ----------

    @Test
    fun `the 401 body is served as json`() = runBlocking<Unit> {
        register()

        testApplication {
            application { authLogoutRoute(tokenStore) }
            val response = client.post("/v1/auth/logout")
            assertEquals("status", HttpStatusCode.Unauthorized, response.status)
            assertEquals(
                "content type",
                ContentType.Application.Json,
                response.contentType()?.withoutParameters(),
            )
        }
    }

    // ---------- wiring ----------

    @Test
    fun `the route answers at the logout path`() = runBlocking<Unit> {
        val account = register()
        val token = session(account.accountId)

        testApplication {
            application { authLogoutRoute(tokenStore) }
            val response = client.post("/v1/auth/logout") {
                header("Authorization", bearer(token.secret.reveal()))
            }
            assertEquals(
                "the route must answer at /v1/auth/logout",
                HttpStatusCode.NoContent,
                response.status,
            )
        }
    }

    @Test
    fun `an unknown auth path is answered 404`() = runBlocking<Unit> {
        testApplication {
            application { authLogoutRoute(tokenStore) }
            // The Ktor client appends "/" to an empty path, so the route must be
            // registered at the exact path: no trailing slash, no extra segment.
            for (path in listOf("/v1/auth/unknown", "/v1/auth/logout/")) {
                val response = client.post(path)
                assertEquals("'$path' must be 404", HttpStatusCode.NotFound, response.status)
            }
        }
    }

    @Test
    fun `a refused call never revokes the presented token`() = runBlocking<Unit> {
        val account = register()
        val token = session(account.accountId)

        testApplication {
            application { authLogoutRoute(tokenStore) }
            for (value in listOf("Basic ${token.secret.reveal()}", token.secret.reveal(), "Bearer ")) {
                val response = client.post("/v1/auth/logout") { header("Authorization", value) }
                assertEquals("header '$value' must be refused", HttpStatusCode.Unauthorized, response.status)
            }
        }

        assertTrue("a refused call must never revoke the presented token", resolves(token))
    }

    // ---------- the route does not trust an unknown spelling of a live token ----------

    @Test
    fun `an altered spelling of a live token is refused and leaves the original live`() = runBlocking<Unit> {
        val account = register()
        val token = session(account.accountId)
        val altered = TokenFixtures.otherSpellingOfSameBytes(token.secret.reveal())
        assertTrue("the altered spelling must differ from the issued spelling", altered != token.secret.reveal())

        testApplication {
            application { authLogoutRoute(tokenStore) }
            val response = client.post("/v1/auth/logout") {
                header("Authorization", bearer(altered))
            }
            assertEquals("the altered spelling must not log out", HttpStatusCode.Unauthorized, response.status)
        }

        assertTrue("the issued token must still resolve", resolves(token))
        assertFalse("the altered spelling must not resolve", resolvesSecret(altered))
    }

    private companion object {
        const val UNAUTHORIZED_BODY = """{"error":"unauthorized","message":"Invalid or missing token"}"""

        /** 43 alphabet characters, so [dev.breaker.server.syncapi.tokens.TokenSecret.parse] accepts the shape. */
        const val UNKNOWN_TOKEN = "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC"
    }
}
