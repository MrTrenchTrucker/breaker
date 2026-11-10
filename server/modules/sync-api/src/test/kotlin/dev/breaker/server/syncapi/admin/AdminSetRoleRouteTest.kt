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
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

// POST /v1/admin/roles: an admin grants or revokes another account's admin role. The first account
// registered is the admin; the last admin can never be demoted.
internal class AdminSetRoleRouteTest : TempDatabaseTest() {

    private class Reply(val status: HttpStatusCode, val body: String)

    private lateinit var temp: TempDatabase
    private lateinit var accountStore: AccountStore
    private lateinit var tokenStore: TokenStore

    private val unauthorizedBody = """{"error":"unauthorized","message":"Invalid or missing token"}"""
    private val forbiddenBody = """{"error":"forbidden","message":"Admin role required"}"""
    private val notFoundBody = """{"error":"not_found","message":"Account not found"}"""
    private val lastAdminBody = """{"error":"conflict","message":"The last admin cannot be demoted"}"""
    private val unavailableBody = """{"error":"unavailable","message":"The service is unavailable, try again later"}"""
    private val tooLargeBody = """{"error":"payload_too_large","message":"Request body exceeds maximum allowed size"}"""
    private val badRequestBody = """{"error":"bad_request","message":"Invalid request body"}"""

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
        TokenFixtures.issuedOf(runBlocking { tokenStore.issueAgent(accountId, scope, "label", null) }).secret.reveal()

    private fun roleBody(username: String, admin: Boolean): String = """{"username":"$username","admin":$admin}"""

