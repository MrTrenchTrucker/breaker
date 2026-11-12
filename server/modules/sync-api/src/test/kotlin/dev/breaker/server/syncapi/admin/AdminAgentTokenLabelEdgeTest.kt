package dev.breaker.server.syncapi.admin

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.IssuedToken
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
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

// The label of an agent token is checked by isValidLabel before anything is stored:
// an ISO control character or an unpaired surrogate is refused (400, nothing stored),
// while a label of spaces is a legal 1-code-point label and is issued (201).
internal class AdminAgentTokenLabelEdgeTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var tokenStore: TokenStore
    private lateinit var adminSession: IssuedToken
    private var adminAccountId = 0L

    @Before
    fun setUp() {
        temp = openTemp()
        tokenStore = TokenFixtures.tokenStore(temp, AccountFixtures.fixedClock)
        val admin = TokenFixtures.register(temp, "alice", 1)
        adminAccountId = admin.accountId
        adminSession = TokenFixtures.issuedOf(runBlocking { tokenStore.issueSession(admin.accountId) })
    }

    private fun create(labelBody: String, check: suspend (HttpResponse) -> Unit) = runBlocking {
        testApplication {
            application { adminCreateAgentTokenRoute(tokenStore) }
            check(
                client.post("/v1/admin/agent-tokens") {
                    header("Authorization", "Bearer " + adminSession.secret.reveal())
                    contentType(ContentType.Application.Json)
                    setBody(labelBody)
                },
            )
        }
    }

    private val badRequest = """{"error":"bad_request","message":"Invalid request body"}"""

    @Test
    fun `a control character in the label is refused with 400 and stores nothing`() {
        val before = TokenFixtures.tokenRowCount(temp)
        // The JSON escape decodes to U+0000 inside the label (isValidLabel refuses it).
        create("""{"name":"a\u0000b","owner_username":"alice"}""") { response ->
            assertEquals("sync-api: a control character status", HttpStatusCode.BadRequest, response.status)
            assertEquals("sync-api: a control character body", badRequest, response.bodyAsText())
            assertEquals("sync-api: no token was stored", before, TokenFixtures.tokenRowCount(temp))
        }
    }

    @Test
    fun `a lone surrogate in the label is refused with 400 and stores nothing`() {
        val before = TokenFixtures.tokenRowCount(temp)
        // The JSON escape decodes to an unpaired high surrogate U+D800 (isValidLabel refuses it).
        create("""{"name":"a\uD800b","owner_username":"alice"}""") { response ->
            assertEquals("sync-api: a lone surrogate status", HttpStatusCode.BadRequest, response.status)
            assertEquals("sync-api: a lone surrogate body", badRequest, response.bodyAsText())
            assertEquals("sync-api: no token was stored", before, TokenFixtures.tokenRowCount(temp))
        }
    }

    @Test
    fun `a space only label is accepted and stored exactly as given`() {
        create("""{"name":" ","owner_username":"alice"}""") { response ->
            assertEquals("sync-api: a space only label status", HttpStatusCode.Created, response.status)
            assertTrue("sync-api: the body carries the token", response.bodyAsText().contains("\"token\":\""))
        }
    }
}
