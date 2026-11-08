package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.accounts.AccountStore
import dev.breaker.server.syncapi.accounts.SaltLookup
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
import org.junit.Before
import org.junit.Test
import java.util.Base64

/** A number of the register body outside the Int range is refused, never wrapped into another number. */
internal class AuthRegisterRangeTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var store: AccountStore

    @Before
    fun setUp() {
        temp = openTemp()
        store = AccountFixtures.newStore(temp)
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun bodyWith(field: String, value: String): String {
        val numbers = linkedMapOf("kdf_version" to "1", "memory_kib" to "65536", "iterations" to "3", "parallelism" to "1")
        numbers[field] = value
        return "{\"username\":\"alice\",\"salt\":\"" + b64(AccountFixtures.salt(1)) + "\"," +
            "\"kdf_params\":{\"memory_kib\":" + numbers["memory_kib"] + ",\"iterations\":" + numbers["iterations"] +
            ",\"parallelism\":" + numbers["parallelism"] + "},\"kdf_version\":" + numbers["kdf_version"] +
            ",\"auth_verifier\":\"" + b64(AccountFixtures.verifierBytes(1)) + "\"}"
    }

    private fun invalid(message: String): String = "{\"error\":\"invalid_field\",\"message\":\"" + message + "\"}"

    private fun rangeMessage(field: String): String =
        if (field == "kdf_version") "kdf_version is out of range" else "kdf_params." + field + " is out of range"

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

    private fun assertNothingStored() {
        assertEquals(SaltLookup.NoSuchAccount, runBlocking { store.saltFor("alice") })
    }

    @Test
    fun `a kdf version that wraps to the legal value is refused and nothing is stored`() {
        val wrapped = post(bodyWith("kdf_version", "4294967297"))
        assertEquals(HttpStatusCode.BadRequest, wrapped.first)
        assertEquals(invalid("kdf_version is out of range"), wrapped.second)
        assertNothingStored()
        assertEquals(HttpStatusCode.Created, post(bodyWith("kdf_version", "1")).first)
    }

    @Test
    fun `each kdf parameter that wraps to a legal value is refused and nothing is stored`() {
        val wrapped = listOf("memory_kib" to "4295032832", "iterations" to "4294967299", "parallelism" to "4294967297")
        for ((field, value) in wrapped) {
            val reply = post(bodyWith(field, value))
            assertEquals("status of " + field, HttpStatusCode.BadRequest, reply.first)
            assertEquals("body of " + field, invalid(rangeMessage(field)), reply.second)
        }
        assertNothingStored()
        assertEquals(HttpStatusCode.Created, post(bodyWith("iterations", "3")).first)
    }

    @Test
    fun `the Int edges reach the store bounds and one step beyond meets the range check`() {
        for (field in listOf("kdf_version", "memory_kib", "iterations", "parallelism")) {
            val storeMessage = if (field == "kdf_version") "invalid field: kdf_version" else "kdf_params out of bounds"
            for (edge in listOf("2147483647", "-2147483648")) {
                val reply = post(bodyWith(field, edge))
                assertEquals("status of " + field + " " + edge, HttpStatusCode.BadRequest, reply.first)
                assertEquals("body of " + field + " " + edge, invalid(storeMessage), reply.second)
            }
            for (beyond in listOf("2147483648", "-2147483649")) {
                val reply = post(bodyWith(field, beyond))
                assertEquals("status of " + field + " " + beyond, HttpStatusCode.BadRequest, reply.first)
                assertEquals("body of " + field + " " + beyond, invalid(rangeMessage(field)), reply.second)
            }
        }
        assertNothingStored()
    }

    @Test
    fun `a legal registration is stored exactly as sent`() {
        assertEquals(HttpStatusCode.Created, post(bodyWith("memory_kib", "131072")).first)
        val found = AccountFixtures.found(runBlocking { store.saltFor("alice") })
        assertEquals(131072, found.kdf.memoryKib)
        assertEquals(3, found.kdf.iterations)
        assertEquals(1, found.kdf.parallelism)
        assertEquals(1, found.kdfVersion)
    }
}
