package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.UNAVAILABLE_BODY
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The guard around one store call: its value, a fixed 503 for a failure, and cancellation passed on. */
internal class StoreGuardTest {

    private val sawCancellation = AtomicBoolean(false)

    private fun serve(block: suspend () -> String): Pair<HttpStatusCode, String> = runBlocking {
        sawCancellation.set(false)
        var status = HttpStatusCode.OK
        var text = ""
        testApplication {
            application {
                routing {
                    post("/t") {
                        try {
                            when (val guarded = call.guardedStoreCall { block() }) {
                                is StoreCall.Done -> call.respondText("done:" + guarded.value)
                                StoreCall.Failed -> Unit
                            }
                        } catch (_: CancellationException) {
                            sawCancellation.set(true)
                            call.respondText("cancelled")
                        }
                    }
                }
            }
            val response = client.post("/t")
            status = response.status
            text = response.bodyAsText()
        }
        Pair(status, text)
    }

    @Test
    fun `a store call that returns is handed on as done`() {
        val reply = serve { "ok" }
        assertEquals(HttpStatusCode.OK, reply.first)
        assertEquals("done:ok", reply.second)
        assertFalse(sawCancellation.get())
    }

    @Test
    fun `a store call that throws answers the fixed 503 and gives failed`() {
        val reply = serve { throw IOException("boom text") }
        assertEquals(HttpStatusCode.ServiceUnavailable, reply.first)
        assertEquals(UNAVAILABLE_BODY, reply.second)
        assertFalse(reply.second.contains("boom"))
    }

    @Test
    fun `a cancelled store call is rethrown and never answered`() {
        val reply = serve { throw CancellationException("x") }
        assertTrue("the cancellation reached the route", sawCancellation.get())
        assertEquals("cancelled", reply.second)
    }
}
