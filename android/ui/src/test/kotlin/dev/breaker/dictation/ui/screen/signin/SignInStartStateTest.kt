package dev.breaker.dictation.ui.screen.signin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The sign-in start states: one fixed name each, with no data in it, so no state can print a server
 * address, a code, a key or a link. Written before the state file, so each name is pinned on its own.
 */
class SignInStartStateTest {

    private val all = listOf(
        SignInStartState.Idle,
        SignInStartState.Opening,
        SignInStartState.Waiting,
        SignInStartState.RefusedNoServer,
        SignInStartState.RefusedAddress,
        SignInStartState.ReturnedNotFinished,
        SignInStartState.ReturnedFinished,
    )

    @Test
    fun `each state prints its own fixed name`() {
        assertEquals("Idle", SignInStartState.Idle.toString())
        assertEquals("Opening", SignInStartState.Opening.toString())
        assertEquals("Waiting", SignInStartState.Waiting.toString())
        assertEquals("RefusedNoServer", SignInStartState.RefusedNoServer.toString())
        assertEquals("RefusedAddress", SignInStartState.RefusedAddress.toString())
        assertEquals("ReturnedNotFinished", SignInStartState.ReturnedNotFinished.toString())
        assertEquals("ReturnedFinished", SignInStartState.ReturnedFinished.toString())
    }

    @Test
    fun `there are exactly seven states and they are all different`() {
        assertEquals(7, all.toSet().size)
    }

    @Test
    fun `no state prints a link name and no state prints a key or a code name`() {
        for (state in all) {
            val printed = state.toString()
            assertTrue("$printed names a link", !printed.contains("link"))
            assertTrue("$printed names a key", !printed.contains("key"))
            assertTrue("$printed names a code", !printed.contains("code"))
        }
    }
}
