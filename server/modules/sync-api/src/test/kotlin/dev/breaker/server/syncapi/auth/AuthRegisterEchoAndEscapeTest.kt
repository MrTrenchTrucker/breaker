package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.accounts.AccountStore
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
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
import org.junit.Before
import org.junit.Test
import java.util.Base64

/** The register answers carry fixed texts (no body key echoed), and a bad unicode escape is a 400. */
internal class AuthRegisterEchoAndEscapeTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var store: AccountStore

    @Before
    fun setUp() {
        temp = openTemp()
        store = AccountFixtures.newStore(temp)
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun saltMember(): String = "\"salt\":\"" + b64(AccountFixtures.salt(1)) + "\","

    private fun verifierMember(): String = "\"auth_verifier\":\"" + b64(AccountFixtures.verifierBytes(1)) + "\""

    private fun kdfMember(extra: String): String =
        "\"kdf_params\":{\"memory_kib\":65536,\"iterations\":3,\"parallelism\":1" + extra + "},\"kdf_version\":1,"

    private fun registerText(usernameRaw: String, extraTop: String, extraKdf: String, withSalt: Boolean): String =
        "{" + extraTop + "\"username\":\"" + usernameRaw + "\"," + (if (withSalt) saltMember() else "") +
            kdfMember(extraKdf) + verifierMember() + "}"

    private fun invalid(message: String): String = "{\"error\":\"invalid_field\",\"message\":\"" + message + "\"}"

    private fun post(requestBody: String): Pair<HttpStatusCode, String> = runBlocking {
        var status = HttpStatusCode.OK
        var text = ""
        testApplication {
            application { routing { authRegisterRoute(store) } }
            val response = client.post("/v1/auth/register") {
                setBody(requestBody)
                contentType(ContentType.Application.Json)
            }
            status = response.status
            text = response.bodyAsText()
        }
        Pair(status, text)
    }

    @Test
    fun `an unknown field name is never echoed and the answer is the fixed JSON`() {
        val odd = "a\\\"b\\\\c"
        val reply = post(registerText("alice", "\"" + odd + "\":1,", "", true))
        assertEquals(HttpStatusCode.BadRequest, reply.first)
        assertEquals(invalid("unknown field"), reply.second)
        assertFalse("the key text is not echoed", reply.second.contains("b\\c"))
    }

    @Test
    fun `an unknown field name inside kdf params is never echoed either`() {
        val odd = "a\\\"b\\\\c"
        val reply = post(registerText("alice", "", ",\"" + odd + "\":1", true))
        assertEquals(HttpStatusCode.BadRequest, reply.first)
        assertEquals(invalid("unknown field in kdf_params"), reply.second)
        assertFalse("the key text is not echoed", reply.second.contains("b\\c"))
    }

    @Test
    fun `a missing field keeps its fixed name`() {
        val reply = post(registerText("alice", "", "", false))
        assertEquals(HttpStatusCode.BadRequest, reply.first)
        assertEquals(invalid("missing field: salt"), reply.second)
    }

    @Test
    fun `a unicode escape that is not four hex digits is refused as malformed JSON`() {
        for (escape in listOf("\\uZZZZ", "\\u+123", "\\u-001", "\\u12", "\\u 123")) {
            val reply = post(registerText(escape + "lice", "", "", true))
            assertEquals("status of " + escape, HttpStatusCode.BadRequest, reply.first)
            assertEquals("body of " + escape, invalid("malformed JSON body"), reply.second)
        }
        val good = post(registerText("\\u0061lice", "", "", true))
        assertEquals(HttpStatusCode.Created, good.first)
        assertEquals("{\"username\":\"alice\",\"role\":\"admin\"}", good.second)
    }
}
