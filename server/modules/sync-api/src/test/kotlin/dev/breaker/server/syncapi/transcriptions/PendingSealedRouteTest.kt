package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.db.PendingSealedStore
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.handoff.HandoffFixtures
import dev.breaker.server.syncapi.syncApiModule
import dev.breaker.server.syncapi.tokens.AgentScope
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import io.ktor.client.request.get
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

// GET /v1/sync/pending-sealed: a user session only, the owner's own rows, created_at then id order, an empty
// store answers [], the fixed 503 on a failure. The tests insert rows directly.
internal class PendingSealedRouteTest : TempDatabaseTest() {

    private class Reply(val status: HttpStatusCode, val body: String, val contentType: String? = null)

    private lateinit var temp: TempDatabase
    private lateinit var tokenStore: TokenStore

    private val sessionRequiredBody = """{"error":"unauthorized","message":"Invalid or missing user session token"}"""
    private val agentRefusedBody = """{"error":"forbidden","message":"User session token required; an agent token is refused"}"""
    private val unavailableBody = """{"error":"unavailable","message":"The service is unavailable, try again later"}"""

    @Before
    fun setUp() {
        temp = openTemp()
        tokenStore = TokenFixtures.tokenStore(temp, AccountFixtures.fixedClock)
    }

    private fun register(name: String, seed: Int) = TokenFixtures.register(temp, name, seed)

    private fun sessionOf(accountId: Long): String =
        TokenFixtures.issuedOf(runBlocking { tokenStore.issueSession(accountId) }).secret.reveal()

    private fun agentOf(accountId: Long, scope: AgentScope): String =
        TokenFixtures.issuedOf(runBlocking { tokenStore.issueAgent(accountId, scope, "label", null) }).secret.reveal()

    private fun get(bearerText: String?, query: String = ""): Reply = runBlocking {
        var reply: Reply? = null
        testApplication {
            application { pendingSealedRoute(tokenStore, PendingSealedStore(temp.db)) }
            val response = client.get("/v1/sync/pending-sealed$query") {
                if (bearerText != null) header("Authorization", "Bearer $bearerText")
            }
            reply = Reply(response.status, response.bodyAsText(), response.headers["Content-Type"])
        }
        reply!!
    }

    private fun insertRow(accountId: Long, id: String, sealedBox: String, createdAt: String) = runBlocking {
        temp.db.transaction { connection ->
            connection.prepareStatement(
                "INSERT INTO pending_sealed (id, account_id, sealed_box, created_at) VALUES (?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, id)
                statement.setLong(2, accountId)
                statement.setString(3, sealedBox)
                statement.setString(4, createdAt)
                statement.executeUpdate()
            }
        }
    }

    private fun rowJson(id: String, sealedBox: String, createdAt: String): String =
        "{\"id\":\"$id\",\"sealed_box\":\"$sealedBox\",\"created_at\":\"$createdAt\"}"

    @Test
    fun `an empty store answers an empty array`() {
        val alice = register("alice", 1)
        val reply = get(sessionOf(alice.accountId))
        assertEquals("sync-api: empty store status", HttpStatusCode.OK, reply.status)
        assertEquals("sync-api: empty store body", "[]", reply.body)
    }

    @Test
    fun `another account's rows are never returned`() {
        val alice = register("alice", 1)
        val bob = register("bob", 2)
        insertRow(alice.accountId, "alice-1", "cmFuZG9t", "2026-10-11T12:00:00Z")
        insertRow(bob.accountId, "bob-1", "cmFuZG9t", "2026-10-11T12:00:01Z")
        assertEquals("sync-api: alice sees her row only", "[" + rowJson("alice-1", "cmFuZG9t", "2026-10-11T12:00:00Z") + "]", get(sessionOf(alice.accountId)).body)
        assertEquals("sync-api: bob sees his row only", "[" + rowJson("bob-1", "cmFuZG9t", "2026-10-11T12:00:01Z") + "]", get(sessionOf(bob.accountId)).body)
    }

    @Test
    fun `an admin user session sees only its own rows`() {
        val root = register("root", 1)
        val alice = register("alice", 2)
        insertRow(root.accountId, "root-1", "cm9vdA==", "2026-10-11T12:00:00Z")
        insertRow(alice.accountId, "alice-1", "YWxpY2U=", "2026-10-11T12:00:01Z")
        assertEquals("sync-api: the admin sees its own row only", "[" + rowJson("root-1", "cm9vdA==", "2026-10-11T12:00:00Z") + "]", get(sessionOf(root.accountId)).body)
    }

    @Test
    fun `rows come back ordered by created_at then id`() {
        val alice = register("alice", 1)
        insertRow(alice.accountId, "b", "Ym", "2026-10-11T12:00:01Z")
        insertRow(alice.accountId, "a", "YWE=", "2026-10-11T12:00:00Z")
        insertRow(alice.accountId, "c", "Yw==", "2026-10-11T12:00:00Z")
        val expected = "[" + rowJson("a", "YWE=", "2026-10-11T12:00:00Z") + "," + rowJson("c", "Yw==", "2026-10-11T12:00:00Z") + "," +
            rowJson("b", "Ym", "2026-10-11T12:00:01Z") + "]"
        assertEquals("sync-api: ordering", expected, get(sessionOf(alice.accountId)).body)
    }

