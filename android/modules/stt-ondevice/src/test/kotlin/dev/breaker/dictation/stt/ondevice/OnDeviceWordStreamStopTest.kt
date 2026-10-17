package dev.breaker.dictation.stt.ondevice

import dev.breaker.dictation.core.model.HeardWord
import dev.breaker.dictation.core.model.WordUpdate
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
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
 * The stop of the on-device word stream from another thread and from inside the callback,
 * the queued chunks at stop, the flush deadline, the queue overflow, the counters and the
 * native failures. Every wait is a signal. The class Timeout rule is only a safety net.
 *
 * The flush deadline tests bound themselves with the injected deadline stub, not with a clock.
 * A variant of stop() that waits for the worker alone and ignores the deadline would never
 * return in the deadline tests; only the 20 second safety net would end them, so a failing
 * run of those tests is a hang, not a wrong count.
 *
 * A stop from another thread is caught with overwhelming probability, not by construction:
 * the order check compares two signals and no clock is allowed to measure the wait.
 */
class OnDeviceWordStreamStopTest {

    @get:Rule
    val safetyNet: Timeout = Timeout.seconds(20)

    private val callers = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @After
    fun cancelCallers() {
        callers.cancel()
    }

    /** One run whose flush was cut short by the deadline: the stream, its updates and the held call. */
    private class ExpiredRun(
        val fake: FakeTimedNative,
        val stream: OnDeviceWordStream,
        val sink: UpdateSink,
        val hold: CompletableDeferred<Unit>,
    )

