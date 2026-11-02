package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.accounts.AccountStore
import dev.breaker.server.syncapi.db.TestDatabases
import dev.breaker.server.syncapi.handoff.HandoffFixtures
import dev.breaker.server.syncapi.handoff.HandoffStore
import dev.breaker.server.syncapi.syncApiModule
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import dev.breaker.server.syncapi.transcriptions.TranscriptionStore
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AuthHandoffStartRouteTest {

    private lateinit var temp: dev.breaker.server.syncapi.db.TempDatabase
    private lateinit var accountStore: AccountStore
    private lateinit var tokenStore: TokenStore
    private lateinit var handoffStore: HandoffStore
    private lateinit var transcriptionStore: TranscriptionStore

    @Before fun setUp() {
        temp = TestDatabases.openTemp()
        accountStore = AccountFixtures.newStore(temp, AccountFixtures.fastHasher())
        tokenStore = TokenFixtures.tokenStore(temp, AccountFixtures.fixedClock)
        handoffStore = HandoffFixtures.handoffStore(temp, tokenStore, AccountFixtures.fixedClock)
        transcriptionStore = SyncRouteTestSupport.newStore(temp)
    }

    @After fun tearDown() {
        temp.close()
    }

    private fun register(name: String, seed: Int): Long {
        val result = runBlocking {
            accountStore.register(name, AccountFixtures.salt(seed), AccountFixtures.validKdf, 1, AccountFixtures.verifier(seed))
        }
        if (result !is dev.breaker.server.syncapi.accounts.RegisterResult.Registered) {
            throw AssertionError("expected Registered but got $result")
        }
        return result.accountId
    }

    private fun sessionToken(store: TokenStore, accountId: Long): String =
        TokenFixtures.issuedOf(runBlocking { store.issueSession(accountId) }).secret.reveal()

    private val SEALED_PACKAGE = HandoffFixtures.SEALED_PACKAGE_TEXT
    private val CHALLENGE = HandoffFixtures.CHALLENGE_FOR_SECRET(HandoffFixtures.SECRET)
    private val SESSION_REQUIRED = """{"error":"unauthorized","message":"Invalid or missing token"}"""
    private val AGENT_REFUSED = """{"error":"forbidden","message":"User session token required; an agent token is refused"}"""
    private val BAD_REQUEST = """{"error":"bad_request","message":"Invalid request body"}"""
    private val PAYLOAD_TOO_LARGE = """{"error":"payload_too_large","message":"Request body exceeds maximum allowed size"}"""

    private fun postStart(token: String?, body: String): Reply {
        var reply: Reply? = null
        runBlocking {
            testApplication {
                application { syncApiModule(accountStore, tokenStore, transcriptionStore, handoffStore) }
                val response = client.post("/v1/auth/handoff/start") {
                    if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                reply = Reply(response.status, response.bodyAsText())
            }
        }
        return checkNotNull(reply)
    }

    @Test fun `start returns 201 with a 43-char code for a live session`() {
        val accountId = register("alice", 1)
        val token = sessionToken(tokenStore, accountId)
        val response = postStart(token, """{"challenge":"$CHALLENGE","sealed_package":"$SEALED_PACKAGE"}""")
        assertEquals("status", HttpStatusCode.Created, response.status)
        val body = response.body
        val codeStart = body.indexOf("\"code\":\"") + 8
        val code = body.substring(codeStart, codeStart + 43)
        assertEquals("code length", 43, code.length)
        assertTrue("code matches pattern", "^[A-Za-z0-9_-]{43}$".toRegex().matches(code))
    }

    @Test fun `start returns exactly the 401 body with no token`() {
        val response = postStart(null, """{"challenge":"$CHALLENGE","sealed_package":"$SEALED_PACKAGE"}""")
        assertEquals("status", HttpStatusCode.Unauthorized, response.status)
        assertEquals("body", SESSION_REQUIRED, response.body)
    }

    @Test fun `start returns exactly the 401 body with a malformed bearer header`() {
        val response = postStart("NotBearer junk", """{"challenge":"$CHALLENGE","sealed_package":"$SEALED_PACKAGE"}""")
        assertEquals("status", HttpStatusCode.Unauthorized, response.status)
        assertEquals("body", SESSION_REQUIRED, response.body)
    }

    @Test fun `start returns exactly the 403 body for an agent token`() {
        val accountId = register("alice", 1)
        val token = TokenFixtures.issuedOf(runBlocking {
            tokenStore.issueAgent(accountId, dev.breaker.server.syncapi.tokens.AgentScope.TRANSCRIBE, "sync-label", null)
        }).secret.reveal()
        val response = postStart(token, """{"challenge":"$CHALLENGE","sealed_package":"$SEALED_PACKAGE"}""")
        assertEquals("status", HttpStatusCode.Forbidden, response.status)
        assertEquals("body", AGENT_REFUSED, response.body)
    }

    @Test fun `start returns 401 for an unknown but well-formed token`() {
        val response = postStart("C".repeat(43), """{"challenge":"$CHALLENGE","sealed_package":"$SEALED_PACKAGE"}""")
        assertEquals("status", HttpStatusCode.Unauthorized, response.status)
        assertEquals("body", SESSION_REQUIRED, response.body)
    }

    @Test fun `start rejects missing challenge with exactly 400`() {
        val accountId = register("alice", 1)
        val token = sessionToken(tokenStore, accountId)
        val response = postStart(token, """{"sealed_package":"$SEALED_PACKAGE"}""")
        assertEquals("status", HttpStatusCode.BadRequest, response.status)
        assertEquals("body", BAD_REQUEST, response.body)
    }

    @Test fun `start rejects missing sealed_package with exactly 400`() {
        val accountId = register("alice", 1)
        val token = sessionToken(tokenStore, accountId)
        val response = postStart(token, """{"challenge":"$CHALLENGE"}""")
        assertEquals("status", HttpStatusCode.BadRequest, response.status)
        assertEquals("body", BAD_REQUEST, response.body)
    }

    @Test fun `start rejects extra unknown field with exactly 400`() {
        val accountId = register("alice", 1)
        val token = sessionToken(tokenStore, accountId)
        val response = postStart(token, """{"challenge":"$CHALLENGE","sealed_package":"$SEALED_PACKAGE","extra":"x"}""")
        assertEquals("status", HttpStatusCode.BadRequest, response.status)
        assertEquals("body", BAD_REQUEST, response.body)
    }

    @Test fun `start rejects duplicate field with exactly 400`() {
        val accountId = register("alice", 1)
        val token = sessionToken(tokenStore, accountId)
        val response = postStart(token, """{"challenge":"$CHALLENGE","challenge":"$CHALLENGE","sealed_package":"$SEALED_PACKAGE"}""")
        assertEquals("status", HttpStatusCode.BadRequest, response.status)
        assertEquals("body", BAD_REQUEST, response.body)
    }

    @Test fun `start rejects wrong challenge type with exactly 400`() {
        val accountId = register("alice", 1)
        val token = sessionToken(tokenStore, accountId)
        val response = postStart(token, """{"challenge":123,"sealed_package":"$SEALED_PACKAGE"}""")
        assertEquals("status", HttpStatusCode.BadRequest, response.status)
        assertEquals("body", BAD_REQUEST, response.body)
    }

    @Test fun `start rejects challenge of wrong pattern with exactly 400`() {
        val accountId = register("alice", 1)
        val token = sessionToken(tokenStore, accountId)
        val response = postStart(token, """{"challenge":"short","sealed_package":"$SEALED_PACKAGE"}""")
        assertEquals("status", HttpStatusCode.BadRequest, response.status)
        assertEquals("body", BAD_REQUEST, response.body)
    }

    @Test fun `start rejects sealed_package not exactly 113 bytes with exactly 400`() {
        val accountId = register("alice", 1)
        val token = sessionToken(tokenStore, accountId)
        val wrong = java.util.Base64.getEncoder().encodeToString(ByteArray(100) { 1 })
        val response = postStart(token, """{"challenge":"$CHALLENGE","sealed_package":"$wrong"}""")
        assertEquals("status", HttpStatusCode.BadRequest, response.status)
        assertEquals("body", BAD_REQUEST, response.body)
    }

    @Test fun `start accepts a non-canonical but pattern-valid sealed_package with 201`() {
        val accountId = register("alice", 1)
        val token = sessionToken(tokenStore, accountId)
        val nonCanonical = nonCanonicalOf(SEALED_PACKAGE)
        assertTrue("the test text really differs", nonCanonical != SEALED_PACKAGE)
        val sameBytes = java.util.Base64.getDecoder().decode(nonCanonical)
            .contentEquals(java.util.Base64.getDecoder().decode(SEALED_PACKAGE))
        assertTrue("and decodes to the same bytes", sameBytes)
        val response = postStart(token, """{"challenge":"$CHALLENGE","sealed_package":"$nonCanonical"}""")
        assertEquals("status", HttpStatusCode.Created, response.status)
    }

    @Test fun `start rejects malformed JSON body with exactly 400`() {
        val accountId = register("alice", 1)
        val token = sessionToken(tokenStore, accountId)
        val response = postStart(token, "not json")
        assertEquals("status", HttpStatusCode.BadRequest, response.status)
        assertEquals("body", BAD_REQUEST, response.body)
    }

    @Test fun `start rejects comma-only members with exactly 400`() {
        val accountId = register("alice", 1)
        val token = sessionToken(tokenStore, accountId)
        val response = postStart(token, """{"challenge":"$CHALLENGE" "sealed_package":"$SEALED_PACKAGE"}""")
        assertEquals("status", HttpStatusCode.BadRequest, response.status)
        assertEquals("body", BAD_REQUEST, response.body)
    }

    @Test fun `start rejects leading comma with exactly 400`() {
        val accountId = register("alice", 1)
        val token = sessionToken(tokenStore, accountId)
        val response = postStart(token, """,{"challenge":"$CHALLENGE","sealed_package":"$SEALED_PACKAGE"}""")
        assertEquals("status", HttpStatusCode.BadRequest, response.status)
        assertEquals("body", BAD_REQUEST, response.body)
    }

    @Test fun `start rejects trailing comma with exactly 400`() {
        val accountId = register("alice", 1)
        val token = sessionToken(tokenStore, accountId)
        val response = postStart(token, """{"challenge":"$CHALLENGE","sealed_package":"$SEALED_PACKAGE",}""")
        assertEquals("status", HttpStatusCode.BadRequest, response.status)
        assertEquals("body", BAD_REQUEST, response.body)
    }

    @Test fun `start rejects missing value after colon with exactly 400`() {
        val accountId = register("alice", 1)
        val token = sessionToken(tokenStore, accountId)
        val response = postStart(token, """{"challenge":"$CHALLENGE","sealed_package":}""")
        assertEquals("status", HttpStatusCode.BadRequest, response.status)
        assertEquals("body", BAD_REQUEST, response.body)
    }

    @Test fun `start refuses a body one byte over 4096 with exactly 413`() {
        val accountId = register("alice", 1)
        val token = sessionToken(tokenStore, accountId)
        val over = "x".repeat(4097)
        val response = postStart(token, over)
        assertEquals("status", HttpStatusCode.PayloadTooLarge, response.status)
        assertEquals("body", PAYLOAD_TOO_LARGE, response.body)
    }

    @Test fun `start accepts a body of exactly 4096 bytes`() {
        val accountId = register("alice", 1)
        val token = sessionToken(tokenStore, accountId)
        val base = """{"challenge":"$CHALLENGE","sealed_package":"$SEALED_PACKAGE"}"""
        val padded = base.dropLast(1) + " ".repeat(4096 - base.length) + "}"
        val response = postStart(token, padded)
        assertEquals("status", HttpStatusCode.Created, response.status)
    }

    @Test fun `start response never echoes the sealed package or the challenge`() {
        val accountId = register("alice", 1)
        val token = sessionToken(tokenStore, accountId)
        val response = postStart(token, """{"challenge":"$CHALLENGE","sealed_package":"$SEALED_PACKAGE"}""")
        assertEquals("status", HttpStatusCode.Created, response.status)
        val body = response.body
        assertFalse("body must not contain the sealed package", body.contains(SEALED_PACKAGE))
        assertFalse("body must not contain the challenge", body.contains(CHALLENGE))
        assertFalse("body must not contain the secret", body.contains(HandoffFixtures.SECRET))
    }
}
