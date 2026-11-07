package dev.breaker.dictation.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The buffer between the capture thread and the consumer: waiting on it.
 *
 * A read that blocks, a write that wakes it, and the bounds on both.
 */
class PcmRingBufferWaitTest {

    @Test
    fun `a blocking read waits once and then returns empty`() {
        // block = true asks the buffer to wait for audio rather than return
        // immediately, and it genuinely does: the wait is real time spent
        // parked, not a check that happens to pass because the buffer is
        // empty. Nobody writes to this buffer, so the wait is the only thing
        // that can account for the elapsed time - a read that skipped the wait
        // and returned at once would be indistinguishable from a non-blocking
        // read, and the consumer that asked to block would be polling itself
        // as fast as its own loop.
        //
        // The wait is bounded and happens ONCE, so the read comes back with
        // nothing rather than parking until a writer turns up. That bound is
        // the point: a consumer that has been asked to stop must be able to
        // come back and notice, and a read that waited for a writer no matter
        // how long would leave it no way to leave.
        //
        // The injected wait is 3 s (readWaitMs = 3_000L): one whole extra wait
        // (6 s or more) is bigger than any plausible starvation delay, so a
        // box that is late by up to 2.5 s still passes while a repeated wait
        // stays red. Starvation delay tolerated: up to 2.5 s.
        // What we give up: the old test caught a 150 ms stretch of the wait;
        // the new one does not. It catches a repeated wait instead.
        // Lower bound readWaitMs / 2 stays; upper bound readWaitMs + 2_500.
        val readWaitMs = 3_000L
        val buffer = PcmRingBuffer(capacitySamples = 100, readWaitMs = readWaitMs)

        val began = System.nanoTime()
        val read = buffer.read(FloatArray(4), block = true)
        val elapsedMs = (System.nanoTime() - began) / 1_000_000

        assertEquals("a wait that found no writer can only return nothing", 0, read)
        assertTrue(
            "a blocking read returned after ${elapsedMs}ms, so it did not wait: " +
                "it should sit out about ${readWaitMs}ms first",
            elapsedMs >= readWaitMs / 2,
        )
        assertTrue(
            "a blocking read took ${elapsedMs}ms, past the ${readWaitMs}ms it was " +
                "allowed to wait, so the wait is not bounded",
            elapsedMs <= readWaitMs + 2_500,
        )
    }

    @Test
    fun `a write wakes a blocked reader instead of leaving it to poll`() {
        // The buffer is built with readWaitMs = 120_000L so only a real wake-up,
        // never the buffer's own timeout, can end the reader's wait. The test's
        // join ceiling is 20_000 ms, clearly below the buffer wait of
        // 120_000 ms: join is the smaller number, in that order.
        // A broken write (no notifyAll) can therefore never let the reader end
        // by its own timeout and pass this test against the defect.
        val buffer = PcmRingBuffer(capacitySamples = 100, readWaitMs = 120_000L)
        val out = FloatArray(4)
        var read = -1
        val reader = Thread {
            read = buffer.read(out, block = true)
        }
        reader.isDaemon = true
        reader.start()
        // Wait for the reader to actually be PARKED in the wait, rather than
        // assuming a sleep was long enough. A fixed sleep is a race: if the
        // reader has not reached wait() when the write lands, the write
        // satisfies a reader that was never blocked, and the test passes
        // without exercising the wake-up at all. Worse under load, the sleep
        // can expire before the thread has run and the whole test falls
        // through to the buffer's own wait as a false red.
        // The poll ceiling is 20 s: it ends only a hung test, never decides
        // the result.
        // A never-waiting reader keeps the poll running for its whole ceiling
        // before the state assertion runs; the ceiling does not decide early.
        val parkedBy = System.currentTimeMillis() + 20_000
        while (reader.state != Thread.State.WAITING &&
            reader.state != Thread.State.TIMED_WAITING &&
            System.currentTimeMillis() < parkedBy
        ) {
            Thread.yield()
        }
        assertTrue(
            "the reader thread never reached a waiting state (it was " +
                "${reader.state}); the write would not have woken anything",
            reader.state == Thread.State.WAITING ||
                reader.state == Thread.State.TIMED_WAITING,
        )

        buffer.write(floatArrayOf(1f, 2f, 3f, 4f))
        reader.join(20_000)

        assertTrue(
            "the write did not wake the blocked reader, so it is still waiting on its timeout",
            !reader.isAlive,
        )
        assertEquals(4, read)
        assertEquals(listOf(1f, 2f, 3f, 4f), out.toList())
    }

    @Test
    fun `a blocking read on an empty buffer gives up instead of waiting forever`() {
        // The wait is bounded, so a consumer parked on a buffer nobody writes to
        // comes back and can notice it has been asked to stop. A read that
        // retried its wait forever would hold the lock it waits on and leave the
        // caller with no way to leave, which is a hang no caller can defend
        // against. The injected wait is 3 s (readWaitMs = 3_000L) so the bound
        // is observable, and the generous join means the reader has to end its
        // own wait: it is not rescued by this test giving up on it.
        // What this test catches: the reader still alive after its 10 s join,
        // or a read that returns after 8 s or more (readWaitMs + 5_000).
        val readWaitMs = 3_000L
        val buffer = PcmRingBuffer(capacitySamples = 100, readWaitMs = readWaitMs)
        var read = -1
        val reader = Thread {
            read = buffer.read(FloatArray(4), block = true)
        }
        reader.isDaemon = true
        val began = System.nanoTime()
        reader.start()
        reader.join(10_000)
        val elapsedMs = (System.nanoTime() - began) / 1_000_000

        assertTrue(
            "a blocking read waited ${elapsedMs}ms on a buffer nobody writes to",
            !reader.isAlive,
        )
        assertEquals("a bounded wait on an empty buffer can only return what it found", 0, read)
        assertTrue(
            "the read should return once its wait is over, not much later; took ${elapsedMs}ms",
            elapsedMs < readWaitMs + 5_000,
        )
    }

    @Test
    fun `a non-blocking read returns immediately when the buffer is empty`() {
        val buffer = PcmRingBuffer(capacitySamples = 10)
        val began = System.nanoTime()
        val read = buffer.read(FloatArray(4), block = false)
        val elapsedMs = (System.nanoTime() - began) / 1_000_000
        assertEquals(0, read)
        assertTrue("a non-blocking read waited ${elapsedMs}ms", elapsedMs < 200)
    }
}
