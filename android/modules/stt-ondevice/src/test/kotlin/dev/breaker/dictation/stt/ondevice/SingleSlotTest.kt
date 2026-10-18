package dev.breaker.dictation.stt.ondevice

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The width of the engine's decode slot.
 *
 * The slot is built in one named place, [singleSlot], so a test can see the
 * width that is asked for without waiting on any thread or any clock.
 */
class SingleSlotTest {

    /** A dispatcher view the test owns; it is only ever compared by identity. */
    private class MarkerDispatcher : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            block.run()
        }
    }

    /**
     * A base dispatcher that records every width it is asked for and answers
     * with [answer], so the test can tell the view it returned from the base itself.
     */
    private class RecordingDispatcher(private val answer: CoroutineDispatcher) : CoroutineDispatcher() {
        val widths = ArrayList<Int>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            block.run()
        }

        override fun limitedParallelism(parallelism: Int, name: String?): CoroutineDispatcher {
            widths.add(parallelism)
            return answer
        }
    }

    @Test
    fun `singleSlot asks the base dispatcher for a width of exactly one`() {
        val view = MarkerDispatcher()
        val base = RecordingDispatcher(view)

        val slot = singleSlot(base)

        assertEquals("singleSlot must ask the base dispatcher for exactly one width, of one", listOf(1), base.widths)
        assertSame("singleSlot must return the single-slot view the base dispatcher built", view, slot)
    }
}
