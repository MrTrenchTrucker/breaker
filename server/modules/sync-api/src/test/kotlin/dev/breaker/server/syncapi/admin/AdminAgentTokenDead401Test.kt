package dev.breaker.server.syncapi.admin

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.tokens.AgentScope
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.IssuedToken
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

// A dead bearer token (expired or revoked) is refused exactly like a missing one
// on the admin agent-token routes: the same 401 and the same fixed body, so the
// answer discloses nothing about which it was.
internal class AdminAgentTokenDead401Test : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var tokenStore: TokenStore

    @Before
    fun setUp() {
        temp = openTemp()
        tokenStore = TokenFixtures.tokenStore(temp, AccountFixtures.fixedClock)
    }

    private fun register(name: String, seed: Int) = TokenFixtures.register(temp, name, seed)

    private fun session(accountId: Long): IssuedToken =
        TokenFixtures.issuedOf(runBlocking { tokenStore.issueSession(accountId) })

    private fun bearer(text: String): String = "Bearer $text"

    private fun createResponse(
        bearerText: String,
        mount: io.ktor.server.application.Application.() -> Unit,
        check: suspend (HttpResponse) -> Unit,
    ) =
        runBlocking {
            testApplication {
                application { mount() }
                check(
                    client.post("/v1/admin/agent-tokens") {
                        header("Authorization", bearerText)
                        contentType(ContentType.Application.Json)
                        setBody("""{"name":"x","owner_username":"alice"}""")
                    },
                )
            }
        }

    private fun revokeResponse(
        bearerText: String,
        tokenId: Long,
        check: suspend (HttpResponse) -> Unit,
    ) =
        runBlocking {
            testApplication {
                application { adminRevokeAgentTokenRoute(tokenStore) }
                check(
                    client.delete("/v1/admin/agent-tokens/$tokenId") {
                        header("Authorization", bearerText)
                    },
                )
            }
        }

    private suspend fun assertUnauthorized401(label: String, response: HttpResponse) {
        assertEquals("sync-api: $label status", HttpStatusCode.Unauthorized, response.status)
        assertEquals("sync-api: $label body", UNAUTHORIZED_BODY, response.bodyAsText())
        assertEquals(
            "sync-api: $label content type",
            ContentType.Application.Json,
            response.contentType()?.withoutParameters(),
        )
    }

    // ---------- item 1 create ----------

    @Test
    fun `an expired admin session token is refused with 401 and the exact body on create`() {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)
        TokenFixtures.expireSession(temp, adminSession.tokenId)

        createResponse(bearer(adminSession.secret.reveal()), { adminCreateAgentTokenRoute(tokenStore) }) {
            assertUnauthorized401("an expired token on create", it)
        }
    }

    @Test
    fun `a revoked admin session token is refused with 401 and the exact body on create`() {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)
        runBlocking { tokenStore.revokeSession(adminSession.tokenId) }

        createResponse(bearer(adminSession.secret.reveal()), { adminCreateAgentTokenRoute(tokenStore) }) {
            assertUnauthorized401("a revoked token on create", it)
        }
    }

    // ---------- item 2 revoke ----------

    @Test
    fun `an expired admin session token is refused with 401 and the exact body on revoke`() {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)
        val target = TokenFixtures.issuedOf(runBlocking {
            tokenStore.issueAgent(admin.accountId, AgentScope.TRANSCRIBE, "agent-label", null)
        })
        TokenFixtures.expireSession(temp, adminSession.tokenId)

        revokeResponse(bearer(adminSession.secret.reveal()), target.tokenId) {
            assertUnauthorized401("an expired token on revoke", it)
        }
    }

    @Test
    fun `a revoked admin session token is refused with 401 and the exact body on revoke`() {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)
        val target = TokenFixtures.issuedOf(runBlocking {
            tokenStore.issueAgent(admin.accountId, AgentScope.TRANSCRIBE, "agent-label", null)
        })
        runBlocking { tokenStore.revokeSession(adminSession.tokenId) }

        revokeResponse(bearer(adminSession.secret.reveal()), target.tokenId) {
            assertUnauthorized401("a revoked token on revoke", it)
        }
    }
}
