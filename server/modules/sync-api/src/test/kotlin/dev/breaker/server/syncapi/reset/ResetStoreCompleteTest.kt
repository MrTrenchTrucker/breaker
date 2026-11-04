package dev.breaker.server.syncapi.reset

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.accounts.Role
import dev.breaker.server.syncapi.auth.runSql
import dev.breaker.server.syncapi.tokens.TokenFixtures
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Completing a reset: the success writes and every refusal. Each test resets Alice first, so she is pending with a live code. */
internal class ResetStoreCompleteTest : ResetStoreTestBase() {

    private fun attemptCount(): Long = checkNotNull(runBlocking { accounts.attempts.attemptOf("alice") }).wrongCount

    private fun codeRows(id: Long = alice): Long = num("SELECT count(*) FROM reset_codes WHERE account_id = $id")

    @Test
    fun `completing a reset writes the new salt cost values and verifier and clears the pending mark`() {
        val code = reset("alice")
        completed(complete("alice", code))
        val expected = AccountFixtures.hex(request().newSalt) + " 131072 4 2 1 " + newVerifier.algo + " " +
            newVerifier.iterations + " " + AccountFixtures.hex(newVerifier.salt) + " " +
            AccountFixtures.hex(newVerifier.hash) + " 0"
        assertEquals(
            "sync-api: the credential columns",
            expected,
            rows(
                "SELECT lower(hex(salt)), kdf_memory_kib, kdf_iterations, kdf_parallelism, kdf_version, verifier_algo, " +
                    "verifier_iters, lower(hex(verifier_salt)), lower(hex(verifier_hash)), reset_pending " +
                    "FROM accounts WHERE id = $alice",
            ).single(),
        )
    }

    @Test
    fun `completing a reset deletes the code and the wrong try row`() {
        val code = reset("alice")
        repeat(2) { wrongTry("alice") }
        completed(complete("alice", code))
        assertEquals("sync-api: code rows", 0L, codeRows())
        assertNull("sync-api: the wrong try row", runBlocking { accounts.attempts.attemptOf("alice") })
    }

    @Test
    fun `completing a reset signs in with a live session and answers keyless keys at the kept key_version`() {
        clock.advance(Duration.ofMinutes(3))
        val resetMs = clock.millis()
        val code = reset("alice")
        clock.advance(Duration.ofMinutes(1))
        val done = completed(complete("alice", code))
        val resolved = checkNotNull(runBlocking { tokens.peek(done.sessionText) })
        assertEquals("sync-api: the session owner", alice, resolved.accountId)
        assertEquals("sync-api: the role", Role.USER, done.role)
        assertNull("sync-api: wrapped_dek", done.keys.wrappedDek)
        assertNull("sync-api: wrapped_box_privkey", done.keys.wrappedBoxPrivkey)
        assertNull("sync-api: box_pubkey", done.keys.boxPubkey)
        assertNull("sync-api: prev_wrapped_dek", done.keys.prevWrappedDek)
        assertEquals("sync-api: key_version", 3, done.keys.keyVersion)
        val expectedReset: Long? = resetMs
        assertEquals("sync-api: reset_at is the time of the reset", expectedReset, done.keys.resetAtMs)
        assertEquals("sync-api: live rows", 1L, TokenFixtures.liveTokenCount(temp, alice))
    }

    @Test
    fun `a code that was used is refused the second time`() {
        val code = reset("alice")
        completed(complete("alice", code))
        val hashAfter = col("hex(verifier_hash)")
        assertEquals("sync-api: second use", CompleteResetResult.Refused, complete("alice", code))
        assertEquals("sync-api: the verifier changed", hashAfter, col("hex(verifier_hash)"))
    }

    @Test
    fun `a wrong code of the right shape is refused and leaves the row the mark and the wrong tries`() {
        reset("alice")
        repeat(2) { wrongTry("alice") }
        val before = worldState()
        assertEquals("sync-api: wrong code", CompleteResetResult.Refused, complete("alice", "B".repeat(43)))
        assertEquals("sync-api: a table changed", before, worldState())
        assertEquals("sync-api: wrong tries", 2L, attemptCount())
        assertEquals("sync-api: code rows", 1L, codeRows())
    }

