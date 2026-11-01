package dev.breaker.server.syncapi

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.accounts.Role
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.IssuedToken
import dev.breaker.server.syncapi.tokens.MutableClock
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

private const val SERVICE_KEY = "test-key"
private const val USE_PATH = "/internal/v1/token/use"
private const val PEEK_PATH = "/internal/v1/token/peek"

// The public module must never answer the internal token-check routes. Each request carries the
// right key and a live session token, so a 404 can only come from the routes not being mounted.
// The control sends the same request to the internal module, which must answer 200.
internal class PublicModuleHasNoInternalRoutesTest : TempDatabaseTest() {

    private class Reply(val status: HttpStatusCode, val body: String)

    private lateinit var temp: TempDatabase
    private lateinit var store: TokenStore
    private var memberId: Long = 0L

    @Before
    fun openStore() {
        temp = openTemp()
        store = TokenFixtures.tokenStore(temp, MutableClock(TokenFixtures.START))
        val admin = TokenFixtures.register(temp, "admin", 1)
        val member = TokenFixtures.register(temp, "member", 2)
        assertEquals("sync-api: the first account must be the admin", Role.ADMIN, admin.role)
        assertEquals("sync-api: the second account must be a user", Role.USER, member.role)
        assertEquals("sync-api: the member must be account 2", 2L, member.accountId)
        memberId = member.accountId
    }

    private fun session(): IssuedToken = TokenFixtures.issuedOf(runBlocking { store.issueSession(memberId) })

    private fun postTo(onInternal: Boolean, path: String, token: String): Reply {
        var reply: Reply? = null
        runBlocking<Unit> {
            testApplication {
                application {
                    if (onInternal) {
                        internalModule(store, SERVICE_KEY)
                    } else {
                        syncApiModule(AccountFixtures.newStore(temp), store)
                    }
                }
                val response = client.post(path) {
                    header("X-Service-Key", SERVICE_KEY)
                    header("X-Presented-Token", token)
                }
                reply = Reply(response.status, response.bodyAsText())
            }
        }
        return checkNotNull(reply)
    }

    @Test
    fun `use is 404 on the public module with the right key and a live session token`() {
        val token = session().secret.reveal()
        val onPublic = postTo(onInternal = false, path = USE_PATH, token = token)
        assertEquals("sync-api: post use on the public module", HttpStatusCode.NotFound, onPublic.status)
        val control = postTo(onInternal = true, path = USE_PATH, token = token)
        assertEquals("sync-api: control, post use on the internal module", HttpStatusCode.OK, control.status)
        assertEquals("sync-api: control body", """{"account_id":2,"kind":"session","role":"user"}""", control.body)
    }

    @Test
    fun `peek is 404 on the public module with the right key and a live session token`() {
        val token = session().secret.reveal()
        val onPublic = postTo(onInternal = false, path = PEEK_PATH, token = token)
        assertEquals("sync-api: post peek on the public module", HttpStatusCode.NotFound, onPublic.status)
        val control = postTo(onInternal = true, path = PEEK_PATH, token = token)
        assertEquals("sync-api: control, post peek on the internal module", HttpStatusCode.OK, control.status)
        assertEquals("sync-api: control body", """{"account_id":2,"kind":"session","role":"user"}""", control.body)
    }
}
