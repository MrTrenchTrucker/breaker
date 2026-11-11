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
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64

internal class AuthRegisterRouteTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var store: AccountStore

    @Before
    fun setup() {
        temp = openTemp()
        store = AccountFixtures.newStore(temp)
        // Existing route tests register more than one account through the route;
        // keep them passing unchanged: open registration in the fixture. New tests
        // for the closed rule close it themselves.
        runBlocking { store.settings.setRegistrationOpenEnabled(true) }
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

    // ---- the install code on the route (ADR-043 slice C)

    private val rightCode = "R".repeat(43)

    private fun withCodeStore(text: String = rightCode, openSwitch: Boolean = false): AccountStore {
        val path = temp.directory.resolve("install-code")
        Files.writeString(path, text)
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        val coded = AccountStore(temp.db, AccountFixtures.fastHasher(), AccountFixtures.fixedClock, installCodeFile = path.toString())
        runBlocking { coded.settings.setRegistrationOpenEnabled(openSwitch) }
        return coded
    }

    private fun withMember(body: String, member: String): String = body.dropLast(1) + ",$member}"

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.postRegister(body: String) =
        client.post("/v1/auth/register") {
            setBody(body)
            contentType(ContentType.Application.Json)
        }

    @Test
    fun `the first account without a code and with a wrong code get the same 400 and nothing is stored`() = runBlocking {
        val coded = withCodeStore()
        testApplication {
            application { routing { authRegisterRoute(coded) } }
            val missing = postRegister(registerBody())
            val wrong = postRegister(withMember(registerBody(), "\"install_code\":\"${"W".repeat(43)}\""))
            val notText = postRegister(withMember(registerBody(), "\"install_code\":123"))
            assertEquals("sync-api: missing", HttpStatusCode.BadRequest, missing.status)
            assertEquals("sync-api: wrong", HttpStatusCode.BadRequest, wrong.status)
            assertEquals("sync-api: not a string", HttpStatusCode.BadRequest, notText.status)
            val missingBody = missing.bodyAsText()
            assertEquals("sync-api: one body for a wrong code", missingBody, wrong.bodyAsText())
            assertEquals("sync-api: one body for a non text code", missingBody, notText.bodyAsText())
            assertTrue("sync-api: the invalid_field error", missingBody.contains("invalid_field"))
            assertTrue("sync-api: the file must still exist", Files.exists(temp.directory.resolve("install-code")))
            val fine = postRegister(withMember(registerBody(), "\"install_code\":\"$rightCode\""))
            assertEquals("sync-api: the right code still works, so nothing was consumed", HttpStatusCode.Created, fine.status)
        }
    }

    @Test
    fun `the right code creates the admin, is not echoed and the code file is deleted`() = runBlocking {
        val coded = withCodeStore()
        testApplication {
            application { routing { authRegisterRoute(coded) } }
            val response = postRegister(withMember(registerBody(), "\"install_code\":\"$rightCode\""))
            assertEquals("sync-api: status", HttpStatusCode.Created, response.status)
            assertEquals("sync-api: body", """{"username":"alice","role":"admin"}""", response.bodyAsText())
        }
        assertFalse("sync-api: the used code file is gone", Files.exists(temp.directory.resolve("install-code")))
    }

    @Test
    fun `a wrong code answer never holds the code that was sent or the one on disk`() = runBlocking {
        val coded = withCodeStore()
        testApplication {
            application { routing { authRegisterRoute(coded) } }
            val sent = "S".repeat(43)
            val body = postRegister(withMember(registerBody(), "\"install_code\":\"$sent\"")).bodyAsText()
            assertFalse("sync-api: the sent code is not echoed", body.contains(sent.take(8)))
            assertFalse("sync-api: the stored code is not leaked", body.contains(rightCode.take(8)))
        }
    }

    @Test
    fun `after the first account the install_code member is tolerated and ignored`() = runBlocking {
        val coded = withCodeStore(openSwitch = true)
        testApplication {
            application { routing { authRegisterRoute(coded) } }
            postRegister(withMember(registerBody("alice"), "\"install_code\":\"$rightCode\""))
            val withJunk = postRegister(withMember(registerBody("bob"), "\"install_code\":\"${"J".repeat(43)}\""))
            val without = postRegister(registerBody("carol"))
            assertEquals("sync-api: a junk code after the first account", HttpStatusCode.Created, withJunk.status)
            assertEquals("sync-api: no code after the first account", HttpStatusCode.Created, without.status)
        }
    }

    @Test
    fun `another unknown member is still refused as an unknown field`() = runBlocking {
        val coded = withCodeStore()
        testApplication {
            application { routing { authRegisterRoute(coded) } }
            val response = postRegister(withMember(withMember(registerBody(), "\"install_code\":\"$rightCode\""), "\"extra\":1"))
            assertEquals("sync-api: status", HttpStatusCode.BadRequest, response.status)
            assertTrue("sync-api: unknown field", response.bodyAsText().contains("unknown field"))
        }
    }
}
