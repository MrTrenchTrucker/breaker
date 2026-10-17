package dev.breaker.dictation.stt.ondevice

import dev.breaker.dictation.core.model.HeardWord
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
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
 * The races and the error rules of the on-device word stream that the other test classes do not reach:
 * two starts at the same moment, a stop from inside an update that acts on the run that made it, a flush
 * timer that fires after its run ended, a cancellation that ends the worker job, a checked exception, and
 * a link failure at the native open with its controls. Every wait is a signal, and the two starts use two
 * coroutines only. The class Timeout rule of 20 seconds is a safety net; no test reads a clock.
 */
class OnDeviceWordStreamRaceTest {

    @get:Rule
    val safetyNet: Timeout = Timeout.seconds(20)

    private val callers = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @After
    fun cancelCallers() {
        callers.cancel()
    }

    /** A word stream over [opener] with the test defaults; [handler] receives an uncaught Error. */
    private fun streamOver(
        opener: NativeStreamingOpener,
        dispatcher: CoroutineDispatcher = ProbeDispatcher(),
        flushDeadline: DecodeDeadline = NEVER_EXPIRES,
        handler: CoroutineExceptionHandler? = null,
        beforeStartClaim: () -> Unit = {},
    ): OnDeviceWordStream = OnDeviceWordStream(
        files = timedFiles(),
        numThreads = 1,
        opener = opener,
        dispatcher = dispatcher,
        flushDeadline = flushDeadline,
        onUncaught = handler,
        beforeStartClaim = beforeStartClaim,
    )

