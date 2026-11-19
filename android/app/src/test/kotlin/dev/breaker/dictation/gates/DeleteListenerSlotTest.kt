package dev.breaker.dictation.gates

import dev.breaker.dictation.host.DeleteListenerSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one completion listener of model deletes. The slot is what TileHost.setDeleteListener sets and what
 * TileHost.deleteModel answers; the source pins read the real TileHost text, so the answer sits inside the
 * completion that ModelDeletion posts to the main thread. Nothing here has been compiled or run: every
 * expectation is a hand trace.
 */
internal class DeleteListenerSlotTest {

    private val calls: MutableList<Boolean> = ArrayList()

    @Test
    fun `a delete that finishes after the listener was cleared calls nothing`() {
        val slot = DeleteListenerSlot()
        slot.set { calls.add(it) }
        slot.set(null)
        slot.answer(true)
        slot.answer(false)
        assertEquals("app: a cleared delete listener must not be called", emptyList<Boolean>(), calls)
    }

    @Test
    fun `an answer when no delete listener was ever set does nothing`() {
        DeleteListenerSlot().answer(true)
    }

    @Test
    fun `a second registration replaces the first delete listener`() {
        val slot = DeleteListenerSlot()
        val first: MutableList<Boolean> = ArrayList()
        slot.set { first.add(it) }
        slot.set { calls.add(it) }
        slot.answer(true)
        assertEquals("app: the replaced delete listener must not be called", emptyList<Boolean>(), first)
        assertEquals("app: the second delete listener must be called once with the answer", listOf(true), calls)
    }

    @Test
    fun `a refused answer reaches the delete listener as false`() {
        val slot = DeleteListenerSlot()
        slot.set { calls.add(it) }
        slot.answer(false)
        assertEquals("app: a refused delete reaches the listener as false", listOf(false), calls)
    }

    private fun tileHostFlat(): String =
        AppSourceFiles.strip(AppSourceFiles.mainFile("kotlin/dev/breaker/dictation/host/TileHost.kt")).code
            .replace(Regex("""\s+"""), " ")

    @Test
    fun `the model delete answers the delete listener on the main completion`() {
        val text = tileHostFlat()
        assertTrue(
            "app: deleteModel must answer the slot inside the completion it passes to ModelDeletion, got: $text",
            text.contains("modelDeletion.request(id) { removed -> onFinished(removed) deleteSlot.answer(removed) }"),
        )
    }

    @Test
    fun `the tile host sets the slot through its one delete listener member`() {
        val text = tileHostFlat()
        assertTrue(
            "app: setDeleteListener must set the slot, got: $text",
            text.contains("fun setDeleteListener(listener: ((Boolean) -> Unit)?) { deleteSlot.set(listener) }"),
        )
    }
}
