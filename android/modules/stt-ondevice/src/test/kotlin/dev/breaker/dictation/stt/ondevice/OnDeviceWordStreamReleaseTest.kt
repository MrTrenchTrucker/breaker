package dev.breaker.dictation.stt.ondevice

import dev.breaker.dictation.core.model.HeardWord
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
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
 * The releases of the on-device word stream. An Exception from a release is counted once and swallowed,
 * a cancellation from a release is rethrown and not counted, and a release that is still held after stop
 * returned counts its failure on the run that owns it. Every wait is a signal. The class Timeout rule of
 * 20 seconds is a safety net only, and no test reads a timer.
 */
class OnDeviceWordStreamReleaseTest {

    @get:Rule
    val safetyNet: Timeout = Timeout.seconds(20)

    private val callers = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @After
    fun cancelCallers() {
        callers.cancel()
    }

    /** A word stream over [opener] on [probe], with a flush deadline that never expires and [onUncaught] for an Error. */
    private fun releaseStream(
        opener: NativeStreamingOpener,
        probe: ProbeDispatcher,
        onUncaught: (Throwable) -> Unit = {},
    ): OnDeviceWordStream = OnDeviceWordStream(
        files = timedFiles(),
        numThreads = 1,
        opener = opener,
        dispatcher = probe,
        flushDeadline = NEVER_EXPIRES,
        onUncaught = CoroutineExceptionHandler { _, error -> onUncaught(error) },
    )

