package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.syncApiModule
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

internal class AuthCompleteResetRouteSafetyTest : ResetRouteTestBase() {

    private val sessionTrigger =
        "CREATE TRIGGER forced_session_failure BEFORE INSERT ON tokens BEGIN SELECT RAISE(ABORT, 'forced'); END"

    /** Runs [block] with both standard streams replaced, restores them in a finally, and returns what was written. */
    private fun capturedOutput(block: () -> Unit): String {
        val originalOut = System.out
        val originalErr = System.err
        val buffer = ByteArrayOutputStream()
        val capture = PrintStream(buffer, true, StandardCharsets.ISO_8859_1.name())
        try {
            System.setOut(capture)
            System.setErr(capture)
            block()
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
            capture.flush()
        }
        assertSame("sync-api: standard out was not restored", originalOut, System.out)
        assertSame("sync-api: standard err was not restored", originalErr, System.err)
        return buffer.toString(StandardCharsets.ISO_8859_1.name())
    }

    /** Two requests with the same body at once, as two coroutines of this one process, with a bounded wait. */
    private fun postTwice(body: String): List<Reply> {
        var replies: List<Reply> = emptyList()
        runBlocking {
            testApplication {
                application { syncApiModule(accountStore, tokenStore, transcriptionStore, handoffStore) }
                replies = withTimeout(60_000L) {
                    coroutineScope {
                        val sent = List(2) {
                            async {
                                val response = client.post(completePath) {
                                    contentType(ContentType.Application.Json)
                                    setBody(body)
                                }
                                Reply(response.status, response.bodyAsText())
                            }
                        }
                        sent.map { each -> each.await() }
                    }
                }
            }
        }
        return replies
    }

    @Test
    fun `two completions with one code give one 200 and one 401 and mint one session`() {
        val adminText = startAdmin()
        val alice = register("Alice", 2)
        val code = resetAs(adminText, "ALICE")
        val replies = postTwice(completeBody("Alice", code))
        assertEquals("sync-api: the two statuses", listOf(200, 401), replies.map { reply -> reply.status.value }.sorted())
        assertEquals("sync-api: body of the refused one", refusedBody, replies.first { reply -> reply.status == HttpStatusCode.Unauthorized }.body)
        assertEquals("sync-api: live tokens of Alice", 1L, TokenFixtures.liveTokenCount(temp, alice))
        assertEquals("sync-api: pending mark", 0L, pendingFlag(alice))
        assertEquals("sync-api: code rows", 0L, codeRows(alice))
    }

    @Test
    fun `a closed database gives the fixed 503 for a reset and for a completion`() {
        val adminText = startAdmin()
        register("Alice", 2)
        val code = resetAs(adminText, "ALICE")
        temp.db.close()
        assertAnswer("reset", postReset(adminText, nameBody("Alice")), HttpStatusCode.ServiceUnavailable, SyncRouteTestSupport.UNAVAILABLE_BODY)
        assertAnswer("completion", postComplete("Alice", code), HttpStatusCode.ServiceUnavailable, SyncRouteTestSupport.UNAVAILABLE_BODY)
    }

    @Test
    fun `a failure while the session is made gives 503 and rolls back the spent code`() {
        val adminText = startAdmin()
        val alice = register("Alice", 2)
        val code = resetAs(adminText, "ALICE")
        val before = stateOf(alice)
        runSql(temp, sessionTrigger)
        assertAnswer("completion under a forced failure", postComplete("Alice", code), HttpStatusCode.ServiceUnavailable, SyncRouteTestSupport.UNAVAILABLE_BODY)
        assertEquals("sync-api: the failed completion changed the account", before, stateOf(alice))
        runSql(temp, "DROP TRIGGER forced_session_failure")
        assertEquals("sync-api: completion after the trigger is dropped", HttpStatusCode.OK, postComplete("Alice", code).status)
    }

    @Test
    fun `no captured output holds the code the new verifier or a request body`() {
        val adminText = startAdmin()
        register("Alice", 2)
        val wrongCode = "B".repeat(43)
        val sent = ArrayList<String>()
        val replies = ArrayList<Reply>()
        val codes = ArrayList<String>()
        val output = capturedOutput {
            System.out.println("sync-api capture check out")
            System.err.println("sync-api capture check err")
            val reset = postReset(adminText, nameBody("ALICE"))
            codes.add(codeOf(reset))
            sent.add(completeBody("Alice", wrongCode))
            replies.add(postTo(completePath, null, sent[0]))
            sent.add(completeBody("Alice", codes[0]))
            replies.add(postTo(completePath, null, sent[1]))
        }
        assertTrue("sync-api: the capture missed standard out", output.contains("sync-api capture check out"))
        assertTrue("sync-api: the capture missed standard err", output.contains("sync-api capture check err"))
        assertEquals("sync-api: statuses of the two completions", listOf(401, 200), replies.map { reply -> reply.status.value })
        val sensitive = listOf(codes[0], wrongCode, textOf(newVerifierBytes), textOf(newSaltBytes)) + sent
        for (value in sensitive) {
            assertFalse("sync-api: the captured output holds a value", output.contains(value))
            for (reply in replies) {
                assertFalse("sync-api: a completion answer holds a value", reply.body.contains(value))
            }
        }
    }
}
