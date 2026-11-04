package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.tokens.TokenFixtures
import io.ktor.http.HttpStatusCode
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class AuthCompleteResetRouteTest : ResetRouteTestBase() {

    /** A keyed Alice with a preset rekeyed_at, reset five minutes later; returns her id and the code. */
    private fun resetKeyedAlice(): Pair<Long, String> {
        val adminText = startAdmin()
        val alice = keyedAccount("Alice", 2)
        runSql(temp, "UPDATE accounts SET rekeyed_at = ${KeyPatterns.REKEYED_AT_MS} WHERE id = $alice")
        clock.advance(Duration.ofMinutes(5))
        return alice to resetAs(adminText, "ALICE")
    }

    @Test
    fun `a right code answers 200 with the login body of a keyless account`() {
        val (alice, code) = resetKeyedAlice()
        val reply = postComplete("ALICE", code)
        assertEquals("sync-api: status", HttpStatusCode.OK, reply.status)
        val body = reply.body
        assertEquals("sync-api: session text length", 43, sessionTextOf(body).length)
        assertEquals("sync-api: role", quoted("user"), rawMember(body, "role"))
        assertEquals("sync-api: wrapped_dek", "null", rawMember(body, "wrapped_dek"))
        assertEquals("sync-api: wrapped_box_privkey", "null", rawMember(body, "wrapped_box_privkey"))
        assertEquals("sync-api: box_pubkey", "null", rawMember(body, "box_pubkey"))
        assertEquals("sync-api: key_version is kept", "1", rawMember(body, "key_version"))
        assertEquals("sync-api: prev_wrapped_dek", "null", rawMember(body, "prev_wrapped_dek"))
        assertEquals("sync-api: rekeyed_at is kept", quoted("2023-11-14T22:13:20.000Z"), rawMember(body, "rekeyed_at"))
        assertEquals("sync-api: reset_at is the reset instant", quoted("2023-11-14T22:18:20.000Z"), rawMember(body, "reset_at"))
        assertEquals("sync-api: pending mark", 0L, pendingFlag(alice))
        assertEquals("sync-api: code rows", 0L, codeRows(alice))
    }

    @Test
    fun `the new session text opens a protected route`() {
        val (_, code) = resetKeyedAlice()
        val sessionText = sessionTextOf(postComplete("Alice", code).body)
        assertEquals("sync-api: GET with the new session", HttpStatusCode.OK, getList(sessionText).status)
    }

    @Test
    fun `the old password is refused and the new password logs in as a keyless account`() {
        val (_, code) = resetKeyedAlice()
        assertEquals("sync-api: completion", HttpStatusCode.OK, postComplete("Alice", code).status)
        assertAnswer("old verifier", loginAs("Alice", AccountFixtures.verifierBytes(2)), HttpStatusCode.Unauthorized, loginRefusedBody)
        val login = loginAs("alice", newVerifierBytes)
        assertEquals("sync-api: login with the new verifier", HttpStatusCode.OK, login.status)
        assertEquals("sync-api: login wrapped_dek", "null", rawMember(login.body, "wrapped_dek"))
        assertEquals("sync-api: login key_version", "1", rawMember(login.body, "key_version"))
        val salt = postTo(saltPath, null, nameBody("Alice"))
        assertTrue("sync-api: the salt route holds the new salt", salt.body.contains(textOf(newSaltBytes)))
        assertTrue("sync-api: the salt route holds the new cost values", salt.body.contains("\"memory_kib\":${newKdf.memoryKib}"))
    }

    @Test
    fun `tokens made before the reset stay dead and only the new session works`() {
        val adminText = startAdmin()
        val alice = register("Alice", 2)
        val oldText = sessionToken(alice)
        val code = resetAs(adminText, "ALICE")
        assertFalse("sync-api: the old session after the reset", sessionWorks(oldText))
        val newText = sessionTextOf(postComplete("Alice", code).body)
        assertFalse("sync-api: the old session after the completion", sessionWorks(oldText))
        assertTrue("sync-api: the new session", sessionWorks(newText))
        assertEquals("sync-api: live tokens of Alice", 1L, TokenFixtures.liveTokenCount(temp, alice))
    }

    @Test
    fun `a locked name completes with a right code and the lock is cleared`() {
        val adminText = startAdmin()
        register("Alice", 2)
        val code = resetAs(adminText, "ALICE")
        recordWrongTries("alice", 5)
        assertTrue("sync-api: the name is locked", runBlocking { accountStore.attempts.lockedUntil("alice") } > 0L)
        assertEquals("sync-api: completion of a locked name", HttpStatusCode.OK, postComplete("Alice", code).status)
        assertEquals("sync-api: the wrong-password row", "none", attemptsOf("alice"))
        assertEquals("sync-api: login after the lock", HttpStatusCode.OK, loginAs("Alice", newVerifierBytes).status)
    }
}
