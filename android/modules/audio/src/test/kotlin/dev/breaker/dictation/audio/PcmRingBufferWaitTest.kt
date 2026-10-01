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
        // The lower bound is half the injected window, which a wait of about
        // 300ms clears comfortably and an immediate return cannot reach; it is
        // asserted even though it sits next to the upper bound, because the
        // upper bound alone would pass just as happily against a read that
        // never waited at all.
        val readWaitMs = 300L
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
            elapsedMs <= readWaitMs + 100,
        )
    }

    @Test
    fun `a write wakes a blocked reader instead of leaving it to poll`() {
        // The wait below is 30 seconds, far longer than this test's join, so
        // the reader can only be finished by a wake-up from the write itself.
        // If a write fails to wake its reader, this test fails; a reader that
        // is merely rescued by a short poll timeout proves nothing about the
        // wake-up it was actually waiting for.
        val buffer = PcmRingBuffer(capacitySamples = 100, readWaitMs = 30_000L)
        val out = FloatArray(4)
        var read = -1
        val reader = Thread {
            read = buffer.read(out, block = true)
        }
        reader.start()
        // Wait for the reader to actually be PARKED in the wait, rather than
        // assuming a sleep was long enough. A fixed sleep is a race: if the
        // reader has not reached wait() when the write lands, the write
        // satisfies a reader that was never blocked, and the test passes
        // without exercising the wake-up at all. Worse under load, the sleep
        // can expire before the thread has run and the whole test falls
        // through to the 30 s read timeout as a false red.
        val parkedBy = System.currentTimeMillis() + 2_000
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
        reader.join(2_000)

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
        // against. The injected wait is short so the bound is observable, and
        // the generous join means the reader has to end its own wait: it is not
        // rescued by this test giving up on it.
        val readWaitMs = 300L
        val buffer = PcmRingBuffer(capacitySamples = 100, readWaitMs = readWaitMs)
        var read = -1
        val reader = Thread {
            read = buffer.read(FloatArray(4), block = true)
        }
        val began = System.nanoTime()
        reader.start()
        reader.join(5_000)
        val elapsedMs = (System.nanoTime() - began) / 1_000_000

        assertTrue(
            "a blocking read waited ${elapsedMs}ms on a buffer nobody writes to",
            !reader.isAlive,
        )
        assertEquals("a bounded wait on an empty buffer can only return what it found", 0, read)
        assertTrue(
            "the read should return once its wait is over, not much later; took ${elapsedMs}ms",
            elapsedMs < readWaitMs + 2_000,
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
