package dev.breaker.server.syncapi.admin

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.accounts.AccountStore
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.AgentScope
import dev.breaker.server.syncapi.tokens.IssuedToken
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

// A TRANSCRIBE-scoped agent token acts at its effective scope, which is transcribe even
// while its owner is an admin. It is therefore refused 403 with the exact forbidden body
// on every admin route (create, revoke, roles, health), and nothing is changed.
internal class AdminAgentTokenTranscribeAdminOwner403Test : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var tokenStore: TokenStore
    private lateinit var accountStore: AccountStore
    private lateinit var adminOwner: IssuedToken
    private var adminAccountId = 0L

    @Before
    fun setUp() {
        temp = openTemp()
        tokenStore = TokenFixtures.tokenStore(temp, AccountFixtures.fixedClock)
        accountStore = AccountFixtures.newStore(temp)
        val admin = TokenFixtures.register(temp, "alice", 1) // first account is the admin
        adminAccountId = admin.accountId
        adminOwner = TokenFixtures.issuedOf(runBlocking {
            tokenStore.issueAgent(admin.accountId, AgentScope.TRANSCRIBE, "agent-label", null)
        })
    }

    private fun roleOf(accountId: Long): String = runBlocking {
        temp.db.transaction { connection ->
            connection.prepareStatement("SELECT role FROM accounts WHERE id = ?").use { statement ->
                statement.setLong(1, accountId)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "sync-api test: no account with id $accountId" }
                    rows.getString(1)
                }
            }
        }
    }

    private fun send(
        verb: HttpMethod,
        path: String,
        check: suspend (HttpResponse) -> Unit,
    ) = runBlocking {
        testApplication {
            application {
                adminCreateAgentTokenRoute(tokenStore)
                adminRevokeAgentTokenRoute(tokenStore)
                adminSetRoleRoute(tokenStore, accountStore)
                adminHealthRoute(tokenStore, "v2.3.0-rc1")
            }
            check(
                client.request(path) {
                    method = verb
                    header("Authorization", "Bearer " + adminOwner.secret.reveal())
                    if (verb == HttpMethod.Post) {
                        contentType(ContentType.Application.Json)
                        setBody("""{"name":"x","owner_username":"alice"}""")
                    }
                },
            )
        }
    }

    private suspend fun assertForbidden403(label: String, response: HttpResponse) {
        assertEquals("sync-api: $label status", HttpStatusCode.Forbidden, response.status)
        assertEquals("sync-api: $label body", FORBIDDEN_BODY, response.bodyAsText())
    }

    @Test
    fun `a transcribe token of an admin owner is refused with 403 on every admin route`() {
        send(HttpMethod.Post, "/v1/admin/agent-tokens") { assertForbidden403("create", it) }
        send(HttpMethod.Delete, "/v1/admin/agent-tokens/123") { assertForbidden403("revoke", it) }
        send(HttpMethod.Post, "/v1/admin/roles") { assertForbidden403("roles", it) }
        send(HttpMethod.Get, "/v1/admin/health") { assertForbidden403("health", it) }
    }

    @Test
    fun `the refused transcribe token still resolves and the owner keeps the admin role`() {
        send(HttpMethod.Get, "/v1/admin/health") { /* no response assertion */ }
        assertEquals(
            "sync-api: the transcribe token is still live",
            true,
            runBlocking { tokenStore.resolve(adminOwner.secret.reveal()) != null },
        )
        assertEquals(
            "sync-api: the owner is still an admin",
            "admin",
            roleOf(adminAccountId),
        )
    }
}
