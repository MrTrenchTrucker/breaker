package dev.breaker.server.syncapi.servicecheck

import dev.breaker.server.syncapi.accounts.Role
import dev.breaker.server.syncapi.tokens.ResolvedToken
import dev.breaker.server.syncapi.tokens.TokenKind
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Proves that a cancelled token-store call is not answered 503: cancellation
// propagates out of `serve` instead of being turned into a service-unavailable
// answer. The three functions (`refuse`, `answer`, `serve`) were moved from
// local declarations inside `serviceCheckRoutes` to internal top-level
// functions in this same file so a test can call `serve` directly through a
// test-only route. `serve` is the only function under test here; the two
// routes it serves are unchanged.
//
// No database is needed: the test builds a ResolvedToken directly and asserts
// on what the server TRIED to send (status + body), not on what Ktor's client
// does when a handler rethrows a CancellationException.
internal class ServiceCheckCancellationTest {

    private class Reply(val status: HttpStatusCode, val body: String)

    private val sawCancellation = AtomicBoolean(false)

    // Mounts a test-only route `post("/t")` whose handler calls `serve(call) { block() }`
    // and answers the call itself. With the cancellation guard in `serve`,
    // sawCancellation becomes true and the body is "cancelled". With the guard
    // removed, `serve` answers 503 itself, sawCancellation stays false and the
    // body is the 503 text. The assertion is about what the server tried to send.
    private fun call(block: suspend () -> ResolvedToken?): Reply = runBlocking {
        sawCancellation.set(false)
        var reply: Reply? = null
        testApplication {
            application {
                routing {
                    post("/t") {
                        try {
                            serve(call) { block() }
                        } catch (_: CancellationException) {
                            sawCancellation.set(true)
                            call.respondText("cancelled", status = HttpStatusCode.OK)
                        }
                    }
                }
            }
            val response = client.post("/t") { }
            reply = Reply(response.status, response.bodyAsText())
        }
        checkNotNull(reply)
    }

    // Cancellation is not answered with 503: sawCancellation is true, status OK,
    // body is "cancelled". With the catch (cancellation: CancellationException)
    // { throw cancellation } branch removed from `serve`, `serve` answers 503
    // itself, the handler ends normally, sawCancellation stays false, and the
    // body is the 503 text - this goes RED.
    @Test(timeout = 20_000)
    fun `cancellation is not answered with 503`() {
        val reply = call { throw CancellationException("x") }
        assertTrue("sync-api: cancellation must be seen", sawCancellation.get())
        assertEquals("sync-api: cancellation status", HttpStatusCode.OK, reply.status)
        assertEquals("sync-api: cancellation body", "cancelled", reply.body)
    }

    // Control: a non-cancellation exception becomes 503 with the fixed body.
    @Test(timeout = 20_000)
    fun `illegal state exception becomes 503 unavailable`() {
        val reply = call { throw IllegalStateException("closed") }
        assertEquals("sync-api: store failure status",
            HttpStatusCode.ServiceUnavailable, reply.status)
        assertEquals("sync-api: store failure body",
            """{"error":"unavailable"}""", reply.body)
    }

    // Control: a null ResolvedToken gives 401 not_live.
    @Test(timeout = 20_000)
    fun `null resolved token gives 401 not live`() {
        val reply = call { null }
        assertEquals("sync-api: null resolved status",
            HttpStatusCode.Unauthorized, reply.status)
        assertEquals("sync-api: null resolved body",
            """{"error":"not_live"}""", reply.body)
    }

    // Control: a ResolvedToken gives 200 with its body.
    @Test(timeout = 20_000)
    fun `resolved token gives 200 with body`() {
        val resolved = ResolvedToken(1L, 2L, Role.USER, TokenKind.SESSION, null, null)
        val reply = call { resolved }
        assertEquals("sync-api: resolved status",
            HttpStatusCode.OK, reply.status)
        assertEquals("sync-api: resolved body",
            """{"account_id":2,"kind":"session","role":"user"}""", reply.body)
    }
}
