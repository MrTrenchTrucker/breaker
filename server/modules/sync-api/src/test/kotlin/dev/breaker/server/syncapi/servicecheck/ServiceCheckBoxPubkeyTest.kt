package dev.breaker.server.syncapi.servicecheck

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.IssuedToken
import dev.breaker.server.syncapi.tokens.MutableClock
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenFixtures.issuedOf
import dev.breaker.server.syncapi.tokens.TokenStore
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// The box_pubkey member of the live answer of the two internal token-check routes. The lookup is a lambda the
// test gives the routes, so the answer is checked as a whole body: with a key, with a keyless account, with no
// lookup wired (the old body), with a refused token (the lookup is never asked), with a lookup that fails and
// with a lookup that is cancelled.
internal class ServiceCheckBoxPubkeyTest : TempDatabaseTest() {

    private class Reply(val status: HttpStatusCode, val body: String)

    private lateinit var temp: TempDatabase
    private lateinit var store: TokenStore
    private var userId: Long = 0L

    private val keyBytes = ByteArray(32) { index -> index.toByte() }
    private val keyText = Base64.getEncoder().encodeToString(keyBytes)

    @Before
    fun openStore() {
        temp = openTemp()
        store = TokenFixtures.tokenStore(temp, MutableClock(TokenFixtures.START))
        TokenFixtures.register(temp, "admin", 1)
        userId = TokenFixtures.register(temp, "member", 2).accountId
    }

    private fun session(): IssuedToken = issuedOf(runBlocking { store.issueSession(userId) })

    private fun call(path: String, token: String, keysFor: (suspend (Long) -> ByteArray?)?): Reply = runBlocking {
        var reply: Reply? = null
        testApplication {
            application { routing { serviceCheckRoutes(store, "test-key", keysFor) } }
            val response = client.post(path) {
                header("X-Service-Key", "test-key")
                header("X-Presented-Token", token)
            }
            reply = Reply(response.status, response.bodyAsText())
        }
        checkNotNull(reply)
    }

    private val paths = listOf("/internal/v1/token/use", "/internal/v1/token/peek")

    private fun oldBody(): String = "{\"account_id\":$userId,\"kind\":\"session\",\"role\":\"user\"}"

    @Test
    fun `a wired lookup puts the owner key in the answer of both routes`() {
        val token = session().secret.reveal()
        for (path in paths) {
            val reply = call(path, token) { id -> if (id == userId) keyBytes else null }
            assertEquals("sync-api: status of $path", HttpStatusCode.OK, reply.status)
            assertEquals(
                "sync-api: body of $path",
                "{\"account_id\":$userId,\"kind\":\"session\",\"role\":\"user\",\"box_pubkey\":\"$keyText\"}",
                reply.body,
            )
        }
    }

    // A key whose standard base64 holds plus and slash: the URL-safe alphabet would print minus and underscore for them.
    @Test
    fun `the key is printed in the standard base64 alphabet with plus and slash`() {
        val token = session().secret.reveal()
        val plusSlashKey = ByteArray(32) { index -> intArrayOf(0xfb, 0xff, 0xbf)[index % 3].toByte() }
        val expected = "+/+/+/+/+/+/+/+/+/+/+/+/+/+/+/+/+/+/+/+/+/8="
        for (path in paths) {
            val reply = call(path, token) { id -> if (id == userId) plusSlashKey else null }
            assertEquals("sync-api: status of $path", HttpStatusCode.OK, reply.status)
            assertEquals(
                "sync-api: body of $path",
                "{\"account_id\":$userId,\"kind\":\"session\",\"role\":\"user\",\"box_pubkey\":\"$expected\"}",
                reply.body,
            )
        }
    }

    @Test
    fun `the lookup is asked for the account of the token and no other`() {
        val token = session().secret.reveal()
        val asked = ArrayList<Long>()
        call(paths[1], token) { id -> asked.add(id); null }
        assertEquals("sync-api: the accounts the lookup was asked for", listOf(userId), asked)
    }

    @Test
    fun `an account with no key gets a null box_pubkey`() {
        val token = session().secret.reveal()
        val reply = call(paths[0], token) { null }
        assertEquals("sync-api: status", HttpStatusCode.OK, reply.status)
        assertEquals("sync-api: body", "{\"account_id\":$userId,\"kind\":\"session\",\"role\":\"user\",\"box_pubkey\":null}", reply.body)
    }

    @Test
    fun `with no lookup wired the answer is the old body with no box_pubkey`() {
        val token = session().secret.reveal()
        for (path in paths) {
            val reply = call(path, token, null)
            assertEquals("sync-api: status of $path", HttpStatusCode.OK, reply.status)
            assertEquals("sync-api: body of $path", oldBody(), reply.body)
        }
    }

    @Test
    fun `a token that is not live is refused and the lookup is never asked`() {
        val asked = AtomicInteger(0)
        for (path in paths) {
            val reply = call(path, "not-a-token") { asked.incrementAndGet(); keyBytes }
            assertEquals("sync-api: status of $path", HttpStatusCode.Unauthorized, reply.status)
            assertEquals("sync-api: body of $path", "{\"error\":\"not_live\"}", reply.body)
        }
        assertEquals("sync-api: the lookup must not run for a refused token", 0, asked.get())
    }

    @Test
    fun `a lookup that fails still answers the token check without the member`() {
        val token = session().secret.reveal()
        for (path in paths) {
            val reply = call(path, token) { throw IllegalStateException("database closed") }
            assertEquals("sync-api: status of $path", HttpStatusCode.OK, reply.status)
            assertEquals("sync-api: body of $path", oldBody(), reply.body)
        }
    }

    // The same test-only route as ServiceCheckCancellationTest: the handler calls answer itself and answers
    // "cancelled" when the cancellation reaches it. With the cancellation rethrow removed from the member
    // builder, the lookup's cancellation is swallowed, the answer is the old 200 body and this goes RED.
    @Test(timeout = 20_000)
    fun `a cancelled lookup is passed on and not turned into an answer`() {
        val token = session().secret.reveal()
        val resolved = runBlocking { store.resolve(token) }
        val sawCancellation = AtomicBoolean(false)
        var body = ""
        runBlocking {
            testApplication {
                application {
                    routing {
                        post("/t") {
                            try {
                                answer(call, resolved) { throw CancellationException("x") }
                            } catch (_: CancellationException) {
                                sawCancellation.set(true)
                                call.respondText("cancelled")
                            }
                        }
                    }
                }
                body = client.post("/t") { }.bodyAsText()
            }
        }
        assertTrue("sync-api: the cancellation must reach the handler", sawCancellation.get())
        assertEquals("sync-api: body", "cancelled", body)
    }
}
