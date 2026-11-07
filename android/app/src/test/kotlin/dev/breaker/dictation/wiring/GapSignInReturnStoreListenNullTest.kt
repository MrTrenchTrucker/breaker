package dev.breaker.dictation.wiring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** listen(null) clears the listener; record keeps the state and calls no listener. */
class GapSignInReturnStoreListenNullTest {

    @Test
    fun `listen null clears the listener and the record still holds state`() {
        val store = SignInReturnStore()
        var calls = 0
        store.listen { calls += 1 }
        store.listen(null)
        store.record(SignInReturnState.NO_ATTEMPT)
        assertEquals("no listener ran after listen(null)", 0, calls)
        assertEquals("the record still holds its state", SignInReturnState.NO_ATTEMPT, store.take())
    }

    @Test
    fun `without listen null the listener is called once`() {
        val store = SignInReturnStore()
        var calls = 0
        store.listen { calls += 1 }
        store.record(SignInReturnState.TAKEN)
        assertEquals("the listener ran once", 1, calls)
    }

    @Test
    fun `record after listen null keeps only its own state`() {
        val store = SignInReturnStore()
        var calls = 0
        store.listen { calls += 1 }
        store.listen(null)
        store.record(SignInReturnState.TAKEN)
        store.record(SignInReturnState.IGNORED)
        assertEquals("no listener ran", 0, calls)
        assertEquals("the latest state wins", SignInReturnState.IGNORED, store.take())
        assertNull("the state is taken", store.take())
    }

    @Test
    fun `clearing the listener on one store does not touch another`() {
        val one = SignInReturnStore()
        val two = SignInReturnStore()
        var oneCalls = 0
        var twoCalls = 0
        one.listen { oneCalls += 1 }
        two.listen { twoCalls += 1 }
        one.listen(null)
        one.record(SignInReturnState.TAKEN)
        two.record(SignInReturnState.TAKEN)
        assertEquals("the first listener did not run", 0, oneCalls)
        assertEquals("the second listener ran once", 1, twoCalls)
    }
}
