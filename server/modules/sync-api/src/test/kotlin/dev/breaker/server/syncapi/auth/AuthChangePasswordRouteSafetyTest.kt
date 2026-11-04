package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.accounts.AccountFixtures
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

internal class AuthChangePasswordRouteSafetyTest : AuthChangePasswordRouteTestBase() {

    private val revokeTrigger =
        "CREATE TRIGGER forced_revoke_failure AFTER UPDATE OF revoked_at_ms ON tokens " +
            "BEGIN SELECT RAISE(ABORT, 'forced'); END"
    private val clearTrigger =
        "CREATE TRIGGER forced_clear_failure BEFORE DELETE ON signin_attempts " +
            "BEGIN SELECT RAISE(ABORT, 'forced'); END"
    private val accountTrigger =
        "CREATE TRIGGER forced_account_failure AFTER UPDATE OF key_version ON accounts " +
            "BEGIN SELECT RAISE(ABORT, 'forced'); END"

    private val forbidden = listOf(
        "println(", "System.out", "System.err", "Logger", "slf4j", "Thread", "synchronized",
    )

    // Gradle runs tests with the module folder as the working directory.
    private val sourceRoot = "src/main/kotlin/dev/breaker/server/syncapi"

    private fun readSources(): List<Pair<String, String>> {
        val names = listOf(
            "auth/AuthChangePasswordRoute.kt",
            "auth/ChangePasswordBody.kt",
            "passwordchange/PasswordChangeStore.kt",
            "accounts/PrevKeyRule.kt",
        )
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

    /**
     * Installs [triggerSql] with another session present and a wrong-password row kept alive (the limit is off, so
     * the proof neither counts nor clears), expects the fixed 503 and an untouched account, drops the trigger, and
     * expects the same request to succeed.
     */
    private fun assertForcedFailureRecovers(triggerSql: String, triggerName: String, label: String) {
        val id = keyedAccount("alice", 1)
        val session = sessionToken(id)
        val other = sessionToken(id)
        limitOff()
        recordWrongTries("alice", 1)
        val before = snapshotOf(id)
        runSql(temp, triggerSql)
        val failed = postChange(session, validChangeBody())
        assertAnswer("$label under a forced failure", failed, HttpStatusCode.ServiceUnavailable, SyncRouteTestSupport.UNAVAILABLE_BODY)
        assertUnchanged(id, before, label)
        assertEquals("sync-api: $label lost the wrong-password row", 1L, wrongCountOf("alice"))
        assertTrue("sync-api: $label revoked the other session", sessionWorks(other))
        assertTrue("sync-api: $label revoked this session", sessionWorks(session))
        runSql(temp, "DROP TRIGGER $triggerName")
        assertAnswer("$label after the trigger is dropped", postChange(session, validChangeBody()), HttpStatusCode.OK, """{"key_version":2}""")
        assertEquals("sync-api: $label left the wrong-password row after success", 0L, wrongCountOf("alice"))
    }

    @Test
    fun `a failure when the other sessions are revoked gets 503 and rolls the account update back`() {
        assertForcedFailureRecovers(revokeTrigger, "forced_revoke_failure", "the revoke failure")
    }

    @Test
    fun `a failure at the last write gets 503 and rolls back the account update and the revoked sessions`() {
        assertForcedFailureRecovers(clearTrigger, "forced_clear_failure", "the last write failure")
    }

    @Test
    fun `a failure in the account update gets 503 and writes nothing`() {
        assertForcedFailureRecovers(accountTrigger, "forced_account_failure", "the account update failure")
    }

    @Test
    fun `no output and no answer holds a key or a verifier for 200 400 401 403 409 423 and 503 requests`() {
        val alice = keyedAccount("alice", 1)
        val bob = register("bob", 2)
        val carol = keyedAccount("carol", 3)
        val dave = keyedAccount("dave", 4)
        val aliceSession = sessionToken(alice)
        val agent = agentText(alice)
        val bobSession = sessionToken(bob)
        val carolSession = sessionToken(carol)
        val daveSession = sessionToken(dave)
        sessionToken(dave)
        recordWrongTries("carol", 5)
        val shortSalt = withText("new_salt", textOf(ByteArray(15) { i -> (i + 9).toByte() }))
        val replies = ArrayList<Reply>()
        val output = capturedOutput {
            System.out.println("sync-api capture check out")
            System.err.println("sync-api capture check err")
            replies.add(postChange(aliceSession, shortSalt))
            replies.add(postChange(aliceSession, withOldVerifier(wrongVerifierBytes)))
            replies.add(postChange(agent, validChangeBody()))
            replies.add(postChange(aliceSession, validChangeBody()))
            replies.add(postChange(bobSession, withOldVerifier(AccountFixtures.verifierBytes(2))))
            replies.add(postChange(carolSession, withOldVerifier(AccountFixtures.verifierBytes(3))))
            runSql(temp, revokeTrigger)
            replies.add(postChange(daveSession, withOldVerifier(AccountFixtures.verifierBytes(4))))
        }
        val expected = listOf(
            HttpStatusCode.BadRequest, HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden, HttpStatusCode.OK,
            HttpStatusCode.Conflict, HttpStatusCode.Locked, HttpStatusCode.ServiceUnavailable,
        )
        assertEquals("sync-api: statuses of the seven requests", expected, replies.map { reply -> reply.status })
        assertTrue("sync-api: the capture missed standard out", output.contains("sync-api capture check out"))
        assertTrue("sync-api: the capture missed standard err", output.contains("sync-api capture check err"))
        val sensitive = listOf(
            oldVerifierBytes, newVerifierBytes, wrongVerifierBytes, newSaltBytes, newDekBytes, newPrivkeyBytes,
            newPubkeyBytes, prevDekBytes, AccountFixtures.verifierBytes(2), AccountFixtures.verifierBytes(3),
            AccountFixtures.verifierBytes(4),
        )
        for (bytes in sensitive) {
            for (value in listOf(textOf(bytes), String(bytes, StandardCharsets.ISO_8859_1))) {
                assertFalse("sync-api: the captured output holds a value", output.contains(value))
                for (reply in replies) {
                    assertFalse("sync-api: an answer body holds a value", reply.body.contains(value))
                }
            }
        }
    }

    @Test
    fun `the route body store and rule sources use no print log thread or lock primitive`() {
        assertEquals("forbidden tokens found", emptyList<String>(), scan(readSources()))
    }

    @Test
    fun `the scan reports forbidden tokens in a text that holds them and none in a clean one`() {
        val dirty = scan(listOf("fake.kt" to "println(x); System.out.flush(); val t = Thread(); synchronized(t) {}"))
        assertTrue("println( must be reported: $dirty", dirty.contains("fake.kt contains println("))
        assertTrue("System.out must be reported: $dirty", dirty.contains("fake.kt contains System.out"))
        assertTrue("Thread must be reported: $dirty", dirty.contains("fake.kt contains Thread"))
        assertTrue("synchronized must be reported: $dirty", dirty.contains("fake.kt contains synchronized"))
        assertEquals("a clean text must report nothing", emptyList<String>(), scan(listOf("clean.kt" to "val x = 1")))
    }
}
