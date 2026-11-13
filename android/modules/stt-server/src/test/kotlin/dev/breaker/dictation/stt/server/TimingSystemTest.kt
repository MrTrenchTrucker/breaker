package dev.breaker.dictation.stt.server

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The real timing of Timing.SYSTEM: the clock moves forward, and the two waits ask for exactly the
 * milliseconds they were given. Neither test waits on a timer: the first reads the clock again until
 * its value changes, the second runs on a dispatcher that records each requested delay and returns
 * at once.
 */
@OptIn(InternalCoroutinesApi::class)
class TimingSystemTest {

    private class RecordingDispatcher : CoroutineDispatcher(), Delay {
        val requested = mutableListOf<Long>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            block.run()
        }

        override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
            requested.add(timeMillis)
            continuation.resume(Unit)
        }
    }

    @Test
    fun `the real monotonic clock moves forward`() {
        val clock = Timing.SYSTEM.clock
        val before = clock.nowNanos()
        var after = before
        var reads = 0
        while (after == before && reads < 10_000_000) {
            after = clock.nowNanos()
            reads++
        }
        assertTrue("the clock did not move in $reads reads: $before then $after", after > before)
    }

    @Test
    fun `the real poll sleep and deadline wait ask for exactly the milliseconds given`() {
        val dispatcher = RecordingDispatcher()
        runBlocking(dispatcher) {
            Timing.SYSTEM.sleeper.sleep(250L)
            Timing.SYSTEM.deadline.await(4_000L)
        }
        assertEquals("the delays the real timing asked for", listOf(250L, 4_000L), dispatcher.requested)
    }
}
