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
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class AuthHandoffExchangeCapTest {

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

    private fun sessionToken(store: TokenStore, accountId: Long): String =
        TokenFixtures.issuedOf(runBlocking { store.issueSession(accountId) }).secret.reveal()

    private val SEALED_PACKAGE = HandoffFixtures.SEALED_PACKAGE_TEXT
    private val CHALLENGE = HandoffFixtures.CHALLENGE_FOR_SECRET(HandoffFixtures.SECRET)
    private val SECRET = HandoffFixtures.SECRET
    private val PAYLOAD_TOO_LARGE = """{"error":"payload_too_large","message":"Request body exceeds maximum allowed size"}"""

    // ---------- start: cap ----------

    @Test
    fun `start refuses a chunked body one byte over 4096 with exactly 413`() {
        val accountId = register("alice", 1)
        val token = sessionToken(tokenStore, accountId)
        val bytes = "x".repeat(4097).toByteArray()
        var reply: Reply? = null
        runBlocking {
            testApplication {
                application { syncApiModule(accountStore, tokenStore, transcriptionStore, handoffStore) }
                val response = client.post("/v1/auth/handoff/start") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    setBody(object : io.ktor.http.content.OutgoingContent.ReadChannelContent() {
                        override fun readFrom(): ByteReadChannel = ByteReadChannel(bytes)
                    })
                    contentType(ContentType.Application.Json)
                }
                reply = Reply(response.status, response.bodyAsText())
            }
        }
        assertEquals("status", HttpStatusCode.PayloadTooLarge, checkNotNull(reply).status)
        assertEquals("body", PAYLOAD_TOO_LARGE, checkNotNull(reply).body)
    }

    // ---------- exchange: cap ----------

    @Test
    fun `exchange refuses a declared body one byte over 4096 with exactly 413`() {
        val accountId = register("alice", 1)
        val token = sessionToken(tokenStore, accountId)
        val code = startAndReturnCode(token)
        val over = "x".repeat(4097)
        var reply: Reply? = null
        runBlocking {
            testApplication {
                application { syncApiModule(accountStore, tokenStore, transcriptionStore, handoffStore) }
                val response = client.post("/v1/auth/handoff/exchange") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"code":"$code","secret":"$SECRET","device_name":"$over"}""")
                }
                reply = Reply(response.status, response.bodyAsText())
            }
        }
        assertEquals("status", HttpStatusCode.PayloadTooLarge, checkNotNull(reply).status)
        assertEquals("body", PAYLOAD_TOO_LARGE, checkNotNull(reply).body)
    }

    @Test
    fun `exchange refuses a chunked body one byte over 4096 with exactly 413`() {
        val accountId = register("alice", 1)
        val token = sessionToken(tokenStore, accountId)
        val code = startAndReturnCode(token)
        val bytes = ("""{"code":"$code","secret":"$SECRET","device_name":"${"x".repeat(4097)}"}""").toByteArray()
        var reply: Reply? = null
        runBlocking {
            testApplication {
                application { syncApiModule(accountStore, tokenStore, transcriptionStore, handoffStore) }
                val response = client.post("/v1/auth/handoff/exchange") {
                    setBody(object : io.ktor.http.content.OutgoingContent.ReadChannelContent() {
                        override fun readFrom(): ByteReadChannel = ByteReadChannel(bytes)
                    })
                    contentType(ContentType.Application.Json)
                }
                reply = Reply(response.status, response.bodyAsText())
            }
        }
        assertEquals("status", HttpStatusCode.PayloadTooLarge, checkNotNull(reply).status)
        assertEquals("body", PAYLOAD_TOO_LARGE, checkNotNull(reply).body)
    }

    @Test
    fun `exchange accepts a body of exactly 4096 bytes`() {
        val accountId = register("alice", 1)
        val token = sessionToken(tokenStore, accountId)
        val code = startAndReturnCode(token)
        val base = """{"code":"$code","secret":"$SECRET"}"""
        val padded = base.dropLast(1) + " ".repeat(4096 - base.length) + "}"
        var reply: Reply? = null
        runBlocking {
            testApplication {
                application { syncApiModule(accountStore, tokenStore, transcriptionStore, handoffStore) }
                val response = client.post("/v1/auth/handoff/exchange") {
                    contentType(ContentType.Application.Json)
                    setBody(padded)
                }
                reply = Reply(response.status, response.bodyAsText())
            }
        }
        assertEquals("status", HttpStatusCode.OK, checkNotNull(reply).status)
    }

    // ---------- startAndReturnCode helper used by cap tests ----------

    private fun startAndReturnCode(token: String): String {
        var code: String? = null
        runBlocking {
            testApplication {
                application { syncApiModule(accountStore, tokenStore, transcriptionStore, handoffStore) }
                val response = client.post("/v1/auth/handoff/start") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"challenge":"$CHALLENGE","sealed_package":"$SEALED_PACKAGE"}""")
                }
                assertEquals("start status", HttpStatusCode.Created, response.status)
                val body = response.bodyAsText()
                val codeStart = body.indexOf("\"code\":\"") + 8
                code = body.substring(codeStart, codeStart + 43)
            }
        }
        return checkNotNull(code)
    }
}
