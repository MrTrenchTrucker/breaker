package dev.breaker.dictation.wiring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Protects the one-slot store that carries the return state from the return activity to the
 * start screen: it keeps the latest state, and taking it clears it.
 */
internal class SignInReturnStoreTest {

    @Test
    fun `take on a fresh store returns nothing`() {
        assertNull("wiring: a fresh store must hold nothing", SignInReturnStore().take())
    }

    @Test
    fun `take returns the recorded state once and then nothing`() {
        val store = SignInReturnStore()
        store.record(SignInReturnState.TAKEN)
        assertEquals("wiring: take must return the recorded state", SignInReturnState.TAKEN, store.take())
        assertNull("wiring: take must clear the state", store.take())
        assertNull("wiring: a second take after a clear must still be nothing", store.take())
    }

    @Test
    fun `record keeps only the latest state`() {
        val store = SignInReturnStore()
        store.record(SignInReturnState.NOT_FINISHED)
        store.record(SignInReturnState.TAKEN)
        assertEquals("wiring: the latest state must win", SignInReturnState.TAKEN, store.take())
        store.record(SignInReturnState.NO_ATTEMPT)
        store.record(SignInReturnState.IGNORED)
        assertEquals("wiring: the latest state must win again", SignInReturnState.IGNORED, store.take())
    }

    @Test
    fun `every state comes back as itself`() {
        val store = SignInReturnStore()
        for (state in SignInReturnState.values()) {
            store.record(state)
            assertEquals("wiring: the store must keep the state as it was given", state, store.take())
        }
    }
}
