package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.accounts.AccountFixtures
import io.ktor.http.HttpStatusCode
import org.junit.Assert.assertEquals
import org.junit.Test

internal class AuthChangePasswordPendingTest : ResetRouteTestBase() {

    private var aliceId = 0L
    private var aliceText = ""

    /**
     * Admin (id 1), a pending Alice (id 2, keyless like every reset account) and a keyed Bob (id 3). Bob's
     * sessions are made first, so Alice's live session, made after the reset through the token store, has
     * a token id that differs from her account id.
     */
    private fun setUpPending(): Long {
        val adminText = startAdmin()
        aliceId = register("Alice", 2)
        val bob = keyedAccount("Bob", 3)
        sessionToken(bob)
        sessionToken(bob)
        resetAs(adminText, "ALICE")
        aliceText = sessionOnPendingAccount(aliceId)
        return bob
    }

    @Test
    fun `a pending account gets 409 with the pending text before the no-keys text`() {
        setUpPending()
        val before = stateOf(aliceId)
        assertAnswer("valid body", postChange(aliceText, validChangeBody()), HttpStatusCode.Conflict, pendingBody)
        assertEquals("sync-api: the refused call changed the account", before, stateOf(aliceId))
    }

    @Test
    fun `a pending account is refused before the old verifier is checked so no wrong try is counted`() {
        setUpPending()
        assertAnswer("wrong old verifier", postChange(aliceText, withOldVerifier(wrongVerifierBytes)), HttpStatusCode.Conflict, pendingBody)
        assertAnswer("old verifier of her former password", postChange(aliceText, withOldVerifier(AccountFixtures.verifierBytes(2))), HttpStatusCode.Conflict, pendingBody)
        assertEquals("sync-api: the wrong-password count", 0L, wrongCountOf("alice"))
        assertEquals("sync-api: the wrong-password row", "none", attemptsOf("alice"))
    }

    @Test
    fun `a pending account is refused before the size and the shape of the body are decided`() {
        setUpPending()
        assertAnswer("5000 byte body", postChange(aliceText, padTo(validChangeBody(), 5000)), HttpStatusCode.Conflict, pendingBody)
        assertAnswer("malformed body", postChange(aliceText, "{ not json"), HttpStatusCode.Conflict, pendingBody)
        assertAnswer("empty body", postChange(aliceText, ""), HttpStatusCode.Conflict, pendingBody)
    }

    @Test
    fun `an account that is not pending still gets 413 for a 5000 byte body`() {
        val bob = setUpPending()
        val bobText = sessionToken(bob)
        assertAnswer("5000 byte body of Bob", postChange(bobText, padTo(validChangeBody(), 5000)), HttpStatusCode.PayloadTooLarge, tooLargeBody)
    }

    @Test
    fun `after the completion the pending text is gone and the no-keys text answers`() {
        val adminText = startAdmin()
        val alice = register("Alice", 2)
        val code = resetAs(adminText, "ALICE")
        val completed = postComplete("Alice", code)
        assertEquals("sync-api: completion", HttpStatusCode.OK, completed.status)
        val sessionText = sessionTextOf(completed.body)
        assertAnswer("change after the completion", postChange(sessionText, withOldVerifier(newVerifierBytes)), HttpStatusCode.Conflict, noKeysBody)
        assertEquals("sync-api: pending mark", 0L, pendingFlag(alice))
    }
}
