package dev.breaker.dictation.commit

import dev.breaker.dictation.core.model.CommitOutcome
import dev.breaker.dictation.core.port.CommitOutcomeResult
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Stores the first task and runs every later one at once, so each call can meet its own poster behaviour. */
private class StoreFirstThenRunInline : BlockPoster {
    val stored: MutableList<Runnable> = ArrayList()
    var posts: Int = 0

    override fun post(task: Runnable): Boolean {
        posts++
        if (posts == 1) {
            stored.add(task)
        } else {
            task.run()
        }
        return true
    }
}

/** Runs the same task twice before post returns, as a main thread that delivers a task twice would. */
private class RunTwicePoster : BlockPoster {
    override fun post(task: Runnable): Boolean {
        task.run()
        task.run()
        return true
    }
}

/**
 * The two signals of a block running on a second thread: started, and release. The bounded wait only fails the test.
 * The bound is 5 seconds, so that it fires before the 10 second time limit of the class and a hang in the claim race fails with its own message.
 */
private const val SAFETY_NET_SECONDS: Long = 5L

private class Gate {
    val started: CompletableDeferred<Unit> = CompletableDeferred()
    private val release: CompletableFuture<Unit> = CompletableFuture()

    fun blockStarted(): Boolean = started.complete(Unit)

    fun waitForRelease() {
        try {
            release.get(SAFETY_NET_SECONDS, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            throw AssertionError("commit: the block was never released, the signal did not arrive", e)
        }
    }

    fun open(): Boolean = release.complete(Unit)
}

/** Runs the posted task on one daemon helper thread, so a block can be running while the caller waits. */
private class ThreadPoster(private val gate: Gate) : BlockPoster {
    private var helper: Thread? = null
    val escaped: MutableList<Throwable> = ArrayList()

    override fun post(task: Runnable): Boolean {
        // A second thread is needed: the deadline can only fire while a block is running if another thread runs the block.
        val created = Thread(
            Runnable {
                try {
                    task.run()
                } catch (e: Throwable) {
                    escaped.add(e)
                    gate.started.completeExceptionally(e)
                }
            },
            "commit-test-main",
        )
        created.isDaemon = true
        created.start()
        helper = created
        return true
    }

    /** Waits for the helper to end and fails the test on a helper that hangs or let a failure escape. */
    fun joinOrFail() {
        val running: Thread = helper ?: return
        running.join(SAFETY_NET_SECONDS * 1000L)
        check(!running.isAlive) { "commit: the helper thread did not end after it was released" }
        check(escaped.isEmpty()) { "commit: a failure escaped the helper thread: $escaped" }
    }
}

/** Passes only once the block is running; lets the block finish only after its own timer made its claim attempt. */
private class GatedDeadline(private val gate: Gate) : HopDeadline {
    override suspend fun elapsed() {
        gate.started.await()
        // The handler runs when this timer has finished, that is after the claim attempt that follows elapsed().
        currentCoroutineContext()[Job]!!.invokeOnCompletion { gate.open() }
    }
}

/** A field whose commit is the running block: it signals "started" and holds until released. */
private class GatedField(private val gate: Gate) : FocusedField {
    val received: MutableList<String> = ArrayList()

    override fun commitText(text: String): FieldCommit {
        received.add(text)
        gate.blockStarted()
        gate.waitForRelease()
        return FieldCommit.ACCEPTED
    }
}

/**
 * The claim of one call: the queued task and the deadline race for it, and
 * exactly one of them wins. None of these tests waits on a clock; a deadline
 * that has already passed is a fake that returns at once.
 */
internal class PostedMainThreadClaimTest {

    /**
     * A hop call that does not return leaves the test thread blocked inside the call, so no
     * assertion can run. This limit turns that into a failure of the named test. It is a
     * safety net, never what a test measures: every test here finishes in a small fraction
     * of it, on one core as in parallel.
     */
    @get:Rule
    val hopTimeLimit: Timeout = Timeout.seconds(10)

    @Test
    fun `a deadline that wins drops the block even when the task runs later`() {
        val poster = ManualPoster()
        val deadline = FiredDeadline()
        val hop = PostedMainThread(poster, { false }, deadline)
        var ran = 0

        val failure: Throwable? = failureOf { hop.call { ran += 1; "late" } }

        assertTrue("commit: a passed deadline must give MainThreadUnavailable, got $failure", failure is MainThreadUnavailable)
        assertEquals("commit: the deadline must have been awaited once", 1, deadline.awaited)
        assertEquals("commit: the task must still be stored, not yet run", 1, poster.tasks.size)
        assertEquals("commit: the block must not run before the main thread gets to it", 0, ran)

        poster.runStored()

        assertEquals("commit: a task that runs after the deadline must not start the block", 0, ran)
        assertTrue("commit: the late task must have been taken out of the store", poster.tasks.isEmpty())
    }

    @Test
    fun `a task that wins the claim returns the block value although the deadline passes`() {
        val poster = InlinePoster()
        val deadline = FiredDeadline()
        val hop = PostedMainThread(poster, { false }, deadline)
        var ran = 0

        var value: String? = null
        val failure: Throwable? = failureOf { value = hop.call { ran += 1; "ran first" } }

        assertNull("commit: a block that ran must not be reported as unavailable, got $failure", failure)
        assertEquals("commit: the block must have run once", 1, ran)
        assertEquals("commit: the caller must get what the block returned", "ran first", value)
        assertEquals("commit: the passed deadline must have been awaited, so it really lost the race", 1, deadline.awaited)
    }

