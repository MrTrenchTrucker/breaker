package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.accounts.AccountStore
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
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
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Base64

/** The login body reader answers 400 for a repeated member and for a bad unicode escape, never a 500. */
internal class AuthLoginParseFixTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var accountStore: AccountStore
    private lateinit var tokenStore: TokenStore

    private companion object {
        const val BAD_REQUEST_BODY = "{\"error\":\"bad_request\",\"message\":\"Invalid request body\"}"
    }

    @Before
    fun setUp() {
        temp = openTemp()
        accountStore = AccountFixtures.newStore(temp)
        tokenStore = TokenFixtures.tokenStore(temp, AccountFixtures.fixedClock)
        runBlocking {
            accountStore.register("alice", AccountFixtures.salt(1), AccountFixtures.validKdf, 1, AccountFixtures.verifier(1))
        }
    }

    private fun verifierText(): String = Base64.getEncoder().encodeToString(AccountFixtures.verifierBytes(1))

    private fun post(requestBody: String): Pair<HttpStatusCode, String> = runBlocking {
        var status = HttpStatusCode.OK
        var text = ""
        testApplication {
            application { authLoginRoute(accountStore, tokenStore) }
            val response = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }
            status = response.status
            text = response.bodyAsText()
        }
        Pair(status, text)
    }

    private fun assertRefused(requestBody: String, label: String) {
        val reply = post(requestBody)
        assertEquals("status of " + label, HttpStatusCode.BadRequest, reply.first)
        assertEquals("body of " + label, BAD_REQUEST_BODY, reply.second)
    }

    private fun assertLoginWorks(requestBody: String) {
        assertEquals(HttpStatusCode.OK, post(requestBody).first)
    }

    @Test
    fun `a repeated username that leaves no verifier is refused with 400`() {
        assertRefused("{\"username\":\"alice\",\"username\":\"bob\"}", "username twice")
        assertLoginWorks("{\"username\":\"alice\",\"auth_verifier\":\"" + verifierText() + "\"}")
    }

    @Test
    fun `a repeated verifier that leaves no username is refused with 400`() {
        assertRefused("{\"auth_verifier\":\"AAAA\",\"auth_verifier\":\"BBBB\"}", "verifier twice")
        assertRefused("{\"auth_verifier\":\"" + verifierText() + "\",\"auth_verifier\":\"" + verifierText() + "\"}", "same verifier twice")
        assertLoginWorks("{\"auth_verifier\":\"" + verifierText() + "\",\"username\":\"alice\"}")
    }

    @Test
    fun `three members with a repeat stay refused`() {
        val good = "\"auth_verifier\":\"" + verifierText() + "\""
        assertRefused("{\"username\":\"alice\"," + good + ",\"username\":\"alice\"}", "three members")
        assertLoginWorks("{\"username\":\"alice\"," + good + "}")
    }

    @Test
    fun `a unicode escape that is not four hex digits is refused with 400`() {
        for (escape in listOf("\\uZZZZ", "\\u+123", "\\u-001", "\\u12", "\\u 123")) {
            assertRefused("{\"username\":\"" + escape + "lice\",\"auth_verifier\":\"" + verifierText() + "\"}", escape)
        }
        assertLoginWorks("{\"username\":\"\\u0061lice\",\"auth_verifier\":\"" + verifierText() + "\"}")
    }
}
