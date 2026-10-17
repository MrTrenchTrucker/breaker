package dev.breaker.dictation.core.port

import dev.breaker.dictation.core.model.HeardWord
import dev.breaker.dictation.core.model.WordUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract of [WordStream], exercised against a fake written only in domain
 * types. The fake is synchronous: it delivers each update on the thread that calls
 * [feed], which is the caller's own thread. So the rule that updates arrive on one
 * thread is stated in the port's KDoc and is not tested here; no thread is started
 * by these tests, and no wait is needed.
 */
class WordStreamFakeTest {
    /** A stream that delivers what it is fed, while it is running, to the first callback it was started with. */
    private class SynchronousWordStream : WordStream {
        private var callback: ((WordUpdate) -> Unit)? = null

        /** How many times a real start happened. A start while running does not count. */
        var realStarts: Int = 0
            private set

        override var isRunning: Boolean = false
            private set

        override fun start(onUpdate: (WordUpdate) -> Unit) {
            if (isRunning) return
            realStarts++
            callback = onUpdate
            isRunning = true
        }

        override fun stop() {
            isRunning = false
            callback = null
        }

        /** Deliver [update] to the callback, if the stream is running; otherwise nothing happens. */
        fun feed(update: WordUpdate) {
            val listener = callback ?: return
            listener(update)
        }
    }

    private val andUpdate = WordUpdate(listOf(HeardWord("and", 1_200L)), false)

    @Test
    fun `a new word stream is not running until it is started`() {
        val stream = SynchronousWordStream()

        assertFalse(stream.isRunning)
        stream.start { }
        assertTrue(stream.isRunning)
        stream.stop()
        assertFalse(stream.isRunning)
    }

    @Test
    fun `a second start while running neither starts again nor replaces the callback`() {
        val stream = SynchronousWordStream()
        val first = mutableListOf<WordUpdate>()
        val second = mutableListOf<WordUpdate>()

        stream.start { first += it }
        stream.start { second += it }
        stream.feed(andUpdate)

        assertEquals("the stream started once", 1, stream.realStarts)
        assertEquals("the first callback stays", listOf(andUpdate), first)
        assertTrue("the second callback was never used", second.isEmpty())
    }

    @Test
    fun `stopping a stopped stream is safe, twice over`() {
        val stream = SynchronousWordStream()

        stream.stop()
        stream.stop()

        assertFalse(stream.isRunning)
    }

    @Test
    fun `no update arrives after stop returns`() {
        val stream = SynchronousWordStream()
        val heard = mutableListOf<WordUpdate>()
        stream.start { heard += it }

        stream.feed(andUpdate)
        stream.stop()
        stream.feed(WordUpdate(listOf(HeardWord("late", 2_000L)), true))

        assertEquals("only the update before stop arrived", listOf(andUpdate), heard)
    }

    @Test
    fun `stopping from inside the callback returns, and later feeds deliver nothing`() {
        val stream = SynchronousWordStream()
        val heard = mutableListOf<WordUpdate>()
        stream.start { update ->
            heard += update
            stream.stop()
        }

        stream.feed(andUpdate)
        assertFalse("the stop from inside the callback took effect", stream.isRunning)
        stream.feed(andUpdate)

        assertEquals("the callback ran once, and the later feed delivered nothing", listOf(andUpdate), heard)
    }

    @Test
    fun `a stopped stream can be started again and delivers to the new callback`() {
        val stream = SynchronousWordStream()
        val old = mutableListOf<WordUpdate>()
        val fresh = mutableListOf<WordUpdate>()
        stream.start { old += it }
        stream.stop()

        stream.start { fresh += it }
        stream.feed(andUpdate)

        assertEquals("two real starts", 2, stream.realStarts)
        assertTrue("the old callback is not used again", old.isEmpty())
        assertEquals(listOf(andUpdate), fresh)
    }
}
