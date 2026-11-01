package dev.breaker.server.syncapi.servicecheck

import dev.breaker.server.syncapi.accounts.Role
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.AgentScope
import dev.breaker.server.syncapi.tokens.IssuedToken
import dev.breaker.server.syncapi.tokens.MutableClock
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenFixtures.issuedOf
import dev.breaker.server.syncapi.tokens.TokenSecret
import dev.breaker.server.syncapi.tokens.TokenStore
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

// A real TokenStore on the temp database with a clock the test moves. The routes are
// mounted on a test application with the service key `test-key`.
internal class ServiceCheckRoutesTest : TempDatabaseTest() {

    private class Reply(val status: HttpStatusCode, val body: String)

    private lateinit var temp: TempDatabase
    private lateinit var clock: MutableClock
    private lateinit var store: TokenStore
    private var userId: Long = 0L

    @Before
    fun openStore() {
        temp = openTemp()
        clock = MutableClock(TokenFixtures.START)
        store = TokenFixtures.tokenStore(temp, clock)
        val admin = TokenFixtures.register(temp, "admin", 1)
        val member = TokenFixtures.register(temp, "member", 2)
        assertEquals("sync-api: the first account must be the admin", Role.ADMIN, admin.role)
        assertEquals("sync-api: the second account must be a user", Role.USER, member.role)
        userId = member.accountId
    }

    private fun session(): IssuedToken = issuedOf(runBlocking { store.issueSession(userId) })

    private fun agent(expiresAt: Instant?): IssuedToken =
        issuedOf(runBlocking { store.issueAgent(userId, AgentScope.TRANSCRIBE, "bot", expiresAt) })

    private fun call(path: String, key: String?, token: String?): Reply = runBlocking {
        var reply: Reply? = null
        testApplication {
            application { routing { serviceCheckRoutes(store, "test-key") } }
            val response = client.post(path) {
                if (key != null) header("X-Service-Key", key)
                if (token != null) header("X-Presented-Token", token)
            }
            reply = Reply(response.status, response.bodyAsText())
        }
        checkNotNull(reply)
    }

    private fun use(key: String?, token: String?): Reply = call("/internal/v1/token/use", key, token)

    private fun peek(key: String?, token: String?): Reply = call("/internal/v1/token/peek", key, token)

