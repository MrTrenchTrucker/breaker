package dev.breaker.dictation.commit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Stores the task, sets the interrupt flag of the thread that posts it, and reports success. */
internal class InterruptingPoster : BlockPoster {
    val tasks: MutableList<Runnable> = ArrayList()
    var posts: Int = 0

    override fun post(task: Runnable): Boolean {
        posts++
        tasks.add(task)
        Thread.currentThread().interrupt()
        return true
    }

    fun runStored() {
        val batch: List<Runnable> = ArrayList(tasks)
        tasks.clear()
        for (task in batch) {
            task.run()
        }
    }
}

/** What [block] threw, or null when it returned. */
internal fun failureOf(block: () -> Unit): Throwable? =
    try {
        block()
        null
    } catch (e: Throwable) {
        e
    }

/** Runs [block] with a clean interrupt flag; returns its value and the flag as it stands afterwards, and leaves the flag clean. */
internal fun <T> withFlag(block: () -> T): Pair<T, Boolean> {
    Thread.interrupted()
    try {
        val value: T = block()
        return Pair(value, Thread.currentThread().isInterrupted)
    } finally {
        Thread.interrupted()
    }
}

private class BrokenStep : Error("commit: test error")

/** Keeps the task and still refuses it: the main thread says no, but the task stays reachable. */
private class StoringRefusingPoster : BlockPoster {
    val tasks: MutableList<Runnable> = ArrayList()
    var posts: Int = 0

    override fun post(task: Runnable): Boolean {
        posts++
        tasks.add(task)
        return false
    }
}

/** Runs the task inside post and swallows whatever escapes it, as the boundary of a real looper would. */
private class EscapeCapturingInlinePoster : BlockPoster {
    var escaped: Throwable? = null

    override fun post(task: Runnable): Boolean {
        try {
            task.run()
        } catch (e: Throwable) {
            escaped = e
        }
        return true
    }
}

private const val NOT_RUN_TEXT: String = "commit: the main thread did not run the block in time"

/** The hop on its own: values, failures, a refused post and an interrupted wait. */
internal class PostedMainThreadTest {

    /**
     * A hop call that does not return leaves the test thread blocked inside the call, so no
     * assertion can run. This limit turns that into a failure of the named test. It is a
     * safety net, never what a test measures: every test here finishes in a small fraction
     * of it, on one core as in parallel.
     */
    @get:Rule
    val hopTimeLimit: Timeout = Timeout.seconds(10)

    @Test
    fun `a call from the main thread runs the block on the caller and posts nothing`() {
        val poster = InlinePoster()
        val deadline = NeverDeadline()
        val hop = PostedMainThread(poster, { true }, deadline)
        var ran = 0

        val value: String = hop.call { ran += 1; "inline value" }

        assertEquals("commit: the block must return its value", "inline value", value)
        assertEquals("commit: the block must run exactly once", 1, ran)
        assertEquals("commit: a call from the main thread must post nothing", 0, poster.posts)
        assertEquals("commit: a call from the main thread must not await the deadline", 0, deadline.awaited)
    }

    @Test
    fun `a posted block that runs returns its value to the caller`() {
        val poster = InlinePoster()
        val hop = PostedMainThread(poster, { false }, NeverDeadline())
        var ran = 0

        val value: String = hop.call { ran += 1; "posted value" }

        assertEquals("commit: the caller must get the value the block returned", "posted value", value)
        assertEquals("commit: the block must run exactly once", 1, ran)
        assertEquals("commit: the block must be posted once", 1, poster.posts)
    }

    @Test
    fun `a block that throws an exception gives the caller that same exception`() {
        val thrown = IllegalStateException("commit: test failure")
        val hop = PostedMainThread(InlinePoster(), { false }, NeverDeadline())

        val failure: Throwable? = failureOf { hop.call<Unit> { throw thrown } }

        assertNotNull("commit: the failure of the block must reach the caller", failure)
        assertSame("commit: the caller must get the very exception the block threw", thrown, failure)
    }

    @Test
    fun `a block that throws an error gives the caller that same error`() {
        val thrown = BrokenStep()
        val hop = PostedMainThread(InlinePoster(), { false }, NeverDeadline())

        val failure: Throwable? = failureOf { hop.call<Unit> { throw thrown } }

        assertNotNull("commit: an error of the block must reach the caller", failure)
        assertSame("commit: the caller must get the very error the block threw", thrown, failure)
    }

    @Test
    fun `a refused post fails at once as unavailable and the deadline is never awaited`() {
        val poster = RefusingPoster()
        val deadline = ForbiddenDeadline()
        val hop = PostedMainThread(poster, { false }, deadline)
        var ran = 0

        val failure: Throwable? = failureOf { hop.call { ran += 1; "never" } }

        assertTrue("commit: a refused post must give MainThreadUnavailable, got $failure", failure is MainThreadUnavailable)
        assertEquals("commit: the block must not run after a refused post", 0, ran)
        assertEquals("commit: the post must have been tried once", 1, poster.posts)
        assertEquals("commit: a refused post must not await the deadline", 0, deadline.awaited)
    }

