package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.SignInAttempt
import dev.breaker.dictation.core.port.SignInStarter
import dev.breaker.dictation.core.usecase.SignInHolder
import dev.breaker.dictation.ui.SignInStartControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Notes each control call as a short name, in order. */
private class SrlControl : SignInStartControl {
    val calls: MutableList<String> = mutableListOf()

    override fun idle() {
        calls.add("idle")
    }

    override fun opening() {
        calls.add("opening")
    }

    override fun waiting() {
        calls.add("waiting")
    }

    override fun refused(noServer: Boolean) {
        calls.add("refused($noServer)")
    }

    override fun returned(finished: Boolean) {
        calls.add("returned($finished)")
    }
}

/** A starter that is never asked: the screen under test only shows a recorded return. */
private class SrlIdleStarter : SignInStarter {
    override fun begin(): SignInAttempt = throw IllegalStateException("no sign-in is started here")
}

/** The one listener slot of the return store: when it fires, what it can read, and when it stays quiet. */
class SignInReturnStoreListenerTest {

    @Test
    fun `the listener fires once for each record`() {
        val store = SignInReturnStore()
        var calls = 0
        store.listen { calls++ }
        store.record(SignInReturnState.TAKEN)
        assertEquals("wiring: one record must call the listener once", 1, calls)
        store.record(SignInReturnState.NOT_FINISHED)
        assertEquals("wiring: a second record must call it once more", 2, calls)
    }

    @Test
    fun `the state is readable from inside the listener`() {
        val store = SignInReturnStore()
        val seen: MutableList<SignInReturnState?> = mutableListOf()
        store.listen { seen.add(store.take()) }
        store.record(SignInReturnState.TAKEN)
        assertEquals(
            "wiring: the state must be stored before the listener runs",
            listOf(SignInReturnState.TAKEN),
            seen,
        )
    }

    @Test
    fun `the listener is not called after listen null`() {
        val store = SignInReturnStore()
        var calls = 0
        store.listen { calls++ }
        store.listen(null)
        store.record(SignInReturnState.TAKEN)
        assertEquals("wiring: a cleared listener must not be called", 0, calls)
        assertEquals("wiring: the state must still be kept", SignInReturnState.TAKEN, store.take())
    }

    @Test
    fun `a second listener on the return store replaces the first`() {
        val store = SignInReturnStore()
        var first = 0
        var second = 0
        store.listen { first++ }
        store.listen { second++ }
        store.record(SignInReturnState.IGNORED)
        assertEquals("wiring: the replaced listener must stay quiet", 0, first)
        assertEquals("wiring: the new listener must be called once", 1, second)
    }

    @Test
    fun `a record with no listener keeps the state`() {
        val store = SignInReturnStore()
        store.record(SignInReturnState.NO_ATTEMPT)
        assertEquals(
            "wiring: a record with no listener must keep the state",
            SignInReturnState.NO_ATTEMPT,
            store.take(),
        )
    }

    @Test
    fun `a throwing listener never undoes the record and never reaches the caller`() {
        val store = SignInReturnStore()
        store.listen { throw IllegalStateException("listener failed") }
        store.record(SignInReturnState.NOT_FINISHED)
        assertEquals(
            "wiring: the state must survive a throwing listener",
            SignInReturnState.NOT_FINISHED,
            store.take(),
        )
    }

    @Test
    fun `a take inside the listener leaves nothing for the next take`() {
        val store = SignInReturnStore()
        store.listen { store.take() }
        store.record(SignInReturnState.TAKEN)
        assertNull("wiring: the listener took the state, so none is left", store.take())
    }

    @Test
    fun `a listener driven resume shows returned finished and returned not finished`() {
        val store = SignInReturnStore()
        val control = SrlControl()
        val wiring = SignInStartWiring(SignInHolder(SrlIdleStarter()))
        val screen = SignInStartScreen(wiring, control, { true }, { "https://h.example" })
        store.listen { screen.onResume(store.take()) }
        store.record(SignInReturnState.TAKEN)
        store.record(SignInReturnState.NOT_FINISHED)
        store.record(SignInReturnState.IGNORED)
        assertEquals(
            "wiring: the screen must show each result as it lands",
            listOf("returned(true)", "returned(false)"),
            control.calls,
        )
    }
}
