package dev.breaker.dictation.ui.screen.signin

import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * The sign-in start handler: a tap opens the website once, a tap while a sign-in is in flight opens
 * nothing, and the refusal and return mappings name the state each answer shows.
 */
class SignInStartIntentHandlerTest {

    private val every = listOf(
        SignInStartState.Idle,
        SignInStartState.Opening,
        SignInStartState.Waiting,
        SignInStartState.RefusedNoServer,
        SignInStartState.RefusedAddress,
        SignInStartState.ReturnedNotFinished,
        SignInStartState.ReturnedFinished,
    )

    @Test
    fun `a new handler shows the idle state and has not started anything`() {
        var calls = 0
        val handler = SignInStartIntentHandler { calls++ }
        assertEquals(SignInStartState.Idle, handler.current())
        assertEquals(0, calls)
    }

    @Test
    fun `a tap in idle opens the website once and shows opening`() {
        var calls = 0
        val handler = SignInStartIntentHandler { calls++ }
        assertEquals(SignInStartState.Opening, handler.tap())
        assertEquals(1, calls)
        assertEquals(SignInStartState.Opening, handler.current())
    }

    @Test
    fun `a tap in each state that may start a sign-in calls onStart once and shows opening`() {
        val starting = listOf(
            SignInStartState.Idle,
            SignInStartState.RefusedNoServer,
            SignInStartState.RefusedAddress,
            SignInStartState.ReturnedNotFinished,
            SignInStartState.ReturnedFinished,
        )
        for (state in starting) {
            var calls = 0
            val handler = SignInStartIntentHandler { calls++ }
            handler.change(state)
            handler.tap()
            assertEquals("calls after a tap in $state", 1, calls)
            assertEquals("state after a tap in $state", SignInStartState.Opening, handler.current())
        }
    }

    @Test
    fun `a tap in opening or waiting calls nothing and keeps the state`() {
        for (state in listOf(SignInStartState.Opening, SignInStartState.Waiting)) {
            var calls = 0
            val handler = SignInStartIntentHandler { calls++ }
            handler.change(state)
            handler.tap()
            assertEquals("calls after a tap in $state", 0, calls)
            assertEquals("state after a tap in $state", state, handler.current())
        }
    }

    @Test
    fun `a second tap while the first sign-in is in flight opens no second website`() {
        var calls = 0
        val handler = SignInStartIntentHandler { calls++ }
        handler.tap()
        handler.tap()
        assertEquals(1, calls)
        assertEquals(SignInStartState.Opening, handler.current())
    }

    @Test
    fun `a state the app sets during the tap is the one kept`() {
        lateinit var handler: SignInStartIntentHandler
        handler = SignInStartIntentHandler { handler.change(SignInStartState.Waiting) }
        handler.tap()
        assertEquals(SignInStartState.Waiting, handler.current())
    }

    @Test
    fun `a refusal with no server shows the no-server state and a bad address shows the other`() {
        val handler = SignInStartIntentHandler { }
        assertEquals(SignInStartState.RefusedNoServer, handler.refusalState(true))
        assertEquals(SignInStartState.RefusedAddress, handler.refusalState(false))
    }

    @Test
    fun `a return that finished shows the finished state and one that did not shows the other`() {
        val handler = SignInStartIntentHandler { }
        assertEquals(SignInStartState.ReturnedFinished, handler.returnState(true))
        assertEquals(SignInStartState.ReturnedNotFinished, handler.returnState(false))
    }

    @Test
    fun `each change sets exactly the state it is given`() {
        val handler = SignInStartIntentHandler { }
        for (state in every) {
            handler.change(state)
            assertEquals(state, handler.current())
        }
    }
}
