package dev.breaker.dictation.ui.screen.firstrun

import dev.breaker.dictation.ui.screen.history.Cancellation
import dev.breaker.dictation.ui.screen.history.DelayedWork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A [DelayedWork] held in memory. It records each delay, keeps the work, runs the oldest wait that
 * was not cancelled when [fire] is called, and never runs a cancelled wait through [fire].
 * [runCancelled] runs the oldest cancelled wait anyway, to show that the timer ignores a wait it ended.
 */
private class HeldWork : DelayedWork {
    val delays = mutableListOf<Long>()
    private val waits = mutableListOf<Held>()

    private class Held(val work: () -> Unit) {
        var cancelled: Boolean = false
    }

    override fun schedule(delayMs: Long, work: () -> Unit): Cancellation {
        delays.add(delayMs)
        val held = Held(work)
        waits.add(held)
        return Cancellation { held.cancelled = true }
    }

    /** Runs the oldest wait that is not cancelled; true when one ran. */
    fun fire(): Boolean {
        val next = waits.firstOrNull { !it.cancelled } ?: return false
        waits.remove(next)
        next.work()
        return true
    }

    /** Runs the oldest cancelled wait, which a real queue would not run; true when one ran. */
    fun runCancelled(): Boolean {
        val next = waits.firstOrNull { it.cancelled } ?: return false
        waits.remove(next)
        next.work()
        return true
    }
}

/**
 * The welcome timer: one wait of the welcome length, a tap that ends it at once, and exactly one
 * hand-over however the wait and the tap are ordered.
 */
class FirstRunWelcomeTest {
    @Test
    fun `the welcome lasts three seconds`() {
        assertTrue("ui: first run: the welcome wait is not 3000 milliseconds", WELCOME_MS == 3000L)
    }

    @Test
    fun `start schedules exactly one wait of the welcome length`() {
        val work = HeldWork()
        val timer = WelcomeTimer(work) {}
        timer.start()
        assertEquals("ui: first run: start did not schedule exactly one wait of WELCOME_MS", listOf(WELCOME_MS), work.delays)
    }

    @Test
    fun `a second start schedules no second wait`() {
        val work = HeldWork()
        val timer = WelcomeTimer(work) {}
        timer.start()
        timer.start()
        assertEquals("ui: first run: a second start scheduled another wait", 1, work.delays.size)
    }

    @Test
    fun `firing the wait calls onDone once`() {
        val work = HeldWork()
        var calls = 0
        val timer = WelcomeTimer(work) { calls++ }
        timer.start()
        assertTrue("ui: first run: the wait did not run", work.fire())
        assertEquals("ui: first run: firing the wait did not hand over exactly once", 1, calls)
        assertTrue("ui: first run: the timer is not finished after the wait", timer.finished)
    }

    @Test
    fun `a tap before the wait ends calls onDone once and a later fire calls nothing`() {
        val work = HeldWork()
        var calls = 0
        val timer = WelcomeTimer(work) { calls++ }
        timer.start()
        timer.tap()
        assertFalse("ui: first run: the tap did not cancel the wait", work.fire())
        assertEquals("ui: first run: a tap then a fire did not hand over exactly once", 1, calls)
    }

    @Test
    fun `a fire then a tap calls onDone once in total`() {
        val work = HeldWork()
        var calls = 0
        val timer = WelcomeTimer(work) { calls++ }
        timer.start()
        work.fire()
        timer.tap()
        assertEquals("ui: first run: a fire then a tap handed over more than once", 1, calls)
    }

    @Test
    fun `cancel then fire calls nothing`() {
        val work = HeldWork()
        var calls = 0
        val timer = WelcomeTimer(work) { calls++ }
        timer.start()
        timer.cancel()
        assertFalse("ui: first run: the cancel did not end the wait", work.fire())
        assertEquals("ui: first run: a cancelled welcome handed over", 0, calls)
        assertFalse("ui: first run: a cancelled timer is finished", timer.finished)
    }

    @Test
    fun `a wait that was cancelled and then run by hand still calls nothing`() {
        val work = HeldWork()
        var calls = 0
        val timer = WelcomeTimer(work) { calls++ }
        timer.start()
        timer.cancel()
        assertTrue("ui: first run: the cancelled wait was not reachable by hand", work.runCancelled())
        assertEquals("ui: first run: a wait that was ended handed over", 0, calls)
        assertFalse("ui: first run: a timer whose wait was ended is finished", timer.finished)
    }

    @Test
    fun `finished is true after a tap or a fire and false before either`() {
        val work = HeldWork()
        val timer = WelcomeTimer(work) {}
        assertFalse("ui: first run: a new timer is finished", timer.finished)
        timer.start()
        work.fire()
        assertTrue("ui: first run: the timer is not finished after a fire", timer.finished)
        val tapped = WelcomeTimer(HeldWork()) {}
        tapped.start()
        assertFalse("ui: first run: a started timer is finished before a tap", tapped.finished)
        tapped.tap()
        assertTrue("ui: first run: the timer is not finished after a tap", tapped.finished)
    }

    @Test
    fun `start after a cancel schedules again, and a finished timer schedules nothing`() {
        val work = HeldWork()
        val timer = WelcomeTimer(work) {}
        timer.start()
        timer.cancel()
        timer.start()
        assertEquals("ui: first run: a start after a cancel did not schedule again", 2, work.delays.size)
        work.fire()
        timer.start()
        assertEquals("ui: first run: a finished timer scheduled a wait", 2, work.delays.size)
    }
}
