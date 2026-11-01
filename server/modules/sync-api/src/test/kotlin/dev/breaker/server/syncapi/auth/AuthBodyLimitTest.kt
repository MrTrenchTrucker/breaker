package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.accounts.AccountStore
import dev.breaker.server.syncapi.accounts.SaltLookup
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.TokenFixtures
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.util.Base64

internal class AuthBodyLimitTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var store: AccountStore

    @Before
    fun setup() {
        temp = openTemp()
        store = AccountFixtures.newStore(temp)
    }

    // -- helpers -----------------------------------------------------------

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun validSalt(): String = b64(ByteArray(16) { 1 })

    private fun validVerifier(): String = b64(ByteArray(32) { 2 })

    private fun registerBody(
        username: String = "alice",
        salt: String = validSalt(),
        memoryKib: Int = 65536,
        iterations: Int = 3,
        parallelism: Int = 1,
        kdfVersion: Int = 1,
        verifier: String = validVerifier(),
    ): String =
        """{"username":"$username","salt":"$salt","kdf_params":{"memory_kib":$memoryKib,"iterations":$iterations,"parallelism":$parallelism},"kdf_version":$kdfVersion,"auth_verifier":"$verifier"}"""

    private fun saltBody(username: String = "nobody"): String = """{"username":"$username"}"""

    private fun verifierB64(seed: Int): String = b64(AccountFixtures.verifierBytes(seed))

    private fun loginBody(username: String = "alice", seed: Int = 1): String =
        """{"username":"$username","auth_verifier":"${verifierB64(seed)}"}"""

    /** Pads body with trailing spaces to exactly n bytes. Every body ends in `}` so trailing spaces are harmless. */
    private fun padTo(body: String, n: Int): String {
        require(body.length <= n) { "body too long: ${body.length} > $n" }
        return body + " ".repeat(n - body.length)
    }

    // -- tests -------------------------------------------------------------

    @Test
    fun `a register body over the cap is refused with 413 and the fixed error`() = runBlocking {
        val over = padTo(registerBody(), 4097)
        testApplication {
            application { routing { authRegisterRoute(store) } }
            val response = client.post("/v1/auth/register") {
                setBody(over)
                contentType(ContentType.Application.Json)
            }
            assertEquals("status", HttpStatusCode.PayloadTooLarge, response.status)
            assertEquals(
                "body",
                """{"error":"payload_too_large","message":"Request body exceeds maximum allowed size"}""",
                response.bodyAsText(),
            )
        }
    }

    @Test
    fun `a salt body over the cap is refused with 413`() = runBlocking {
        val over = padTo(saltBody(), 4097)
        testApplication {
            application { authSaltRoute(store) }
            val response = client.post("/v1/auth/salt") {
                setBody(over)
                contentType(ContentType.Application.Json)
            }
            assertEquals("status", HttpStatusCode.PayloadTooLarge, response.status)
            assertEquals(
                "body",
                """{"error":"payload_too_large","message":"Request body exceeds maximum allowed size"}""",
                response.bodyAsText(),
            )
        }
    }

    @Test
    fun `a login body over the cap is refused with 413`() = runBlocking {
        val over = padTo(loginBody(), 4097)
        testApplication {
            val tokenStore = TokenFixtures.tokenStore(temp, AccountFixtures.fixedClock)
            application { authLoginRoute(store, tokenStore) }
            val response = client.post("/v1/auth/login") {
                setBody(over)
                contentType(ContentType.Application.Json)
            }
            assertEquals("status", HttpStatusCode.PayloadTooLarge, response.status)
            assertEquals(
                "body",
                """{"error":"payload_too_large","message":"Request body exceeds maximum allowed size"}""",
                response.bodyAsText(),
            )
        }
    }

    @Test
    fun `a refused register stores nothing`() = runBlocking {
        val over = padTo(registerBody(), 4097)
        testApplication {
            application { routing { authRegisterRoute(store) } }
            client.post("/v1/auth/register") {
                setBody(over)
                contentType(ContentType.Application.Json)
            }
        }
        val lookup = store.saltFor("alice")
        assertEquals("lookup", SaltLookup.NoSuchAccount, lookup)
    }

    @Test
    fun `a register body of exactly the cap is accepted`() = runBlocking {
        val at = padTo(registerBody(), 4096)
        testApplication {
            application { routing { authRegisterRoute(store) } }
            val response = client.post("/v1/auth/register") {
                setBody(at)
                contentType(ContentType.Application.Json)
            }
            assertEquals("status", HttpStatusCode.Created, response.status)
        }
    }

    @Test
    fun `a salt body of exactly the cap is not refused`() = runBlocking {
        val at = padTo(saltBody(), 4096)
        testApplication {
            application { authSaltRoute(store) }
            val response = client.post("/v1/auth/salt") {
                setBody(at)
                contentType(ContentType.Application.Json)
            }
            assertFalse("not 413", response.status == HttpStatusCode.PayloadTooLarge)
            assertEquals("status", HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `a login body of exactly the cap is not refused`() = runBlocking {
        val at = padTo(loginBody(), 4096)
        testApplication {
            val tokenStore = TokenFixtures.tokenStore(temp, AccountFixtures.fixedClock)
            application { authLoginRoute(store, tokenStore) }
            val response = client.post("/v1/auth/login") {
                setBody(at)
                contentType(ContentType.Application.Json)
            }
            assertFalse("not 413", response.status == HttpStatusCode.PayloadTooLarge)
            assertEquals("status", HttpStatusCode.Unauthorized, response.status)
        }
    }

    @Test
    fun `a chunked body over the cap is refused`() = runBlocking {
        val bytes = padTo(registerBody(), 4097).toByteArray()
        testApplication {
            application { routing { authRegisterRoute(store) } }
            val response = client.post("/v1/auth/register") {
                setBody(object : OutgoingContent.ReadChannelContent() {
                    override fun readFrom(): ByteReadChannel = ByteReadChannel(bytes)
                })
                contentType(ContentType.Application.Json)
            }
            assertEquals("status", HttpStatusCode.PayloadTooLarge, response.status)
        }
    }

    @Test
    fun `the cap is 4096 bytes`() = runBlocking {
        assertEquals("cap", 4096L, MAX_AUTH_BODY_BYTES)
    }
}
