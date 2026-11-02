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

class AuthHandoffExchangeRouteTest {

    private lateinit var temp: dev.breaker.server.syncapi.db.TempDatabase
    private lateinit var accountStore: AccountStore
    private lateinit var tokenStore: TokenStore
    private lateinit var handoffStore: HandoffStore
    private lateinit var transcriptionStore: TranscriptionStore

    @Before
    fun setUp() {
        temp = TestDatabases.openTemp()
        accountStore = AccountFixtures.newStore(temp, AccountFixtures.fastHasher())
        tokenStore = TokenFixtures.tokenStore(temp, AccountFixtures.fixedClock)
        handoffStore = HandoffFixtures.handoffStore(temp, tokenStore, AccountFixtures.fixedClock)
        transcriptionStore = SyncRouteTestSupport.newStore(temp)
    }

    @After
    fun tearDown() {
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

    private fun setKeyVersion(accountId: Long, keyVersion: Int) {
        runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement("UPDATE accounts SET key_version = ? WHERE id = ?").use { s ->
                    s.setInt(1, keyVersion); s.setLong(2, accountId); s.executeUpdate()
                }
            }
        }
    }

    private fun sessionToken(store: TokenStore, accountId: Long): String =
        TokenFixtures.issuedOf(runBlocking { store.issueSession(accountId) }).secret.reveal()

    private val SEALED_PACKAGE = HandoffFixtures.SEALED_PACKAGE_TEXT
    private val CHALLENGE = HandoffFixtures.CHALLENGE_FOR_SECRET(HandoffFixtures.SECRET)
    private val SECRET = HandoffFixtures.SECRET
    private val WRONG_SECRET = "X" + "Secret-" + "Secret-" + "Secret-" + "Secret-" + "Secret-" + "Secret-"
    private val EXCHANGE_REFUSED = """{"error":"unauthorized","message":"Invalid sign-in code or secret"}"""
    private val SESSION_REQUIRED = """{"error":"unauthorized","message":"Invalid or missing token"}"""
    private val BAD_REQUEST = """{"error":"bad_request","message":"Invalid request body"}"""

    private fun code(token: String): String = codeFor(token, SEALED_PACKAGE)

    private fun codeFor(token: String, packageText: String): String {
        var c: String? = null
        runBlocking {
            testApplication {
                application { syncApiModule(accountStore, tokenStore, transcriptionStore, handoffStore) }
                val r = client.post("/v1/auth/handoff/start") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"challenge":"$CHALLENGE","sealed_package":"$packageText"}""")
                }
                assertEquals(HttpStatusCode.Created, r.status)
                val b = r.bodyAsText(); val s = b.indexOf("\"code\":\"") + 8
                c = b.substring(s, s + 43)
            }
        }
        return checkNotNull(c)
    }

    private fun exch(body: String): Reply {
        var r: Reply? = null
        runBlocking {
            testApplication {
                application { syncApiModule(accountStore, tokenStore, transcriptionStore, handoffStore) }
                val resp = client.post("/v1/auth/handoff/exchange") {
                    contentType(ContentType.Application.Json); setBody(body)
                }
                r = Reply(resp.status, resp.bodyAsText())
            }
        }
        return checkNotNull(r)
    }

    @Test fun `exchange returns exactly 200 with token role key_version sealed_package username account_id`() {
        val aid = register("alice", 1); setKeyVersion(aid, 3)
        val token = sessionToken(tokenStore, aid); val c = code(token)
        val r = exch("""{"code":"$c","secret":"$SECRET"}""")
        assertEquals(HttpStatusCode.OK, r.status)
        val b = r.body
        assertTrue("role admin", b.contains("\"role\":\"admin\""))
        assertTrue("key_version 3", b.contains("\"key_version\":3"))
        assertTrue("sealed_package equals", b.contains("\"sealed_package\":\"$SEALED_PACKAGE\""))
        assertTrue("username alice", b.contains("\"username\":\"alice\""))
        assertTrue("account_id", b.contains("\"account_id\":$aid"))
        assertFalse("no secret echo", b.contains(SECRET))
    }

    @Test fun `exchange omits device_name and answers 200`() {
        val aid = register("alice", 1); val token = sessionToken(tokenStore, aid); val c = code(token)
        assertEquals(HttpStatusCode.OK, exch("""{"code":"$c","secret":"$SECRET"}""").status)
    }

    @Test fun `exchange accepts a null device_name and answers 200`() {
        val aid = register("alice", 1); val token = sessionToken(tokenStore, aid); val c = code(token)
        assertEquals(HttpStatusCode.OK, exch("""{"code":"$c","secret":"$SECRET","device_name":null}""").status)
    }

    @Test fun `exchange accepts a 64-char device_name and answers 200`() {
        val aid = register("alice", 1); val token = sessionToken(tokenStore, aid); val c = code(token)
        val name = "A".repeat(64)
        assertEquals(HttpStatusCode.OK, exch("""{"code":"$c","secret":"$SECRET","device_name":"$name"}""").status)
    }


    @Test fun `exchange rejects 65-char device_name and code stays live`() {
        val aid = register("alice", 1); val token = sessionToken(tokenStore, aid); val c = code(token)
        val name = "A".repeat(65)
        val refused = exch("""{"code":"$c","secret":"$SECRET","device_name":"$name"}""")
        assertEquals(HttpStatusCode.BadRequest, refused.status)
        assertEquals(BAD_REQUEST, refused.body)
        assertEquals(HttpStatusCode.OK, exch("""{"code":"$c","secret":"$SECRET"}""").status)
    }

    @Test fun `exchange rejects an ISO control character in device_name with 400`() {
        val aid = register("alice", 1); val token = sessionToken(tokenStore, aid); val c = code(token)
        val name = "Device" + "\\u001f"
        val r = exch("""{"code":"$c","secret":"$SECRET","device_name":"$name"}""")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertEquals(BAD_REQUEST, r.body)
    }

    @Test fun `exchange rejects an unpaired surrogate in device_name with 400`() {
        val aid = register("alice", 1); val token = sessionToken(tokenStore, aid); val c = code(token)
        val name = "Device" + "\\ud800"
        val r = exch("""{"code":"$c","secret":"$SECRET","device_name":"$name"}""")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertEquals(BAD_REQUEST, r.body)
    }


    @Test fun `exchange consumes the code so a second exchange of the same code is 401`() {
        val aid = register("alice", 1); val token = sessionToken(tokenStore, aid); val c = code(token)
        assertEquals(HttpStatusCode.OK, exch("""{"code":"$c","secret":"$SECRET"}""").status)
        val second = exch("""{"code":"$c","secret":"$SECRET"}""")
        assertEquals(HttpStatusCode.Unauthorized, second.status)
        assertEquals(EXCHANGE_REFUSED, second.body)
    }


    @Test fun `exchange unknown code is the one 401`() {
        val r = exch("""{"code":"${"C".repeat(43)}","secret":"$SECRET"}""")
        assertEquals(HttpStatusCode.Unauthorized, r.status)
        assertEquals(EXCHANGE_REFUSED, r.body)
    }

    @Test fun `exchange wrong secret leaves code live`() {
        val aid = register("alice", 1); val token = sessionToken(tokenStore, aid); val c = code(token)
        val wrong = exch("""{"code":"$c","secret":"$WRONG_SECRET"}""")
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        assertEquals(EXCHANGE_REFUSED, wrong.body)
        assertEquals(HttpStatusCode.OK, exch("""{"code":"$c","secret":"$SECRET"}""").status)
    }

    @Test fun `exchange wrong-shaped secret is the one 401`() {
        val aid = register("alice", 1); val token = sessionToken(tokenStore, aid); val c = code(token)
        val r = exch("""{"code":"$c","secret":"wrong-shaped"}""")
        assertEquals(HttpStatusCode.Unauthorized, r.status)
        assertEquals(EXCHANGE_REFUSED, r.body)
    }

    @Test fun `exchange malformed code is the one 401`() {
        val r = exch("""{"code":"not a valid code!","secret":"$SECRET"}""")
        assertEquals(HttpStatusCode.Unauthorized, r.status)
        assertEquals(EXCHANGE_REFUSED, r.body)
    }

    @Test fun `exchange missing secret is exactly 400`() {
        val r = exch("""{"code":"${"C".repeat(43)}"}""")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertEquals(BAD_REQUEST, r.body)
    }
}