    private fun expiresAt(id: Long): Long? =
        inTransaction<Long?>(temp) { connection ->
            connection.prepareStatement("SELECT expires_at_ms FROM tokens WHERE id = ?").use { statement ->
                statement.setLong(1, id)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "sync-api test: no token row with id $id" }
                    val value = rows.getLong(1)
                    if (rows.wasNull()) null else value
                }
            }
        }

    @Test
    fun `a wrong key gives 403 and the fixed body on both routes`() {
        val token = session().secret.reveal()
        for (key in listOf("wrong-key", "test-key-extra", "test")) {
            for (reply in listOf(use(key, token), peek(key, token))) {
                assertEquals("sync-api: wrong key status for $key", HttpStatusCode.Forbidden, reply.status)
                assertEquals("sync-api: wrong key body for $key", "{\"error\":\"bad_service_key\"}", reply.body)
            }
        }
    }

    @Test
    fun `a missing key gives 403 and the fixed body on both routes`() {
        val token = session().secret.reveal()
        for (reply in listOf(use(null, token), peek(null, token))) {
            assertEquals("sync-api: missing key status", HttpStatusCode.Forbidden, reply.status)
            assertEquals("sync-api: missing key body", "{\"error\":\"bad_service_key\"}", reply.body)
        }
    }

    @Test
    fun `an empty or blank key header gives 403 on both routes`() {
        val token = session().secret.reveal()
        for (key in listOf("", " ", "   ")) {
            for (reply in listOf(use(key, token), peek(key, token))) {
                assertEquals("sync-api: blank key status", HttpStatusCode.Forbidden, reply.status)
                assertEquals("sync-api: blank key body", "{\"error\":\"bad_service_key\"}", reply.body)
            }
        }
    }

    @Test
    fun `the key is checked first so a wrong key with a live token moves nothing`() {
        val issued = session()
        val before = expiresAt(issued.tokenId)
        val reply = use("wrong-key", issued.secret.reveal())
        assertEquals("sync-api: status", HttpStatusCode.Forbidden, reply.status)
        assertEquals("sync-api: the expiry did not move", before, expiresAt(issued.tokenId))
    }

    @Test
    fun `use with a live session token gives 200 and moves the expiry to now plus thirty days`() {
        clock.advance(Duration.ofDays(5))
        val issued = session()
        clock.advance(Duration.ofDays(2))
        val reply = use("test-key", issued.secret.reveal())
        assertEquals("sync-api: status", HttpStatusCode.OK, reply.status)
        assertEquals("sync-api: body", "{\"account_id\":$userId,\"kind\":\"session\",\"role\":\"user\"}", reply.body)
        assertEquals("sync-api: the expiry moved", clock.millis() + 2_592_000_000L, expiresAt(issued.tokenId))
    }

    @Test
    fun `peek with a live session token gives the same 200 and moves nothing`() {
        clock.advance(Duration.ofDays(5))
        val issued = session()
        val before = expiresAt(issued.tokenId)
        clock.advance(Duration.ofDays(2))
        val reply = peek("test-key", issued.secret.reveal())
        assertEquals("sync-api: status", HttpStatusCode.OK, reply.status)
        assertEquals("sync-api: body", "{\"account_id\":$userId,\"kind\":\"session\",\"role\":\"user\"}", reply.body)
        assertEquals("sync-api: the expiry did not move", before, expiresAt(issued.tokenId))
    }

    @Test
    fun `an unknown token gives 401 and the same body on both routes`() {
        val neverIssued = TokenSecret.generate(TokenFixtures.fakeRandom(77)).reveal()
        for (reply in listOf(use("test-key", neverIssued), peek("test-key", neverIssued))) {
            assertEquals("sync-api: status", HttpStatusCode.Unauthorized, reply.status)
            assertEquals("sync-api: body", "{\"error\":\"not_live\"}", reply.body)
        }
    }

    @Test
    fun `an expired session and a revoked agent token give the same 401 body`() {
        val expired = session().secret.reveal()
        val revoked = agent(null)
        assertEquals("sync-api: the agent token was revoked", true, runBlocking { store.revokeAgentToken(revoked.tokenId) })
        clock.advance(Duration.ofDays(31))
        val expiredReply = use("test-key", expired)
        val revokedReply = peek("test-key", revoked.secret.reveal())
        assertEquals("sync-api: expired status", HttpStatusCode.Unauthorized, expiredReply.status)
        assertEquals("sync-api: revoked status", HttpStatusCode.Unauthorized, revokedReply.status)
        assertEquals("sync-api: expired body", "{\"error\":\"not_live\"}", expiredReply.body)
        assertEquals("sync-api: revoked body", "{\"error\":\"not_live\"}", revokedReply.body)
    }

    @Test
    fun `a missing or malformed presented token gives 401 and the same body`() {
        for (reply in listOf(use("test-key", null), use("test-key", "not-a-token"), peek("test-key", null), peek("test-key", ""))) {
            assertEquals("sync-api: status", HttpStatusCode.Unauthorized, reply.status)
            assertEquals("sync-api: body", "{\"error\":\"not_live\"}", reply.body)
        }
    }

    @Test
    fun `an agent token is reported as an agent with its owner role`() {
        val issued = agent(null)
        val reply = peek("test-key", issued.secret.reveal())
        assertEquals("sync-api: status", HttpStatusCode.OK, reply.status)
        assertEquals("sync-api: body", "{\"account_id\":$userId,\"kind\":\"agent\",\"role\":\"user\"}", reply.body)
    }

    @Test
    fun `neither the key nor the token reaches the output or a body`() {
        val token = session().secret.reveal()
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val oldOut = System.out
        val oldErr = System.err
        val bodies = StringBuilder()
        System.setOut(PrintStream(out, true, "UTF-8"))
        System.setErr(PrintStream(err, true, "UTF-8"))
        try {
            bodies.append(use("wrong-key", token).body)
            bodies.append(use("test-key", token).body)
        } finally {
            System.setOut(oldOut)
            System.setErr(oldErr)
        }
        val seen = out.toString("UTF-8") + err.toString("UTF-8") + bodies.toString()
        assertFalse("sync-api: the key text appears", seen.contains("test-key"))
        assertFalse("sync-api: the token text appears", seen.contains(token))
    }

    @Test
    fun `an oversized presented token gives 401 and the same body on both routes`() {
        val oversized = "A".repeat(10_000)
        for (reply in listOf(use("test-key", oversized), peek("test-key", oversized))) {
            assertEquals("sync-api: status", HttpStatusCode.Unauthorized, reply.status)
            assertEquals("sync-api: body", "{\"error\":\"not_live\"}", reply.body)
        }
    }

    @Test
    fun `an agent token with an expiry is not moved by use`() {
        val issued = agent(TokenFixtures.START.plus(Duration.ofDays(60)))
        val before = expiresAt(issued.tokenId)
        assertNotNull("sync-api: the agent token has an expiry", before)
        clock.advance(Duration.ofDays(10))
        val reply = use("test-key", issued.secret.reveal())
        assertEquals("sync-api: status", HttpStatusCode.OK, reply.status)
        assertEquals("sync-api: body", "{\"account_id\":$userId,\"kind\":\"agent\",\"role\":\"user\"}", reply.body)
        assertEquals("sync-api: the agent expiry did not move", before, expiresAt(issued.tokenId))
    }
}
