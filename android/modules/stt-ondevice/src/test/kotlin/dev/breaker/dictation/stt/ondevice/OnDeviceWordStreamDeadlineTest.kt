package dev.breaker.dictation.stt.ondevice

import dev.breaker.dictation.core.model.HeardWord
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
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
 * The paths of the on-device word stream that the stop and behaviour test classes do not reach:
 * a stop that waits for a held update before it arms the flush deadline, a start after a stop on
 * the deadline path, a tail drain that never finishes, a stop during the native open, a start from
 * inside the update after a stop from inside it, an older worker that ends after a newer start, and
 * the error rules: an Exception is counted and ends the run quietly, an Error escapes the
 * worker, and a cancellation is rethrown and not counted. Every wait is a signal. The class Timeout
 * rule of 20 seconds is only a safety net; no test reads a clock, and the deadline is an injected
 * stub or recorder.
 *
 * The order check in the first test compares two signals and no clock is allowed to measure the
 * wait, so a stop that armed the deadline too early is caught with high probability, not by
 * construction. A failing run of the drain test is a hang only if the deadline is never fired.
 */
class OnDeviceWordStreamDeadlineTest {

    @get:Rule
    val safetyNet: Timeout = Timeout.seconds(20)

    private val callers = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @After
    fun cancelCallers() {
        callers.cancel()
    }

    /** Records each arm() on [events] and never expires: the test does not fire it. */
    private class RecordingDeadline(private val events: Channel<String>) : DecodeDeadline {
        override fun arm(audioMs: Long, onExpired: () -> Unit): AutoCloseable {
            events.trySend("armed")
            return AutoCloseable { }
        }
    }

    /** A stream over [fake] with a probe slot and a flush deadline that never expires; [handler] takes an uncaught Error. */
    private fun testStream(fake: FakeTimedNative, handler: CoroutineExceptionHandler? = null): OnDeviceWordStream =
        OnDeviceWordStream(
            files = timedFiles(),
            numThreads = 1,
            opener = NativeStreamingOpener { _, threads -> fake.open(threads) },
            dispatcher = ProbeDispatcher(),
            flushDeadline = NEVER_EXPIRES,
            onUncaught = handler,
        )

