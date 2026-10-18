package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.accounts.AccountStore
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TestDatabases
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import io.ktor.client.request.header
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

/**
 * Additional tests for [authLoginRoute] — POST /v1/auth/login.
 *
 * Covers: malformed JSON, missing field, wrong type, extra field,
 * Authorization header handling, no token/verifier leakage, and wiring.
 */
class AuthLoginRouteExtraTest {

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

    private fun register(name: String, seed: Int) {
        runBlocking {
            accountStore.register(name, AccountFixtures.salt(seed), AccountFixtures.validKdf, 1, AccountFixtures.verifier(seed))
        }
    }

    private fun verifierB64(seed: Int): String =
        Base64.getEncoder().encodeToString(AccountFixtures.verifierBytes(seed))

    @Test
    fun `POST login 400 response has exact wire names`() = runBlocking<Unit> {
        testApplication {
            application { authLoginRoute(accountStore, tokenStore) }

            val response = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("{}")
            }

            assertEquals("status", HttpStatusCode.BadRequest, response.status)
            val body = response.bodyAsText()
            assertTrue("must have error", body.contains("\"error\":"))
            assertTrue("must have message", body.contains("\"message\":"))
            assertFalse("must not have token", body.contains("\"token\""))
            assertFalse("must not have role", body.contains("\"role\""))
        }
    }

    // ---------- 400 refusals ----------

    @Test
    fun `POST login malformed JSON body returns 400`() = runBlocking<Unit> {
        testApplication {
            application { authLoginRoute(accountStore, tokenStore) }

            val response = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("{invalid")
            }

            assertEquals("status", HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `POST login missing auth_verifier returns 400`() = runBlocking<Unit> {
        testApplication {
            application { authLoginRoute(accountStore, tokenStore) }

            val response = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice"}""")
            }

            assertEquals("status", HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `POST login missing username returns 400`() = runBlocking<Unit> {
        testApplication {
            application { authLoginRoute(accountStore, tokenStore) }

            val response = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"auth_verifier":"${verifierB64(1)}"}""")
            }

            assertEquals("status", HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `POST login wrong JSON type for username returns 400`() = runBlocking<Unit> {
        testApplication {
            application { authLoginRoute(accountStore, tokenStore) }

            val response = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":123,"auth_verifier":"${verifierB64(1)}"}""")
            }

            assertEquals("status", HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `POST login wrong JSON type for auth_verifier returns 400`() = runBlocking<Unit> {
        testApplication {
            application { authLoginRoute(accountStore, tokenStore) }

            val response = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice","auth_verifier":123}""")
            }

            assertEquals("status", HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `POST login null username returns 400`() = runBlocking<Unit> {
        testApplication {
            application { authLoginRoute(accountStore, tokenStore) }

            val response = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":null,"auth_verifier":"${verifierB64(1)}"}""")
            }

            assertEquals("status", HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `POST login null auth_verifier returns 400`() = runBlocking<Unit> {
        testApplication {
            application { authLoginRoute(accountStore, tokenStore) }

            val response = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice","auth_verifier":null}""")
            }

            assertEquals("status", HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `POST login extra unknown field returns 400`() = runBlocking<Unit> {
        register("alice", 1)

        testApplication {
            application { authLoginRoute(accountStore, tokenStore) }

            val response = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice","auth_verifier":"${verifierB64(1)}","extra":"field"}""")
            }

            assertEquals("status", HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `POST login empty body returns 400`() = runBlocking<Unit> {
        testApplication {
            application { authLoginRoute(accountStore, tokenStore) }

            val response = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("")
            }

            assertEquals("status", HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `POST login empty JSON object returns 400`() = runBlocking<Unit> {
        testApplication {
            application { authLoginRoute(accountStore, tokenStore) }

            val response = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("{}")
            }

            assertEquals("status", HttpStatusCode.BadRequest, response.status)
        }
    }

    // ---------- Authorization header parsing ----------

    @Test
    fun `POST login works without Authorization header`() = runBlocking<Unit> {
        register("alice", 1)

        testApplication {
            application { authLoginRoute(accountStore, tokenStore) }

            val response = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice","auth_verifier":"${verifierB64(1)}"}""")
            }

            assertEquals("status without auth header", HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun `POST login ignores Authorization header when present`() = runBlocking<Unit> {
        register("alice", 1)

        testApplication {
            application { authLoginRoute(accountStore, tokenStore) }

            val response = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice","auth_verifier":"${verifierB64(1)}"}""")
                header("Authorization", "Bearer some_token")
            }

            assertEquals("status with auth header", HttpStatusCode.OK, response.status)
        }
    }

    // ---------- no token/verifier leakage ----------

    @Test
    fun `POST login error body does not contain token or verifier`() = runBlocking<Unit> {
        register("alice", 1)

        testApplication {
            application { authLoginRoute(accountStore, tokenStore) }

            val response = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"nobody","auth_verifier":"${verifierB64(1)}"}""")
            }

            assertEquals("status", HttpStatusCode.Unauthorized, response.status)
            val body = response.bodyAsText()
            assertFalse("error body must not contain token", body.contains("\"token\""))
            assertFalse("error body must not contain the verifier value", body.contains(verifierB64(1)))
            assertFalse("error body must not contain auth_verifier", body.contains("auth_verifier"))
            assertFalse("error body must not contain wrapped_dek", body.contains("wrapped_dek"))
        }
    }

    @Test
    fun `POST login 200 body does not contain the auth_verifier key`() = runBlocking<Unit> {
        register("alice", 1)

        testApplication {
            application { authLoginRoute(accountStore, tokenStore) }

            val response = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice","auth_verifier":"${verifierB64(1)}"}""")
            }

            assertEquals("status", HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertFalse("200 body must not contain auth_verifier", body.contains("auth_verifier"))
        }
    }

    // ---------- wiring ----------

    @Test
    fun `POST login route is wired at the correct path`() = runBlocking<Unit> {
        register("alice", 1)

        testApplication {
            application { authLoginRoute(accountStore, tokenStore) }

            val response = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice","auth_verifier":"${verifierB64(1)}"}""")
            }

            assertTrue(
                "route must respond at /v1/auth/login, got ${response.status}",
                response.status == HttpStatusCode.OK || response.status == HttpStatusCode.Unauthorized,
            )
        }
    }

    @Test
    fun `POST login returns 404 for unknown path`() = runBlocking<Unit> {
        register("alice", 1)

        testApplication {
            application { authLoginRoute(accountStore, tokenStore) }

            val response = client.post("/v1/auth/unknown") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice","auth_verifier":"${verifierB64(1)}"}""")
            }

            assertEquals("unknown path must be 404", HttpStatusCode.NotFound, response.status)
        }
    }

    // ---------- content type ----------

    @Test
    fun `POST login returns JSON content type on 200`() = runBlocking<Unit> {
        register("alice", 1)

        testApplication {
            application { authLoginRoute(accountStore, tokenStore) }

            val response = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice","auth_verifier":"${verifierB64(1)}"}""")
            }

            assertEquals(
                "content type",
                ContentType.Application.Json,
                response.contentType()?.withoutParameters(),
            )
        }
    }

    @Test
    fun `POST login returns JSON content type on 401`() = runBlocking<Unit> {
        register("alice", 1)

        testApplication {
            application { authLoginRoute(accountStore, tokenStore) }

            val response = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice","auth_verifier":"${verifierB64(99)}"}""")
            }

            assertEquals(
                "content type",
                ContentType.Application.Json,
                response.contentType()?.withoutParameters(),
            )
        }
    }

    @Test
    fun `POST login returns JSON content type on 400`() = runBlocking<Unit> {
        testApplication {
            application { authLoginRoute(accountStore, tokenStore) }

            val response = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("{}")
            }

            assertEquals(
                "content type",
                ContentType.Application.Json,
                response.contentType()?.withoutParameters(),
            )
        }
    }

    // ---------- case-insensitive username ----------

    @Test
    fun `POST login finds account case-insensitively`() = runBlocking<Unit> {
        register("alice", 1)

        testApplication {
            application { authLoginRoute(accountStore, tokenStore) }

            val response = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"ALICE","auth_verifier":"${verifierB64(1)}"}""")
            }

            assertEquals("status", HttpStatusCode.OK, response.status)
        }
    }

    // ---------- token is unique per login ----------

    @Test
    fun `POST login issues a token on success`() = runBlocking<Unit> {
        register("alice", 1)

        testApplication {
            application { authLoginRoute(accountStore, tokenStore) }

            val response = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice","auth_verifier":"${verifierB64(1)}"}""")
            }

            assertEquals("status", HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            // Token must be a non-empty string
            assertTrue("token must be present", body.contains("\"token\":\""))
            assertTrue("token must be non-empty", !body.contains("\"token\":\"\""))
        }
    }
}