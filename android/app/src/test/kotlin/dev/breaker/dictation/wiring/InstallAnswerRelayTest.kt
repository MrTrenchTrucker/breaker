package dev.breaker.dictation.wiring

import dev.breaker.dictation.updater.InstallReasons
import dev.breaker.dictation.updater.InstallResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The install answer holder over a main post that holds each block until the test runs the queue, so a
 * delivery is seen only when the main thread would run it.
 */
class InstallAnswerRelayTest {

    /** A main post that holds each block until [runQueue] runs them in the order they were posted. */
    private class HeldMainPost : MainPost {
        val queue: MutableList<() -> Unit> = ArrayList()

        override fun post(block: () -> Unit) {
            queue.add(block)
        }

        fun runQueue() {
            val blocks: List<() -> Unit> = ArrayList(queue)
            queue.clear()
            for (block in blocks) block()
        }
    }

    private fun refused(): InstallResult = InstallResult.Refused(InstallReasons.NOT_ALLOWED)

    @Test
    fun `an answer reaches the listener only when the main post runs`() {
        val post = HeldMainPost()
        val answers = InstallAnswerRelay(post)
        val seen: MutableList<InstallResult> = ArrayList()
        answers.listen { seen.add(it) }
        answers.report(refused())
        assertEquals("the answer is held until the main post runs", 0, seen.size)
        post.runQueue()
        assertEquals("the listener takes the answer once", 1, seen.size)
    }

    @Test
    fun `a listener set before the post runs takes the answer`() {
        val post = HeldMainPost()
        val answers = InstallAnswerRelay(post)
        val seen: MutableList<InstallResult> = ArrayList()
        answers.report(refused())
        answers.listen { seen.add(it) }
        post.runQueue()
        assertEquals("the listener set at delivery time takes the answer", 1, seen.size)
    }

    @Test
    fun `an answer with no listener set when the post runs is dropped`() {
        val post = HeldMainPost()
        val answers = InstallAnswerRelay(post)
        val seen: MutableList<InstallResult> = ArrayList()
        answers.report(refused())
        post.runQueue()
        answers.listen { seen.add(it) }
        post.runQueue()
        assertEquals("nothing is replayed to a later listener", 0, seen.size)
    }

    @Test
    fun `release clears the listener so a later answer is dropped`() {
        val post = HeldMainPost()
        val answers = InstallAnswerRelay(post)
        val seen: MutableList<InstallResult> = ArrayList()
        answers.listen { seen.add(it) }
        answers.release()
        answers.report(refused())
        post.runQueue()
        assertEquals("a released listener takes nothing", 0, seen.size)
    }

    @Test
    fun `a second listen replaces the first`() {
        val post = HeldMainPost()
        val answers = InstallAnswerRelay(post)
        val first: MutableList<InstallResult> = ArrayList()
        val second: MutableList<InstallResult> = ArrayList()
        answers.listen { first.add(it) }
        answers.listen { second.add(it) }
        answers.report(refused())
        post.runQueue()
        assertEquals("the replaced listener takes nothing", 0, first.size)
        assertEquals("the newest listener takes the answer", 1, second.size)
    }

    @Test
    fun `answers are delivered in the order they were reported`() {
        val post = HeldMainPost()
        val answers = InstallAnswerRelay(post)
        val seen: MutableList<InstallResult> = ArrayList()
        answers.listen { seen.add(it) }
        answers.report(InstallResult.Started)
        answers.report(refused())
        post.runQueue()
        assertEquals("both answers are delivered", 2, seen.size)
        assertEquals("the start is delivered first", InstallResult.Started, seen[0])
        assertTrue("the refusal is delivered second", seen[1] is InstallResult.Refused)
    }

    @Test
    fun `a finish reaches the finish listener only when the main post runs`() {
        val post = HeldMainPost()
        val answers = InstallAnswerRelay(post)
        val seen: MutableList<Int> = ArrayList()
        answers.listenFinished { seen.add(it) }
        answers.reportFinished(3)
        assertEquals("the finish is held until the main post runs", 0, seen.size)
        post.runQueue()
        assertEquals("the finish listener takes the finish once", 1, seen.size)
    }

    @Test
    fun `the finish listener takes the status code it was given`() {
        val post = HeldMainPost()
        val answers = InstallAnswerRelay(post)
        val seen: MutableList<Int> = ArrayList()
        answers.listenFinished { seen.add(it) }
        answers.reportFinished(3)
        answers.reportFinished(1)
        post.runQueue()
        assertEquals("both finishes are delivered in order", listOf(3, 1), seen)
    }

    @Test
    fun `a finish with no finish listener set when the post runs is dropped`() {
        val post = HeldMainPost()
        val answers = InstallAnswerRelay(post)
        val seen: MutableList<Int> = ArrayList()
        answers.reportFinished(3)
        post.runQueue()
        answers.listenFinished { seen.add(it) }
        post.runQueue()
        assertEquals("nothing is replayed to a later finish listener", 0, seen.size)
    }

    @Test
    fun `release clears the finish listener too`() {
        val post = HeldMainPost()
        val answers = InstallAnswerRelay(post)
        val seen: MutableList<Int> = ArrayList()
        answers.listenFinished { seen.add(it) }
        answers.release()
        answers.reportFinished(3)
        post.runQueue()
        assertEquals("a released finish listener takes nothing", 0, seen.size)
    }

    @Test
    fun `a second listenFinished replaces the first`() {
        val post = HeldMainPost()
        val answers = InstallAnswerRelay(post)
        val first: MutableList<Int> = ArrayList()
        val second: MutableList<Int> = ArrayList()
        answers.listenFinished { first.add(it) }
        answers.listenFinished { second.add(it) }
        answers.reportFinished(3)
        post.runQueue()
        assertEquals("the replaced finish listener takes nothing", 0, first.size)
        assertEquals("the newest finish listener takes the finish", 1, second.size)
    }

    @Test
    fun `a finish does not reach the answer listener`() {
        val post = HeldMainPost()
        val answers = InstallAnswerRelay(post)
        val seen: MutableList<InstallResult> = ArrayList()
        answers.listen { seen.add(it) }
        answers.reportFinished(3)
        post.runQueue()
        assertEquals("the answer listener takes no finish", 0, seen.size)
    }
}
