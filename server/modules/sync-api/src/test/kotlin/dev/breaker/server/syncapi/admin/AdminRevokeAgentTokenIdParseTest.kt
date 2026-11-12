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
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

// The id of DELETE /v1/admin/agent-tokens/{id} is read with toLongOrNull. Anything that is not
// a plain Long is refused 404 with the exact not-found body (nothing is revoked). The one
// exception is the empty id, which Ktor's path matcher never hands to the handler, so it is
// pinned to its status alone.
internal class AdminRevokeAgentTokenIdParseTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var tokenStore: TokenStore
    private lateinit var adminSession: IssuedToken
    private var adminAccountId = 0L

    @Before
    fun setUp() {
        temp = openTemp()
        tokenStore = TokenFixtures.tokenStore(temp, AccountFixtures.fixedClock)
        val admin = TokenFixtures.register(temp, "alice", 1)
        adminAccountId = admin.accountId
        adminSession = TokenFixtures.issuedOf(runBlocking { tokenStore.issueSession(admin.accountId) })
    }

    private fun bearer(text: String): String = "Bearer $text"

    private fun revoke(id: String, check: suspend (HttpResponse) -> Unit) = runBlocking {
        testApplication {
            application { adminRevokeAgentTokenRoute(tokenStore) }
            check(
                client.delete("/v1/admin/agent-tokens/$id") {
                    header("Authorization", bearer(adminSession.secret.reveal()))
                },
            )
        }
    }

    private suspend fun assertNotFound404(label: String, response: HttpResponse) {
        assertEquals("sync-api: $label status", HttpStatusCode.NotFound, response.status)
        assertEquals("sync-api: $label body", NOT_FOUND_BODY, response.bodyAsText())
    }

    @Test
    fun `a non numeric id is refused with 404 and the exact body`() {
        revoke("abc") { assertNotFound404("a non numeric id", it) }
    }

    @Test
    fun `a decimal id is refused with 404 and the exact body`() {
        revoke("1.5") { assertNotFound404("a decimal id", it) }
    }

    @Test
    fun `an overflowing id is refused with 404 and the exact body`() {
        revoke("99999999999999999999") { assertNotFound404("an overflowing id", it) }
    }

    @Test
    fun `a negative id is refused with 404 and the exact body and revokes nothing`() {
        val target = TokenFixtures.issuedOf(runBlocking {
            tokenStore.issueAgent(adminAccountId, AgentScope.TRANSCRIBE, "agent-label", null)
        })
        revoke("-1") { assertNotFound404("a negative id", it) }
        assertEquals(
            "sync-api: the untouched token still resolves",
            true,
            runBlocking { tokenStore.resolve(target.secret.reveal()) != null },
        )
    }

    // The empty id never reaches the handler (the path pattern does not match a missing
    // segment), so it is pinned to its status alone, per the ruling.
    @Test
    fun `an empty id is refused with 404 and no body is asserted`() {
        revoke("") {
            assertEquals("sync-api: an empty id status", HttpStatusCode.NotFound, it.status)
        }
    }
}
