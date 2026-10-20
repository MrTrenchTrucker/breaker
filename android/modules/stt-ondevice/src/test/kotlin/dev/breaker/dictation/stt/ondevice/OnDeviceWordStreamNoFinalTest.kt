package dev.breaker.dictation.stt.ondevice

import dev.breaker.dictation.core.model.HeardWord
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
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
 * The five no-final paths of the on-device word stream, each pinned by one explicit test, plus the
 * at-most-one-final claim per start. Each test asserts, on its own path, that no update has
 * final == true, with the partial updates as they are. The existing stop, race, deadline and
 * behaviour tests already cover parts of these paths; these tests state the no-final claim on each
 * path itself, so a mutant that makes a path deliver a final is caught here by name. Every wait is
 * a signal; the class Timeout rule of 20 seconds is only a safety net.
 */
class OnDeviceWordStreamNoFinalTest {

    @get:Rule
    val safetyNet: Timeout = Timeout.seconds(20)

    private val callers = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @After
    fun cancelCallers() {
        callers.cancel()
    }

    /** A stream over [fake] with a probe slot and the given flush deadline. */
    private fun testStream(fake: FakeTimedNative, flushDeadline: DecodeDeadline = NEVER_EXPIRES): OnDeviceWordStream =
        OnDeviceWordStream(
            files = timedFiles(),
            numThreads = 1,
            opener = NativeStreamingOpener { _, threads -> fake.open(threads) },
            dispatcher = ProbeDispatcher(),
            flushDeadline = flushDeadline,
        )

    @Test
    fun `the deadline path abandons the tail flush and delivers no final`() {
        val fake = FakeTimedNative(listOf(timedResult(listOf("hi"), listOf(0.0f))))
        // The second decode is the tail drain's first decode; the hold stands for a drain that never returns.
        val tailHold = CompletableDeferred<Unit>()
        fake.holdOn("decode", tailHold, nth = 2)
        val deadline = StubDeadline()
        val sink = UpdateSink(1)
        val stream = testStream(fake, flushDeadline = deadline)
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
        assertEquals("only the partial was delivered on the deadline path", listOf(false), sink.updates.map { it.final })
        assertTrue("no update on the deadline path is final", sink.updates.none { it.final })
    }

    @Test
    fun `a stop from inside the update ends the run with no final`() {
        val fake = FakeTimedNative(listOf(timedResult(listOf("hi"), listOf(0.0f))))
        val probe = ProbeDispatcher()
        val stopped = CompletableDeferred<Unit>()
        val sink = UpdateSink(0)
        val stream = OnDeviceWordStream(
            files = timedFiles(),
            numThreads = 1,
            opener = NativeStreamingOpener { _, threads -> fake.open(threads) },
            dispatcher = probe,
            flushDeadline = NEVER_EXPIRES,
        )
        stream.start { update ->
            if (!update.final) {
                stopped.complete(Unit)
                stream.stop() // from inside the update: ends this run at once
            }
            sink.record(update)
        }
        stream.feed(timedChunk())
        runBlocking { stopped.await() }
        awaitSlotIdle(probe)
        assertFalse("the stream is not running after the in-update stop", stream.isRunning)
        assertEquals("the update that stopped is the last one and is not final", listOf(false), sink.updates.map { it.final })
        assertTrue("no update after the in-update stop is final", sink.updates.none { it.final })
    }

    @Test
    fun `an abandoned open delivers no update and no final`() {
        val fake = FakeTimedNative(listOf(timedResult(listOf("hi"), listOf(0.0f))))
        val hold = CompletableDeferred<Unit>()
        fake.holdOn("open", hold)
        val sink = UpdateSink(0)
        val stream = testStream(fake)
        stream.start { sink.record(it) }
        stream.feed(timedChunk()) // queued before stop: it must not reach the stream either
        runBlocking { fake.entered("open").await() }
        stream.stop()
        hold.complete(Unit)
        runBlocking { fake.recognizerReleased.await() }
        assertEquals("no update is delivered for an abandoned open", emptyList<Boolean>(), sink.updates.map { it.final })
        assertTrue("no update is final for an abandoned open", sink.updates.none { it.final })
        assertEquals("no audio reaches the engine", 0, fake.count("acceptWaveform"))
    }

