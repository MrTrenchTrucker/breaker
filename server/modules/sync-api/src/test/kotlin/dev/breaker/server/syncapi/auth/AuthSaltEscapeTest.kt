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
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The salt body reader answers 400 for a unicode escape that is not four hex digits, never a 500. */
internal class AuthSaltEscapeTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var store: AccountStore

    @Before
    fun setUp() {
        temp = openTemp()
        store = AccountFixtures.newStore(temp)
        runBlocking {
            store.register("alice", AccountFixtures.salt(1), AccountFixtures.validKdf, 1, AccountFixtures.verifier(1))
        }
    }

    private fun post(requestBody: String): Pair<HttpStatusCode, String> = runBlocking {
        var status = HttpStatusCode.OK
        var text = ""
        testApplication {
            application { authSaltRoute(store) }
            val response = client.post("/v1/auth/salt") {
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }
            status = response.status
            text = response.bodyAsText()
        }
        Pair(status, text)
    }

    @Test
    fun `a unicode escape that is not four hex digits is refused with 400 on the salt route`() {
        for (escape in listOf("\\uZZZZ", "\\u+123", "\\u-001", "\\u12", "\\u 123")) {
            val reply = post("{\"username\":\"" + escape + "lice\"}")
            assertEquals("status of " + escape, HttpStatusCode.BadRequest, reply.first)
            assertEquals(
                "body of " + escape,
                "{\"error\":\"bad_request\",\"message\":\"Invalid request body\"}",
                reply.second,
            )
        }
        val good = post("{\"username\":\"\\u0061lice\"}")
        assertEquals(HttpStatusCode.OK, good.first)
        assertTrue("the salt of the account", good.second.contains("\"salt\":\""))
    }
}
