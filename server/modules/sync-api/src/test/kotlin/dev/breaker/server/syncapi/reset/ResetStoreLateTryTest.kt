package dev.breaker.server.syncapi.reset

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.accounts.Role
import dev.breaker.server.syncapi.accounts.VerifyResult
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Wrong tries that land after a reset. recordWrong reads no account state on purpose: a no-op for a
 * pending account would tell pending names apart from active and unknown names again. So a late try
 * is an ordinary count, and complete-reset clears it. A lock never blocks a completion with the
 * right code.
 */
internal class ResetStoreLateTryTest : ResetStoreTestBase() {

    private fun attemptOf(name: String) = runBlocking { accounts.attempts.attemptOf(name) }

    private fun verifyAlice(seed: Int): VerifyResult =
        runBlocking { accounts.verify("alice", AccountFixtures.verifier(seed)) }

    @Test
    fun `a late wrong try after a reset is a plain row and complete clears it`() {
        val code = reset("alice")
        assertNull("sync-api: no row right after the reset", attemptOf("alice"))
        wrongTry("alice")
        val one = checkNotNull(attemptOf("alice"))
        assertEquals("sync-api: a late try is one count", 1L, one.wrongCount)
        assertEquals("sync-api: a late try is not a lock", 0L, one.lockedUntilMs)
        repeat(4) { wrongTry("alice") }
        val five = checkNotNull(attemptOf("alice"))
        assertEquals("sync-api: five late tries count five", 5L, five.wrongCount)
        assertEquals(
            "sync-api: five late tries lock for fifteen minutes",
            clock.millis() + Duration.ofMinutes(15).toMillis(),
            five.lockedUntilMs,
        )
        completed(complete("alice", code))
        assertNull("sync-api: complete clears the late row", attemptOf("alice"))
    }

    @Test
    fun `a late try on an active name and on a second pending name counts the same way`() {
        val bobBefore = checkNotNull(attemptOf("bob")).wrongCount
        assertEquals("sync-api: bob row before the control try", 2L, bobBefore)
        wrongTry("bob")
        val bobAfter = checkNotNull(attemptOf("bob")).wrongCount
        assertEquals("sync-api: bob active row after one try", 3L, bobAfter)
        reset("carol")
        assertNull("sync-api: no carol row right after the reset", attemptOf("carol"))
        wrongTry("carol")
        val carol = checkNotNull(attemptOf("carol"))
        assertEquals("sync-api: carol late try is one count", 1L, carol.wrongCount)
        assertEquals("sync-api: carol late try is not a lock", 0L, carol.lockedUntilMs)
    }

    @Test
    fun `a locked pending name completes with the live code and the new verifier logs in`() {
        val code = reset("alice")
        repeat(5) {
            assertEquals("sync-api: wrong try on a pending name", VerifyResult.Rejected, verifyAlice(5))
        }
        assertEquals("sync-api: the sixth try is locked", VerifyResult.Locked(900L), verifyAlice(5))
        completed(complete("alice", code))
        assertNull("sync-api: complete clears the lock row", attemptOf("alice"))
        val fresh = VerifyResult.Verified(alice, Role.USER)
        assertEquals("sync-api: the new verifier logs in", fresh, verifyAlice(77))
        assertEquals("sync-api: the old verifier is refused", VerifyResult.Rejected, verifyAlice(1))
    }
}