    @Test
    fun `a main thread that never runs the task still frees the caller at the deadline`() {
        val poster = ManualPoster()
        val hop = PostedMainThread(poster, { false }, FiredDeadline())

        val failure: Throwable? = failureOf { hop.call { "never" } }

        assertTrue("commit: a stuck main thread must still end the call, got $failure", failure is MainThreadUnavailable)
        assertEquals("commit: the task must still be waiting in the stuck main thread", 1, poster.tasks.size)
    }

    @Test
    fun `a stored task run twice never starts the block`() {
        val poster = ManualPoster()
        val hop = PostedMainThread(poster, { false }, FiredDeadline())
        var ran = 0
        failureOf { hop.call { ran += 1; "late" } }
        val task: Runnable = poster.tasks[0]

        task.run()
        task.run()
        poster.runStored()
        poster.runStored()

        assertEquals("commit: a dropped task must stay dropped however often it is run", 0, ran)
        assertTrue("commit: the store must be empty after it was run", poster.tasks.isEmpty())
    }

    @Test
    fun `a task delivered twice runs the block once`() {
        val hop = PostedMainThread(RunTwicePoster(), { false }, NeverDeadline())
        var ran = 0

        val value: String = hop.call { ran += 1; "once" }

        assertEquals("commit: the caller must get the value of the first run", "once", value)
        assertEquals("commit: a task that is delivered twice must start the block once", 1, ran)
    }

    @Test
    fun `each call holds its own claim so an abandoned call does not spoil the next`() {
        val poster = StoreFirstThenRunInline()
        val hop = PostedMainThread(poster, { false }, FiredDeadline())
        var firstRan = 0
        var secondRan = 0

        val firstFailure: Throwable? = failureOf { hop.call { firstRan += 1; "first" } }
        val secondValue: String = hop.call { secondRan += 1; "second" }
        poster.stored[0].run()

        assertTrue("commit: the first call must be abandoned, got $firstFailure", firstFailure is MainThreadUnavailable)
        assertEquals("commit: the second call must return its own value", "second", secondValue)
        assertEquals("commit: the second block must have run once", 1, secondRan)
        assertEquals("commit: the abandoned first block must never run, even after the second call", 0, firstRan)
        assertEquals("commit: both calls must have posted", 2, poster.posts)
    }

    @Test
    fun `an interrupted wait drops a block that is still queued`() {
        val poster = InterruptingPoster()
        val hop = PostedMainThread(poster, { false }, NeverDeadline())
        var ran = 0

        val (failure: Throwable?, flag: Boolean) = withFlag { failureOf { hop.call { ran += 1; "late" } } }
        val laterFlag: Boolean = withFlag { poster.runStored() }.second

        assertTrue("commit: an interrupted wait must give MainThreadUnavailable, got $failure", failure is MainThreadUnavailable)
        assertTrue("commit: the interrupt flag must be set after the interrupted wait", flag)
        assertEquals("commit: a task that runs after an interrupted wait must not start the block", 0, ran)
        assertFalse("commit: running the dropped task must not touch the interrupt flag", laterFlag)
    }

    @Test
    fun `a deadline that fires while the block is running does not fail the call and the block result comes back`() {
        val gate = Gate()
        val poster = ThreadPoster(gate)
        val hop = PostedMainThread(poster, { false }, GatedDeadline(gate))
        var value: String? = null
        val failure: Throwable? = try {
            failureOf {
                value = hop.call {
                    gate.blockStarted()
                    gate.waitForRelease()
                    "finished while the deadline had fired"
                }
            }
        } finally {
            gate.open()
            poster.joinOrFail()
        }

        assertNull("commit: a block that was running must not be reported as unavailable, got $failure", failure)
        assertEquals("commit: the caller must get what the block returned", "finished while the deadline had fired", value)
    }

    @Test
    fun `a block that threw while the deadline had fired still reaches the caller as that same exception`() {
        val gate = Gate()
        val poster = ThreadPoster(gate)
        val hop = PostedMainThread(poster, { false }, GatedDeadline(gate))
        val thrown = IllegalStateException("test: the block failed")
        val failure: Throwable? = try {
            failureOf {
                hop.call<Unit> {
                    gate.blockStarted()
                    gate.waitForRelease()
                    throw thrown
                }
            }
        } finally {
            gate.open()
            poster.joinOrFail()
        }

        assertSame("commit: the caller must get the very exception the running block threw, got $failure", thrown, failure)
    }

    @Test
    fun `a commit whose block is running when the deadline fires reports what it did`() {
        val rig = Rig(sdkInt = 32, withField = true)
        val gate = Gate()
        val field = GatedField(gate)
        val poster = ThreadPoster(gate)
        rig.focus.field = field
        val hop = PostedMainThread(poster, { false }, GatedDeadline(gate))
        val service = CommitService(rig.focus, rig.clipboard, rig.notice, hop, 32)

        val result: CommitOutcomeResult = try {
            service.commit(Texts.request())
        } finally {
            gate.open()
            poster.joinOrFail()
        }

        assertEquals("commit: a block that was running must report COMMITTED, got ${result.detail}", CommitOutcome.COMMITTED, result.outcome)
        assertNull("commit: a clean commit must carry no detail", result.detail)
        assertEquals("commit: the field must receive the text once", listOf(Texts.SECRET), field.received)
        assertTrue("commit: nothing may be copied after an accepted commit", rig.clipboard.writes.isEmpty())
        assertEquals("commit: no notice after an accepted commit", 0, rig.notice.shown)
    }
}
