package dev.breaker.server.syncapi.sync

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/** The log policy: the encrypted text and the decoded text never reach standard output or standard error. */
internal class SyncLogPolicyTest : SyncRouteTestBase() {

    private val marker = "MARKER-PLAIN-7f3a"

    @Before
    fun setUp() {
        openSyncStores()
    }

    /** Runs [block] with standard output and standard error captured, and returns what was written. */
    private fun captureOutput(block: () -> Unit): String {
        val buffer = ByteArrayOutputStream()
        val originalOut = System.out
        val originalErr = System.err
        try {
            val sink = PrintStream(buffer, true, Charsets.UTF_8)
            System.setOut(sink)
            System.setErr(sink)
            block()
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
        return String(buffer.toByteArray(), Charsets.UTF_8)
    }

    @Test
    fun `a push and a pull write neither the encrypted text nor the decoded text to the captured output`() =
        runBlocking<Unit> {
            val auth = bearer(issueSession(account("alice", 1)).secret.reveal())
            val encrypted = validEncrypted(marker)
            val output = captureOutput {
                runBlocking {
                    testApplication {
                        application { installSyncRoutes() }
                        client.postSync(auth, utf8(pushBody(CLIENT_ID, "0", encrypted)))
                        client.pullSync(auth)
                    }
                }
            }
            assertFalse("the decoded text is not logged", output.contains(marker))
            assertFalse("the encrypted text is not logged", output.contains(encrypted))
        }

    @Test
    fun `a refused body writes neither the decoded text nor its encrypted form to the captured output`() =
        runBlocking<Unit> {
            val auth = bearer(issueSession(account("alice", 1)).secret.reveal())
            val encrypted = encryptedOf(syncBodyJson(marker, source = "Local"))
            var refusedBody = ""
            val output = captureOutput {
                runBlocking {
                    testApplication {
                        application { installSyncRoutes() }
                        val response = client.postSync(auth, utf8(pushBody(CLIENT_ID, "0", encrypted)))
                        assertEquals("status", HttpStatusCode.BadRequest, response.status)
                        refusedBody = response.bodyAsText()
                    }
                }
            }
            assertEquals("the refusal body is the fixed one", BAD_REQUEST_BODY, refusedBody)
            assertFalse("the decoded text is not logged", output.contains(marker))
            assertFalse("the encrypted text is not logged", output.contains(encrypted))
        }
}