    /**
     * Starts a run whose input finish is held until the test releases it, stops it from a plain
     * thread, and expires the deadline by hand. Returns once stop has returned.
     */
    private fun stopAtDeadline(): ExpiredRun {
        val fake = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}hi"), listOf(0.0f))))
        val hold = CompletableDeferred<Unit>()
        fake.holdOn("inputFinished", hold)
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
        return ExpiredRun(fake, stream, sink, hold)
    }

    @Test
    fun `stop from another thread returns only after a running callback has returned`() {
        val fake = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}hi"), listOf(0.0f))))
        val events = Channel<String>(Channel.UNLIMITED)
        val inCallback = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        val stream = timedStream(fake, onStopWaiting = { hold.complete(Unit) })
        stream.start { update ->
            if (!update.final && !inCallback.isCompleted) {
                inCallback.complete(Unit)
                runBlocking { hold.await() }
                events.trySend("callback-end")
            }
        }
        stream.feed(timedChunk())
        runBlocking { inCallback.await() }
        val stopper = callers.launch {
            stream.stop()
            events.trySend("stop-end")
        }
        runBlocking { stopper.join() }
        assertEquals("the callback ended before stop returned", listOf("callback-end", "stop-end"), drainNow(events))
        assertFalse("the stream is stopped once stop returns", stream.isRunning)
    }

    @Test
    fun `stop from inside onUpdate returns at once with no tail flush and no final`() {
        val fake = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}hi"), listOf(0.0f))))
        val sink = UpdateSink(0)
        val stillRunning = AtomicBoolean(true)
        val stream = timedStream(fake)
        stream.start { update ->
            sink.record(update)
            if (!update.final) {
                stream.stop()
                stillRunning.set(stream.isRunning)
            }
        }
        stream.feed(timedChunk())
        runBlocking { fake.recognizerReleased.await() }
        assertFalse("stop from inside the callback marks the stream not running at once", stillRunning.get())
        assertEquals(
            "the callback that stopped is the last update ever delivered, and it is not final",
            listOf(false),
            sink.updates.map { it.final },
        )
        assertFalse("no input finish follows a stop from inside the callback", fake.names().contains("inputFinished"))
        assertEquals("only the one block reached the engine", listOf(160), fake.sizes())
    }

    @Test
    fun `a full queue drops the chunk, counts it and the feed call returns at once`() {
        val fake = FakeTimedNative(
            listOf(
                timedResult(listOf("${TIMED_MARK}hi"), listOf(0.0f)),
                timedResult(listOf("${TIMED_MARK}hi", "${TIMED_MARK}there"), listOf(0.0f, 0.5f)),
                timedResult(
                    listOf("${TIMED_MARK}hi", "${TIMED_MARK}there", "${TIMED_MARK}bye"),
                    listOf(0.0f, 0.5f, 0.75f),
                ),
            ),
        )
        val sink = UpdateSink(3)
        val inCallback = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        val held = AtomicBoolean(false)
        val stream = timedStream(fake, capacity = 2)
        stream.start { update ->
            sink.record(update)
            if (!update.final && held.compareAndSet(false, true)) {
                inCallback.complete(Unit)
                runBlocking { hold.await() }
            }
        }
        stream.feed(timedChunk(50))
        runBlocking { inCallback.await() }
        for (size in listOf(100, 200, 300, 400, 500)) stream.feed(timedChunk(size))
        assertEquals("with two queued, the other three of five were dropped", 3L, stream.droppedChunks)
        assertEquals("the dropped samples are the sizes of the three dropped blocks", 1_200L, stream.droppedSamples)
        hold.complete(Unit)
        sink.awaitExpected()
        stream.stop()
        assertEquals("the counters still hold after stop", 3L, stream.droppedChunks)
        assertEquals(
            "the two queued blocks were heard after the hold, then the final one",
            listOf(
                listOf(HeardWord("hi", 0L)) to false,
                listOf(HeardWord("hi", 0L), HeardWord("there", 500L)) to false,
                listOf(HeardWord("hi", 0L), HeardWord("there", 500L), HeardWord("bye", 750L)) to false,
                listOf(HeardWord("hi", 0L), HeardWord("there", 500L), HeardWord("bye", 750L)) to true,
            ),
            sink.updates.map { it.words to it.final },
        )
    }

    @Test
    fun `the counters count from the last start and reset on the next start`() {
        var fake = FakeTimedNative(listOf(TIMED_NO_WORDS))
        val hold = CompletableDeferred<Unit>()
        fake.holdOn("open", hold)
        val stream = OnDeviceWordStream(
            files = timedFiles(),
            numThreads = 1,
            opener = NativeStreamingOpener { _, threads -> fake.open(threads) },
            queueCapacity = 1,
            dispatcher = ProbeDispatcher(),
            flushDeadline = NEVER_EXPIRES,
        )
        stream.start { }
        stream.feed(timedChunk(100))
        stream.feed(timedChunk(200))
        stream.feed(timedChunk(300))
        assertEquals("one block fits and two are dropped", 2L, stream.droppedChunks)
        assertEquals("the dropped samples are 200 and 300", 500L, stream.droppedSamples)
        hold.complete(Unit)
        stream.stop()
        assertEquals("the counters are still readable after stop", 2L, stream.droppedChunks)

        fake = FakeTimedNative(listOf(TIMED_NO_WORDS))
        stream.start { }
        assertEquals("the next start resets the dropped chunks", 0L, stream.droppedChunks)
        assertEquals("the next start resets the dropped samples", 0L, stream.droppedSamples)
        stream.stop()
    }

    @Test
    fun `a chunk queued at stop never reaches the recognizer and exactly one final arrives`() {
        val fake = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}hi"), listOf(0.0f))))
        val sink = UpdateSink(0)
        val inCallback = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        val waiting = CompletableDeferred<Unit>()
        val stream = timedStream(fake, onStopWaiting = { waiting.complete(Unit) })
        stream.start { update ->
            sink.record(update)
            if (!update.final && !inCallback.isCompleted) {
                inCallback.complete(Unit)
                runBlocking { hold.await() }
            }
        }
        stream.feed(timedChunk(160))
        runBlocking { inCallback.await() }
        stream.feed(timedChunk(170))
        stream.feed(timedChunk(180))
        val stopper = callers.launch { stream.stop() }
        runBlocking { waiting.await() }
        hold.complete(Unit)
        runBlocking { stopper.join() }
        assertEquals(
            "only the block taken before stop and the tail silence reached the engine",
            listOf(160, 10_560),
            fake.sizes(),
        )
        assertEquals("exactly one final update arrived", listOf(false, true), sink.updates.map { it.final })
    }

    @Test
    fun `the callback posts each update with trySend and a stop from another thread returns with the final posted`() {
        val fake = FakeTimedNative(
            listOf(
                timedResult(listOf("${TIMED_MARK}hi"), listOf(0.0f)),
                timedResult(listOf("${TIMED_MARK}hi", "${TIMED_MARK}there"), listOf(0.0f, 0.5f)),
            ),
        )
        val posted = Channel<WordUpdate>(Channel.UNLIMITED)
        val seen = ArrayList<WordUpdate>()
        val stream = timedStream(fake)
        stream.start { update -> posted.trySend(update) }
        stream.feed(timedChunk())
        stream.feed(timedChunk())
        runBlocking { repeat(2) { seen.add(posted.receive()) } }
        val stopper = callers.launch { stream.stop() }
        runBlocking { stopper.join() }
        seen.addAll(drainNow(posted))
        assertEquals("two partial updates and then the final one were posted", listOf(false, false, true), seen.map { it.final })
        assertEquals(
            "the final update carries the last hypothesis",
            listOf(HeardWord("hi", 0L), HeardWord("there", 500L)),
            seen.last().words,
        )
    }

    @Test
    fun `a flush that never returns is abandoned at the deadline and stop returns with no final`() {
        val run = stopAtDeadline()
        assertFalse("stop returned with the stream marked not running", run.stream.isRunning)
        assertEquals("the expired flush is counted once", 1L, run.stream.flushTimeouts)
        run.hold.complete(Unit)
        runBlocking { run.fake.recognizerReleased.await() }
        assertEquals("the flush that finished late delivered no final", listOf(false), run.sink.updates.map { it.final })
        assertEquals("the held input finish was called exactly once", 1, run.fake.count("inputFinished"))
    }

    @Test
    fun `a stop after the deadline path returns at once and the late flush delivers no final`() {
        val run = stopAtDeadline()
        run.stream.stop()
        run.hold.complete(Unit)
        runBlocking { run.fake.recognizerReleased.await() }
        assertEquals("no final after a second stop on the deadline path", listOf(false), run.sink.updates.map { it.final })
        assertEquals("the expired flush is still counted once", 1L, run.stream.flushTimeouts)
    }

    @Test
    fun `a native failure in open, acceptWaveform, decode or result ends the run quietly`() {
        for (method in listOf("open", "acceptWaveform", "decode", "result")) {
            val fake = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}hi"), listOf(0.0f))))
            fake.throwOn(method, RuntimeException("scripted failure in $method"))
            val probe = ProbeDispatcher()
            val sink = UpdateSink(0)
            val stream = timedStream(fake, dispatcher = probe)
            stream.start { sink.record(it) }
            stream.feed(timedChunk())
            runBlocking { fake.entered(method).await() }
            stream.stop()
            // A failed open may still be unwinding when stop() returns at once, so wait for the slot to go idle.
            awaitSlotIdle(probe)
            assertEquals("the failure in $method is counted once", 1L, stream.failures)
            assertFalse("the stream is not running after a failure in $method", stream.isRunning)
            assertTrue("no update is delivered after a failure in $method", sink.updates.isEmpty())
            if (method == "open") {
                assertEquals("a failed open leaves nothing to release", listOf("open"), fake.names())
            } else {
                assertTrue("the stream is released after a failure in $method", fake.streamReleased.isCompleted)
                assertTrue("the recogniser is released after a failure in $method", fake.recognizerReleased.isCompleted)
            }
        }
    }

    @Test
    fun `a failure in createStream ends the run quietly and releases the recogniser`() {
        val fake = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}hi"), listOf(0.0f))))
        fake.throwOn("createStream", RuntimeException("scripted failure in createStream"))
        val sink = UpdateSink(0)
        val stream = timedStream(fake)
        stream.start { sink.record(it) }
        stream.feed(timedChunk())
        // Wait until the open has returned: a stop before that would abandon the run, and no stream would be made.
        runBlocking { fake.entered("createStream").await() }
        stream.stop()
        assertEquals("the createStream failure is counted once", 1L, stream.failures)
        assertFalse("the stream is not running after a createStream failure", stream.isRunning)
        assertTrue("no update is delivered after a createStream failure", sink.updates.isEmpty())
        assertEquals("no stream was made, so only the recogniser is released", listOf("open", "createStream", "release"), fake.names())
        assertTrue("the recogniser is released after a createStream failure", fake.recognizerReleased.isCompleted)
    }

    @Test
    fun `a failure in the final drain ends the run quietly and no final is delivered`() {
        for ((method, nth) in listOf("inputFinished" to 1, "decode" to 2, "result" to 2)) {
            val fake = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}hi"), listOf(0.0f))))
            fake.throwOn(method, RuntimeException("scripted failure in the final drain at $method"), nth)
            val sink = UpdateSink(1)
            val stream = timedStream(fake)
            stream.start { sink.record(it) }
            stream.feed(timedChunk())
            sink.awaitExpected()
            stream.stop()
            assertEquals("the drain failure in $method is counted once", 1L, stream.failures)
            assertFalse("the stream is not running after a drain failure in $method", stream.isRunning)
            assertEquals(
                "only the partial update was delivered after a drain failure in $method",
                listOf(false),
                sink.updates.map { it.final },
            )
            assertTrue("the stream is released after a drain failure in $method", fake.streamReleased.isCompleted)
            assertTrue("the recogniser is released after a drain failure in $method", fake.recognizerReleased.isCompleted)
        }
    }

    @Test
    fun `a decode that never finishes ends the run quietly at the step bound`() {
        val fake = FakeTimedNative(listOf(TIMED_NO_WORDS), neverReady = true)
        val sink = UpdateSink(0)
        val stream = timedStream(fake)
        stream.start { sink.record(it) }
        stream.feed(timedChunk())
        runBlocking { fake.entered("decode").await() }
        stream.stop()
        assertEquals("the step bound is a native failure and is counted once", 1L, stream.failures)
        assertFalse("the stream is not running after the bound", stream.isRunning)
        assertFalse("the decode loop was stopped by the adapter bound, not by the poll guard", fake.pollOverflow.get())
        assertTrue("the decode loop ran before the bound stopped it", fake.count("decode") in 1..1_000)
        assertTrue("no update is delivered for a decode that never finished", sink.updates.isEmpty())
        assertTrue("the stream is released after the bound", fake.streamReleased.isCompleted)
        assertTrue("the recogniser is released after the bound", fake.recognizerReleased.isCompleted)
    }

    @Test
    fun `a callback that throws ends the run quietly and no final is delivered`() {
        val fake = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}hi"), listOf(0.0f))))
        val sink = UpdateSink(0)
        val stream = timedStream(fake)
        stream.start { update ->
            sink.record(update)
            throw RuntimeException("scripted callback failure")
        }
        stream.feed(timedChunk())
        runBlocking { fake.recognizerReleased.await() }
        stream.stop()
        assertEquals("the RuntimeException from the callback is counted once", 1L, stream.failures)
        assertFalse("the stream is not running after a callback that threw", stream.isRunning)
        assertEquals("the partial update that threw is the only update", listOf(false), sink.updates.map { it.final })
        assertTrue("the stream is released after a callback that threw", fake.streamReleased.isCompleted)
    }

    @Test
    fun `after a failed run stop waits for the releases and a later start works`() {
        var fake = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}hi"), listOf(0.0f))))
        fake.throwOn("decode", RuntimeException("scripted failure in decode"))
        val stream = OnDeviceWordStream(
            files = timedFiles(),
            numThreads = 1,
            opener = NativeStreamingOpener { _, threads -> fake.open(threads) },
            dispatcher = ProbeDispatcher(),
            flushDeadline = NEVER_EXPIRES,
        )
        stream.start { }
        stream.feed(timedChunk())
        runBlocking { fake.entered("decode").await() }
        stream.stop()
        assertEquals("the failed run's decode failure is counted", 1L, stream.failures)
        assertTrue("the failed run's stream is released before stop returns", fake.streamReleased.isCompleted)
        assertTrue("the failed run's recogniser is released before stop returns", fake.recognizerReleased.isCompleted)

        fake = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}ok"), listOf(0.0f))))
        val sink = UpdateSink(1)
        stream.start { sink.record(it) }
        stream.feed(timedChunk())
        sink.awaitExpected()
        stream.stop()
        assertEquals("the restart delivers its partial and its final update", listOf(false, true), sink.updates.map { it.final })
        assertEquals("the restart's word", listOf(HeardWord("ok", 0L)), sink.updates.first().words)
    }

    @Test
    fun `the flush limit is 2 seconds, ignores the tail length and is under the 5 second input limit`() {
        // The default flushDeadline arms afterAudio(::flushLimitMs) with a real timer, so the wiring of
        // that default argument is not observable here without a clock. Only the limit function is pinned.
        assertEquals("the flush limit is 2 seconds", 2_000L, FLUSH_LIMIT_MS)
        assertEquals("the limit for a 0 ms tail is 2 seconds", 2_000L, flushLimitMs(0L))
        assertEquals("the limit for the 660 ms tail is 2 seconds", 2_000L, flushLimitMs(660L))
        assertTrue("the flush limit is under the 5 second input limit", FLUSH_LIMIT_MS < 5_000L)
    }
}
