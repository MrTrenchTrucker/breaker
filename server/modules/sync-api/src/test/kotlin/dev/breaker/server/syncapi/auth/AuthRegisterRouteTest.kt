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

internal class AuthRegisterRouteTest : TempDatabaseTest() {

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

    // -- happy path --------------------------------------------------------

    @Test
    fun `register happy path returns 201 with username and role`() = runBlocking {
        testApplication {
            application { routing { authRegisterRoute(store) } }
            val response = client.post("/v1/auth/register") {
                setBody(registerBody())
                contentType(ContentType.Application.Json)
            }
            assertEquals("status", HttpStatusCode.Created, response.status)
            assertEquals("body", """{"username":"alice","role":"admin"}""", response.bodyAsText())
        }
    }

    @Test
    fun `first account is admin`() = runBlocking {
        testApplication {
            application { routing { authRegisterRoute(store) } }
            val response = client.post("/v1/auth/register") {
                setBody(registerBody("alice"))
                contentType(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.Created, response.status)
            assertEquals("""{"username":"alice","role":"admin"}""", response.bodyAsText())
        }
    }

    @Test
    fun `second account is user`() = runBlocking {
        testApplication {
            application { routing { authRegisterRoute(store) } }
            client.post("/v1/auth/register") {
                setBody(registerBody("alice"))
                contentType(ContentType.Application.Json)
            }
            val response = client.post("/v1/auth/register") {
                setBody(registerBody("bob"))
                contentType(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.Created, response.status)
            assertEquals("""{"username":"bob","role":"user"}""", response.bodyAsText())
        }
    }

    @Test
    fun `response content type is json`() = runBlocking {
        testApplication {
            application { routing { authRegisterRoute(store) } }
            val response = client.post("/v1/auth/register") {
                setBody(registerBody())
                contentType(ContentType.Application.Json)
            }
            assertEquals(
                "content type",
                ContentType.Application.Json,
                response.contentType()?.withoutParameters(),
            )
        }
    }

    // -- kdf_params bounds -------------------------------------------------

    @Test
    fun `kdf_params below floor is refused with 400`() = runBlocking {
        testApplication {
            application { routing { authRegisterRoute(store) } }
            val response = client.post("/v1/auth/register") {
                setBody(registerBody(memoryKib = 65535))
                contentType(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `kdf_params above ceiling is refused with 400`() = runBlocking {
        testApplication {
            application { routing { authRegisterRoute(store) } }
            val response = client.post("/v1/auth/register") {
                setBody(registerBody(memoryKib = 262145))
                contentType(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `kdf_params iterations below floor is refused with 400`() = runBlocking {
        testApplication {
            application { routing { authRegisterRoute(store) } }
            val response = client.post("/v1/auth/register") {
                setBody(registerBody(iterations = 2))
                contentType(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `kdf_params iterations above ceiling is refused with 400`() = runBlocking {
        testApplication {
            application { routing { authRegisterRoute(store) } }
            val response = client.post("/v1/auth/register") {
                setBody(registerBody(iterations = 11))
                contentType(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `kdf_params parallelism below floor is refused with 400`() = runBlocking {
        testApplication {
            application { routing { authRegisterRoute(store) } }
            val response = client.post("/v1/auth/register") {
                setBody(registerBody(parallelism = 0))
                contentType(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `kdf_params parallelism above ceiling is refused with 400`() = runBlocking {
        testApplication {
            application { routing { authRegisterRoute(store) } }
            val response = client.post("/v1/auth/register") {
                setBody(registerBody(parallelism = 5))
                contentType(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    // -- kdf_version -------------------------------------------------------

    @Test
    fun `kdf_version 2 is refused with 400`() = runBlocking {
        testApplication {
            application { routing { authRegisterRoute(store) } }
            val response = client.post("/v1/auth/register") {
                setBody(registerBody(kdfVersion = 2))
                contentType(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `kdf_version 0 is refused with 400`() = runBlocking {
        testApplication {
            application { routing { authRegisterRoute(store) } }
            val response = client.post("/v1/auth/register") {
                setBody(registerBody(kdfVersion = 0))
                contentType(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    // -- verifier length ---------------------------------------------------

    @Test
    fun `verifier 31 bytes is refused with 400`() = runBlocking {
        testApplication {
            application { routing { authRegisterRoute(store) } }
            val verifier31 = b64(ByteArray(31) { 2 })
            val response = client.post("/v1/auth/register") {
                setBody(registerBody(verifier = verifier31))
                contentType(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `verifier 33 bytes is refused with 400`() = runBlocking {
        testApplication {
            application { routing { authRegisterRoute(store) } }
            val verifier33 = b64(ByteArray(33) { 2 })
            val response = client.post("/v1/auth/register") {
                setBody(registerBody(verifier = verifier33))
                contentType(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `salt 15 bytes is refused with 400`() = runBlocking {
        testApplication {
            application { routing { authRegisterRoute(store) } }
            val salt15 = b64(ByteArray(15) { 1 })
            val response = client.post("/v1/auth/register") {
                setBody(registerBody(salt = salt15))
                contentType(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `salt 17 bytes is refused with 400`() = runBlocking {
        testApplication {
            application { routing { authRegisterRoute(store) } }
            val salt17 = b64(ByteArray(17) { 1 })
            val response = client.post("/v1/auth/register") {
                setBody(registerBody(salt = salt17))
                contentType(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    // -- username validation ------------------------------------------------

    @Test
    fun `username with invalid charset is refused with 400`() = runBlocking {
        testApplication {
            application { routing { authRegisterRoute(store) } }
            val response = client.post("/v1/auth/register") {
                setBody(registerBody(username = "bad name"))
                contentType(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `username too short is refused with 400`() = runBlocking {
        testApplication {
            application { routing { authRegisterRoute(store) } }
            val response = client.post("/v1/auth/register") {
                setBody(registerBody(username = ""))
                contentType(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `username too long is refused with 400`() = runBlocking {
        testApplication {
            application { routing { authRegisterRoute(store) } }
            val response = client.post("/v1/auth/register") {
                setBody(registerBody(username = "a".repeat(65)))
                contentType(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `username at max length 64 is accepted`() = runBlocking {
        testApplication {
            application { routing { authRegisterRoute(store) } }
            val response = client.post("/v1/auth/register") {
                setBody(registerBody(username = "a".repeat(64)))
                contentType(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.Created, response.status)
        }
    }

    @Test
    fun `username with underscore dot and dash is accepted`() = runBlocking {
        testApplication {
            application { routing { authRegisterRoute(store) } }
            val response = client.post("/v1/auth/register") {
                setBody(registerBody(username = "a.b_c-d"))
                contentType(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.Created, response.status)
        }
    }

    // -- duplicate username -------------------------------------------------

    @Test
    fun `duplicate username is refused with 409`() = runBlocking {
        testApplication {
            application { routing { authRegisterRoute(store) } }
            client.post("/v1/auth/register") {
                setBody(registerBody("alice"))
                contentType(ContentType.Application.Json)
            }
            val response = client.post("/v1/auth/register") {
                setBody(registerBody("alice"))
                contentType(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals(
                "error body",
                """{"error":"username_taken","message":"Username already registered"}""",
                response.bodyAsText(),
            )
        }
    }

    @Test
    fun `duplicate username case insensitive is refused with 409`() = runBlocking {
        testApplication {
            application { routing { authRegisterRoute(store) } }
            client.post("/v1/auth/register") {
                setBody(registerBody("Alice"))
                contentType(ContentType.Application.Json)
            }
            val response = client.post("/v1/auth/register") {
                setBody(registerBody("ALICE"))
                contentType(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.Conflict, response.status)
        }
    }
}
