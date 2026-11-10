package dev.breaker.server.syncapi.admin

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.accounts.AccountStore
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.handoff.HandoffFixtures
import dev.breaker.server.syncapi.syncApiModule
import dev.breaker.server.syncapi.tokens.AgentScope
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

// GET /v1/admin/health: an admin sees the status and the server's git tag. The status is always ok (the token
// check needs the database), the tag comes from the configuration, and an empty value is answered as "unknown".
internal class AdminHealthRouteTest : TempDatabaseTest() {

    private class Reply(val status: HttpStatusCode, val body: String, val contentType: ContentType?)

    private lateinit var temp: TempDatabase
    private lateinit var accountStore: AccountStore
    private lateinit var tokenStore: TokenStore

    private val unauthorizedBody = """{"error":"unauthorized","message":"Invalid or missing token"}"""
    private val forbiddenBody = """{"error":"forbidden","message":"Admin role required"}"""
    private val unavailableBody = """{"error":"unavailable","message":"The service is unavailable, try again later"}"""

    @Before
    fun setUp() {
        temp = openTemp()
        accountStore = AccountFixtures.newStore(temp)
        tokenStore = TokenFixtures.tokenStore(temp, AccountFixtures.fixedClock)
    }

    private fun register(name: String, seed: Int) = TokenFixtures.register(temp, name, seed)

    private fun sessionOf(accountId: Long): String =
        TokenFixtures.issuedOf(runBlocking { tokenStore.issueSession(accountId) }).secret.reveal()

    private fun agentOf(accountId: Long, scope: AgentScope): String =
        TokenFixtures.issuedOf(runBlocking { tokenStore.issueAgent(accountId, scope, "health-probe", null) }).secret.reveal()

    private fun getHealth(bearerText: String?, gitTag: String = "v2.3.0-rc1"): Reply = runBlocking {
        var reply: Reply? = null
        testApplication {
            application { adminHealthRoute(tokenStore, gitTag) }
            val response = client.get("/v1/admin/health") {
                if (bearerText != null) header("Authorization", "Bearer $bearerText")
            }
            reply = Reply(response.status, response.bodyAsText(), response.contentType()?.withoutParameters())
        }
        reply!!
    }

    private fun assertReply(label: String, status: HttpStatusCode, body: String, reply: Reply) {
        assertEquals("sync-api: status of $label", status, reply.status)
        assertEquals("sync-api: body of $label", body, reply.body)
    }

    private fun demote(accountId: Long) = runBlocking {
        temp.db.transaction { connection ->
            connection.prepareStatement("UPDATE accounts SET role = 'user' WHERE id = ?").use { statement ->
                statement.setLong(1, accountId)
                statement.executeUpdate()
            }
        }
    }

    @Test
    fun `no token and an unknown token get 401`() {
        register("root", 1)
        assertReply("no token", HttpStatusCode.Unauthorized, unauthorizedBody, getHealth(null))
        assertReply("unknown token", HttpStatusCode.Unauthorized, unauthorizedBody, getHealth("x".repeat(43)))
    }

    @Test
    fun `a user session and a transcribe agent token get 403`() {
        register("root", 1)
        val alice = register("Alice", 2)
        assertReply("user session", HttpStatusCode.Forbidden, forbiddenBody, getHealth(sessionOf(alice.accountId)))
        assertReply("transcribe agent", HttpStatusCode.Forbidden, forbiddenBody, getHealth(agentOf(alice.accountId, AgentScope.TRANSCRIBE)))
    }

    @Test
    fun `an admin session gets 200 with ok and the configured tag`() {
        val root = register("root", 1)
        assertReply("admin session", HttpStatusCode.OK, """{"status":"ok","git_tag":"v2.3.0-rc1"}""", getHealth(sessionOf(root.accountId)))
    }

    @Test
    fun `an empty configured tag is answered as unknown`() {
        val root = register("root", 1)
        assertReply("empty tag", HttpStatusCode.OK, """{"status":"ok","git_tag":"unknown"}""", getHealth(sessionOf(root.accountId), gitTag = ""))
    }

    @Test
    fun `an admin scoped agent token of an admin gets 200`() {
        val root = register("root", 1)
        assertReply("admin agent token", HttpStatusCode.OK, """{"status":"ok","git_tag":"v2.3.0-rc1"}""", getHealth(agentOf(root.accountId, AgentScope.ADMIN)))
    }

    @Test
    fun `a demoted admin gets 403 on the live session and on its admin agent token`() {
        register("root", 1)
        val alice = register("Alice", 2)
        // A second admin: promote Alice directly, then demote her again.
        runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement("UPDATE accounts SET role = 'admin' WHERE id = ?").use { statement ->
                    statement.setLong(1, alice.accountId)
                    statement.executeUpdate()
                }
            }
        }
        val text = sessionOf(alice.accountId)
        val agent = agentOf(alice.accountId, AgentScope.ADMIN)
        assertReply("while an admin", HttpStatusCode.OK, """{"status":"ok","git_tag":"v2.3.0-rc1"}""", getHealth(text))
        demote(alice.accountId)
        assertReply("old session after demotion", HttpStatusCode.Forbidden, forbiddenBody, getHealth(text))
        assertReply("old agent token after demotion", HttpStatusCode.Forbidden, forbiddenBody, getHealth(agent))
    }

    @Test
    fun `a failed token check answers the fixed 503`() {
        val root = register("root", 1)
        val text = sessionOf(root.accountId)
        temp.closeKeepingFiles()
        assertReply("closed database", HttpStatusCode.ServiceUnavailable, unavailableBody, getHealth(text))
    }

    @Test
    fun `the real application serves the route with the tag it was given`() {
        val root = register("root", 1)
        val text = sessionOf(root.accountId)
        var reply: Reply? = null
        runBlocking {
            testApplication {
                application {
                    syncApiModule(
                        accountStore,
                        tokenStore,
                        SyncRouteTestSupport.newStore(temp),
                        HandoffFixtures.handoffStore(temp, tokenStore, AccountFixtures.fixedClock),
                        gitTag = "build-7",
                    )
                }
                val response = client.get("/v1/admin/health") { header("Authorization", "Bearer $text") }
                reply = Reply(response.status, response.bodyAsText(), response.contentType()?.withoutParameters())
            }
        }
        assertReply("through the application module", HttpStatusCode.OK, """{"status":"ok","git_tag":"build-7"}""", reply!!)
    }

    @Test
    fun `every answer of the route is json`() {
        val root = register("root", 1)
        val alice = register("Alice", 2)
        val rootText = sessionOf(root.accountId)
        val aliceText = sessionOf(alice.accountId)
        assertEquals("content type of the 200", ContentType.Application.Json, getHealth(rootText).contentType)
        assertEquals("content type of the 401", ContentType.Application.Json, getHealth(null).contentType)
        assertEquals("content type of the 403", ContentType.Application.Json, getHealth(aliceText).contentType)
        temp.closeKeepingFiles()
        assertEquals("content type of the 503", ContentType.Application.Json, getHealth(rootText).contentType)
    }
}
