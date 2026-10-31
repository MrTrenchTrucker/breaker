package dev.breaker.server.syncapi.admin

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.AgentScope
import dev.breaker.server.syncapi.tokens.IssuedToken
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import io.ktor.client.request.delete
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
 * Scheme-only tests for [adminRevokeAgentTokenRoute] -- `DELETE /v1/admin/agent-tokens/{id}`.
 *
 * The bearer scheme word is case-insensitive, but it must be the ASCII word
 * "bearer" -- a Cyrillic look-alike such as "B\u0435\u0430r\u0435r" is NOT a match and is
 * refused with 401, so the agent token stays live.
 */
internal class AdminRevokeAgentTokenRouteSchemeTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var tokenStore: TokenStore

    @Before
    fun setUp() {
        temp = openTemp()
        tokenStore = TokenFixtures.tokenStore(temp, AccountFixtures.fixedClock)
    }

    // ---------- helpers (copied verbatim from AdminRevokeAgentTokenRouteTest) ----------

    private fun register(name: String, seed: Int) = TokenFixtures.register(temp, name, seed)

    private fun session(accountId: Long): IssuedToken = TokenFixtures.issuedOf(runBlocking { tokenStore.issueSession(accountId) })

    private fun agent(accountId: Long, scope: AgentScope = AgentScope.TRANSCRIBE): IssuedToken =
        TokenFixtures.issuedOf(runBlocking { tokenStore.issueAgent(accountId, scope, "agent-label", null) })


    private fun resolves(token: IssuedToken): Boolean =
        runBlocking { tokenStore.resolve(token.secret.reveal()) } != null

    // ---------- Authorization scheme parsing ----------

    @Test
    fun `a Cyrillic Bearer look-alike is refused with 401 and leaves the agent token live`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)
        val target = agent(admin.accountId)
        assertTrue("the agent token must resolve before the refused call", resolves(target))

        // "B\u0435\u0430r\u0435r" with Cyrillic e (U+0435) and a (U+0430) -- looks like "Bearer"
        // but equals("bearer", ignoreCase = true) is false.
        val lookalike = "B\u0435\u0430r\u0435r ${adminSession.secret.reveal()}"

        testApplication {
            application { adminRevokeAgentTokenRoute(tokenStore) }
            val response = client.delete("/v1/admin/agent-tokens/${target.tokenId}") {
                header("Authorization", lookalike)
            }
            assertEquals("a Cyrillic look-alike scheme must be refused", HttpStatusCode.Unauthorized, response.status)
            assertEquals("the 401 body is fixed", UNAUTHORIZED_BODY, response.bodyAsText())
        }

        assertTrue("a refused call must not revoke the agent token", resolves(target))
    }

    @Test
    fun `the ASCII bearer scheme is accepted in any case and revokes`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)
        val token = adminSession.secret.reveal()

        testApplication {
            application { adminRevokeAgentTokenRoute(tokenStore) }
            for (scheme in listOf("bearer", "Bearer", "BEARER")) {
                val target = agent(admin.accountId)
                assertTrue("the agent token must resolve before scheme '$scheme'", resolves(target))
                val response = client.delete("/v1/admin/agent-tokens/${target.tokenId}") {
                    header("Authorization", "$scheme $token")
                }
                assertEquals("scheme '$scheme' must be accepted", HttpStatusCode.NoContent, response.status)
                assertFalse("the agent token must be revoked after scheme '$scheme'", resolves(target))
            }
        }
    }
}