    @Test
    fun `the unavailable failure carries a constant message that holds nothing from the block`() {
        val leaking: () -> String = { throw IllegalStateException(Texts.LEAK_MARKER) }
        val stored = ManualPoster()
        val lateFailure: Throwable? =
            failureOf { PostedMainThread(stored, { false }, FiredDeadline()).call(leaking) }
        val refusedFailure: Throwable? =
            failureOf { PostedMainThread(RefusingPoster(), { false }, ForbiddenDeadline()).call(leaking) }

        assertTrue("commit: the late path must give MainThreadUnavailable, got $lateFailure", lateFailure is MainThreadUnavailable)
        assertTrue("commit: the refused path must give MainThreadUnavailable, got $refusedFailure", refusedFailure is MainThreadUnavailable)
        val lateMessage: String = lateFailure?.message ?: ""
        val refusedMessage: String = refusedFailure?.message ?: ""
        assertTrue("commit: the message must start with the module prefix: $lateMessage", lateMessage.startsWith("commit:"))
        assertFalse("commit: the message must not hold anything from the block: $lateMessage", lateMessage.contains(Texts.LEAK_MARKER))
        assertFalse("commit: the message must not hold anything from the block: $refusedMessage", refusedMessage.contains(Texts.LEAK_MARKER))
        assertEquals("commit: the late and refused paths must share one constant message", lateMessage, refusedMessage)
    }

    @Test
    fun `an interrupted wait fails as unavailable and puts the interrupt flag back`() {
        val poster = InterruptingPoster()
        val hop = PostedMainThread(poster, { false }, NeverDeadline())

        val (failure: Throwable?, flag: Boolean) = withFlag { failureOf { hop.call { "never" } } }

        assertTrue("commit: an interrupted wait must give MainThreadUnavailable, got $failure", failure is MainThreadUnavailable)
        assertTrue("commit: the interrupt flag must be set again after an interrupted wait", flag)
        assertEquals("commit: the block must have been posted once", 1, poster.posts)
    }

    @Test
    fun `a call that is not interrupted leaves the interrupt flag clear`() {
        val hop = PostedMainThread(InlinePoster(), { false }, NeverDeadline())

        val (value: String, flag: Boolean) = withFlag { hop.call { "plain" } }

        assertEquals("commit: the block must return its value", "plain", value)
        assertFalse("commit: a plain call must not set the interrupt flag", flag)
    }

    @Test
    fun `a refused post leaves the interrupt flag clear`() {
        val hop = PostedMainThread(RefusingPoster(), { false }, ForbiddenDeadline())

        val (failure: Throwable?, flag: Boolean) = withFlag { failureOf { hop.call { "never" } } }

        assertTrue("commit: a refused post must give MainThreadUnavailable, got $failure", failure is MainThreadUnavailable)
        assertFalse("commit: a refused post must not set the interrupt flag", flag)
    }

    @Test
    fun `a task whose post was refused never starts the block if it is run anyway`() {
        val poster = StoringRefusingPoster()
        val deadline = ForbiddenDeadline()
        val hop = PostedMainThread(poster, { false }, deadline)
        var ran = 0

        val failure: Throwable? = failureOf { hop.call { ran += 1; "never" } }

        assertTrue("commit: a refused post must give MainThreadUnavailable, got $failure", failure is MainThreadUnavailable)
        assertEquals("commit: a refused post must not await the deadline", 0, deadline.awaited)
        assertEquals("commit: the refused task must be kept for this test", 1, poster.tasks.size)
        poster.tasks.single().run()
        assertEquals("commit: a refused task that is run anyway must not start the block", 0, ran)
    }

    @Test
    fun `the failure of an interrupted wait carries only the constant message`() {
        val hop = PostedMainThread(InterruptingPoster(), { false }, NeverDeadline())

        val failure: Throwable? = withFlag { failureOf { hop.call { "never" } } }.first

        assertTrue("commit: an interrupted wait must give MainThreadUnavailable, got $failure", failure is MainThreadUnavailable)
        assertEquals("commit: the interrupted wait must carry exactly the constant message", NOT_RUN_TEXT, failure?.message)
    }

    @Test
    fun `the late and the refused failures carry exactly the constant message`() {
        val lateFailure: Throwable? =
            failureOf { PostedMainThread(ManualPoster(), { false }, FiredDeadline()).call { "never" } }
        val refusedFailure: Throwable? =
            failureOf { PostedMainThread(RefusingPoster(), { false }, ForbiddenDeadline()).call { "never" } }

        assertTrue("commit: the late path must give MainThreadUnavailable, got $lateFailure", lateFailure is MainThreadUnavailable)
        assertTrue("commit: the refused path must give MainThreadUnavailable, got $refusedFailure", refusedFailure is MainThreadUnavailable)
        assertEquals("commit: the late path must carry exactly the constant message", NOT_RUN_TEXT, lateFailure?.message)
        assertEquals("commit: the refused path must carry exactly the constant message", NOT_RUN_TEXT, refusedFailure?.message)
    }

    @Test
    fun `an Error thrown by a running block reaches the caller even when the poster swallows what escapes the task`() {
        val thrown = BrokenStep()
        val poster = EscapeCapturingInlinePoster()
        val hop = PostedMainThread(poster, { false }, NeverDeadline())

        val failure: Throwable? = failureOf { hop.call<Unit> { throw thrown } }

        assertSame("commit: the caller must get the very error the block threw", thrown, failure)
        assertNull("commit: nothing may escape the posted task", poster.escaped)
    }
}
