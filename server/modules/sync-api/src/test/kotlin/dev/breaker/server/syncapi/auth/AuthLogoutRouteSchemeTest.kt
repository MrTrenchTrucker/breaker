package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.IssuedToken
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import io.ktor.client.request.header
import io.ktor.client.request.post
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
 * Scheme-only tests for [authLogoutRoute] -- `POST /v1/auth/logout`.
 *
 * The bearer scheme word is case-insensitive (RFC 6750), but it must be the
 * ASCII word "Bearer" -- a Cyrillic look-alike such as "B\u0435\u0430r\u0435r" (B + U+0435 + U+0430
 * + r + U+0435 + r) is NOT a match and is refused with 401, so the live token
 * survives.
 */
internal class AuthLogoutRouteSchemeTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var tokenStore: TokenStore

    @Before
    fun setUp() {
        temp = openTemp()
        tokenStore = TokenFixtures.tokenStore(temp, AccountFixtures.fixedClock)
    }

    // ---------- helpers (copied verbatim from AuthLogoutRouteTest) ----------

    private fun register(name: String = "alice", seed: Int = 1) =
        TokenFixtures.register(temp, name, seed)

    private fun session(accountId: Long): IssuedToken = TokenFixtures.issuedOf(runBlocking { tokenStore.issueSession(accountId) })


    private fun resolves(token: IssuedToken): Boolean =
        runBlocking { tokenStore.resolve(token.secret.reveal()) } != null

    // ---------- Authorization scheme parsing ----------

    @Test
    fun `a Cyrillic Bearer look-alike is refused with 401 and leaves the token live`() = runBlocking<Unit> {
        val account = register()
        val token = session(account.accountId)
        assertTrue("a fresh session token must resolve before the refused call", resolves(token))

        // "B\u0435\u0430r\u0435r" with Cyrillic e (U+0435) and a (U+0430) -- looks like "Bearer"
        // but equals("Bearer", ignoreCase = true) is false.
        val lookalike = "B\u0435\u0430r\u0435r ${token.secret.reveal()}"

        testApplication {
            application { authLogoutRoute(tokenStore) }
            val response = client.post("/v1/auth/logout") {
                header("Authorization", lookalike)
            }
            assertEquals("a Cyrillic look-alike scheme must be refused", HttpStatusCode.Unauthorized, response.status)
            assertEquals("the 401 body is fixed", UNAUTHORIZED_BODY, response.bodyAsText())
        }

        assertTrue("a refused call must not revoke the live token", resolves(token))
    }

    @Test
    fun `the ASCII Bearer scheme is accepted in any case and logs out`() = runBlocking<Unit> {
        val account = register()

        for (scheme in listOf("Bearer", "bearer", "BEARER")) {
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

    private companion object {
        const val UNAUTHORIZED_BODY = """{"error":"unauthorized","message":"Invalid or missing token"}"""
    }
}
