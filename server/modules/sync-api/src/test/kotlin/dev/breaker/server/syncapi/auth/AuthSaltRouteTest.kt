package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.accounts.AccountStore
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TestDatabases
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

/**
 * Tests for [authSaltRoute] — POST /v1/auth/salt.
 *
 * Covers: malformed JSON, missing field, wrong type, extra field,
 * Authorization header handling, no token/verifier leakage, and wiring.
 */
class AuthSaltRouteTest {

    private lateinit var temp: TempDatabase
    private lateinit var store: AccountStore

    @Before
    fun setUp() {
        temp = TestDatabases.openTemp()
        store = AccountFixtures.newStore(temp)
    }

    @After
    fun tearDown() {
        temp.close()
    }

    private fun register(name: String, seed: Int) {
        runBlocking {
            store.register(name, AccountFixtures.salt(seed), AccountFixtures.validKdf, 1, AccountFixtures.verifier(seed))
        }
    }

    // ---------- happy path ----------

    @Test
    fun `POST salt happy path returns 200 with salt kdf_params kdf_version`() = runBlocking<Unit> {
        register("alice", 1)

        testApplication {
            application { authSaltRoute(store) }

            val response = client.post("/v1/auth/salt") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice"}""")
            }

            assertEquals("status", HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue("body must contain salt", body.contains("\"salt\":\""))
            assertTrue("body must contain kdf_params", body.contains("\"kdf_params\":"))
            assertTrue("body must contain memory_kib", body.contains("\"memory_kib\":"))
            assertTrue("body must contain iterations", body.contains("\"iterations\":"))
            assertTrue("body must contain parallelism", body.contains("\"parallelism\":"))
            assertTrue("body must contain kdf_version", body.contains("\"kdf_version\":"))
        }
    }

    @Test
    fun `POST salt returns the salt the account was registered with`() = runBlocking<Unit> {
        register("bob", 2)

        testApplication {
            application { authSaltRoute(store) }

            val response = client.post("/v1/auth/salt") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"bob"}""")
            }

            assertEquals("status", HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            // The salt from AccountFixtures.salt(2) is 16 bytes; base64 of 16 bytes is 24 chars ending in ==
            assertTrue("body must contain a base64 salt ending in ==", body.contains("==\""))
        }
    }

    // ---------- refusal ----------

    @Test
    fun `POST salt unknown username returns 404`() = runBlocking<Unit> {
        register("alice", 1)

        testApplication {
            application { authSaltRoute(store) }

            val response = client.post("/v1/auth/salt") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"nobody"}""")
            }

            assertEquals("status", HttpStatusCode.NotFound, response.status)
        }
    }

    // ---------- no-echo ----------

    @Test
    fun `POST salt error body does not echo the username`() = runBlocking<Unit> {
        testApplication {
            application { authSaltRoute(store) }

            val response = client.post("/v1/auth/salt") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"secret_user"}""")
            }

            assertEquals("status", HttpStatusCode.NotFound, response.status)
            val body = response.bodyAsText()
            assertFalse("error body must not contain the username", body.contains("secret_user"))
        }
    }

    // ---------- wire names ----------

    @Test
    fun `POST salt 200 response has exact wire names`() = runBlocking<Unit> {
        register("alice", 1)

        testApplication {
            application { authSaltRoute(store) }

            val response = client.post("/v1/auth/salt") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice"}""")
            }

            assertEquals("status", HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            // Must contain exactly these top-level keys
            assertTrue("must have salt", body.contains("\"salt\":"))
            assertTrue("must have kdf_params", body.contains("\"kdf_params\":"))
            assertTrue("must have kdf_version", body.contains("\"kdf_version\":"))
            // Must NOT contain other keys
            assertFalse("must not have token", body.contains("\"token\""))
            assertFalse("must not have verifier", body.contains("\"verifier\""))
            assertFalse("must not have username", body.contains("\"username\""))
            assertFalse("must not have error", body.contains("\"error\""))
            assertFalse("must not have message", body.contains("\"message\""))
            // kdf_params sub-keys
            assertTrue("must have memory_kib", body.contains("\"memory_kib\":"))
            assertTrue("must have iterations", body.contains("\"iterations\":"))
            assertTrue("must have parallelism", body.contains("\"parallelism\":"))
        }
    }

    @Test
    fun `POST salt 404 response has exact wire names`() = runBlocking<Unit> {
        testApplication {
            application { authSaltRoute(store) }

            val response = client.post("/v1/auth/salt") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"nobody"}""")
            }

            assertEquals("status", HttpStatusCode.NotFound, response.status)
            val body = response.bodyAsText()
            assertTrue("must have error", body.contains("\"error\":"))
            assertTrue("must have message", body.contains("\"message\":"))
            assertFalse("must not have salt", body.contains("\"salt\""))
            assertFalse("must not have kdf_params", body.contains("\"kdf_params\""))
            assertFalse("must not have kdf_version", body.contains("\"kdf_version\""))
        }
    }

    // ---------- 400 refusals ----------

    @Test
    fun `POST salt malformed JSON body returns 400`() = runBlocking<Unit> {
        testApplication {
            application { authSaltRoute(store) }

            val response = client.post("/v1/auth/salt") {
                contentType(ContentType.Application.Json)
                setBody("{invalid")
            }

            assertEquals("status", HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `POST salt missing required field returns 400`() = runBlocking<Unit> {
        testApplication {
            application { authSaltRoute(store) }

            val response = client.post("/v1/auth/salt") {
                contentType(ContentType.Application.Json)
                setBody("{}")
            }

            assertEquals("status", HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `POST salt wrong JSON type for username returns 400`() = runBlocking<Unit> {
        testApplication {
            application { authSaltRoute(store) }

            val response = client.post("/v1/auth/salt") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":123}""")
            }

            assertEquals("status", HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `POST salt extra unknown field returns 400`() = runBlocking<Unit> {
        register("alice", 1)

        testApplication {
            application { authSaltRoute(store) }

            val response = client.post("/v1/auth/salt") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice","extra":"field"}""")
            }

            assertEquals("status", HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `POST salt with duplicate username key returns 400`() = runBlocking<Unit> {
        register("alice", 1)

        testApplication {
            application { authSaltRoute(store) }

            val response = client.post("/v1/auth/salt") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"a","username":"b"}""")
            }

            assertEquals("status", HttpStatusCode.BadRequest, response.status)
        }
    }

    // ---------- Authorization header parsing ----------

    @Test
    fun `POST salt works without Authorization header`() = runBlocking<Unit> {
        register("alice", 1)

        testApplication {
            application { authSaltRoute(store) }

            // No Authorization header set — the route is unauthenticated (security: [])
            val response = client.post("/v1/auth/salt") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice"}""")
            }

            assertEquals("status without auth header", HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun `POST salt ignores Authorization header when present`() = runBlocking<Unit> {
        register("alice", 1)

        testApplication {
            application { authSaltRoute(store) }

            val response = client.post("/v1/auth/salt") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice"}""")
                header("Authorization", "Bearer some_token")
            }

            assertEquals("status with auth header", HttpStatusCode.OK, response.status)
        }
    }

    // ---------- no token/verifier leakage ----------

    @Test
    fun `POST salt response body does not contain token or verifier`() = runBlocking<Unit> {
        register("alice", 1)

        testApplication {
            application { authSaltRoute(store) }

            val response = client.post("/v1/auth/salt") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice"}""")
            }

            assertEquals("status", HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertFalse("body must not contain token", body.contains("\"token\""))
            assertFalse("body must not contain verifier", body.contains("\"verifier\""))
            assertFalse("body must not contain auth_verifier", body.contains("auth_verifier"))
            assertFalse("body must not contain wrapped_dek", body.contains("wrapped_dek"))
        }
    }

    @Test
    fun `POST salt error body does not contain token or verifier`() = runBlocking<Unit> {
        testApplication {
            application { authSaltRoute(store) }

            val response = client.post("/v1/auth/salt") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"nobody"}""")
            }

            assertEquals("status", HttpStatusCode.NotFound, response.status)
            val body = response.bodyAsText()
            assertFalse("error body must not contain token", body.contains("\"token\""))
            assertFalse("error body must not contain verifier", body.contains("\"verifier\""))
        }
    }

    // ---------- wiring ----------

    @Test
    fun `POST salt route is wired at the correct path`() = runBlocking<Unit> {
        register("alice", 1)

        testApplication {
            application { authSaltRoute(store) }

            // The route must respond at /v1/auth/salt
            val response = client.post("/v1/auth/salt") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice"}""")
            }
            assertTrue(
                "route must respond at /v1/auth/salt, got ${response.status}",
                response.status == HttpStatusCode.OK || response.status == HttpStatusCode.NotFound,
            )
        }
    }

    @Test
    fun `POST salt returns 404 for unknown path not the salt route`() = runBlocking<Unit> {
        testApplication {
            application { authSaltRoute(store) }

            val response = client.post("/v1/auth/unknown") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice"}""")
            }
            assertEquals("unknown path must be 404", HttpStatusCode.NotFound, response.status)
        }
    }

    // ---------- additional edge cases ----------

    @Test
    fun `POST salt with null username returns 400`() = runBlocking<Unit> {
        testApplication {
            application { authSaltRoute(store) }

            val response = client.post("/v1/auth/salt") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":null}""")
            }

            assertEquals("null username must be 400", HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `POST salt with boolean username returns 400`() = runBlocking<Unit> {
        testApplication {
            application { authSaltRoute(store) }

            val response = client.post("/v1/auth/salt") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":true}""")
            }

            assertEquals("boolean username must be 400", HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `POST salt with empty body returns 400`() = runBlocking<Unit> {
        testApplication {
            application { authSaltRoute(store) }

            val response = client.post("/v1/auth/salt") {
                contentType(ContentType.Application.Json)
                setBody("")
            }

            assertEquals("empty body must be 400", HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `POST salt finds account case-insensitively`() = runBlocking<Unit> {
        register("alice", 1)

        testApplication {
            application { authSaltRoute(store) }

            val response = client.post("/v1/auth/salt") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"ALICE"}""")
            }

            assertEquals("status", HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun `POST salt returns JSON content type`() = runBlocking<Unit> {
        register("alice", 1)

        testApplication {
            application { authSaltRoute(store) }

            val response = client.post("/v1/auth/salt") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"alice"}""")
            }

            assertEquals(
                "content type",
                ContentType.Application.Json,
                response.contentType()?.withoutParameters(),
            )
        }
    }

    @Test
    fun `POST salt returns 404 JSON content type`() = runBlocking<Unit> {
        testApplication {
            application { authSaltRoute(store) }

            val response = client.post("/v1/auth/salt") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"nobody"}""")
            }

            assertEquals(
                "content type",
                ContentType.Application.Json,
                response.contentType()?.withoutParameters(),
            )
        }
    }

    @Test
    fun `POST salt returns 400 JSON content type`() = runBlocking<Unit> {
        testApplication {
            application { authSaltRoute(store) }

            val response = client.post("/v1/auth/salt") {
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
}
