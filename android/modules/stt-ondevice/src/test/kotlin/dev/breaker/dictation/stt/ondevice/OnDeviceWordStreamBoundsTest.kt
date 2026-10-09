package dev.breaker.dictation.stt.ondevice

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * Pins the numbers the on-device word stream takes from its constants and defaults: the decode step
 * bound, the default queue capacity, the length the tail flush deadline is armed with, the sample rate
 * of a fed block, the rule for an Error from a release, and the order of stop against a held update,
 * a held final and a held tail. Every wait is a signal. The class Timeout rule is only a safety net,
 * and no test reads a clock.
 *
 * Two tests order a stop against a held callback. Their failing side depends on a short window in the
 * code, so they catch a wrong order with high probability and not by construction.
 */
class OnDeviceWordStreamBoundsTest {

    @get:Rule
    val safetyNet: Timeout = Timeout.seconds(20)

    private val callers = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @After
    fun cancelCallers() {
        callers.cancel()
    }

    /** Records the audio length of each arm(). With [expireAtOnce] set, it also ends the deadline at once, inside arm(). */
    private class LengthRecorder(private val expireAtOnce: Boolean) : DecodeDeadline {
        val lengths = CopyOnWriteArrayList<Long>()

        override fun arm(audioMs: Long, onExpired: () -> Unit): AutoCloseable {
            lengths.add(audioMs)
            if (expireAtOnce) onExpired()
            return AutoCloseable { }
        }
    }

    /** Runs a block of [samples] through a recogniser that is never ready, and returns how many decode steps ran. */
    private fun decodeStepsFor(samples: Int): Int {
        val fake = FakeTimedNative(listOf(TIMED_NO_WORDS), neverReady = true)
        val stream = timedStream(fake)
        stream.start { }
        stream.feed(timedChunk(samples))
        runBlocking { fake.recognizerReleased.await() }
        stream.stop()
        assertEquals("the step bound ends the run as one native failure", 1L, stream.failures)
        assertFalse("the step bound, not the poll guard, ended the decode loop", fake.pollOverflow.get())
        return fake.count("decode")
    }

    @Test
    fun `a decode loop allows the frames of the block plus 16 steps and no more`() {
        assertEquals("a 1600 sample block allows 10 frames and 16 steps: 26 decode steps", 26, decodeStepsFor(1600))
        assertEquals("a 320 sample block allows 2 frames and 16 steps: 18 decode steps", 18, decodeStepsFor(320))
    }

    @Test
    fun `the default queue holds 64 chunks while the worker is held and drops the 65th`() {
        val fake = FakeTimedNative(listOf(TIMED_NO_WORDS))
        val hold = CompletableDeferred<Unit>()
        fake.holdOn("open", hold)
        val stream = OnDeviceWordStream(
            files = timedFiles(),
            numThreads = 1,
            opener = NativeStreamingOpener { _, threads -> fake.open(threads) },
            dispatcher = ProbeDispatcher(),
            flushDeadline = NEVER_EXPIRES,
        )
        stream.start { }
        runBlocking { fake.entered("open").await() }
        try {
            repeat(64) { stream.feed(timedChunk()) }
            assertEquals("the first 64 chunks are queued with the default capacity", 0L, stream.droppedChunks)
            stream.feed(timedChunk())
            assertEquals("the 65th chunk is the first one dropped", 1L, stream.droppedChunks)
            assertEquals("the dropped samples are the 160 of the 65th chunk", 160L, stream.droppedSamples)
        } finally {
            stream.stop()
            hold.complete(Unit)
        }
        runBlocking { fake.recognizerReleased.await() }
    }

