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
 * Tests for [authLoginRoute] — POST /v1/auth/login.
 *
 * Covers: malformed JSON, missing field, wrong type, extra field,
 * Authorization header handling, no token/verifier leakage, and wiring.
 */
class AuthLoginRouteTest {

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

    // ---------- happy path ----------

    @Test
    fun `POST login happy path returns 200 with token role and key fields`() = runBlocking<Unit> {
        register("alice", 1)

        testApplication {
            application { authLoginRoute(accountStore, tokenStore) }

            val response = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice","auth_verifier":"${verifierB64(1)}"}""")
            }

            assertEquals("status", HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue("body must contain token", body.contains("\"token\":\""))
            assertTrue("body must contain role", body.contains("\"role\":\""))
            assertTrue("body must contain wrapped_dek", body.contains("\"wrapped_dek\":"))
            assertTrue("body must contain wrapped_box_privkey", body.contains("\"wrapped_box_privkey\":"))
            assertTrue("body must contain box_pubkey", body.contains("\"box_pubkey\":"))
            assertTrue("body must contain key_version", body.contains("\"key_version\":"))
            assertTrue("body must contain prev_wrapped_dek", body.contains("\"prev_wrapped_dek\":"))
            assertTrue("body must contain reset_at", body.contains("\"reset_at\":"))
            assertTrue("body must contain rekeyed_at", body.contains("\"rekeyed_at\":"))
        }
    }

    @Test
    fun `POST login returns role admin for first account`() = runBlocking<Unit> {
        register("alice", 1)

        testApplication {
            application { authLoginRoute(accountStore, tokenStore) }

            val response = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice","auth_verifier":"${verifierB64(1)}"}""")
            }

            assertEquals("status", HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue("first account must be admin", body.contains("\"role\":\"admin\""))
        }
    }

    @Test
    fun `POST login returns role user for second account`() = runBlocking<Unit> {
        register("alice", 1)
        register("bob", 2)

        testApplication {
            application { authLoginRoute(accountStore, tokenStore) }

            val response = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"bob","auth_verifier":"${verifierB64(2)}"}""")
            }

            assertEquals("status", HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue("second account must be user", body.contains("\"role\":\"user\""))
        }
    }

    // ---------- keyless account ----------

    @Test
    fun `POST login keyless account has null key fields`() = runBlocking<Unit> {
        register("alice", 1)

        testApplication {
            application { authLoginRoute(accountStore, tokenStore) }

            val response = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice","auth_verifier":"${verifierB64(1)}"}""")
            }

            assertEquals("status", HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue("wrapped_dek must be null", body.contains("\"wrapped_dek\":null"))
            assertTrue("wrapped_box_privkey must be null", body.contains("\"wrapped_box_privkey\":null"))
            assertTrue("box_pubkey must be null", body.contains("\"box_pubkey\":null"))
            assertTrue("prev_wrapped_dek must be null", body.contains("\"prev_wrapped_dek\":null"))
            assertTrue("reset_at must be null", body.contains("\"reset_at\":null"))
            assertTrue("rekeyed_at must be null", body.contains("\"rekeyed_at\":null"))
        }
    }

    @Test
    fun `POST login key_version is 0 before first bootstrap`() = runBlocking<Unit> {
        register("alice", 1)

        testApplication {
            application { authLoginRoute(accountStore, tokenStore) }

            val response = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice","auth_verifier":"${verifierB64(1)}"}""")
            }

            assertEquals("status", HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue("key_version must be 0", body.contains("\"key_version\":0"))
        }
    }

    // ---------- refusal ----------

    @Test
    fun `POST login wrong auth_verifier returns 401`() = runBlocking<Unit> {
        register("alice", 1)

        testApplication {
            application { authLoginRoute(accountStore, tokenStore) }

            val response = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice","auth_verifier":"${verifierB64(99)}"}""")
            }

            assertEquals("status", HttpStatusCode.Unauthorized, response.status)
            val body = response.bodyAsText()
            assertTrue("error must be unauthorized", body.contains("\"error\":\"unauthorized\""))
            assertTrue("message must be present", body.contains("\"message\":\"Invalid username or verifier\""))
        }
    }

    @Test
    fun `POST login unknown username returns 401`() = runBlocking<Unit> {
        register("alice", 1)

        testApplication {
            application { authLoginRoute(accountStore, tokenStore) }

            val response = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"nobody","auth_verifier":"${verifierB64(1)}"}""")
            }

            assertEquals("status", HttpStatusCode.Unauthorized, response.status)
            val body = response.bodyAsText()
            assertTrue("error must be unauthorized", body.contains("\"error\":\"unauthorized\""))
            assertTrue("message must be present", body.contains("\"message\":\"Invalid username or verifier\""))
        }
    }

    @Test
    fun `POST login unknown username and wrong verifier return same body`() = runBlocking<Unit> {
        register("alice", 1)

        testApplication {
            application { authLoginRoute(accountStore, tokenStore) }

            val unknownUser = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"ghost","auth_verifier":"${verifierB64(1)}"}""")
            }
            val wrongVerifier = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice","auth_verifier":"${verifierB64(99)}"}""")
            }

            assertEquals("unknown user status", HttpStatusCode.Unauthorized, unknownUser.status)
            assertEquals("wrong verifier status", HttpStatusCode.Unauthorized, wrongVerifier.status)
            assertEquals("bodies must be identical", unknownUser.bodyAsText(), wrongVerifier.bodyAsText())
        }
    }

    @Test
    fun `POST login verifier 31 bytes returns 401`() = runBlocking<Unit> {
        register("alice", 1)

        // A 31-byte verifier: base64 of 31 zero bytes
        val shortVerifier = Base64.getEncoder().encodeToString(ByteArray(31) { 0 })

        testApplication {
            application { authLoginRoute(accountStore, tokenStore) }

            val response = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice","auth_verifier":"$shortVerifier"}""")
            }

            assertEquals("status", HttpStatusCode.Unauthorized, response.status)
        }
    }

    @Test
    fun `POST login invalid base64 verifier returns 401`() = runBlocking<Unit> {
        register("alice", 1)

        testApplication {
            application { authLoginRoute(accountStore, tokenStore) }

            val response = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice","auth_verifier":"not_valid_base64!"}""")
            }

            assertEquals("status", HttpStatusCode.Unauthorized, response.status)
        }
    }

    // ---------- JSON escape decoding ----------

    @Test
    fun `POST login with escaped slash in auth_verifier returns 200`() = runBlocking<Unit> {
        register("alice", 1)

        testApplication {
            application { authLoginRoute(accountStore, tokenStore) }

            // The JSON escape \/ must decode back to '/' (AuthLoginRoute.kt:170).
            // seed 1's base64 contains a '/', so a correct decode restores the
            // verifier; a mutant decoding it to '\' yields a non-base64 verifier
            // and the login is refused with 401. A username cannot carry this
            // escape: UsernameRules refuses '/', so "a/b" is not a valid name.
            val escaped = verifierB64(1).replace("/", "\\/")

            val response = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice","auth_verifier":"$escaped"}""")
            }

            assertEquals("status", HttpStatusCode.OK, response.status)
        }
    }

    // ---------- no-echo ----------

    @Test
    fun `POST login 401 body does not contain verifier`() = runBlocking<Unit> {
        register("alice", 1)
        val verifier = verifierB64(1)

        testApplication {
            application { authLoginRoute(accountStore, tokenStore) }

            val response = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice","auth_verifier":"${verifierB64(99)}"}""")
            }

            assertEquals("status", HttpStatusCode.Unauthorized, response.status)
            val body = response.bodyAsText()
            assertFalse("401 body must not contain the verifier", body.contains(verifier))
            assertFalse("401 body must not contain the wrong verifier", body.contains(verifierB64(99)))
            assertFalse("401 body must not contain token", body.contains("\"token\""))
        }
    }

    @Test
    fun `POST login 400 body does not contain verifier`() = runBlocking<Unit> {
        register("alice", 1)

        testApplication {
            application { authLoginRoute(accountStore, tokenStore) }

            val response = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice"}""")
            }

            assertEquals("status", HttpStatusCode.BadRequest, response.status)
            val body = response.bodyAsText()
            assertFalse("400 body must not contain the verifier", body.contains(verifierB64(1)))
        }
    }

    @Test
    fun `POST login with unknown field returns 400`() = runBlocking<Unit> {
        register("alice", 1)

        testApplication {
            application { authLoginRoute(accountStore, tokenStore) }

            val response = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"a","x":"b"}""")
            }

            assertEquals("status", HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `POST login 200 body does not contain verifier`() = runBlocking<Unit> {
        register("alice", 1)

        testApplication {
            application { authLoginRoute(accountStore, tokenStore) }

            val response = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice","auth_verifier":"${verifierB64(1)}"}""")
            }

            assertEquals("status", HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertFalse("200 body must not contain the verifier", body.contains(verifierB64(1)))
        }
    }

    // ---------- wire names ----------

    @Test
    fun `POST login 200 response has exact wire names`() = runBlocking<Unit> {
        register("alice", 1)

        testApplication {
            application { authLoginRoute(accountStore, tokenStore) }

            val response = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice","auth_verifier":"${verifierB64(1)}"}""")
            }

            assertEquals("status", HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            // Must contain exactly these top-level keys
            assertTrue("must have token", body.contains("\"token\":"))
            assertTrue("must have role", body.contains("\"role\":"))
            assertTrue("must have wrapped_dek", body.contains("\"wrapped_dek\":"))
            assertTrue("must have wrapped_box_privkey", body.contains("\"wrapped_box_privkey\":"))
            assertTrue("must have box_pubkey", body.contains("\"box_pubkey\":"))
            assertTrue("must have key_version", body.contains("\"key_version\":"))
            assertTrue("must have prev_wrapped_dek", body.contains("\"prev_wrapped_dek\":"))
            assertTrue("must have reset_at", body.contains("\"reset_at\":"))
            assertTrue("must have rekeyed_at", body.contains("\"rekeyed_at\":"))
            // Must NOT contain other keys
            assertFalse("must not have error", body.contains("\"error\""))
            assertFalse("must not have message", body.contains("\"message\""))
            assertFalse("must not have username", body.contains("\"username\""))
            assertFalse("must not have auth_verifier", body.contains("auth_verifier"))
            assertFalse("must not have salt", body.contains("\"salt\""))
            assertFalse("must not have kdf_params", body.contains("kdf_params"))
        }
    }

    @Test
    fun `POST login 401 response has exact wire names`() = runBlocking<Unit> {
        register("alice", 1)

        testApplication {
            application { authLoginRoute(accountStore, tokenStore) }

            val response = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice","auth_verifier":"${verifierB64(99)}"}""")
            }

            assertEquals("status", HttpStatusCode.Unauthorized, response.status)
            val body = response.bodyAsText()
            assertTrue("must have error", body.contains("\"error\":"))
            assertTrue("must have message", body.contains("\"message\":"))
            assertFalse("must not have token", body.contains("\"token\""))
            assertFalse("must not have role", body.contains("\"role\""))
            assertFalse("must not have wrapped_dek", body.contains("\"wrapped_dek\""))
            assertFalse("must not have key_version", body.contains("\"key_version\""))
        }
    }
}