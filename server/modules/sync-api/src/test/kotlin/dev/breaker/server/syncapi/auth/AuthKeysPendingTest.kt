package dev.breaker.server.syncapi.auth

import io.ktor.http.HttpStatusCode
import org.junit.Assert.assertEquals
import org.junit.Test

internal class AuthKeysPendingTest : ResetRouteTestBase() {

    @Test
    fun `a pending account gets 409 with the pending text and is not keyed while another account is keyed`() {
        val adminText = startAdmin()
        val alice = register("Alice", 2)
        val bob = register("Bob", 3)
        resetAs(adminText, "ALICE")
        // Sessions made after the reset, because the reset ended every older one.
        val bobText = sessionToken(bob)
        val aliceText = sessionOnPendingAccount(alice)
        val before = stateOf(alice)
        assertAnswer("keys on a pending account", postKeys(aliceText, validBody()), HttpStatusCode.Conflict, pendingBody)
        assertKeyless(alice, "the keys call on a pending account")
        assertEquals("sync-api: the refused keys call changed the account", before, stateOf(alice))
        assertAnswer("keys on an account that is not pending", postKeys(bobText, validBody()), HttpStatusCode.OK, """{"key_version":1}""")
        assertStored(bob, 1, "the keys call of the other account")
    }

    @Test
    fun `a pending account with a bad body gets 400 and not 409`() {
        val adminText = startAdmin()
        val alice = register("Alice", 2)
        resetAs(adminText, "ALICE")
        val aliceText = sessionOnPendingAccount(alice)
        assertAnswer("not json", postKeys(aliceText, "{ not json"), HttpStatusCode.BadRequest, badRequestBody)
        assertAnswer("empty object", postKeys(aliceText, "{}"), HttpStatusCode.BadRequest, badRequestBody)
        assertKeyless(alice, "the bad keys calls")
    }

    @Test
    fun `after the completion the account takes its keys and key_version becomes 1`() {
        val adminText = startAdmin()
        val alice = register("Alice", 2)
        val code = resetAs(adminText, "ALICE")
        val completed = postComplete("Alice", code)
        assertEquals("sync-api: completion", HttpStatusCode.OK, completed.status)
        val sessionText = sessionTextOf(completed.body)
        assertAnswer("keys after the completion", postKeys(sessionText, validBody()), HttpStatusCode.OK, """{"key_version":1}""")
        assertStored(alice, 1, "the keys call after the completion")
    }
}