    /**
     * Two starts pass the refusal check together and then race for the claim. Both wait at the hook until
     * both have passed it, so the race is forced without a clock. With the compare-and-set claim the loser
     * returns as a double start does and its worker never runs: one open. With a check followed by a plain
     * set both starts launch a worker and the engine is opened twice. Turns RED if the claim is a plain set.
     */
    // The hook holds both starts after the early check and before the claim, so a plain set after that check
    // is forced to fail here. A claim that reads the current run again and then sets it in two steps has no
    // hold between its own read and its set, so it would pass these tests only by chance. Atomicity of the
    // claim rests on compareAndSet itself.
    @Test
    fun `two starts at the same moment make one run and the start that loses the claim opens nothing`() {
        val fake = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}hi"), listOf(0.0f))))
        val probe = ProbeDispatcher()
        val checked = AtomicInteger(0)
        val bothChecked = CompletableDeferred<Unit>()
        val stream = streamOver(
            opener = NativeStreamingOpener { _, threads -> fake.open(threads) },
            dispatcher = probe,
            beforeStartClaim = {
                if (checked.incrementAndGet() == 2) bothChecked.complete(Unit)
                runBlocking { bothChecked.await() }
            },
        )
        val first = callers.launch { stream.start { } }
        val second = callers.launch { stream.start { } }
        runBlocking {
            first.join()
            second.join()
        }
        awaitSlotIdle(probe)
        assertEquals("both starts passed the check before either claimed", 2, checked.get())
        assertEquals("only the start that won the claim opened the engine", 1, fake.count("open"))
        assertEquals("only the start that won the claim made a stream", 1, fake.count("createStream"))
        assertTrue("the run that won the claim is running", stream.isRunning)
        stream.stop()
        assertFalse("the stream is stopped once stop returns", stream.isRunning)
    }

    /**
     * A stop from inside an update acts on the run whose callback is running. The callback stops, starts a
     * later run and stops again; the second stop must act on the first run, which is already stopped, and
     * leave the later run alone. Turns RED if stop() acts on the newest run: the later run's channel is
     * closed, its feed is refused and its final never comes. That run fails by the 20 second safety net.
     */
    @Test
    fun `a stop from inside an update acts on the run that made it so a later run keeps its final`() {
        val first = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}hi"), listOf(0.0f))))
        val second = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}ok"), listOf(0.0f))))
        val opens = AtomicInteger(0)
        val firstSink = UpdateSink(1)
        val secondSink = UpdateSink(1)
        val restarted = AtomicBoolean(false)
        val stream = streamOver(
            opener = NativeStreamingOpener { _, threads ->
                if (opens.incrementAndGet() == 1) first.open(threads) else second.open(threads)
            },
        )
        stream.start { update ->
            if (!update.final && restarted.compareAndSet(false, true)) {
                stream.stop()                              // from inside the update: abandons the run that made it
                stream.start { secondSink.record(it) }     // a later run, queued behind the abandoned worker
                stream.stop()                              // again from inside the update: acts on the first run only
            }
            // Recorded last: the test feeds the later run only after this update has returned.
            firstSink.record(update)
        }
        stream.feed(timedChunk())
        firstSink.awaitExpected()
        stream.feed(timedChunk())                          // goes to the later run
        secondSink.awaitExpected()
        stream.stop()
        assertEquals("the first run delivered only its partial update", listOf(false), firstSink.updates.map { it.final })
        assertEquals("the first run never reached its input finish", 0, first.count("inputFinished"))
        assertEquals("the later run delivers its partial and its final update", listOf(false, true), secondSink.updates.map { it.final })
        assertEquals("the later run's word", listOf(HeardWord("ok", 0L)), secondSink.updates.first().words)
    }

    /**
     * The tail of the first run fails, so its worker ends before the flush timer. The timer is fired by hand
     * after the failed run ended, and it must count nothing: the worker claimed the final in its finally. Then
     * a second run starts and the same timer fires again into it. Turns RED if the worker does not claim the
     * final when it ends, because the late timer then counts a flush timeout for the first run, which the
     * first assertion reads. The second run starts at zero either way.
     */
    @Test
    fun `a flush timer that fires after its failed run ended counts nothing and the next run starts at zero`() {
        val first = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}hi"), listOf(0.0f))))
        first.throwOn("inputFinished", RuntimeException("scripted failure in the tail"))
        val second = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}ok"), listOf(0.0f))))
        val opens = AtomicInteger(0)
        val deadline = StubDeadline()
        val firstSink = UpdateSink(1)
        val secondSink = UpdateSink(1)
        val stream = streamOver(
            opener = NativeStreamingOpener { _, threads ->
                if (opens.incrementAndGet() == 1) first.open(threads) else second.open(threads)
            },
            flushDeadline = deadline,
        )
        stream.start { firstSink.record(it) }
        stream.feed(timedChunk())
        firstSink.awaitExpected()
        stream.stop()                                      // the tail fails; stop arms the deadline and returns when the worker ends
        assertEquals("the failed tail is counted once", 1L, stream.failures)
        val lateTimer = runBlocking { deadline.armed.await() }
        lateTimer()                                        // the timer of the failed run fires after its worker ended
        assertEquals("a late timer of a failed run counts no flush timeout", 0L, stream.flushTimeouts)
        stream.start { secondSink.record(it) }             // a later run: its counters start at zero
        lateTimer()                                        // the same timer fires again, while the later run is the newest
        assertEquals("the later run counts no flush timeout", 0L, stream.flushTimeouts)
        assertEquals("the later run counts no failure", 0L, stream.failures)
        stream.feed(timedChunk())
        secondSink.awaitExpected()
        stream.stop()
        assertEquals("the later run delivers its partial and its final update", listOf(false, true), secondSink.updates.map { it.final })
    }

    /**
     * A CancellationException from a native call is rethrown, so the worker job ends cancelled, not merely
     * completed. Turns RED if the rethrow is removed: the exception is then counted as a failure and the job
     * completes normally, so the first assertion on the job fails.
     */
    @Test
    fun `a CancellationException from a native call ends the worker job as cancelled and is not counted`() {
        val fake = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}hi"), listOf(0.0f))))
        fake.throwOn("acceptWaveform", CancellationException("scripted cancellation in acceptWaveform"))
        val probe = ProbeDispatcher()
        val sink = UpdateSink(0)
        val stream = streamOver(
            opener = NativeStreamingOpener { _, threads -> fake.open(threads) },
            dispatcher = probe,
        )
        stream.start { sink.record(it) }
        val job = checkNotNull(stream.currentJob)
        stream.feed(timedChunk())
        awaitSlotIdle(probe)
        assertTrue("the worker job ends cancelled", job.isCancelled)
        assertTrue("the worker job is completed", job.isCompleted)
        assertEquals("a cancellation is not counted as a failure", 0L, stream.failures)
        assertTrue("the stream is released after the cancellation", fake.streamReleased.isCompleted)
        assertTrue("the recogniser is released after the cancellation", fake.recognizerReleased.isCompleted)
        assertTrue("no update is delivered after the cancellation", sink.updates.isEmpty())
    }

    /**
     * A checked Exception (not a RuntimeException) from a native call is an Exception, so it is counted and
     * ends the run with no final. Turns RED if the catch is narrowed to RuntimeException: the exception then
     * escapes the worker, nothing is counted, and the first assertion on the count fails.
     */
    @Test
    fun `a checked Exception from a native call is counted and ends the run with no final`() {
        val fake = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}hi"), listOf(0.0f))))
        fake.throwOn("acceptWaveform", IOException("scripted checked failure in acceptWaveform"))
        val probe = ProbeDispatcher()
        val sink = UpdateSink(0)
        val stream = streamOver(
            opener = NativeStreamingOpener { _, threads -> fake.open(threads) },
            dispatcher = probe,
        )
        stream.start { sink.record(it) }
        stream.feed(timedChunk())
        awaitSlotIdle(probe)
        assertEquals("the checked Exception is counted once", 1L, stream.failures)
        assertFalse("the stream is not running after the checked Exception", stream.isRunning)
        assertTrue("no update is delivered after the checked Exception", sink.updates.isEmpty())
        assertTrue("the stream is released after the checked Exception", fake.streamReleased.isCompleted)
        assertTrue("the recogniser is released after the checked Exception", fake.recognizerReleased.isCompleted)
        stream.stop()
    }

    /**
     * A LinkageError from the native open is a link failure: it is counted, the run ends with no stream and no
     * final, and nothing reaches the uncaught handler. Turns RED if the open's catch is removed: the Error then
     * reaches the handler and the first assertion fails. A widening to Error keeps this test green; the
     * AssertionError control below is the test that turns RED for that widening.
     */
    @Test
    fun `a LinkageError from the native open is counted, ends the run with no final and reaches no handler`() {
        val fake = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}hi"), listOf(0.0f))))
        fake.throwOn("open", LinkageError("scripted link failure at open"))
        val probe = ProbeDispatcher()
        val uncaught = CompletableDeferred<Throwable>()
        val sink = UpdateSink(0)
        val stream = streamOver(
            opener = NativeStreamingOpener { _, threads -> fake.open(threads) },
            dispatcher = probe,
            handler = CoroutineExceptionHandler { _, error -> uncaught.complete(error) },
        )
        stream.start { sink.record(it) }
        stream.feed(timedChunk())
        awaitSlotIdle(probe)
        assertEquals("the link failure at the open is counted once", 1L, stream.failures)
        assertFalse("the run is not running after the link failure", stream.isRunning)
        assertFalse("no Error reaches the uncaught handler for a link failure at the open", uncaught.isCompleted)
        assertEquals("nothing is opened or made after the failed open", listOf("open"), fake.names())
        assertTrue("no update and no final is delivered", sink.updates.isEmpty())
        stream.stop()
    }

    /**
     * CONTROL for the open's catch: an AssertionError at the open is not a link failure, so it is not caught; it
     * propagates to the uncaught handler and is not counted. Turns RED if the open's catch is widened to Error:
     * the AssertionError is then counted and the handler is never called.
     */
    @Test
    fun `an AssertionError at the native open is not caught and propagates to the handler`() {
        val fake = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}hi"), listOf(0.0f))))
        fake.throwOn("open", AssertionError("scripted assertion at open"))
        val probe = ProbeDispatcher()
        val uncaught = CompletableDeferred<Throwable>()
        val stream = streamOver(
            opener = NativeStreamingOpener { _, threads -> fake.open(threads) },
            dispatcher = probe,
            handler = CoroutineExceptionHandler { _, error -> uncaught.complete(error) },
        )
        stream.start { }
        awaitSlotIdle(probe)
        assertTrue("the AssertionError reached the uncaught handler", uncaught.isCompleted)
        assertTrue("it is the scripted error", uncaught.getCompleted() is AssertionError)
        assertEquals("an Error is not counted as a failure", 0L, stream.failures)
        assertFalse("the run is not running after the Error", stream.isRunning)
        stream.stop()
    }

    /**
     * A LinkageError from a later native call is not a link failure at the open, so it is not caught: it propagates
     * to the uncaught handler, and the native objects are released first. Turns RED if the catch is widened to the
     * other native calls: the LinkageError is then counted, the handler is never called and the first assertion
     * fails.
     */
    @Test
    fun `a LinkageError from a later native call is not caught and propagates to the handler`() {
        val fake = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}hi"), listOf(0.0f))))
        fake.throwOn("acceptWaveform", LinkageError("scripted link failure in acceptWaveform"))
        val probe = ProbeDispatcher()
        val uncaught = CompletableDeferred<Throwable>()
        val sink = UpdateSink(0)
        val stream = streamOver(
            opener = NativeStreamingOpener { _, threads -> fake.open(threads) },
            dispatcher = probe,
            handler = CoroutineExceptionHandler { _, error -> uncaught.complete(error) },
        )
        stream.start { sink.record(it) }
        stream.feed(timedChunk())
        awaitSlotIdle(probe)
        assertTrue("the LinkageError from a later call reached the uncaught handler", uncaught.isCompleted)
        assertTrue("it is the scripted link failure", uncaught.getCompleted() is LinkageError)
        assertEquals("an Error from a later call is not counted as a failure", 0L, stream.failures)
        assertTrue("the stream is released before the error leaves the worker", fake.streamReleased.isCompleted)
        assertTrue("the recogniser is released before the error leaves the worker", fake.recognizerReleased.isCompleted)
        assertTrue("no update is delivered after the error", sink.updates.isEmpty())
    }
}
