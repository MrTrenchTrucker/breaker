package dev.breaker.server.syncapi.admin

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.accounts.Role
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Types
import java.time.Duration

/**
 * Tests for [adminEndSessionRoute] — `DELETE /v1/admin/sessions/{id}`.
 *
 * Admin-only: ends one session by id. The bearer token identifies the caller and is checked
 * with `isAdmin(resolved)` before the id is ever read, so a non-admin with a garbage id gets
 * 403, not 404. A session that is unknown, an agent-token id, already revoked, expired or past
 * the 180-day cap is one fixed 404 with no part of the id in the body.
 */
internal class AuthAdminEndSessionRouteTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var tokenStore: TokenStore

    @Before
    fun setUp() {
        temp = openTemp()
        tokenStore = TokenFixtures.tokenStore(temp, AccountFixtures.fixedClock)
    }

    // ---------- helpers ----------
    private fun register(name: String, seed: Int) = TokenFixtures.register(temp, name, seed)

    private fun session(accountId: Long): IssuedToken =
        TokenFixtures.issuedOf(runBlocking { tokenStore.issueSession(accountId) })

    private fun agent(accountId: Long, scope: AgentScope = AgentScope.TRANSCRIBE): IssuedToken =
        TokenFixtures.issuedOf(runBlocking { tokenStore.issueAgent(accountId, scope, "agent-label", null) })

    private fun bearer(text: String): String = "Bearer $text"

    private fun resolves(token: IssuedToken): Boolean =
        runBlocking { tokenStore.resolve(token.secret.reveal()) } != null

    // Every raw INSERT gets its own unique token_hash: token_hash is NOT NULL UNIQUE.
    private var hashSeed = 0
    private fun nextHash() = ByteArray(32) { (hashSeed++).toByte() }

    private fun insertSessionRow(accountId: Long, createdMs: Long, expiresMs: Long?): Long = runBlocking {
        inTransaction(temp) { connection ->
            connection.prepareStatement(
                "INSERT INTO tokens (account_id, kind, scope, label, token_hash, " +
                    "created_at_ms, expires_at_ms, device_name) " +
                    "VALUES (?, 'session', NULL, NULL, ?, ?, ?, NULL)",
            ).use { statement ->
                statement.setLong(1, accountId)
                statement.setBytes(2, nextHash())
                statement.setLong(3, createdMs)
                if (expiresMs == null) statement.setNull(4, Types.BIGINT) else statement.setLong(4, expiresMs)
                statement.executeUpdate()
                connection.createStatement().use { query ->
                    query.executeQuery("SELECT last_insert_rowid()").use { rows ->
                        check(rows.next()) { "sync-api test: row id missing" }
                        rows.getLong(1)
                    }
                }
            }
        }
    }

    // ---------- happy path ----------

    @Test
    fun `an admin session ends another account's session with 204 and an empty body`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)
        val victim = register("bob", 2)
        val victimSession = session(victim.accountId)

        testApplication {
            application { adminEndSessionRoute(tokenStore) }
            val response = client.delete("/v1/admin/sessions/${victimSession.tokenId}") {
                header("Authorization", bearer(adminSession.secret.reveal()))
            }
            assertEquals("status", HttpStatusCode.NoContent, response.status)
            assertEquals("a 204 carries no content", "", response.bodyAsText())
        }
        assertFalse("the victim's session no longer resolves", resolves(victimSession))
        assertTrue("the admin's session still resolves", resolves(adminSession))
    }

    @Test
    fun `an admin-scoped agent token ends a session with 204 and the victim dies`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminAgent = agent(admin.accountId, AgentScope.ADMIN)
        val victim = register("bob", 2)
        val victimSession = session(victim.accountId)

        testApplication {
            application { adminEndSessionRoute(tokenStore) }
            val response = client.delete("/v1/admin/sessions/${victimSession.tokenId}") {
                header("Authorization", bearer(adminAgent.secret.reveal()))
            }
            assertEquals("status", HttpStatusCode.NoContent, response.status)
            assertEquals("a 204 carries no content", "", response.bodyAsText())
        }
        assertFalse("the victim's session no longer resolves", resolves(victimSession))
    }

    @Test
    fun `an admin ending their own session answers 204 and the token dies`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)

        testApplication {
            application { adminEndSessionRoute(tokenStore) }
            val response = client.delete("/v1/admin/sessions/${adminSession.tokenId}") {
                header("Authorization", bearer(adminSession.secret.reveal()))
            }
            assertEquals("status", HttpStatusCode.NoContent, response.status)
            assertEquals("a 204 carries no content", "", response.bodyAsText())
        }
        assertFalse("the admin's own session no longer resolves", resolves(adminSession))
    }

    // ---------- refusals ----------

    @Test
    fun `a non-admin session gets 403 and the victim stays live`() = runBlocking<Unit> {
        register("alice", 1) // first account: admin
        val bob = register("bob", 2) // second account: user
        val bobSession = session(bob.accountId)
        val victim = register("carol", 3)
        val victimSession = session(victim.accountId)

        testApplication {
            application { adminEndSessionRoute(tokenStore) }
            val response = client.delete("/v1/admin/sessions/${victimSession.tokenId}") {
                header("Authorization", bearer(bobSession.secret.reveal()))
            }
            assertEquals("status", HttpStatusCode.Forbidden, response.status)
            assertEquals("body", FORBIDDEN_BODY, response.bodyAsText())
        }
        assertTrue("a refused end-session must not touch the victim", resolves(victimSession))
        assertTrue("the caller's session stays live", resolves(bobSession))
    }

    @Test
    fun `a transcribe-scoped agent token gets 403`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val bob = register("bob", 2)
        val victim = session(bob.accountId)
        val agent = agent(bob.accountId, AgentScope.TRANSCRIBE)

        testApplication {
            application { adminEndSessionRoute(tokenStore) }
            val response = client.delete("/v1/admin/sessions/${victim.tokenId}") {
                header("Authorization", bearer(agent.secret.reveal()))
            }
            assertEquals("status", HttpStatusCode.Forbidden, response.status)
            assertEquals("body", FORBIDDEN_BODY, response.bodyAsText())
        }
        assertTrue("a refused call must not end the session", resolves(victim))
    }

    @Test
    fun `a demoted owner's admin-scoped agent token gets 403 and the victim stays live`() = runBlocking<Unit> {
        val alice = register("alice", 1) // admin
        val adminAgent = agent(alice.accountId, AgentScope.ADMIN)
        TokenFixtures.setRole(temp, alice.accountId, Role.USER) // demote after minting
        val victim = register("bob", 2)
        val victimSession = session(victim.accountId)

        testApplication {
            application { adminEndSessionRoute(tokenStore) }
            val response = client.delete("/v1/admin/sessions/${victimSession.tokenId}") {
                header("Authorization", bearer(adminAgent.secret.reveal()))
            }
            assertEquals("status", HttpStatusCode.Forbidden, response.status)
            assertEquals("body", FORBIDDEN_BODY, response.bodyAsText())
        }
        assertTrue("a refused end-session must not touch the victim", resolves(victimSession))
    }

    @Test
    fun `a non-admin with a garbage id gets 403, not 404`() = runBlocking<Unit> {
        register("alice", 1) // first account: admin
        val bob = register("bob", 2) // second account: user
        val bobSession = session(bob.accountId)

        testApplication {
            application { adminEndSessionRoute(tokenStore) }
            val response = client.delete("/v1/admin/sessions/not-a-number") {
                header("Authorization", bearer(bobSession.secret.reveal()))
            }
            assertEquals("status", HttpStatusCode.Forbidden, response.status)
            assertEquals("body", FORBIDDEN_BODY, response.bodyAsText())
        }
        assertTrue("auth failed before the id was read, so the caller stays live", resolves(bobSession))
    }

    @Test
    fun `no token gets 401`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val victim = session(admin.accountId)

        testApplication {
            application { adminEndSessionRoute(tokenStore) }
            val response = client.delete("/v1/admin/sessions/${victim.tokenId}")
            assertEquals("status", HttpStatusCode.Unauthorized, response.status)
            assertEquals("body", UNAUTHORIZED_BODY, response.bodyAsText())
        }
        assertTrue("a refused call must not end the session", resolves(victim))
    }

    @Test
    fun `a dead token gets 401`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)
        val liveTarget = session(admin.accountId)
        runBlocking { tokenStore.revokeSession(adminSession.tokenId) }

        testApplication {
            application { adminEndSessionRoute(tokenStore) }
            val response = client.delete("/v1/admin/sessions/${liveTarget.tokenId}") {
                header("Authorization", bearer(adminSession.secret.reveal()))
            }
            assertEquals("status", HttpStatusCode.Unauthorized, response.status)
            assertEquals("body", UNAUTHORIZED_BODY, response.bodyAsText())
        }
        assertTrue("the refused call must not revoke the target", resolves(liveTarget))
    }

    // ---------- not found ----------

    @Test
    fun `a malformed id gets 404`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)

        testApplication {
            application { adminEndSessionRoute(tokenStore) }
            val response = client.delete("/v1/admin/sessions/not-a-number") {
                header("Authorization", bearer(adminSession.secret.reveal()))
            }
            assertEquals("status", HttpStatusCode.NotFound, response.status)
            assertEquals("body", SESSION_NOT_FOUND_BODY, response.bodyAsText())
        }
    }

    // The id is parsed by the digits-only rule, so "+5" and "-5" are refused, not read as numbers.
    // A substitute that parses with toLongOrNull would end the live session the digits name, so the
    // id here is a live one and the test asserts that session survives.
    @Test
    fun `a signed id is refused, not read as a number`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)
        val victim = session(admin.accountId)

        testApplication {
            application { adminEndSessionRoute(tokenStore) }
            for (raw in listOf("+${victim.tokenId}", "-${victim.tokenId}")) {
                val response = client.delete("/v1/admin/sessions/$raw") {
                    header("Authorization", bearer(adminSession.secret.reveal()))
                }
                assertEquals("status for $raw", HttpStatusCode.NotFound, response.status)
                assertEquals("body for $raw", SESSION_NOT_FOUND_BODY, response.bodyAsText())
            }
        }
        assertTrue("the signed id did not end the session", resolves(victim))
    }

    @Test
    fun `an unknown id gets 404`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)

        testApplication {
            application { adminEndSessionRoute(tokenStore) }
            val response = client.delete("/v1/admin/sessions/999999") {
                header("Authorization", bearer(adminSession.secret.reveal()))
            }
            assertEquals("status", HttpStatusCode.NotFound, response.status)
            assertEquals("body", SESSION_NOT_FOUND_BODY, response.bodyAsText())
        }
    }

    @Test
    fun `an agent-token id gets 404 and the agent row stays live`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)
        val agent = agent(admin.accountId)

        testApplication {
            application { adminEndSessionRoute(tokenStore) }
            val response = client.delete("/v1/admin/sessions/${agent.tokenId}") {
                header("Authorization", bearer(adminSession.secret.reveal()))
            }
            assertEquals("status", HttpStatusCode.NotFound, response.status)
            assertEquals("body", SESSION_NOT_FOUND_BODY, response.bodyAsText())
        }
        assertTrue("an agent token is not a session and stays live", resolves(agent))
    }

    @Test
    fun `an already-revoked id gets 404`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)
        val id = TokenFixtures.issuedOf(runBlocking { tokenStore.issueSession(admin.accountId) }).tokenId
        runBlocking { tokenStore.revokeSession(id) }
        val revokedAtFirst = TokenFixtures.revokedAtMs(temp, id)

        testApplication {
            application { adminEndSessionRoute(tokenStore) }
            val response = client.delete("/v1/admin/sessions/$id") {
                header("Authorization", bearer(adminSession.secret.reveal()))
            }
            assertEquals("status", HttpStatusCode.NotFound, response.status)
            assertEquals("body", SESSION_NOT_FOUND_BODY, response.bodyAsText())
        }
        assertEquals("the first revoke time stands", revokedAtFirst, TokenFixtures.revokedAtMs(temp, id))
    }

    @Test
    fun `an expired id gets 404`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)
        val id = TokenFixtures.issuedOf(runBlocking { tokenStore.issueSession(admin.accountId) }).tokenId
        TokenFixtures.expireSession(temp, id)

        testApplication {
            application { adminEndSessionRoute(tokenStore) }
            val response = client.delete("/v1/admin/sessions/$id") {
                header("Authorization", bearer(adminSession.secret.reveal()))
            }
            assertEquals("status", HttpStatusCode.NotFound, response.status)
            assertEquals("body", SESSION_NOT_FOUND_BODY, response.bodyAsText())
        }
        assertNull("revoked_at_ms stays null", TokenFixtures.revokedAtMs(temp, id))
        assertTrue("the admin's session stays live", resolves(adminSession))
    }

    @Test
    fun `an id past the 180-day cap gets 404`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)
        val created = TokenFixtures.START.toEpochMilli()
        val tooLate = created + Duration.ofDays(180).toMillis() + 1
        val id = insertSessionRow(admin.accountId, created, tooLate)

        testApplication {
            application { adminEndSessionRoute(tokenStore) }
            val response = client.delete("/v1/admin/sessions/$id") {
                header("Authorization", bearer(adminSession.secret.reveal()))
            }
            assertEquals("status", HttpStatusCode.NotFound, response.status)
            assertEquals("body", SESSION_NOT_FOUND_BODY, response.bodyAsText())
        }
        assertNull("revoked_at_ms stays null", TokenFixtures.revokedAtMs(temp, id))
        assertTrue("the admin's session stays live", resolves(adminSession))
    }

    @Test
    fun `a store failure answers the fixed 503`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)
        val victim = session(admin.accountId)

        // Close the store: the next store call throws IllegalStateException, answered 503.
        temp.closeKeepingFiles()

        testApplication {
            application { adminEndSessionRoute(tokenStore) }
            val response = client.delete("/v1/admin/sessions/${victim.tokenId}") {
                header("Authorization", bearer(adminSession.secret.reveal()))
            }
            assertEquals("status", HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("body", SERVICE_UNAVAILABLE_BODY, response.bodyAsText())
        }
    }

    @Test
    fun `the 404 body is exactly the error and message object`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)

        testApplication {
            application { adminEndSessionRoute(tokenStore) }
            val response = client.delete("/v1/admin/sessions/999999") {
                header("Authorization", bearer(adminSession.secret.reveal()))
            }
            assertEquals("the exact 404 body", SESSION_NOT_FOUND_BODY, response.bodyAsText())
            assertEquals("the wire name set is {error, message}", setOf("error", "message"), errorWireNames(response.bodyAsText()))
        }
    }

    // ---------- helpers ----------

    /** The top-level names of a JSON object body, parsed by hand. */
    private fun errorWireNames(body: String): Set<String> {
        val trimmed = body.trim()
        assertTrue("the error body must be a JSON object: $body", trimmed.startsWith("{") && trimmed.endsWith("}"))
        val inner = trimmed.substring(1, trimmed.length - 1)
        val names = LinkedHashSet<String>()
        var pos = 0
        while (pos < inner.length && inner[pos] == '"') {
            val keyEnd = inner.indexOf('"', pos + 1)
            if (keyEnd < 0) break
            names.add(inner.substring(pos + 1, keyEnd))
            val colon = inner.indexOf(':', keyEnd)
            if (colon < 0) break
            val next = inner.indexOf(',', colon)
            pos = if (next < 0) inner.length else next + 1
        }
        return names
    }
}
