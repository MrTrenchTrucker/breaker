package dev.breaker.server.syncapi.admin

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.accounts.Role
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.AgentScope
import dev.breaker.server.syncapi.tokens.IssuedToken
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// The held hazards of the agent-token hunt that no other test pins: an admin scoped agent token as the caller,
// the cross-account revoke, the absence of a cap and of a name rule, the label bounds and the expiry bounds.
internal class AgentTokenHuntHazardsTest : TempDatabaseTest() {

    private class Reply(val status: HttpStatusCode, val body: String)

    private lateinit var temp: TempDatabase
    private lateinit var tokenStore: TokenStore

    private val unauthorizedBody = """{"error":"unauthorized","message":"Invalid or missing token"}"""
    private val forbiddenBody = """{"error":"forbidden","message":"Admin role required"}"""
    private val badRequestBody = """{"error":"bad_request","message":"Invalid request body"}"""

    @Before
    fun setUp() {
        temp = openTemp()
        tokenStore = TokenFixtures.tokenStore(temp, AccountFixtures.fixedClock)
    }

    private fun register(name: String, seed: Int) = TokenFixtures.register(temp, name, seed)

    private fun sessionOf(accountId: Long): String =
        TokenFixtures.issuedOf(runBlocking { tokenStore.issueSession(accountId) }).secret.reveal()

    private fun agentOf(accountId: Long, scope: AgentScope): IssuedToken =
        TokenFixtures.issuedOf(runBlocking { tokenStore.issueAgent(accountId, scope, "agent-label", null) })