    @Test
    fun `the tail flush deadline is armed with the 660 ms tail padding`() {
        val fake = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}hi"), listOf(0.0f))))
        val hold = CompletableDeferred<Unit>()
        fake.holdOn("inputFinished", hold)
        val recorder = LengthRecorder(expireAtOnce = true)
        val sink = UpdateSink(1)
        val stream = timedStream(fake, flushDeadline = recorder)
        stream.start { sink.record(it) }
        stream.feed(timedChunk())
        sink.awaitExpected()
        try {
            stream.stop()
            assertEquals("the flush deadline is armed once, with 660 ms", listOf(660L), recorder.lengths.toList())
        } finally {
            hold.complete(Unit)
        }
        runBlocking { fake.recognizerReleased.await() }
    }

    @Test
    fun `each block is fed at 16000 Hz including the tail silence`() {
        val fake = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}hi"), listOf(0.0f))))
        val sink = UpdateSink(1)
        val stream = timedStream(fake)
        stream.start { sink.record(it) }
        stream.feed(timedChunk())
        sink.awaitExpected()
        stream.stop()
        assertEquals(
            "the clip and the tail silence are both fed at 16000 Hz",
            listOf("160 @16000", "10560 @16000"),
            fake.calls.filter { it.name == "acceptWaveform" }.map { it.detail },
        )
    }

    /**
     * An Error from a stream release is not caught: the recogniser is still released, because the release
     * runs in a finally after the failing one, and the Error then reaches the uncaught handler. No failure is
     * counted. Turns RED if the release helper catches Throwable again (the handler is then never called), or
     * if the finally around the two releases is dropped (the recogniser is then not released).
     */
    @Test
    fun `an Error from a stream release still releases the recogniser, then propagates`() {
        val fake = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}hi"), listOf(0.0f))))
        fake.throwOn("streamRelease", AssertionError("scripted assertion in streamRelease"))
        val probe = ProbeDispatcher()
        val uncaught = CompletableDeferred<Throwable>()
        val sink = UpdateSink(1)
        val stream = OnDeviceWordStream(
            files = timedFiles(),
            numThreads = 1,
            opener = NativeStreamingOpener { _, threads -> fake.open(threads) },
            dispatcher = probe,
            flushDeadline = NEVER_EXPIRES,
            onUncaught = CoroutineExceptionHandler { _, error -> uncaught.complete(error) },
        )
        stream.start { sink.record(it) }
        stream.feed(timedChunk())
        sink.awaitExpected()
        stream.stop()
        awaitSlotIdle(probe)
        assertTrue("the Error from the stream release reaches the uncaught handler", uncaught.isCompleted)
        assertTrue("it is the scripted assertion", uncaught.getCompleted() is AssertionError)
        assertTrue("the stream release was attempted", fake.streamReleased.isCompleted)
        assertTrue("the recogniser is released even though the stream release threw", fake.recognizerReleased.isCompleted)
        assertEquals("an Error from a release is not counted as a failure", 0L, stream.failures)
        assertFalse("the stream is not running after the stop", stream.isRunning)
    }

    /**
     * An Exception from a stream release is counted once in failures and swallowed: the recogniser is still
     * released, nothing reaches the uncaught handler, and the final is delivered as usual. Turns RED if the
     * release helper stops counting (the count is then 0), or if it stops catching Exceptions (the Exception
     * then escapes the worker: the recogniser is not released and the handler is called).
     */
    @Test
    fun `an Exception from a release is counted and the recogniser is still released`() {
        val fake = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}hi"), listOf(0.0f))))
        fake.throwOn("streamRelease", IllegalStateException("scripted failure in streamRelease"))
        val probe = ProbeDispatcher()
        val uncaught = CompletableDeferred<Throwable>()
        val sink = UpdateSink(1)
        val stream = OnDeviceWordStream(
            files = timedFiles(),
            numThreads = 1,
            opener = NativeStreamingOpener { _, threads -> fake.open(threads) },
            dispatcher = probe,
            flushDeadline = NEVER_EXPIRES,
            onUncaught = CoroutineExceptionHandler { _, error -> uncaught.complete(error) },
        )
        stream.start { sink.record(it) }
        stream.feed(timedChunk())
        sink.awaitExpected()
        stream.stop()
        awaitSlotIdle(probe)
        assertEquals("the Exception from the stream release is counted once", 1L, stream.failures)
        assertTrue("the stream release was attempted", fake.streamReleased.isCompleted)
        assertTrue("the recogniser is released even though the stream release threw", fake.recognizerReleased.isCompleted)
        assertFalse("no Error reaches the uncaught handler for an Exception", uncaught.isCompleted)
        assertEquals("the final is delivered once, after the partial", listOf(false, true), sink.updates.map { it.final })
        assertFalse("the stream is not running after the stop", stream.isRunning)
    }

    @Test
    fun `a flush timer that expires while the final is held counts no timeout and the final comes once`() {
        val fake = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}hi"), listOf(0.0f))))
        val deadline = StubDeadline()
        val finalHeld = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        val sink = UpdateSink(1)
        val stream = timedStream(fake, flushDeadline = deadline)
        stream.start { update ->
            sink.record(update)
            if (update.final) {
                finalHeld.complete(Unit)
                runBlocking { hold.await() }
            }
        }
        stream.feed(timedChunk())
        sink.awaitExpected()
        val stopper = callers.launch { stream.stop() }
        val expire = runBlocking { deadline.armed.await() }
        runBlocking { finalHeld.await() }
        try {
            expire()
            assertEquals("the timer that expires during the final counts no timeout", 0L, stream.flushTimeouts)
        } finally {
            hold.complete(Unit)
        }
        runBlocking { stopper.join() }
        assertEquals("exactly one final arrived after the partial", listOf(false, true), sink.updates.map { it.final })
    }

    @Test
    fun `the flush deadline is armed only after a held running update has returned`() {
        val fake = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}hi"), listOf(0.0f))))
        val inCallback = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        val callbackEnded = AtomicBoolean(false)
        val endedAtArm = CompletableDeferred<Boolean>()
        val deadline = DecodeDeadline { _, _ ->
            endedAtArm.complete(callbackEnded.get())
            AutoCloseable { }
        }
        val stream = timedStream(fake, flushDeadline = deadline, onStopWaiting = { hold.complete(Unit) })
        stream.start { update ->
            if (!update.final && !inCallback.isCompleted) {
                inCallback.complete(Unit)
                runBlocking { hold.await() }
                callbackEnded.set(true)
            }
        }
        stream.feed(timedChunk())
        runBlocking { inCallback.await() }
        val stopper = callers.launch { stream.stop() }
        runBlocking { stopper.join() }
        assertTrue("the deadline was armed after the held update had returned", runBlocking { endedAtArm.await() })
        assertFalse("the stream is stopped once stop returns", stream.isRunning)
    }

    @Test
    fun `stop returns only after a held tail has delivered its final`() {
        val fake = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}hi"), listOf(0.0f))))
        val tailHold = CompletableDeferred<Unit>()
        fake.holdOn("inputFinished", tailHold)
        val deadline = StubDeadline()
        val events = CopyOnWriteArrayList<String>()
        val sink = UpdateSink(1)
        val stream = timedStream(fake, flushDeadline = deadline)
        stream.start { update ->
            sink.record(update)
            if (update.final) events.add("final")
        }
        stream.feed(timedChunk())
        sink.awaitExpected()
        val stopper = callers.launch {
            stream.stop()
            events.add("stop-end")
        }
        try {
            runBlocking { fake.entered("inputFinished").await() }
            runBlocking { deadline.armed.await() }
        } finally {
            tailHold.complete(Unit)
        }
        runBlocking { stopper.join() }
        assertEquals("the final is delivered before stop returns", listOf("final", "stop-end"), events.toList())
    }
}