    @Test
    fun `the account comes from the token and never from the query string`() {
        val alice = register("alice", 1)
        val bob = register("bob", 2)
        insertRow(alice.accountId, "alice-1", "cmFuZG9t", "2026-10-11T12:00:00Z")
        insertRow(bob.accountId, "bob-1", "Ym9i", "2026-10-11T12:00:01Z")
        val reply = get(sessionOf(alice.accountId), "?account_id=${bob.accountId}")
        assertEquals("sync-api: status", HttpStatusCode.OK, reply.status)
        assertEquals("sync-api: only the token's rows, none of bob's", "[" + rowJson("alice-1", "cmFuZG9t", "2026-10-11T12:00:00Z") + "]", reply.body)
    }

    @Test
    fun `the 200 answer is application json`() {
        val alice = register("alice", 1)
        insertRow(alice.accountId, "alice-1", "cmFuZG9t", "2026-10-11T12:00:00Z")
        val reply = get(sessionOf(alice.accountId))
        assertEquals("sync-api: status", HttpStatusCode.OK, reply.status)
        assertTrue("sync-api: content type is ${reply.contentType}", reply.contentType.orEmpty().startsWith("application/json"))
    }

    @Test
    fun `rows with the same created_at come back by id ascending whatever the insert order`() {
        val alice = register("alice", 1)
        insertRow(alice.accountId, "z", "eg==", "2026-10-11T12:00:00Z")
        insertRow(alice.accountId, "a", "YQ==", "2026-10-11T12:00:00Z")
        val expected = "[" + rowJson("a", "YQ==", "2026-10-11T12:00:00Z") + "," + rowJson("z", "eg==", "2026-10-11T12:00:00Z") + "]"
        assertEquals("sync-api: tie broken by id", expected, get(sessionOf(alice.accountId)).body)
    }

    @Test
    fun `a transcribe agent token and an admin scoped agent token both get 403`() {
        val root = register("root", 1)
        insertRow(root.accountId, "root-1", "cm9vdA==", "2026-10-11T12:00:00Z")
        for (scope in listOf(AgentScope.TRANSCRIBE, AgentScope.ADMIN)) {
            val reply = get(agentOf(root.accountId, scope))
            assertEquals("sync-api: status for an agent token of scope $scope", HttpStatusCode.Forbidden, reply.status)
            assertEquals("sync-api: body for an agent token of scope $scope", agentRefusedBody, reply.body)
        }
    }

    @Test
    fun `no token, an unknown token and a dead session token get 401`() {
        val alice = register("alice", 1)
        val issued = TokenFixtures.issuedOf(runBlocking { tokenStore.issueSession(alice.accountId) })
        TokenFixtures.expireSession(temp, issued.tokenId)
        assertEquals("sync-api: no token", sessionRequiredBody, get(null).body)
        assertEquals("sync-api: unknown token", sessionRequiredBody, get("x".repeat(43)).body)
        val dead = get(issued.secret.reveal())
        assertEquals("sync-api: dead token status", HttpStatusCode.Unauthorized, dead.status)
        assertEquals("sync-api: dead token body", sessionRequiredBody, dead.body)
    }

    @Test
    fun `a failure of the store answers the fixed 503 with nothing of the failure`() {
        val alice = register("alice", 1)
        val text = sessionOf(alice.accountId)
        runBlocking {
            temp.db.transaction { connection ->
                connection.createStatement().use { statement -> statement.execute("DROP TABLE pending_sealed") }
            }
        }
        val reply = get(text)
        assertEquals("sync-api: store failure status", HttpStatusCode.ServiceUnavailable, reply.status)
        assertEquals("sync-api: store failure body", unavailableBody, reply.body)
        assertFalse("sync-api: no table name in the body", reply.body.contains("pending_sealed"))
    }

    @Test
    fun `a failed token check answers the fixed 503`() {
        val alice = register("alice", 1)
        val text = sessionOf(alice.accountId)
        temp.closeKeepingFiles()
        assertEquals("sync-api: closed database", unavailableBody, get(text).body)
    }

    @Test
    fun `the real application serves the route`() {
        val alice = register("alice", 1)
        insertRow(alice.accountId, "alice-1", "cmFuZG9t", "2026-10-11T12:00:00Z")
        val text = sessionOf(alice.accountId)
        var reply: Reply? = null
        runBlocking {
            testApplication {
                application {
                    syncApiModule(
                        AccountFixtures.newStore(temp),
                        tokenStore,
                        SyncRouteTestSupport.newStore(temp),
                        HandoffFixtures.handoffStore(temp, tokenStore, AccountFixtures.fixedClock),
                    )
                }
                val response = client.get("/v1/sync/pending-sealed") { header("Authorization", "Bearer $text") }
                reply = Reply(response.status, response.bodyAsText())
            }
        }
        assertEquals("sync-api: through the application module", "[" + rowJson("alice-1", "cmFuZG9t", "2026-10-11T12:00:00Z") + "]", reply!!.body)
    }
}
