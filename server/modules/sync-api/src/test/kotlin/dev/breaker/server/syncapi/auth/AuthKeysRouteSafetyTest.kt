package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport
import io.ktor.http.HttpStatusCode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

internal class AuthKeysRouteSafetyTest : AuthKeysRouteTestBase() {

    private val forbidden = listOf(
        "println(", "System.out", "System.err", "Logger", "slf4j", "Thread", "synchronized",
    )

    // Gradle runs tests with the module folder as the working directory.
    private val sourceRoot = "src/main/kotlin/dev/breaker/server/syncapi"

    private fun readSources(): List<Pair<String, String>> {
        val names = listOf("auth/AuthKeysRoute.kt", "accounts/AccountKeys.kt")
        return names.map { name ->
            val file = File("$sourceRoot/$name")
            assertTrue("the source file must exist: ${file.absolutePath}", file.isFile)
            name to file.readText()
        }
    }

    /** One line per (file, forbidden token) pair that is present. */
    private fun scan(sources: List<Pair<String, String>>): List<String> {
        val hits = ArrayList<String>()
        for ((name, text) in sources) {
            for (token in forbidden) {
                if (text.contains(token)) {
                    hits.add("$name contains $token")
                }
            }
        }
        return hits
    }

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

    @Test
    fun `a store failure gets 503 with the exact body writes nothing and works again after the trigger is dropped`() {
        val id = register("alice", 1)
        val session = sessionToken(id)
        runSql(temp, forcedFailureTrigger)
        val failed = postKeys(session, validBody())
        assertEquals("sync-api: status under a store failure", HttpStatusCode.ServiceUnavailable, failed.status)
        assertEquals("sync-api: body under a store failure", SyncRouteTestSupport.UNAVAILABLE_BODY, failed.body)
        assertKeyless(id, "a post under a store failure")
        runSql(temp, "DROP TRIGGER forced_keys_failure")
        val again = postKeys(session, validBody())
        assertEquals("sync-api: status after the trigger is dropped", HttpStatusCode.OK, again.status)
        assertEquals("sync-api: body after the trigger is dropped", """{"key_version":1}""", again.body)
        assertStored(id, 1, "the post after the trigger is dropped")
    }

    @Test
    fun `no output and no answer holds a key for 200 400 403 409 and 503 requests`() {
        val alice = register("alice", 1)
        val bob = register("bob", 2)
        val session = sessionToken(alice)
        val agent = agentText(alice)
        val bobSession = sessionToken(bob)
        val replies = ArrayList<Reply>()
        val output = capturedOutput {
            System.out.println("sync-api capture check out")
            System.err.println("sync-api capture check err")
            replies.add(postKeys(session, "{ not json"))
            replies.add(postKeys(agent, validBody()))
            replies.add(postKeys(session, validBody()))
            replies.add(postKeys(session, validBody()))
            runSql(temp, forcedFailureTrigger)
            replies.add(postKeys(bobSession, validBody()))
        }
        val expected = listOf(
            HttpStatusCode.BadRequest, HttpStatusCode.Forbidden, HttpStatusCode.OK,
            HttpStatusCode.Conflict, HttpStatusCode.ServiceUnavailable,
        )
        assertEquals("sync-api: statuses of the five requests", expected, replies.map { reply -> reply.status })
        assertTrue("sync-api: the capture missed standard out", output.contains("sync-api capture check out"))
        assertTrue("sync-api: the capture missed standard err", output.contains("sync-api capture check err"))
        val keyTexts = listOf(
            wrappedDekText, wrappedBoxPrivkeyText, boxPubkeyText,
            String(wrappedDekBytes, StandardCharsets.ISO_8859_1),
            String(wrappedBoxPrivkeyBytes, StandardCharsets.ISO_8859_1),
            String(boxPubkeyBytes, StandardCharsets.ISO_8859_1),
        )
        for (value in keyTexts) {
            assertFalse("sync-api: the captured output holds a key", output.contains(value))
            for (reply in replies) {
                assertFalse("sync-api: an answer body holds a key", reply.body.contains(value))
            }
        }
    }

    @Test
    fun `the route and key type sources use no print log thread or lock primitive`() {
        assertEquals("forbidden tokens found", emptyList<String>(), scan(readSources()))
    }

    @Test
    fun `the scan reports forbidden tokens in a text that holds them and none in a clean one`() {
        val dirty = scan(listOf("fake.kt" to "println(x); System.out.flush(); val t = Thread()"))
        assertTrue("println( must be reported: $dirty", dirty.contains("fake.kt contains println("))
        assertTrue("System.out must be reported: $dirty", dirty.contains("fake.kt contains System.out"))
        assertTrue("Thread must be reported: $dirty", dirty.contains("fake.kt contains Thread"))
        assertEquals("a clean text must report nothing", emptyList<String>(), scan(listOf("clean.kt" to "val x = 1")))
    }
}
