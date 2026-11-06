package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.accounts.AccountFixtures
import io.ktor.http.HttpStatusCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class AuthLoginPendingTest : ResetRouteTestBase() {

    /**
     * A pending Alice (the old verifier is seed 2) answers every login as an ordinary account answers a wrong
     * verifier, byte for byte, six times in a row with the limit off, with no wrong try counted and no lock.
     */
    private fun assertPendingAnswersAsWrong() {
        val adminText = startAdmin()
        pendingAccount(adminText, "Alice", 2)
        register("Bob", 3)
        val reference = loginAs("Bob", wrongVerifierBytes)
        assertAnswer("wrong verifier of an ordinary account", reference, HttpStatusCode.Unauthorized, loginRefusedBody)
        val before = attemptsOf("alice")
        assertEquals("sync-api: Alice starts without a wrong-password row", "none", before)
        val tries = listOf(
            "right verifier of the old password" to AccountFixtures.verifierBytes(2),
            "wrong verifier" to wrongVerifierBytes,
            "verifier of the later password" to newVerifierBytes,
            "verifier of another account" to AccountFixtures.verifierBytes(3),
            "right verifier of the old password again" to AccountFixtures.verifierBytes(2),
            "sixth try" to wrongVerifierBytes,
        )
        for ((label, bytes) in tries) {
            val reply = loginAs("Alice", bytes)
            assertAnswer(label, reply, reference.status, reference.body)
        }
        assertEquals("sync-api: a pending login counted a wrong try", before, attemptsOf("alice"))
    }

    /**
     * A pending Alice (the old verifier is seed 2) with the limit on: the first five logins answer the
     * wrong verifier's 401 byte for byte and each counts one wrong try; the sixth answers 423 and the body.
     */
    private fun assertPendingLocksAfterFiveWrongTries() {
        val adminText = startAdmin()
        pendingAccount(adminText, "Alice", 2)
        register("Bob", 3)
        val reference = loginAs("Bob", wrongVerifierBytes)
        assertAnswer("wrong verifier", reference, HttpStatusCode.Unauthorized, loginRefusedBody)
        assertEquals("sync-api: the pending name has no wrong-password row yet", "none", attemptsOf("alice"))
        val tries = listOf(
            "right verifier of the old password" to AccountFixtures.verifierBytes(2),
            "wrong verifier" to wrongVerifierBytes,
            "verifier of the later password" to newVerifierBytes,
            "verifier of another account" to AccountFixtures.verifierBytes(3),
            "right verifier of the old password again" to AccountFixtures.verifierBytes(2),
        )
        for ((label, bytes) in tries) {
            assertAnswer(label, loginAs("Alice", bytes), reference.status, reference.body)
        }
        assertTrue("sync-api: five wrong tries did not leave a 5/ row", attemptsOf("alice").startsWith("5/"))
        assertAnswer("sixth try", loginAs("Alice", wrongVerifierBytes), HttpStatusCode.Locked, lockedBody)
    }

    @Test
    fun `a pending login counts five wrong tries and locks on the sixth with the limit on`() {
        assertPendingLocksAfterFiveWrongTries()
    }

    @Test
    fun `a pending login gives the wrong verifier 401 byte for byte and counts nothing with the limit off`() {
        limitOff()
        assertPendingAnswersAsWrong()
    }

    @Test
    fun `the salt route answers a pending account as before the reset`() {
        val adminText = startAdmin()
        register("Alice", 2)
        val before = postTo(saltPath, null, nameBody("Alice"))
        assertEquals("sync-api: salt status before the reset", HttpStatusCode.OK, before.status)
        assertTrue("sync-api: salt text before the reset", before.body.contains(textOf(AccountFixtures.salt(2))))
        resetAs(adminText, "ALICE")
        assertAnswer("salt after the reset", postTo(saltPath, null, nameBody("alice")), HttpStatusCode.OK, before.body)
    }
}