    @Test
    fun `a checked Exception from a native call ends the run quietly with no final`() {
        val fake = FakeTimedNative(listOf(timedResult(listOf("hi"), listOf(0.0f))))
        fake.throwOn("acceptWaveform", IOException("scripted checked failure in acceptWaveform"))
        val probe = ProbeDispatcher()
        val sink = UpdateSink(0)
        val stream = OnDeviceWordStream(
            files = timedFiles(),
            numThreads = 1,
            opener = NativeStreamingOpener { _, threads -> fake.open(threads) },
            dispatcher = probe,
            flushDeadline = NEVER_EXPIRES,
        )
        stream.start { sink.record(it) }
        stream.feed(timedChunk())
        awaitSlotIdle(probe)
        assertEquals("the checked Exception is counted once", 1L, stream.failures)
        assertFalse("the stream is not running after the checked Exception", stream.isRunning)
        assertEquals("no update is delivered after the checked Exception", emptyList<Boolean>(), sink.updates.map { it.final })
        assertTrue("no update after the native failure is final", sink.updates.none { it.final })
        assertTrue("the stream is released after the checked Exception", fake.streamReleased.isCompleted)
        assertTrue("the recogniser is released after the checked Exception", fake.recognizerReleased.isCompleted)
        stream.stop()
    }

    @Test
    fun `a callback that throws on a partial ends the run quietly with no final`() {
        val fake = FakeTimedNative(listOf(timedResult(listOf("hi"), listOf(0.0f))))
        val probe = ProbeDispatcher()
        val sink = UpdateSink(0)
        val stream = OnDeviceWordStream(
            files = timedFiles(),
            numThreads = 1,
            opener = NativeStreamingOpener { _, threads -> fake.open(threads) },
            dispatcher = probe,
            flushDeadline = NEVER_EXPIRES,
        )
        stream.start { update ->
            sink.record(update)
            if (!update.final) throw IOException("scripted failure in the update callback on a partial")
        }
        stream.feed(timedChunk())
        awaitSlotIdle(probe)
        assertEquals("the callback failure is counted once", 1L, stream.failures)
        assertFalse("the stream is not running after the callback threw", stream.isRunning)
        assertEquals("the partial that threw is the only update", listOf(false), sink.updates.map { it.final })
        assertTrue("no update after the throwing callback is final", sink.updates.none { it.final })
        stream.stop()
    }

    @Test
    fun `a callback that throws on the final update does not deliver a second final`() {
        val fake = FakeTimedNative(listOf(timedResult(listOf("hi"), listOf(0.0f))))
        val probe = ProbeDispatcher()
        val sink = UpdateSink(1)
        val stream = OnDeviceWordStream(
            files = timedFiles(),
            numThreads = 1,
            opener = NativeStreamingOpener { _, threads -> fake.open(threads) },
            dispatcher = probe,
            flushDeadline = NEVER_EXPIRES,
        )
        stream.start { update ->
            sink.record(update)
            if (update.final) throw IOException("scripted failure in the update callback on the final")
        }
        stream.feed(timedChunk())
        sink.awaitExpected()
        stream.stop()
        runBlocking { fake.recognizerReleased.await() }
        assertEquals("one final update arrived, then the callback threw", listOf(false, true), sink.updates.map { it.final })
        assertEquals("exactly one final was delivered", 1, sink.updates.count { it.final })
        assertEquals("the final callback failure is counted once", 1L, stream.failures)
        assertFalse("the stream is not running after the callback threw on the final", stream.isRunning)
    }

    @Test
    fun `a run delivers at most one final per start and only at stop`() {
        val fake = FakeTimedNative(
            listOf(
                timedResult(listOf("hi"), listOf(0.0f)),
                timedResult(listOf("hello"), listOf(0.0f)),
            ),
        )
        val sink = UpdateSink(2)
        val stream = testStream(fake)
        stream.start { sink.record(it) }
        stream.feed(timedChunk())
        stream.feed(timedChunk())
        sink.awaitExpected()
        assertFalse("no final update is delivered before stop", sink.updates.any { it.final })
        stream.stop()
        assertEquals(
            "two partials, then exactly one final, at stop",
            listOf(false, false, true),
            sink.updates.map { it.final },
        )
        assertEquals("exactly one final per start", 1, sink.updates.count { it.final })
        assertTrue("the final update is the last one delivered", sink.updates.last().final)
        assertEquals("the final carries the last hypothesis", listOf(HeardWord("hello", 0L)), sink.updates.last().words)
    }
}
