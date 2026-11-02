package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.accounts.AccountStore
import dev.breaker.server.syncapi.accounts.VerifierHasher
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TestDatabases
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64

/** Tests for the locked answer: 423 + Retry-After on POST /v1/auth/login. */
class AuthLoginRouteLockedTest {

    private lateinit var temp: TempDatabase
    private lateinit var accountStore: AccountStore
    private lateinit var tokenStore: TokenStore

    @Before
    fun setUp() {
        temp = TestDatabases.openTemp()
        accountStore = AccountFixtures.newStore(temp)
        tokenStore = TokenFixtures.tokenStore(temp, AccountFixtures.fixedClock)
    }

    @After
    fun tearDown() {
        temp.close()
    }

    private val start = Instant.ofEpochMilli(AccountFixtures.FIXED_MILLIS)

    private fun register(name: String, seed: Int) {
        runBlocking {
            accountStore.register(name, AccountFixtures.salt(seed), AccountFixtures.validKdf, 1, AccountFixtures.verifier(seed))
        }
    }

    private fun verifierB64(seed: Int): String =
        Base64.getEncoder().encodeToString(AccountFixtures.verifierBytes(seed))

    private fun loginBody(username: String = "alice", seed: Int = 1): String =
        """{"username":"$username","auth_verifier":"${verifierB64(seed)}"}"""

    @Test
    fun `fifth wrong try returns plain 401`() = runBlocking {
        register("alice", 1)
        testApplication {
            application { authLoginRoute(accountStore, tokenStore) }

            repeat(4) {
                client.post("/v1/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(loginBody("alice", 2))
                }
            }
            val response = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(loginBody("alice", 2))
            }
            assertEquals("fifth wrong is plain 401", HttpStatusCode.Unauthorized, response.status)
        }
    }

    @Test
    fun `sixth wrong try inside the wait returns 423 with Retry-After`() = runBlocking {
        register("alice", 1)
        testApplication {
            application { authLoginRoute(accountStore, tokenStore) }

            repeat(5) {
                client.post("/v1/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(loginBody("alice", 2))
                }
            }
            val response = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(loginBody("alice", 2))
            }
            assertEquals("sixth wrong inside wait is 423", HttpStatusCode.Locked, response.status)
            val retryAfter = response.headers["Retry-After"] ?: ""
            assertTrue("Retry-After header present", retryAfter.isNotEmpty())
            val body = response.bodyAsText()
            assertTrue("body has locked error", body.contains("\"error\":\"locked\""))
            assertTrue("body says minutes", body.contains("Try again in"))
        }
    }

    @Test
    fun `right password inside the wait returns 423`() = runBlocking {
        register("alice", 1)
        testApplication {
            application { authLoginRoute(accountStore, tokenStore) }

            repeat(5) {
                client.post("/v1/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(loginBody("alice", 2))
                }
            }
            val response = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(loginBody("alice", 1))
            }
            assertEquals("right password inside wait is 423", HttpStatusCode.Locked, response.status)
        }
    }

    @Test
    fun `423 body is byte-for-byte equal for known and unknown name`() = runBlocking {
        register("alice", 1)
        testApplication {
            application { authLoginRoute(accountStore, tokenStore) }

            repeat(5) {
                client.post("/v1/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(loginBody("alice", 2))
                }
            }
            // Lock the unknown name too: five wrong logins for a name with no account
            repeat(5) {
                client.post("/v1/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(loginBody("nobody", 2))
                }
            }
            val known = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(loginBody("alice", 2))
            }
            val unknown = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(loginBody("nobody", 2))
            }
            assertEquals("known 423 status", HttpStatusCode.Locked, known.status)
            assertEquals("unknown 423 status", HttpStatusCode.Locked, unknown.status)
            assertEquals("known Retry-After", known.headers["Retry-After"], unknown.headers["Retry-After"])
            assertEquals("known 423 body", known.bodyAsText(), unknown.bodyAsText())
        }
    }

    @Test
    fun `right password after wait expires returns 200`() = runBlocking {
        register("alice", 1)
        testApplication {
            application { authLoginRoute(accountStore, tokenStore) }
            repeat(5) {
                client.post("/v1/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(loginBody("alice", 2))
                }
            }
        }
        val laterStore = AccountFixtures.newStore(
            temp,
            VerifierHasher(iterations = AccountFixtures.FAST_ITERATIONS),
            mutableClock(start.plusSeconds(16 * 60L)),
        )
        testApplication {
            application { authLoginRoute(laterStore, tokenStore) }
            val response = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(loginBody("alice", 1))
            }
            assertEquals("right password after the wait is 200", HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue("body has token", body.contains("\"token\":\""))
        }
    }

    @Test
    fun `a lock with 899 seconds left says 15 minutes and Retry-After 899`() = runBlocking {
        register("alice", 1)
        testApplication {
            application { authLoginRoute(accountStore, tokenStore) }
            repeat(5) {
                client.post("/v1/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(loginBody("alice", 2))
                }
            }
        }
        val laterStore = AccountFixtures.newStore(
            temp,
            VerifierHasher(iterations = AccountFixtures.FAST_ITERATIONS),
            mutableClock(start.plusSeconds(1)),
        )
        testApplication {
            application { authLoginRoute(laterStore, tokenStore) }
            val response = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(loginBody("alice", 2))
            }
            assertEquals("a locked name is 423", HttpStatusCode.Locked, response.status)
            assertEquals("899 seconds are left", "899", response.headers["Retry-After"])
            assertTrue("899 seconds left say 15 minutes", response.bodyAsText().contains("Try again in 15 minutes."))
        }
    }

    private fun mutableClock(instant: Instant): Clock =
        object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = instant
        }
}
