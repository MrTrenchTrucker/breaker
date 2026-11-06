package dev.breaker.dictation.wiring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests of SignInReturnStore written from the app card and the class KDoc: one state at a time,
 * one listener slot, record keeps the state before it calls the listener, a throwing listener
 * never undoes the record and never reaches the caller, take returns the state and clears it.
 */
class ReaderSignInReturnStoreRulesTest {
    @Test
    fun `a fresh return store holds nothing and take returns null`() {
        val store = SignInReturnStore()
        assertNull("a fresh store must hold nothing", store.take())
    }

    @Test
    fun `a second record replaces a first record that was not taken`() {
        val store = SignInReturnStore()
        store.record(SignInReturnState.NOT_FINISHED)
        store.record(SignInReturnState.TAKEN)
        assertEquals("the latest state must win", SignInReturnState.TAKEN, store.take())
        assertNull("only one state is held", store.take())
    }

    @Test
    fun `a throwing listener never reaches the caller and the state is still held`() {
        val store = SignInReturnStore()
        store.listen { throw IllegalStateException("listener failed") }
        var reached = false
        try {
            store.record(SignInReturnState.NOT_FINISHED)
        } catch (e: IllegalStateException) {
            reached = true
        }
        assertFalse("the record call must not throw", reached)
        assertEquals(
            "the record stays after a throwing listener",
            SignInReturnState.NOT_FINISHED,
            store.take(),
        )
    }

    @Test
    fun `a listener that takes on every record sees each state in order`() {
        val store = SignInReturnStore()
        val seen = mutableListOf<SignInReturnState?>()
        store.listen { seen.add(store.take()) }
        store.record(SignInReturnState.TAKEN)
        store.record(SignInReturnState.IGNORED)
        assertEquals(
            "each record is readable from inside its own listener call",
            listOf<SignInReturnState?>(SignInReturnState.TAKEN, SignInReturnState.IGNORED),
            seen,
        )
        assertNull("both were taken inside the listener", store.take())
    }

    @Test
    fun `two stores share neither a state nor a listener`() {
        val one = SignInReturnStore()
        val two = SignInReturnStore()
        var oneCalls = 0
        var twoCalls = 0
        one.listen { oneCalls += 1 }
        two.listen { twoCalls += 1 }
        one.record(SignInReturnState.TAKEN)
        assertEquals("the first listener ran once", 1, oneCalls)
        assertEquals("the second listener did not run", 0, twoCalls)
        assertNull("the second store holds nothing", two.take())
        assertEquals("the first store holds its state", SignInReturnState.TAKEN, one.take())
    }

    @Test
    fun `clearing the listener after a record leaves the held state alone`() {
        val store = SignInReturnStore()
        var calls = 0
        store.listen { calls += 1 }
        store.record(SignInReturnState.NO_ATTEMPT)
        store.listen(null)
        assertEquals("the listener ran for the record before it was cleared", 1, calls)
        assertEquals(
            "clearing the listener does not clear the state",
            SignInReturnState.NO_ATTEMPT,
            store.take(),
        )
    }
}
