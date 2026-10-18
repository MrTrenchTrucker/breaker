package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.accounts.AccountStore
import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

internal class AuthRegisterRouteExtraTest : TempDatabaseTest() {

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
    ): String = """{"username":"$username","salt":"$salt","kdf_params":{"memory_kib":$memoryKib,"iterations":$iterations,"parallelism":$parallelism},"kdf_version":$kdfVersion,"auth_verifier":"$verifier"}"""

    // -- malformed JSON ------------------------------------------------------

    @Test
    fun `malformed JSON body is refused with 400`() = runBlocking {
        testApplication {
            application { routing { authRegisterRoute(store) } }
            val response = client.post("/v1/auth/register") {
                setBody("""{"username":"alice","salt":""")
                contentType(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `empty body is refused with 400`() = runBlocking {
        testApplication {
            application { routing { authRegisterRoute(store) } }
            val response = client.post("/v1/auth/register") {
                setBody("")
                contentType(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `body that is not a JSON object is refused with 400`() = runBlocking {
        testApplication {
            application { routing { authRegisterRoute(store) } }
            val response = client.post("/v1/auth/register") {
                setBody("[1,2,3]")
                contentType(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    // -- missing required field ---------------------------------------------

    @Test
    fun `missing salt field is refused with 400`() = runBlocking {
        testApplication {
            application { routing { authRegisterRoute(store) } }
            val body = """{"username":"alice","kdf_params":{"memory_kib":65536,"iterations":3,"parallelism":1},"kdf_version":1,"auth_verifier":"${validVerifier()}"}"""
            val response = client.post("/v1/auth/register") {
                setBody(body)
                contentType(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `missing username field is refused with 400`() = runBlocking {
        testApplication {
            application { routing { authRegisterRoute(store) } }
            val body = """{"salt":"${validSalt()}","kdf_params":{"memory_kib":65536,"iterations":3,"parallelism":1},"kdf_version":1,"auth_verifier":"${validVerifier()}"}"""
            val response = client.post("/v1/auth/register") {
                setBody(body)
                contentType(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `missing kdf_params field is refused with 400`() = runBlocking {
        testApplication {
            application { routing { authRegisterRoute(store) } }
            val body = """{"username":"alice","salt":"${validSalt()}","kdf_version":1,"auth_verifier":"${validVerifier()}"}"""
            val response = client.post("/v1/auth/register") {
                setBody(body)
                contentType(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `missing kdf_version field is refused with 400`() = runBlocking {
        testApplication {
            application { routing { authRegisterRoute(store) } }
            val body = """{"username":"alice","salt":"${validSalt()}","kdf_params":{"memory_kib":65536,"iterations":3,"parallelism":1},"auth_verifier":"${validVerifier()}"}"""
            val response = client.post("/v1/auth/register") {
                setBody(body)
                contentType(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `missing auth_verifier field is refused with 400`() = runBlocking {
        testApplication {
            application { routing { authRegisterRoute(store) } }
            val body = """{"username":"alice","salt":"${validSalt()}","kdf_params":{"memory_kib":65536,"iterations":3,"parallelism":1},"kdf_version":1}"""
            val response = client.post("/v1/auth/register") {
                setBody(body)
                contentType(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    // -- wrong JSON type -----------------------------------------------------

    @Test
    fun `kdf_version as string is refused with 400`() = runBlocking {
        testApplication {
            application { routing { authRegisterRoute(store) } }
            val body = """{"username":"alice","salt":"${validSalt()}","kdf_params":{"memory_kib":65536,"iterations":3,"parallelism":1},"kdf_version":"1","auth_verifier":"${validVerifier()}"}"""
            val response = client.post("/v1/auth/register") {
                setBody(body)
                contentType(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `username as number is refused with 400`() = runBlocking {
        testApplication {
            application { routing { authRegisterRoute(store) } }
            val body = """{"username":123,"salt":"${validSalt()}","kdf_params":{"memory_kib":65536,"iterations":3,"parallelism":1},"kdf_version":1,"auth_verifier":"${validVerifier()}"}"""
            val response = client.post("/v1/auth/register") {
                setBody(body)
                contentType(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `salt as number is refused with 400`() = runBlocking {
        testApplication {
            application { routing { authRegisterRoute(store) } }
            val body = """{"username":"alice","salt":123,"kdf_params":{"memory_kib":65536,"iterations":3,"parallelism":1},"kdf_version":1,"auth_verifier":"${validVerifier()}"}"""
            val response = client.post("/v1/auth/register") {
                setBody(body)
                contentType(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `kdf_params as string is refused with 400`() = runBlocking {
        testApplication {
            application { routing { authRegisterRoute(store) } }
            val body = """{"username":"alice","salt":"${validSalt()}","kdf_params":"hello","kdf_version":1,"auth_verifier":"${validVerifier()}"}"""
            val response = client.post("/v1/auth/register") {
                setBody(body)
                contentType(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `kdf_params memory_kib as string is refused with 400`() = runBlocking {
        testApplication {
            application { routing { authRegisterRoute(store) } }
            val body = """{"username":"alice","salt":"${validSalt()}","kdf_params":{"memory_kib":"65536","iterations":3,"parallelism":1},"kdf_version":1,"auth_verifier":"${validVerifier()}"}"""
            val response = client.post("/v1/auth/register") {
                setBody(body)
                contentType(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `auth_verifier as number is refused with 400`() = runBlocking {
        testApplication {
            application { routing { authRegisterRoute(store) } }
            val body = """{"username":"alice","salt":"${validSalt()}","kdf_params":{"memory_kib":65536,"iterations":3,"parallelism":1},"kdf_version":1,"auth_verifier":123}"""
            val response = client.post("/v1/auth/register") {
                setBody(body)
                contentType(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    // -- extra unknown field -------------------------------------------------

    @Test
    fun `extra unknown field is refused with 400`() = runBlocking {
        testApplication {
            application { routing { authRegisterRoute(store) } }
            val body = """{"username":"alice","salt":"${validSalt()}","kdf_params":{"memory_kib":65536,"iterations":3,"parallelism":1},"kdf_version":1,"auth_verifier":"${validVerifier()}","extra_field":"value"}"""
            val response = client.post("/v1/auth/register") {
                setBody(body)
                contentType(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `extra unknown field in kdf_params is refused with 400`() = runBlocking {
        testApplication {
            application { routing { authRegisterRoute(store) } }
            val body = """{"username":"alice","salt":"${validSalt()}","kdf_params":{"memory_kib":65536,"iterations":3,"parallelism":1,"extra":1},"kdf_version":1,"auth_verifier":"${validVerifier()}"}"""
            val response = client.post("/v1/auth/register") {
                setBody(body)
                contentType(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    // -- no echo / secrecy ---------------------------------------------------

    @Test
    fun `error body does not contain password verifier or salt`() = runBlocking {
        testApplication {
            application { routing { authRegisterRoute(store) } }
            val verifierStr = b64(ByteArray(32) { 42 })
            val saltStr = b64(ByteArray(16) { 43 })
            val body = registerBody(username = "alice", salt = saltStr, memoryKib = 65535, verifier = verifierStr)
            val response = client.post("/v1/auth/register") {
                setBody(body)
                contentType(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val responseBody = response.bodyAsText()
            assertFalse("error body must not contain the verifier", responseBody.contains(verifierStr))
            assertFalse("error body must not contain the salt", responseBody.contains(saltStr))
        }
    }

    @Test
    fun `token and verifier never appear in response body`() = runBlocking {
        testApplication {
            application { routing { authRegisterRoute(store) } }
            val verifierStr = b64(ByteArray(32) { 42 })
            val saltStr = b64(ByteArray(16) { 43 })
            val body = registerBody(username = "alice", salt = saltStr, verifier = verifierStr)
            val response = client.post("/v1/auth/register") {
                setBody(body)
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer secret-token-123")
            }
            assertEquals(HttpStatusCode.Created, response.status)
            val responseBody = response.bodyAsText()
            assertFalse("response must not contain the verifier", responseBody.contains(verifierStr))
            assertFalse("response must not contain the salt", responseBody.contains(saltStr))
            assertFalse("response must not contain the token", responseBody.contains("secret-token-123"))
        }
    }

    @Test
    fun `success body contains only username and role`() = runBlocking {
        testApplication {
            application { routing { authRegisterRoute(store) } }
            val response = client.post("/v1/auth/register") {
                setBody(registerBody("alice"))
                contentType(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.Created, response.status)
            val responseBody = response.bodyAsText()
            assertTrue("response must contain username", responseBody.contains("\"username\""))
            assertTrue("response must contain role", responseBody.contains("\"role\""))
            assertFalse("response must not contain salt", responseBody.contains("salt"))
            assertFalse("response must not contain verifier", responseBody.contains("verifier"))
            assertFalse("response must not contain kdf", responseBody.contains("kdf"))
        }
    }

    // -- wire names ----------------------------------------------------------

    @Test
    fun `request wire names match the spec exactly`() = runBlocking {
        testApplication {
            application { routing { authRegisterRoute(store) } }
            val requestBody = registerBody("alice")
            val response = client.post("/v1/auth/register") {
                setBody(requestBody)
                contentType(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.Created, response.status)
            // The exact body match ensures the response wire names are exactly {username, role}
            assertEquals("""{"username":"alice","role":"admin"}""", response.bodyAsText())
        }
    }

    @Test
    fun `error response wire names are exactly error and message`() = runBlocking {
        testApplication {
            application { routing { authRegisterRoute(store) } }
            val response = client.post("/v1/auth/register") {
                setBody(registerBody(memoryKib = 65535))
                contentType(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = response.bodyAsText()
            assertTrue("error body must contain error", body.contains("\"error\""))
            assertTrue("error body must contain message", body.contains("\"message\""))
            assertFalse("error body must not contain username", body.contains("\"username\""))
            assertFalse("error body must not contain salt", body.contains("\"salt\""))
            assertFalse("error body must not contain role", body.contains("\"role\""))
        }
    }

    @Test
    fun `kdf_params wire names match the spec exactly`() = runBlocking {
        testApplication {
            application { routing { authRegisterRoute(store) } }
            // If kdf_params had wrong wire names, the store would reject it as out of bounds
            val response = client.post("/v1/auth/register") {
                setBody(registerBody("alice", memoryKib = 131072, iterations = 5, parallelism = 2))
                contentType(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.Created, response.status)
        }
    }

    // -- Authorization header ------------------------------------------------

    @Test
    fun `register works without authorization header`() = runBlocking {
        testApplication {
            application { routing { authRegisterRoute(store) } }
            val response = client.post("/v1/auth/register") {
                setBody(registerBody())
                contentType(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.Created, response.status)
        }
    }

    @Test
    fun `register works with authorization header present`() = runBlocking {
        testApplication {
            application { routing { authRegisterRoute(store) } }
            val response = client.post("/v1/auth/register") {
                setBody(registerBody())
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer some-token")
            }
            assertEquals(HttpStatusCode.Created, response.status)
        }
    }

    @Test
    fun `authorization header value is not reflected in response`() = runBlocking {
        testApplication {
            application { routing { authRegisterRoute(store) } }
            val response = client.post("/v1/auth/register") {
                setBody(registerBody())
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer my-secret-token-xyz")
            }
            assertEquals(HttpStatusCode.Created, response.status)
            assertFalse(
                "response must not contain the auth token",
                response.bodyAsText().contains("my-secret-token-xyz"),
            )
        }
    }
}