    /**
     * A checked Exception from the stream release is counted once and swallowed: the recogniser is still
     * released, nothing reaches the uncaught handler, and the final is delivered as usual. Turns RED if the
     * catch in the release helper is narrowed to RuntimeException: the IOException then escapes the worker,
     * the handler is called and the count stays at 0.
     */
    @Test
    fun `an IOException from the stream release is counted once and swallowed`() {
        val fake = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}hi"), listOf(0.0f))))
        fake.throwOn("streamRelease", IOException("scripted checked failure in streamRelease"))
        val probe = ProbeDispatcher()
        val uncaught = CompletableDeferred<Throwable>()
        val sink = UpdateSink(1)
        val stream = releaseStream(NativeStreamingOpener { _, threads -> fake.open(threads) }, probe) { uncaught.complete(it) }
        stream.start { sink.record(it) }
        stream.feed(timedChunk())
        sink.awaitExpected()
        stream.stop()
        awaitSlotIdle(probe)
        assertEquals("the IOException from the stream release is counted once", 1L, stream.failures)
        assertTrue("the stream release was attempted", fake.streamReleased.isCompleted)
        assertTrue("the recogniser is released even though the stream release threw", fake.recognizerReleased.isCompleted)
        assertFalse("no exception reaches the uncaught handler for a checked Exception", uncaught.isCompleted)
        assertEquals("the final is delivered as usual, after the partial", listOf(false, true), sink.updates.map { it.final })
        assertFalse("the stream is not running after the stop", stream.isRunning)
    }

    /**
     * A checked Exception from the recogniser release is counted once and swallowed, and nothing reaches the
     * uncaught handler. Turns RED if the catch in the release helper is narrowed to RuntimeException: the
     * IOException then escapes the worker, the handler is called and the count stays at 0.
     */
    @Test
    fun `an IOException from the recogniser release is counted once and swallowed`() {
        val fake = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}hi"), listOf(0.0f))))
        fake.throwOn("release", IOException("scripted checked failure in release"))
        val probe = ProbeDispatcher()
        val uncaught = CompletableDeferred<Throwable>()
        val sink = UpdateSink(1)
        val stream = releaseStream(NativeStreamingOpener { _, threads -> fake.open(threads) }, probe) { uncaught.complete(it) }
        stream.start { sink.record(it) }
        stream.feed(timedChunk())
        sink.awaitExpected()
        stream.stop()
        awaitSlotIdle(probe)
        assertEquals("the IOException from the recogniser release is counted once", 1L, stream.failures)
        assertTrue("the stream release was attempted first", fake.streamReleased.isCompleted)
        assertTrue("the recogniser release was attempted", fake.recognizerReleased.isCompleted)
        assertFalse("no exception reaches the uncaught handler for a checked Exception", uncaught.isCompleted)
        assertEquals("the final is delivered as usual, after the partial", listOf(false, true), sink.updates.map { it.final })
    }

    /**
     * A CancellationException from the stream release is rethrown, so the worker job ends cancelled, and it
     * is not counted. Turns RED if the rethrow is replaced by a swallow: the job then completes normally and
     * the first job assertion fails. Turns RED if it is replaced by a count: the failure count is then 1.
     */
    @Test
    fun `a CancellationException from the stream release ends the worker job as cancelled and is not counted`() {
        val fake = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}hi"), listOf(0.0f))))
        fake.throwOn("streamRelease", CancellationException("scripted cancellation in streamRelease"))
        val probe = ProbeDispatcher()
        val sink = UpdateSink(1)
        val stream = releaseStream(NativeStreamingOpener { _, threads -> fake.open(threads) }, probe)
        stream.start { sink.record(it) }
        val job = checkNotNull(stream.currentJob)
        stream.feed(timedChunk())
        sink.awaitExpected()
        stream.stop()
        awaitSlotIdle(probe)
        assertTrue("the worker job ends cancelled", job.isCancelled)
        assertEquals("a cancellation from the stream release is not counted", 0L, stream.failures)
        assertTrue("the recogniser is released after the cancellation", fake.recognizerReleased.isCompleted)
        assertEquals("the final is delivered before the release ran", listOf(false, true), sink.updates.map { it.final })
    }

    /**
     * A CancellationException from the recogniser release is rethrown, so the worker job ends cancelled, and it
     * is not counted. Turns RED if the rethrow is replaced by a swallow: the job then completes normally. Turns
     * RED if it is replaced by a count: the failure count is then 1.
     */
    @Test
    fun `a CancellationException from the recogniser release ends the worker job as cancelled and is not counted`() {
        val fake = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}hi"), listOf(0.0f))))
        fake.throwOn("release", CancellationException("scripted cancellation in release"))
        val probe = ProbeDispatcher()
        val sink = UpdateSink(1)
        val stream = releaseStream(NativeStreamingOpener { _, threads -> fake.open(threads) }, probe)
        stream.start { sink.record(it) }
        val job = checkNotNull(stream.currentJob)
        stream.feed(timedChunk())
        sink.awaitExpected()
        stream.stop()
        awaitSlotIdle(probe)
        assertTrue("the worker job ends cancelled", job.isCancelled)
        assertEquals("a cancellation from the recogniser release is not counted", 0L, stream.failures)
        assertTrue("the stream was released before the cancellation", fake.streamReleased.isCompleted)
        assertEquals("the final is delivered before the release ran", listOf(false, true), sink.updates.map { it.final })
    }

    /**
     * An older run whose stream release is still held after stop returned. A newer run starts in the meantime,
     * and then the old release throws. The Exception belongs to the old run, so the newer run counts no failure.
     * Turns RED if the release helper counts on the newest run instead of its own run: the newer run then reads 1.
     */
    @Test
    fun `a release still held after stop counts its failure on its own run and not on the newer run`() {
        val fakeOld = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}hi"), listOf(0.0f))))
        val tailHold = CompletableDeferred<Unit>()
        val releaseHold = CompletableDeferred<Unit>()
        fakeOld.holdOn("inputFinished", tailHold)
        fakeOld.holdOn("streamRelease", releaseHold)
        fakeOld.throwOn("streamRelease", IOException("scripted checked failure in the old run's release"))
        val fakeNew = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}ok"), listOf(0.0f))))
        val opens = AtomicInteger(0)
        val probe = ProbeDispatcher()
        val deadline = StubDeadline()
        val oldSink = UpdateSink(1)
        val newSink = UpdateSink(1)
        val stream = OnDeviceWordStream(
            files = timedFiles(),
            numThreads = 1,
            opener = NativeStreamingOpener { _, threads ->
                if (opens.incrementAndGet() == 1) fakeOld.open(threads) else fakeNew.open(threads)
            },
            dispatcher = probe,
            flushDeadline = deadline,
        )

        stream.start { oldSink.record(it) }
        stream.feed(timedChunk())
        oldSink.awaitExpected()
        // The old worker is held inside the tail, before its final, so the flush deadline is the one that ends the wait.
        val stopper = callers.launch { stream.stop() }
        val expire = runBlocking { deadline.armed.await() }
        expire()
        runBlocking { stopper.join() }
        assertFalse("stop returned with the old run no longer running", stream.isRunning)

        // The old worker still holds the slot. The newer run is claimed now and queues behind it.
        stream.start { newSink.record(it) }
        stream.feed(timedChunk())
        assertTrue("the newer run is running", stream.isRunning)
        tailHold.complete(Unit)
        runBlocking { fakeOld.entered("streamRelease").await() }
        assertFalse("the old stream release is still held", fakeOld.streamReleased.isCompleted)

        releaseHold.complete(Unit)
        runBlocking { fakeOld.recognizerReleased.await() }
        newSink.awaitExpected()
        stream.stop()
        awaitSlotIdle(probe)
        assertTrue("the old stream release ran to its end", fakeOld.streamReleased.isCompleted)
        assertEquals("the newer run counts no failure: the old one belongs to the old run", 0L, stream.failures)
        assertEquals("the newer run counts no flush timeout", 0L, stream.flushTimeouts)
        assertEquals("the newer run delivers its partial and its final", listOf(false, true), newSink.updates.map { it.final })
        assertEquals("the newer run's word", listOf(HeardWord("ok", 0L)), newSink.updates.first().words)
    }

    /**
     * A feed before start is not counted: the counters are read straight after the feed, before start() resets
     * them, and the chunk never reaches the engine. Turns RED if feed() counts a dropped chunk while no run exists.
     */
    @Test
    fun `a feed before start is not counted as dropped and the chunk never reaches the engine`() {
        val fake = FakeTimedNative(listOf(TIMED_NO_WORDS), finalResult = TIMED_NO_WORDS)
        val stream = timedStream(fake)
        stream.feed(timedChunk())
        assertEquals("a feed before start is not counted as a dropped chunk", 0L, stream.droppedChunks)
        assertEquals("no samples are counted as dropped before start", 0L, stream.droppedSamples)
        stream.start { }
        runBlocking { fake.entered("createStream").await() }
        stream.stop()
        assertEquals("only the tail silence reached the engine", listOf(10_560), fake.sizes())
    }

    /**
     * A stop before any start returns with no count and no native call. Turns RED if stop() counts a dropped
     * chunk while no run exists.
     */
    @Test
    fun `a stop before any start drops nothing, makes no native call and does not throw`() {
        val fake = FakeTimedNative(listOf(TIMED_NO_WORDS))
        val stream = timedStream(fake)
        stream.stop()
        assertEquals("a stop before any start counts no dropped chunk", 0L, stream.droppedChunks)
        assertEquals("a stop before any start counts no dropped samples", 0L, stream.droppedSamples)
        assertTrue("a stop before any start makes no native call", fake.calls.isEmpty())
        assertFalse("the stream is not running", stream.isRunning)
    }
}