    @Test
    fun `stop does not arm the flush deadline while a running update is held`() {
        val fake = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}hi"), listOf(0.0f))))
        val events = Channel<String>(Channel.UNLIMITED)
        val inCallback = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        val waiting = CompletableDeferred<Unit>()
        val held = AtomicBoolean(false)
        val stream = timedStream(
            fake,
            flushDeadline = RecordingDeadline(events),
            onStopWaiting = { waiting.complete(Unit) },
        )
        stream.start { update ->
            if (!update.final && held.compareAndSet(false, true)) {
                inCallback.complete(Unit)
                runBlocking { hold.await() }
                events.trySend("callback-end")
            }
        }
        stream.feed(timedChunk())
        runBlocking { inCallback.await() }
        val stopper = callers.launch { stream.stop() }
        runBlocking { waiting.await() }
        assertEquals("nothing is armed while the running update is still held", emptyList<String>(), drainNow(events))
        hold.complete(Unit)
        runBlocking { stopper.join() }
        assertEquals(
            "the callback ended before the flush deadline was armed",
            listOf("callback-end", "armed"),
            drainNow(events),
        )
        assertFalse("the stream is stopped once stop returns", stream.isRunning)
    }

    @Test
    fun `a start after a stop on the deadline path begins a fresh run and an abandoned worker leaves it running`() {
        var fake = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}hi"), listOf(0.0f))))
        val hold = CompletableDeferred<Unit>()
        fake.holdOn("inputFinished", hold)
        val deadline = StubDeadline()
        val stream = OnDeviceWordStream(
            files = timedFiles(),
            numThreads = 1,
            opener = NativeStreamingOpener { _, threads -> fake.open(threads) },
            dispatcher = ProbeDispatcher(),
            flushDeadline = deadline,
        )
        val first = UpdateSink(1)
        stream.start { first.record(it) }
        stream.feed(timedChunk())
        first.awaitExpected()
        val stopper = callers.launch { stream.stop() }
        val expire = runBlocking { deadline.armed.await() }
        expire()
        runBlocking { stopper.join() }
        assertFalse("the deadline stop leaves the stream not running", stream.isRunning)

        // The abandoned worker still holds the slot inside its held input finish. The fresh run queues behind it.
        val abandoned = fake
        fake = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}ok"), listOf(0.0f))))
        val second = UpdateSink(1)
        stream.start { second.record(it) }
        assertTrue("a start after the deadline stop begins a fresh run", stream.isRunning)
        stream.feed(timedChunk())
        hold.complete(Unit)
        runBlocking { abandoned.recognizerReleased.await() }
        // The fresh run's open runs only after the abandoned worker has ended, so the flags it reads are final.
        runBlocking { fake.entered("open").await() }
        assertTrue("the abandoned worker leaves the fresh run running", stream.isRunning)
        second.awaitExpected()
        stream.stop()
        assertEquals("the fresh run delivers its partial and its final update", listOf(false, true), second.updates.map { it.final })
        assertEquals("the fresh run's word", listOf(HeardWord("ok", 0L)), second.updates.first().words)
        assertEquals("the abandoned run delivered only its partial update", listOf(false), first.updates.map { it.final })
    }

    @Test
    fun `a tail drain that never finishes is abandoned at the deadline and the expiry is counted once`() {
        val fake = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}hi"), listOf(0.0f))))
        // The second decode is the tail drain's first decode; the hold stands for a drain that never returns.
        val tailHold = CompletableDeferred<Unit>()
        fake.holdOn("decode", tailHold, nth = 2)
        val deadline = StubDeadline()
        val sink = UpdateSink(1)
        val stream = timedStream(fake, flushDeadline = deadline)
        stream.start { sink.record(it) }
        stream.feed(timedChunk())
        sink.awaitExpected()
        val stopper = callers.launch { stream.stop() }
        val expire = runBlocking { deadline.armed.await() }
        expire()
        runBlocking { stopper.join() }
        assertFalse("stop returned with the stream not running", stream.isRunning)
        assertEquals("the expired tail drain is counted once", 1L, stream.flushTimeouts)
        // Release the held drain so the abandoned worker can finish and release its objects.
        tailHold.complete(Unit)
        runBlocking { fake.recognizerReleased.await() }
        assertEquals("the abandoned drain delivered no final", listOf(false), sink.updates.map { it.final })
        assertTrue("the stream is released after the abandoned drain", fake.streamReleased.isCompleted)
        assertEquals("the drain was the second decode", 2, fake.count("decode"))
        assertEquals("the expiry is still counted once", 1L, stream.flushTimeouts)
    }

    @Test
    fun `a stop during the native open returns at once and the open that returns later delivers nothing`() {
        val fake = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}hi"), listOf(0.0f))))
        val hold = CompletableDeferred<Unit>()
        fake.holdOn("open", hold)
        val deadline = StubDeadline()
        val sink = UpdateSink(0)
        val stream = OnDeviceWordStream(
            files = timedFiles(),
            numThreads = 1,
            opener = NativeStreamingOpener { _, threads -> fake.open(threads) },
            dispatcher = ProbeDispatcher(),
            flushDeadline = deadline,
        )
        stream.start { sink.record(it) }
        runBlocking { fake.entered("open").await() }
        // The open is still held here. stop() must return without waiting for it: the test would hang otherwise.
        stream.stop()
        assertFalse("stop returned with the stream not running", stream.isRunning)
        assertFalse("no flush deadline is armed for an abandoned open", deadline.armed.isCompleted)
        assertEquals("no flush timeout is counted for an abandoned open", 0L, stream.flushTimeouts)
        hold.complete(Unit)
        runBlocking { fake.recognizerReleased.await() }
        assertFalse("the run is still not running after the open returns", stream.isRunning)
        assertTrue("no update is delivered for an abandoned open", sink.updates.isEmpty())
        assertEquals("no flush timeout after the open returns", 0L, stream.flushTimeouts)
    }

    @Test
    fun `an abandoned open never makes a stream, feeds audio or delivers a final`() {
        val fake = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}hi"), listOf(0.0f))))
        val hold = CompletableDeferred<Unit>()
        fake.holdOn("open", hold)
        val sink = UpdateSink(0)
        val stream = testStream(fake)
        stream.start { sink.record(it) }
        // Queued before stop: it must not reach the stream either.
        stream.feed(timedChunk())
        runBlocking { fake.entered("open").await() }
        stream.stop()
        hold.complete(Unit)
        runBlocking { fake.recognizerReleased.await() }
        assertEquals("after the open returns, only the recogniser is released", listOf("open", "release"), fake.names())
        assertEquals("no audio reaches the engine", 0, fake.count("acceptWaveform"))
        assertTrue("no update and no final is delivered", sink.updates.isEmpty())
    }

    @Test
    fun `start from inside the update after a stop from inside it queues a fresh run behind the abandoned worker`() {
        val first = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}hi"), listOf(0.0f))))
        val second = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}ok"), listOf(0.0f))))
        val opens = AtomicInteger(0)
        val probe = ProbeDispatcher()
        val firstSink = UpdateSink(1)
        val secondSink = UpdateSink(1)
        val restarted = AtomicBoolean(false)
        val stream = OnDeviceWordStream(
            files = timedFiles(),
            numThreads = 1,
            opener = NativeStreamingOpener { _, threads ->
                if (opens.incrementAndGet() == 1) first.open(threads) else second.open(threads)
            },
            dispatcher = probe,
            flushDeadline = NEVER_EXPIRES,
        )
        stream.start { update ->
            if (!update.final && restarted.compareAndSet(false, true)) {
                stream.stop()                              // from inside the update: abandons this run at once
                stream.start { secondSink.record(it) }     // a fresh run, queued behind the abandoned worker
            }
            // Recorded last: the test may not feed until the fresh run is in place.
            firstSink.record(update)
        }
        stream.feed(timedChunk())
        firstSink.awaitExpected()
        stream.feed(timedChunk())                          // goes to the fresh run
        secondSink.awaitExpected()                         // one slot: this update comes after the old worker ended
        assertTrue("the old worker released both objects before the fresh run's update", first.recognizerReleased.isCompleted)
        assertTrue("the old worker released its stream before the fresh run's update", first.streamReleased.isCompleted)
        assertTrue("the fresh run is running", stream.isRunning)
        stream.stop()
        assertEquals("the old run delivered only its partial update", listOf(false), firstSink.updates.map { it.final })
        assertEquals("the old run never reached its input finish", 0, first.count("inputFinished"))
        assertEquals("the fresh run delivers its partial and its final update", listOf(false, true), secondSink.updates.map { it.final })
        assertEquals("the fresh run's word", listOf(HeardWord("ok", 0L)), secondSink.updates.first().words)
    }

    @Test
    fun `a worker of an older run that fails after a new start counts nothing and writes nothing into the new run`() {
        val first = FakeTimedNative(listOf(TIMED_NO_WORDS))
        val second = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}ok"), listOf(0.0f))))
        val hold = CompletableDeferred<Unit>()
        first.holdOn("open", hold)
        first.throwOn("open", RuntimeException("scripted failure after the open was abandoned"))
        val opens = AtomicInteger(0)
        val stream = OnDeviceWordStream(
            files = timedFiles(),
            numThreads = 1,
            opener = NativeStreamingOpener { _, threads ->
                if (opens.incrementAndGet() == 1) first.open(threads) else second.open(threads)
            },
            dispatcher = ProbeDispatcher(),
            flushDeadline = NEVER_EXPIRES,
        )
        val oldSink = UpdateSink(0)
        val newSink = UpdateSink(1)
        stream.start { oldSink.record(it) }
        runBlocking { first.entered("open").await() }
        stream.stop()                                      // abandons the old run while its open is held
        stream.start { newSink.record(it) }                // a new run: its counters start at zero
        stream.feed(timedChunk())
        hold.complete(Unit)                                // the old open now throws; the new run waits behind it
        newSink.awaitExpected()                            // the old worker has ended by now: one slot
        assertEquals("the old worker counted no failure into the new run", 0L, stream.failures)
        assertEquals("the old worker counted no flush timeout into the new run", 0L, stream.flushTimeouts)
        assertTrue("the new run is still running", stream.isRunning)
        assertTrue("the old run delivered no update", oldSink.updates.isEmpty())
        stream.stop()
        assertEquals("the new run delivers its partial and its final update", listOf(false, true), newSink.updates.map { it.final })
    }

    @Test
    fun `a CancellationException from a native call is rethrown and is not counted as a failure`() {
        val fake = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}hi"), listOf(0.0f))))
        fake.throwOn("acceptWaveform", CancellationException("scripted cancellation in acceptWaveform"))
        val sink = UpdateSink(0)
        val stream = testStream(fake)
        stream.start { sink.record(it) }
        stream.feed(timedChunk())
        runBlocking { fake.recognizerReleased.await() }
        stream.stop()
        assertEquals("a cancellation is not counted as a failure", 0L, stream.failures)
        assertTrue("the stream is released after the cancellation", fake.streamReleased.isCompleted)
        assertFalse("the stream is not running after the cancellation", stream.isRunning)
        assertTrue("no update is delivered after the cancellation", sink.updates.isEmpty())
    }

    @Test
    fun `an AssertionError from a native call escapes the worker and releases both objects`() {
        val fake = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}hi"), listOf(0.0f))))
        fake.throwOn("acceptWaveform", AssertionError("scripted assertion in acceptWaveform"))
        val sink = UpdateSink(0)
        val uncaught = CompletableDeferred<Throwable>()
        val stream = testStream(fake, CoroutineExceptionHandler { _, error -> uncaught.complete(error) })
        stream.start { sink.record(it) }
        stream.feed(timedChunk())
        // The handler runs only after the worker has ended, so the releases below are already done.
        val error = runBlocking { uncaught.await() }
        assertTrue("the AssertionError reached the uncaught handler", error is AssertionError)
        assertEquals("it is the scripted error", "scripted assertion in acceptWaveform", error.message)
        assertTrue("the stream is released before the error leaves the worker", fake.streamReleased.isCompleted)
        assertTrue("the recogniser is released before the error leaves the worker", fake.recognizerReleased.isCompleted)
        assertFalse("the run's flag is down after the error", stream.isRunning)
        assertTrue("no update is delivered after the error", sink.updates.isEmpty())
        assertEquals("an Error is not counted as a failure", 0L, stream.failures)
    }

    @Test
    fun `an AssertionError from the update callback escapes the worker and no final is delivered`() {
        val fake = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}hi"), listOf(0.0f))))
        val sink = UpdateSink(0)
        val uncaught = CompletableDeferred<Throwable>()
        val stream = testStream(fake, CoroutineExceptionHandler { _, error -> uncaught.complete(error) })
        stream.start { update ->
            sink.record(update)
            throw AssertionError("scripted assertion in the callback")
        }
        stream.feed(timedChunk())
        val error = runBlocking { uncaught.await() }
        assertTrue("the AssertionError reached the uncaught handler", error is AssertionError)
        assertEquals("the partial update that threw is the only update", listOf(false), sink.updates.map { it.final })
        assertTrue("the stream is released after the callback threw", fake.streamReleased.isCompleted)
        assertTrue("the recogniser is released after the callback threw", fake.recognizerReleased.isCompleted)
        assertFalse("the run's flag is down after the error", stream.isRunning)
        assertEquals("an Error is not counted as a failure", 0L, stream.failures)
    }
}