    private fun postRoles(bearerText: String?, body: String): Reply = runBlocking {
        var reply: Reply? = null
        testApplication {
            application { adminSetRoleRoute(tokenStore, accountStore) }
            val response = client.post("/v1/admin/roles") {
                if (bearerText != null) header("Authorization", "Bearer $bearerText")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            reply = Reply(response.status, response.bodyAsText())
        }
        reply!!
    }

    // The same request, served by the real application module, so only the mount in the application can answer it.
    private fun postRolesMounted(bearerText: String, body: String): Reply = runBlocking {
        var reply: Reply? = null
        testApplication {
            application {
                syncApiModule(
                    accountStore,
                    tokenStore,
                    SyncRouteTestSupport.newStore(temp),
                    HandoffFixtures.handoffStore(temp, tokenStore, AccountFixtures.fixedClock),
                )
            }
            val response = client.post("/v1/admin/roles") {
                header("Authorization", "Bearer $bearerText")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            reply = Reply(response.status, response.bodyAsText())
        }
        reply!!
    }

    private fun assertReply(label: String, status: HttpStatusCode, body: String, reply: Reply) {
        assertEquals("sync-api: status of $label", status, reply.status)
        assertEquals("sync-api: body of $label", body, reply.body)
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

    private fun adminCount(): Long = runBlocking {
        temp.db.transaction { connection ->
            connection.prepareStatement("SELECT count(*) FROM accounts WHERE role = 'admin'").use { statement ->
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "sync-api test: no count row" }
                    rows.getLong(1)
                }
            }
        }
    }

    private fun markResetPending(accountId: Long) = runBlocking {
        temp.db.transaction { connection ->
            connection.prepareStatement("UPDATE accounts SET reset_pending = 1 WHERE id = ?").use { statement ->
                statement.setLong(1, accountId)
                statement.executeUpdate()
            }
        }
    }

    @Test
    fun `no token and an unknown token get 401 and change nothing`() {
        register("root", 1)
        val alice = register("Alice", 2)
        assertReply("no token", HttpStatusCode.Unauthorized, unauthorizedBody, postRoles(null, roleBody("Alice", true)))
        assertReply("unknown token", HttpStatusCode.Unauthorized, unauthorizedBody, postRoles("x".repeat(43), roleBody("Alice", true)))
        assertEquals("sync-api: Alice is still a user", "user", roleOf(alice.accountId))
    }

    @Test
    fun `a user session and a transcribe agent token get 403 and change nothing`() {
        register("root", 1)
        val alice = register("Alice", 2)
        val bob = register("Bob", 3)
        assertReply("user session", HttpStatusCode.Forbidden, forbiddenBody, postRoles(sessionOf(bob.accountId), roleBody("Alice", true)))
        assertReply("agent token", HttpStatusCode.Forbidden, forbiddenBody, postRoles(agentOf(alice.accountId, AgentScope.TRANSCRIBE), roleBody("Alice", true)))
        assertEquals("sync-api: Alice is still a user", "user", roleOf(alice.accountId))
    }

    @Test
    fun `an admin grants the admin role and the answer names the stored username`() {
        val root = register("root", 1)
        val alice = register("Alice", 2)
        val reply = postRoles(sessionOf(root.accountId), roleBody("alice", true))
        assertReply("grant typed in lower case", HttpStatusCode.OK, """{"username":"Alice","role":"admin"}""", reply)
        assertEquals("sync-api: Alice is an admin now", "admin", roleOf(alice.accountId))
    }

    @Test
    fun `an admin revokes the admin role of another admin`() {
        val root = register("root", 1)
        val alice = register("Alice", 2)
        val text = sessionOf(root.accountId)
        postRoles(text, roleBody("Alice", true))
        val reply = postRoles(text, roleBody("Alice", false))
        assertReply("revoke", HttpStatusCode.OK, """{"username":"Alice","role":"user"}""", reply)
        assertEquals("sync-api: Alice is a user again", "user", roleOf(alice.accountId))
        assertEquals("sync-api: root is still the admin", 1L, adminCount())
    }

    @Test
    fun `granting an admin and revoking a user are both answered 200 and change nothing`() {
        val root = register("root", 1)
        val alice = register("Alice", 2)
        val text = sessionOf(root.accountId)
        assertReply("revoke a user", HttpStatusCode.OK, """{"username":"Alice","role":"user"}""", postRoles(text, roleBody("Alice", false)))
        assertReply("grant an admin", HttpStatusCode.OK, """{"username":"root","role":"admin"}""", postRoles(text, roleBody("root", true)))
        assertEquals("sync-api: Alice unchanged", "user", roleOf(alice.accountId))
        assertEquals("sync-api: one admin", 1L, adminCount())
    }

    @Test
    fun `the last admin cannot be demoted and one admin always remains`() {
        val root = register("root", 1)
        register("Alice", 2)
        val text = sessionOf(root.accountId)
        assertReply("self-demotion of the only admin", HttpStatusCode.Conflict, lastAdminBody, postRoles(text, roleBody("root", false)))
        assertEquals("sync-api: root is still an admin", "admin", roleOf(root.accountId))
        postRoles(text, roleBody("Alice", true))
        assertReply("demote Alice", HttpStatusCode.OK, """{"username":"Alice","role":"user"}""", postRoles(text, roleBody("Alice", false)))
        assertReply("demote root, the last one again", HttpStatusCode.Conflict, lastAdminBody, postRoles(text, roleBody("root", false)))
        assertEquals("sync-api: one admin remains", 1L, adminCount())
    }

    @Test
    fun `two admins demoting each other leave one admin`() {
        val root = register("root", 1)
        val alice = register("Alice", 2)
        val rootText = sessionOf(root.accountId)
        postRoles(rootText, roleBody("Alice", true))
        val aliceText = sessionOf(alice.accountId)
        assertReply("root demotes Alice", HttpStatusCode.OK, """{"username":"Alice","role":"user"}""", postRoles(rootText, roleBody("Alice", false)))
        assertReply("Alice, now a user, tries to demote root", HttpStatusCode.Forbidden, forbiddenBody, postRoles(aliceText, roleBody("root", false)))
        assertEquals("sync-api: root is the admin", "admin", roleOf(root.accountId))
    }

    @Test
    fun `a demoted admin loses the admin role on the live session at once`() {
        val root = register("root", 1)
        val alice = register("Alice", 2)
        val rootText = sessionOf(root.accountId)
        postRoles(rootText, roleBody("Alice", true))
        val aliceText = sessionOf(alice.accountId)
        val aliceAgent = agentOf(alice.accountId, AgentScope.ADMIN)
        assertReply("Alice as admin", HttpStatusCode.OK, """{"username":"root","role":"admin"}""", postRoles(aliceText, roleBody("root", true)))
        assertReply("Alice's admin agent token", HttpStatusCode.OK, """{"username":"root","role":"admin"}""", postRoles(aliceAgent, roleBody("root", true)))
        postRoles(rootText, roleBody("Alice", false))
        assertReply("Alice's old session", HttpStatusCode.Forbidden, forbiddenBody, postRoles(aliceText, roleBody("root", true)))
        assertReply("Alice's old agent token", HttpStatusCode.Forbidden, forbiddenBody, postRoles(aliceAgent, roleBody("root", true)))
    }

    @Test
    fun `an admin may demote itself while another admin remains and is then refused`() {
        val root = register("root", 1)
        register("Alice", 2)
        val rootText = sessionOf(root.accountId)
        postRoles(rootText, roleBody("Alice", true))
        assertReply("self-demotion", HttpStatusCode.OK, """{"username":"root","role":"user"}""", postRoles(rootText, roleBody("root", false)))
        assertReply("the next call", HttpStatusCode.Forbidden, forbiddenBody, postRoles(rootText, roleBody("root", true)))
    }

    @Test
    fun `an account waiting for a reset can be promoted`() {
        val root = register("root", 1)
        val alice = register("Alice", 2)
        markResetPending(alice.accountId)
        assertReply("promotion of a pending account", HttpStatusCode.OK, """{"username":"Alice","role":"admin"}""", postRoles(sessionOf(root.accountId), roleBody("Alice", true)))
        assertEquals("sync-api: Alice is an admin", "admin", roleOf(alice.accountId))
    }

    @Test
    fun `an unknown name and a name that is no username answer 404`() {
        val root = register("root", 1)
        val text = sessionOf(root.accountId)
        assertReply("unknown", HttpStatusCode.NotFound, notFoundBody, postRoles(text, roleBody("Nobody", true)))
        for (name in listOf("", "a b", "a@b", "x".repeat(200))) {
            assertReply("name '$name'", HttpStatusCode.NotFound, notFoundBody, postRoles(text, roleBody(name, true)))
        }
        assertEquals("sync-api: nothing changed", 1L, adminCount())
    }

    @Test
    fun `a body that is not exactly a username string and an admin boolean answers 400`() {
        val root = register("root", 1)
        register("Alice", 2)
        val text = sessionOf(root.accountId)
        val bodies = listOf(
            "", "[]", "null", "{}",
            """{"username":"Alice"}""",
            """{"admin":true}""",
            """{"username":"Alice","admin":"true"}""",
            """{"username":"Alice","admin":1}""",
            """{"username":"Alice","admin":null}""",
            """{"username":5,"admin":true}""",
            """{"username":"Alice","admin":true,"extra":1}""",
            """{"username":"Alice","username":"Alice","admin":true}""",
            """{"username":"Alice","admin":true} x""",
        )
        for (body in bodies) {
            assertReply("body '$body'", HttpStatusCode.BadRequest, badRequestBody, postRoles(text, body))
        }
        assertEquals("sync-api: nothing changed", 1L, adminCount())
    }

    @Test
    fun `the real application serves the route`() {
        val root = register("root", 1)
        val alice = register("Alice", 2)
        val reply = postRolesMounted(sessionOf(root.accountId), roleBody("Alice", true))
        assertReply("grant through the application module", HttpStatusCode.OK, """{"username":"Alice","role":"admin"}""", reply)
        assertEquals("sync-api: Alice is an admin", "admin", roleOf(alice.accountId))
    }

    @Test
    fun `a body over the 4096 byte cap answers 413 and changes nothing`() {
        val root = register("root", 1)
        val alice = register("Alice", 2)
        val tooBig = """{"username":"${"x".repeat(5000)}","admin":true}"""
        assertReply("oversized body", HttpStatusCode.PayloadTooLarge, tooLargeBody, postRoles(sessionOf(root.accountId), tooBig))
        assertEquals("sync-api: Alice is still a user", "user", roleOf(alice.accountId))
    }

    @Test
    fun `a store failure while the role is written answers the fixed 503 and nothing of the failure`() {
        val root = register("root", 1)
        val alice = register("Alice", 2)
        val text = sessionOf(root.accountId)
        runBlocking {
            temp.db.transaction { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE TRIGGER no_role_writes BEFORE UPDATE OF role ON accounts BEGIN SELECT RAISE(ABORT, 'refused-marker'); END")
                }
            }
        }
        val reply = postRoles(text, roleBody("Alice", true))
        assertReply("role write failure", HttpStatusCode.ServiceUnavailable, unavailableBody, reply)
        assertFalse("sync-api: nothing of the failure in the body", reply.body.contains("refused-marker"))
        assertEquals("sync-api: Alice is still a user", "user", roleOf(alice.accountId))
    }

    @Test
    fun `a failed token check answers the fixed 503`() {
        val root = register("root", 1)
        val text = sessionOf(root.accountId)
        temp.closeKeepingFiles()
        assertReply("closed database", HttpStatusCode.ServiceUnavailable, unavailableBody, postRoles(text, roleBody("root", true)))
    }
}