    @Test
    fun `a malformed code is refused and writes nothing`() {
        val code = reset("alice")
        val before = worldState()
        for (bad in listOf("", code.dropLast(1), code + "A", code.dropLast(1) + "+", " ".repeat(43))) {
            assertEquals("sync-api: malformed code of ${bad.length} characters", CompleteResetResult.Refused, complete("alice", bad))
        }
        assertEquals("sync-api: a table changed", before, worldState())
    }

    @Test
    fun `the code of another account does not complete this account`() {
        val forAlice = reset("alice")
        val forBob = reset("bob")
        assertEquals("sync-api: bob code on alice", CompleteResetResult.Refused, complete("alice", forBob))
        assertEquals("sync-api: alice code on bob", CompleteResetResult.Refused, complete("bob", forAlice))
        assertEquals("sync-api: both code rows stay", 2L, codeRows(alice) + codeRows(bob))
        completed(complete("alice", forAlice))
    }

    @Test
    fun `a code is live one millisecond before its expiry and completes`() {
        val code = reset("alice")
        val expires = clock.millis() + 86_400_000L
        clock.set(Instant.ofEpochMilli(expires - 1L))
        assertTrue("sync-api: checkCode", runBlocking { store.checkCode("alice", code) })
        completed(complete("alice", code))
    }

    @Test
    fun `a code is dead at its expiry instant`() {
        val code = reset("alice")
        clock.set(Instant.ofEpochMilli(clock.millis() + 86_400_000L))
        assertFalse("sync-api: checkCode", runBlocking { store.checkCode("alice", code) })
        assertEquals("sync-api: complete", CompleteResetResult.Refused, complete("alice", code))
        assertEquals("sync-api: reset_pending", "1", col("reset_pending"))
    }

    @Test
    fun `an account that is not pending is refused even when it holds a code row`() {
        val code = reset("alice")
        runSql(temp, "UPDATE accounts SET reset_pending = 0 WHERE id = $alice")
        val before = worldState()
        assertEquals("sync-api: not pending", CompleteResetResult.Refused, complete("alice", code))
        assertEquals("sync-api: a table changed", before, worldState())
    }

    @Test
    fun `a locked name completes with a right code and the lock is cleared`() {
        val code = reset("alice")
        repeat(5) { wrongTry("alice") }
        assertTrue("sync-api: setup lock", checkNotNull(runBlocking { accounts.attempts.attemptOf("alice") }).lockedUntilMs > clock.millis())
        completed(complete("alice", code))
        assertNull("sync-api: the lock row", runBlocking { accounts.attempts.attemptOf("alice") })
    }

    @Test
    fun `an unknown name a malformed code and a missing row all give the same Refused and write nothing`() {
        val code = reset("alice")
        val before = worldState()
        assertEquals("sync-api: unknown name", CompleteResetResult.Refused, complete("nobody", code))
        assertEquals("sync-api: malformed code", CompleteResetResult.Refused, complete("alice", code.dropLast(1)))
        assertEquals("sync-api: account without a code row", CompleteResetResult.Refused, complete("carol", code))
        assertEquals("sync-api: a table changed", before, worldState())
    }

    @Test
    fun `two coroutines on one code give one Completed and one Refused`() {
        val code = reset("alice")
        val results = runBlocking {
            withTimeout(60_000L) {
                coroutineScope {
                    val one = async(Dispatchers.Default) { store.complete("alice", code, request()) }
                    val two = async(Dispatchers.Default) { store.complete("alice", code, request()) }
                    listOf(one.await(), two.await())
                }
            }
        }
        assertEquals("sync-api: one Completed", 1, results.count { result -> result is CompleteResetResult.Completed })
        assertEquals("sync-api: one Refused", 1, results.count { result -> result == CompleteResetResult.Refused })
        assertEquals("sync-api: one session was made", 1L, TokenFixtures.liveTokenCount(temp, alice))
    }
}
