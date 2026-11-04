package dev.breaker.server.syncapi.admin

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.accounts.AccountStore
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.AgentScope
import dev.breaker.server.syncapi.tokens.IssuedToken
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * the admin routes answer 503 Service Unavailable when the store fails, instead of
 * letting the exception escape to the server. The closed database is the realistic store failure
 * (the same IllegalStateException the handoff routes already answer 503 for); the exception text
 * never reaches a body. A cancellation is still rethrown (it is a shutdown, not a store error).
 */
internal class AdminStoreFailure503Test : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var tokenStore: TokenStore
    private lateinit var accountStore: AccountStore

    @Before
    fun setUp() {
        temp = openTemp()
        tokenStore = TokenFixtures.tokenStore(temp, AccountFixtures.fixedClock)
        accountStore = AccountFixtures.newStore(temp)
    }

    private fun register(name: String, seed: Int) = TokenFixtures.register(temp, name, seed)

    private fun session(accountId: Long): IssuedToken =
        TokenFixtures.issuedOf(runBlocking { tokenStore.issueSession(accountId) })

    private fun agent(accountId: Long): IssuedToken =
        TokenFixtures.issuedOf(runBlocking { tokenStore.issueAgent(accountId, AgentScope.TRANSCRIBE, "agent-label", null) })

    private fun bearer(issued: IssuedToken): String = "Bearer " + issued.secret.reveal()

    @Test
    fun `a store failure on the revoke route answers 503 with the plain body`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)
        val target = agent(admin.accountId)

        // Close the store: the next resolve throws IllegalStateException, which the route answers 503.
        temp.closeKeepingFiles()

        testApplication {
            application { adminRevokeAgentTokenRoute(tokenStore) }
            val response = client.delete("/v1/admin/agent-tokens/${target.tokenId}") {
                header("Authorization", bearer(adminSession))
            }
            assertEquals("status", HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("body", SERVICE_UNAVAILABLE_BODY, response.bodyAsText())
        }
    }

    @Test
    fun `a store failure on the wrong-pw-limit route answers 503 with the plain body`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)

        // Close the store: the next resolve (the first store call in the route) throws, answered 503.
        temp.closeKeepingFiles()

        testApplication {
            application { adminWrongPwLimitRoute(tokenStore, accountStore.settings) }
            val response = client.put("/v1/admin/wrong-pw-limit") {
                header("Authorization", bearer(adminSession))
                setBody("""{"enabled":true}""")
            }
            assertEquals("status", HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("body", SERVICE_UNAVAILABLE_BODY, response.bodyAsText())
        }
    }
}
