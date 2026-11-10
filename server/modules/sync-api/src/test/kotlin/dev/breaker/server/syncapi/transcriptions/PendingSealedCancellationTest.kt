package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.TokenFixtures
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// A cancelled read of the pending-sealed route is passed on and never answered. The whole answer of the route
// is the top-level servePendingSealed, so a test-only route calls it and shows what the server tried to send.
internal class PendingSealedCancellationTest : TempDatabaseTest() {

    private class Reply(val status: HttpStatusCode, val body: String)

    private val sawCancellation = AtomicBoolean(false)

    private fun serve(read: suspend (Long) -> List<dev.breaker.server.syncapi.db.PendingSealedRow>): Reply {
        val temp = openTemp()
        val tokenStore = TokenFixtures.tokenStore(temp, AccountFixtures.fixedClock)
        val alice = TokenFixtures.register(temp, "alice", 1)
        val text = TokenFixtures.issuedOf(runBlocking { tokenStore.issueSession(alice.accountId) }).secret.reveal()
        sawCancellation.set(false)
        return runBlocking {
            var reply: Reply? = null
            testApplication {
                application {
                    routing {
                        get("/t") {
                            try {
                                servePendingSealed(call, tokenStore, read)
                            } catch (_: CancellationException) {
                                sawCancellation.set(true)
                                call.respondText("cancelled", status = HttpStatusCode.OK)
                            }
                        }
                    }
                }
                val response = client.get("/t") { header("Authorization", "Bearer $text") }
                reply = Reply(response.status, response.bodyAsText())
            }
            checkNotNull(reply)
        }
    }

    @Test
    fun `a cancelled read is passed on and not answered`() {
        val reply = serve { throw CancellationException("stop") }
        assertTrue("sync-api: the cancellation must reach the caller", sawCancellation.get())
        assertEquals("sync-api: cancellation status", HttpStatusCode.OK, reply.status)
        assertEquals("sync-api: cancellation body", "cancelled", reply.body)
    }

    @Test
    fun `a failed read that is not a cancellation answers the fixed 503`() {
        val reply = serve { throw IllegalStateException("closed") }
        assertFalse("sync-api: a plain failure is not a cancellation", sawCancellation.get())
        assertEquals("sync-api: failure status", HttpStatusCode.ServiceUnavailable, reply.status)
        assertEquals(
            "sync-api: failure body",
            """{"error":"unavailable","message":"The service is unavailable, try again later"}""",
            reply.body,
        )
    }
}