    private fun send(verb: HttpMethod, path: String, bearerText: String?, body: String? = null): Reply = runBlocking {
        var reply: Reply? = null
        testApplication {
            application {
                adminCreateAgentTokenRoute(tokenStore)
                adminRevokeAgentTokenRoute(tokenStore)
            }
            val response = client.request(path) {
                method = verb
                if (bearerText != null) header("Authorization", "Bearer $bearerText")
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
            reply = Reply(response.status, response.bodyAsText())
        }
        reply!!
    }

    private fun create(bearerText: String?, name: String, owner: String, expiresAt: String? = null): Reply {
        val expiry = if (expiresAt == null) "" else ",\"expires_at\":\"" + expiresAt + "\""
        return send(HttpMethod.Post, "/v1/admin/agent-tokens", bearerText, """{"name":"$name","owner_username":"$owner"$expiry}""")
    }

    private fun revoke(bearerText: String?, tokenId: Long): Reply =
        send(HttpMethod.Delete, "/v1/admin/agent-tokens/$tokenId", bearerText)

    private fun assertStatus(label: String, status: HttpStatusCode, reply: Reply) {
        assertEquals("sync-api: status of $label", status, reply.status)
    }

    // ---------- an admin scoped agent token as the caller ----------

    @Test
    fun `an admin scoped agent token of an admin may create an agent token`() {
        val root = register("root", 1)
        val reply = create(agentOf(root.accountId, AgentScope.ADMIN).secret.reveal(), "staff", "root")
        assertStatus("create by an admin agent token", HttpStatusCode.Created, reply)
        assertTrue("sync-api: a token value is returned", reply.body.contains("\"token\":\""))
    }

    @Test
    fun `an admin scoped agent token may not create once its owner is demoted`() {
        register("root", 1)
        val alice = register("Alice", 2)
        TokenFixtures.setRole(temp, alice.accountId, Role.ADMIN)
        val aliceAgent = agentOf(alice.accountId, AgentScope.ADMIN).secret.reveal()
        assertStatus("while Alice is an admin", HttpStatusCode.Created, create(aliceAgent, "staff", "root"))
        TokenFixtures.setRole(temp, alice.accountId, Role.USER)
        val refused = create(aliceAgent, "staff", "root")
        assertStatus("Alice's old agent token after demotion", HttpStatusCode.Forbidden, refused)
        assertEquals("sync-api: body of the refusal", forbiddenBody, refused.body)
    }

    // ---------- revoke across accounts ----------

    @Test
    fun `an admin revokes another account's agent token, which then stops working, and a second revoke is 204`() {
        val root = register("root", 1)
        val bob = register("Bob", 2)
        val rootText = sessionOf(root.accountId)
        val bobAgent = agentOf(bob.accountId, AgentScope.TRANSCRIBE)
        // Live before the revoke: a transcribe agent token reaches the route and is refused as non-admin (403, not 401).
        assertStatus("bob's agent token while live", HttpStatusCode.Forbidden, create(bobAgent.secret.reveal(), "x", "Bob"))
        assertStatus("first revoke", HttpStatusCode.NoContent, revoke(rootText, bobAgent.tokenId))
        assertEquals("sync-api: bob's agent token is dead", unauthorizedBody, create(bobAgent.secret.reveal(), "x", "Bob").body)
        assertStatus("second revoke", HttpStatusCode.NoContent, revoke(rootText, bobAgent.tokenId))
    }

    @Test
    fun `a revoke of an unknown id is 404 and a revoke by a non admin is 403 and leaves the token live`() {
        val root = register("root", 1)
        val bob = register("Bob", 2)
        val bobAgent = agentOf(bob.accountId, AgentScope.TRANSCRIBE)
        assertStatus("unknown id", HttpStatusCode.NotFound, revoke(sessionOf(root.accountId), 999_999L))
        assertStatus("a user session", HttpStatusCode.Forbidden, revoke(sessionOf(bob.accountId), bobAgent.tokenId))
        assertStatus("the token itself", HttpStatusCode.Forbidden, revoke(bobAgent.secret.reveal(), bobAgent.tokenId))
        assertStatus("bob's token is still live (403, not 401)", HttpStatusCode.Forbidden, create(bobAgent.secret.reveal(), "x", "Bob"))
    }

    // ---------- create: no cap, no name rule, label bounds ----------

    @Test
    fun `an owner may hold five agent tokens with the same name`() {
        val root = register("root", 1)
        val rootText = sessionOf(root.accountId)
        for (n in 1..5) {
            assertStatus("token $n of five", HttpStatusCode.Created, create(rootText, "same-name", "root"))
        }
        assertEquals("sync-api: five live tokens and the session", 6L, TokenFixtures.liveTokenCount(temp, root.accountId))
    }

    @Test
    fun `a label of 64 code points is accepted, 65 is refused, an emoji pair counts as one and an empty label is refused`() {
        val root = register("root", 1)
        val rootText = sessionOf(root.accountId)
        val emoji = "😀"
        assertStatus("64 ASCII", HttpStatusCode.Created, create(rootText, "a".repeat(64), "root"))
        assertStatus("65 ASCII", HttpStatusCode.BadRequest, create(rootText, "a".repeat(65), "root"))
        assertStatus("64 emoji, 128 UTF-16 units", HttpStatusCode.Created, create(rootText, emoji.repeat(64), "root"))
        assertStatus("65 emoji", HttpStatusCode.BadRequest, create(rootText, emoji.repeat(65), "root"))
        assertEquals("sync-api: an empty label body", badRequestBody, create(rootText, "", "root").body)
    }

    // ---------- expires_at bounds ----------

    @Test
    fun `an expiry of exactly now is refused and one millisecond later is accepted`() {
        val root = register("root", 1)
        val rootText = sessionOf(root.accountId)
        val now: Instant = AccountFixtures.fixedClock.instant()
        assertEquals("sync-api: exactly now", badRequestBody, create(rootText, "t", "root", now.toString()).body)
        assertStatus("one millisecond ahead", HttpStatusCode.Created, create(rootText, "t", "root", now.plusMillis(1).toString()))
    }

    @Test
    fun `the last instant that fits a Long millisecond count is accepted and the first year past it is refused`() {
        val root = register("root", 1)
        val rootText = sessionOf(root.accountId)
        assertStatus("the last instant that fits", HttpStatusCode.Created, create(rootText, "t", "root", "+292278994-08-17T07:12:55.807Z"))
        assertStatus("January of that year fits too", HttpStatusCode.Created, create(rootText, "t", "root", "+292278994-01-01T00:00:00Z"))
        assertEquals("sync-api: the first year past it", badRequestBody, create(rootText, "t", "root", "+292278995-01-01T00:00:00Z").body)
    }
}
