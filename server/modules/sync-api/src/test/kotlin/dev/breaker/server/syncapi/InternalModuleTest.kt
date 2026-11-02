package dev.breaker.server.syncapi

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.accounts.Role
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.handoff.HandoffFixtures
import dev.breaker.server.syncapi.tokens.IssuedToken
import dev.breaker.server.syncapi.tokens.MutableClock
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

private const val SERVICE_KEY = "test-key"
private const val USE_PATH = "/internal/v1/token/use"
private const val PEEK_PATH = "/internal/v1/token/peek"

// The internal module through testApplication, with a real TokenStore on the temp database.
// Every route the internal module must not have is paired with a control: the same request on
// the public module must not be answered 404.
internal class InternalModuleTest : TempDatabaseTest() {

    private class Reply(val status: HttpStatusCode, val body: String)

    private enum class Mount { INTERNAL, PUBLIC }

    private enum class Verb { GET, POST, DELETE }

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

    private fun send(mount: Mount, verb: Verb, path: String, key: String?, token: String?): Reply {
        var reply: Reply? = null
        runBlocking<Unit> {
            testApplication {
                application {
                    when (mount) {
                        Mount.INTERNAL -> internalModule(store, SERVICE_KEY)
                        Mount.PUBLIC -> syncApiModule(
                            AccountFixtures.newStore(temp),
                            store,
                            dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.newStore(temp),
                            HandoffFixtures.handoffStore(temp, store, AccountFixtures.fixedClock),
                        )
                    }
                }
                val response = when (verb) {
                    Verb.GET -> client.get(path) {
                        if (key != null) header("X-Service-Key", key)
                        if (token != null) header("X-Presented-Token", token)
                    }
                    Verb.POST -> client.post(path) {
                        if (key != null) header("X-Service-Key", key)
                        if (token != null) header("X-Presented-Token", token)
                    }
                    Verb.DELETE -> client.delete(path) {
                        if (key != null) header("X-Service-Key", key)
                        if (token != null) header("X-Presented-Token", token)
                    }
                }
                reply = Reply(response.status, response.bodyAsText())
            }
        }
        return checkNotNull(reply)
    }

    @Test
    fun `use with the right key and a live session token answers 200 with the account`() {
        val token = session().secret.reveal()
        val reply = send(Mount.INTERNAL, Verb.POST, USE_PATH, SERVICE_KEY, token)
        assertEquals("sync-api: use status", HttpStatusCode.OK, reply.status)
        assertEquals("sync-api: use body", """{"account_id":2,"kind":"session","role":"user"}""", reply.body)
    }

    @Test
    fun `peek with the right key and a live session token answers 200 with the account`() {
        val token = session().secret.reveal()
        val reply = send(Mount.INTERNAL, Verb.POST, PEEK_PATH, SERVICE_KEY, token)
        assertEquals("sync-api: peek status", HttpStatusCode.OK, reply.status)
        assertEquals("sync-api: peek body", """{"account_id":2,"kind":"session","role":"user"}""", reply.body)
    }

    @Test
    fun `a wrong key is refused with 403 bad_service_key on both internal routes`() {
        val token = session().secret.reveal()
        for (path in listOf(USE_PATH, PEEK_PATH)) {
            val reply = send(Mount.INTERNAL, Verb.POST, path, "wrong-key", token)
            assertEquals("sync-api: wrong key status on $path", HttpStatusCode.Forbidden, reply.status)
            assertEquals("sync-api: wrong key body on $path", """{"error":"bad_service_key"}""", reply.body)
        }
    }

    @Test
    fun `a missing key is refused with 403 bad_service_key on both internal routes`() {
        val token = session().secret.reveal()
        for (path in listOf(USE_PATH, PEEK_PATH)) {
            val reply = send(Mount.INTERNAL, Verb.POST, path, null, token)
            assertEquals("sync-api: missing key status on $path", HttpStatusCode.Forbidden, reply.status)
            assertEquals("sync-api: missing key body on $path", """{"error":"bad_service_key"}""", reply.body)
        }
    }

    @Test
    fun `a right key with no token is refused with 401 not_live on both internal routes`() {
        for (path in listOf(USE_PATH, PEEK_PATH)) {
            val reply = send(Mount.INTERNAL, Verb.POST, path, SERVICE_KEY, null)
            assertEquals("sync-api: no token status on $path", HttpStatusCode.Unauthorized, reply.status)
            assertEquals("sync-api: no token body on $path", """{"error":"not_live"}""", reply.body)
        }
    }

    @Test
    fun `a get on either internal path is not 200 even with the right key and a live token`() {
        val token = session().secret.reveal()
        for (path in listOf(USE_PATH, PEEK_PATH)) {
            val asGet = send(Mount.INTERNAL, Verb.GET, path, SERVICE_KEY, token)
            assertNotEquals("sync-api: a get on $path must not be 200", HttpStatusCode.OK, asGet.status)
            val asPost = send(Mount.INTERNAL, Verb.POST, path, SERVICE_KEY, token)
            assertEquals("sync-api: control, the post on $path is 200", HttpStatusCode.OK, asPost.status)
        }
    }

    @Test
    fun `health is 404 on the internal module and 200 on the public module`() {
        val onInternal = send(Mount.INTERNAL, Verb.GET, "/health", null, null)
        val onPublic = send(Mount.PUBLIC, Verb.GET, "/health", null, null)
        assertEquals("sync-api: get health on the internal module", HttpStatusCode.NotFound, onInternal.status)
        assertEquals("sync-api: control, get health on the public module", HttpStatusCode.OK, onPublic.status)
    }

    @Test
    fun `the auth routes are 404 on the internal module and not 404 on the public module`() {
        for (path in listOf("/v1/auth/register", "/v1/auth/salt", "/v1/auth/login", "/v1/auth/logout")) {
            val onInternal = send(Mount.INTERNAL, Verb.POST, path, null, null)
            val onPublic = send(Mount.PUBLIC, Verb.POST, path, null, null)
            assertEquals("sync-api: post $path on the internal module", HttpStatusCode.NotFound, onInternal.status)
            assertNotEquals("sync-api: control, post $path on the public module", HttpStatusCode.NotFound, onPublic.status)
        }
    }

    @Test
    fun `the admin revoke route is 404 on the internal module and 401 without a token on the public module`() {
        val path = "/v1/admin/agent-tokens/1"
        val onInternal = send(Mount.INTERNAL, Verb.DELETE, path, null, null)
        val onPublic = send(Mount.PUBLIC, Verb.DELETE, path, null, null)
        assertEquals("sync-api: delete $path on the internal module", HttpStatusCode.NotFound, onInternal.status)
        assertEquals("sync-api: control, delete $path on the public module with no token", HttpStatusCode.Unauthorized, onPublic.status)
    }
}
