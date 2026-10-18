package dev.breaker.dictation.stt.ondevice

import dev.breaker.dictation.core.model.HeardWord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * The lifecycle of the on-device word stream: the calls it makes, the updates its callback
 * sees, the single slot they run on, and the arguments it refuses. Every wait is a signal.
 * The class Timeout rule is only a safety net for a test that never gets its signal. The
 * stops from other threads, the queue overflow, the flush deadline and the native failures
 * are in the stop test class.
 */
class OnDeviceWordStreamTest {

    @get:Rule
    val safetyNet: Timeout = Timeout.seconds(20)

    @Test
    fun `a new stream is not running and runs only between start and stop`() {
        val fake = FakeTimedNative(listOf(TIMED_NO_WORDS))
        val stream = timedStream(fake)
        assertFalse("a new stream must not be running", stream.isRunning)
        stream.start { }
        assertTrue("a started stream must be running", stream.isRunning)
        stream.stop()
        assertFalse("a stopped stream must not be running", stream.isRunning)
    }

    @Test
    fun `a second start is a no-op so the first callback stays and one worker runs`() {
        val fake = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}hi"), listOf(0.0f))))
        val first = UpdateSink(1)
        val second = UpdateSink(0)
        val stream = timedStream(fake)
        stream.start { first.record(it) }
        stream.start { second.record(it) }
        stream.feed(timedChunk())
        first.awaitExpected()
        stream.stop()
        assertEquals("the recogniser is opened once", 1, fake.count("open"))
        assertEquals(
            "the first callback gets the partial update and then the final one",
            listOf(false, true),
            first.updates.map { it.final },
        )
        assertTrue("the second callback must never be called", second.updates.isEmpty())
    }

    @Test
    fun `a start called from inside onUpdate while running is a no-op and the first callback stays`() {
        val fake = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}hi"), listOf(0.0f))))
        val first = UpdateSink(1)
        val second = UpdateSink(0)
        val stream = timedStream(fake)
        stream.start { update ->
            first.record(update)
            if (!update.final) stream.start { second.record(it) }
        }
        stream.feed(timedChunk())
        first.awaitExpected()
        stream.stop()
        assertEquals("the recogniser is opened once", 1, fake.count("open"))
        assertEquals(listOf(false, true), first.updates.map { it.final })
        assertTrue("a start from inside the callback adds no callback", second.updates.isEmpty())
    }

    @Test
    fun `stop when stopped is safe and makes no native call`() {
        val fake = FakeTimedNative(listOf(TIMED_NO_WORDS))
        val stream = timedStream(fake)
        stream.stop()
        assertTrue("a stream that never started made no native call", fake.calls.isEmpty())
        stream.start { }
        stream.feed(timedChunk())
        stream.stop()
        // A stop during the open returns at once, so wait until the worker has released the recogniser.
        runBlocking { fake.recognizerReleased.await() }
        val afterRun = fake.calls.size
        stream.stop()
        assertEquals("a second stop after a run makes no native call", afterRun, fake.calls.size)
        assertFalse("the stream is stopped", stream.isRunning)
    }

    @Test
    fun `stop twice from the test thread is safe and delivers exactly one final`() {
        val fake = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}hi"), listOf(0.0f))))
        val sink = UpdateSink(1)
        val stream = timedStream(fake)
        stream.start { sink.record(it) }
        stream.feed(timedChunk())
        sink.awaitExpected()
        stream.stop()
        val callsAfterFirst = fake.calls.size
        stream.stop()
        assertEquals("a second stop makes no native call", callsAfterFirst, fake.calls.size)
        assertEquals("exactly one final update, after the partial one", listOf(false, true), sink.updates.map { it.final })
        assertFalse("the stream is stopped", stream.isRunning)
    }

    @Test
    fun `each update carries the whole hypothesis and no final update is sent before stop`() {
        val fake = FakeTimedNative(
            listOf(
                timedResult(listOf("${TIMED_MARK}he"), listOf(0.0f)),
                timedResult(listOf("${TIMED_MARK}he", "llo"), listOf(0.0f, 0.1f)),
                timedResult(listOf("${TIMED_MARK}he", "llo", "${TIMED_MARK}there"), listOf(0.0f, 0.1f, 0.5f)),
            ),
        )
        val sink = UpdateSink(3)
        val stream = timedStream(fake)
        stream.start { sink.record(it) }
        repeat(3) { stream.feed(timedChunk()) }
        sink.awaitExpected()
        assertFalse("no final update may be delivered before stop", sink.updates.any { it.final })
        stream.stop()
        assertEquals(
            "whole hypotheses in order, then the final one with the last hypothesis",
            listOf(
                listOf(HeardWord("he", 0L)) to false,
                listOf(HeardWord("hello", 0L)) to false,
                listOf(HeardWord("hello", 0L), HeardWord("there", 500L)) to false,
                listOf(HeardWord("hello", 0L), HeardWord("there", 500L)) to true,
            ),
            sink.updates.map { it.words to it.final },
        )
    }

    @Test
    fun `an identical hypothesis is not delivered a second time`() {
        val same = timedResult(listOf("${TIMED_MARK}hi"), listOf(0.0f))
        val fake = FakeTimedNative(
            listOf(same, same, timedResult(listOf("${TIMED_MARK}hi", "${TIMED_MARK}there"), listOf(0.0f, 0.5f))),
        )
        val sink = UpdateSink(2)
        val stream = timedStream(fake)
        stream.start { sink.record(it) }
        repeat(3) { stream.feed(timedChunk()) }
        sink.awaitExpected()
        stream.stop()
        assertEquals(
            "the repeated hypothesis is skipped, and the final update repeats the last one",
            listOf(
                listOf(HeardWord("hi", 0L)) to false,
                listOf(HeardWord("hi", 0L), HeardWord("there", 500L)) to false,
                listOf(HeardWord("hi", 0L), HeardWord("there", 500L)) to true,
            ),
            sink.updates.map { it.words to it.final },
        )
    }

    @Test
    fun `the native calls run in one fixed order and no endpoint rule is consulted`() {
        val fake = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}hi"), listOf(0.0f))))
        val sink = UpdateSink(1)
        val stream = timedStream(fake)
        stream.start { sink.record(it) }
        stream.feed(timedChunk())
        sink.awaitExpected()
        stream.stop()
        assertEquals(
            "the exact native call order, with no endpoint call anywhere",
            listOf(
                "open", "createStream", "acceptWaveform", "decode", "result",
                "acceptWaveform", "inputFinished", "decode", "result", "streamRelease", "release",
            ),
            fake.names(),
        )
    }

    @Test
    fun `the silence after the input is 10560 zero samples and is fed before the input is finished`() {
        val fake = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}hi"), listOf(0.0f))))
        val sink = UpdateSink(1)
        val stream = timedStream(fake)
        stream.start { sink.record(it) }
        stream.feed(timedChunk())
        sink.awaitExpected()
        stream.stop()
        val calls = fake.calls.map { it.toString() }
        assertEquals("the clip is fed first, as it is", 160, fake.received.first().size)
        assertEquals("the silence is one block of 10560 samples", 10_560, fake.received.last().size)
        assertTrue("the silence is all zeros", fake.received.last().all { it == 0.0f })
        assertTrue(
            "the silence is fed before the input is finished",
            calls.indexOf("acceptWaveform 10560 @16000") in 0 until calls.indexOf("inputFinished"),
        )
        assertTrue(
            "one drain decode follows the input being finished",
            calls.indexOf("inputFinished") < calls.lastIndexOf("decode"),
        )
    }

    @Test
    fun `every update and every native call runs on the one slot with the marker set and never overlaps`() {
        val probe = ProbeDispatcher()
        val fake = FakeTimedNative(
            listOf(
                timedResult(listOf("${TIMED_MARK}hi"), listOf(0.0f)),
                timedResult(listOf("${TIMED_MARK}hi", "${TIMED_MARK}there"), listOf(0.0f, 0.5f)),
            ),
        )
        val sink = UpdateSink(2)
        val stream = timedStream(fake, dispatcher = probe)
        stream.start { sink.record(it) }
        repeat(2) { stream.feed(timedChunk()) }
        sink.awaitExpected()
        stream.stop()
        assertEquals("two partial updates and the final one", 3, sink.updates.size)
        assertTrue("every update, the final one included, ran with the marker set", sink.updates.all { it.onSlot })
        assertTrue("every native call ran with the marker set", fake.calls.all { it.onSlot })
        assertEquals("never more than one block ran at a time", 1, probe.maxBlocks.get())
    }

    @Test
    fun `two word streams run their callbacks at the same time on their own slots`() {
        val fakeA = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}aa"), listOf(0.0f))))
        val fakeB = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}bb"), listOf(0.0f))))
        val inA = CompletableDeferred<Unit>()
        val holdA = CompletableDeferred<Unit>()
        val heardB = CompletableDeferred<List<HeardWord>>()
        val streamA = OnDeviceWordStream(
            files = timedFiles(),
            numThreads = 1,
            opener = NativeStreamingOpener { _, threads -> fakeA.open(threads) },
            flushDeadline = NEVER_EXPIRES,
        )
        val streamB = OnDeviceWordStream(
            files = timedFiles(),
            numThreads = 1,
            opener = NativeStreamingOpener { _, threads -> fakeB.open(threads) },
            flushDeadline = NEVER_EXPIRES,
        )
        streamA.start { update ->
            if (!update.final) {
                inA.complete(Unit)
                runBlocking { holdA.await() }
            }
        }
        streamA.feed(timedChunk())
        runBlocking { inA.await() }
        streamB.start { update -> if (!update.final) heardB.complete(update.words) }
        streamB.feed(timedChunk())
        assertEquals(
            "the second stream is heard while the first callback is still held",
            listOf(HeardWord("bb", 0L)),
            runBlocking { heardB.await() },
        )
        holdA.complete(Unit)
        streamA.stop()
        streamB.stop()
    }

    @Test
    fun `a feed after stop is ignored, makes no update and is not counted as dropped`() {
        val fake = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}hi"), listOf(0.0f))))
        val sink = UpdateSink(1)
        val stream = timedStream(fake)
        stream.start { sink.record(it) }
        stream.feed(timedChunk())
        sink.awaitExpected()
        stream.stop()
        val updatesBefore = sink.updates.size
        val callsBefore = fake.calls.size
        stream.feed(timedChunk())
        stream.feed(timedChunk(320))
        assertEquals("a feed after stop makes no update", updatesBefore, sink.updates.size)
        assertEquals("a feed after stop makes no native call", callsBefore, fake.calls.size)
        assertEquals("a feed after stop is not counted as a dropped chunk", 0L, stream.droppedChunks)
        assertEquals("a feed after stop is not counted as dropped samples", 0L, stream.droppedSamples)
    }

    @Test
    fun `a feed before start is ignored and not counted`() {
        val fake = FakeTimedNative(listOf(TIMED_NO_WORDS), finalResult = TIMED_NO_WORDS)
        val sink = UpdateSink(0)
        val stream = timedStream(fake)
        stream.feed(timedChunk())
        stream.start { sink.record(it) }
        // Wait until the open has returned: a stop before that would abandon the run and deliver no final.
        runBlocking { fake.entered("createStream").await() }
        stream.stop()
        assertEquals("nothing is dropped for a feed before start", 0L, stream.droppedChunks)
        assertEquals("no samples are dropped for a feed before start", 0L, stream.droppedSamples)
        assertEquals("only the silence reached the engine", listOf(10_560), fake.sizes())
        assertEquals(
            "the only update is an empty final one",
            listOf(emptyList<HeardWord>() to true),
            sink.updates.map { it.words to it.final },
        )
    }

    @Test
    fun `an empty feed is ignored and is not counted as a dropped chunk`() {
        val fake = FakeTimedNative(listOf(TIMED_NO_WORDS))
        val stream = timedStream(fake)
        stream.start { }
        stream.feed(FloatArray(0))
        runBlocking { fake.entered("createStream").await() }
        stream.stop()
        assertEquals("an empty feed is not counted as dropped", 0L, stream.droppedChunks)
        assertEquals("an empty feed reaches the engine as nothing", listOf(10_560), fake.sizes())
    }

    @Test
    fun `a copy is taken of each block so a later change to the array does not reach the engine`() {
        val fake = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}hi"), listOf(0.0f))))
        val hold = CompletableDeferred<Unit>()
        fake.holdOn("open", hold)
        val sink = UpdateSink(1)
        val stream = timedStream(fake)
        stream.start { sink.record(it) }
        val block = FloatArray(4) { 0.25f }
        stream.feed(block)
        block.fill(9.0f)
        hold.complete(Unit)
        sink.awaitExpected()
        stream.stop()
        assertEquals("the first block the engine accepts is the one fed", 4, fake.received.first().size)
        assertTrue(
            "the engine received the values from before the change",
            fake.received.first().all { it == 0.25f },
        )
    }

    @Test
    fun `a time of twelve hundredths of a second is 120 ms and a restart counts from its own stream`() {
        var fake = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}ab"), listOf(0.12f))))
        val stream = OnDeviceWordStream(
            files = timedFiles(),
            numThreads = 1,
            opener = NativeStreamingOpener { _, threads -> fake.open(threads) },
            dispatcher = ProbeDispatcher(),
            flushDeadline = NEVER_EXPIRES,
        )
        val first = UpdateSink(1)
        stream.start { first.record(it) }
        stream.feed(timedChunk())
        first.awaitExpected()
        stream.stop()
        assertEquals(
            "the first run's word starts 120 ms in",
            listOf(HeardWord("ab", 120L)),
            first.updates.first().words,
        )

        fake = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}cd"), listOf(0.02f))))
        val second = UpdateSink(1)
        stream.start { second.record(it) }
        stream.feed(timedChunk())
        second.awaitExpected()
        stream.stop()
        assertEquals(
            "the restart counts from its new stream, so 0.02 s is 20 ms and not 140",
            listOf(HeardWord("cd", 20L)),
            second.updates.first().words,
        )
    }

    @Test
    fun `a queue capacity of zero or less is refused with a message before anything is opened`() {
        for (bad in listOf(0, -1)) {
            val fake = FakeTimedNative(listOf(TIMED_NO_WORDS))
            val refusal: IllegalArgumentException? = try {
                timedStream(fake, capacity = bad)
                null
            } catch (e: IllegalArgumentException) {
                e
            }
            assertNotNull("a queue capacity of $bad must be refused", refusal)
            assertTrue(
                "the refusal for capacity $bad must carry a message",
                refusal!!.message.orEmpty().isNotBlank(),
            )
            assertTrue("nothing is opened for capacity $bad", fake.calls.isEmpty())
        }
    }

    @Test
    fun `the default queue capacity is 64 chunks`() {
        assertEquals("the default capacity", 64, OnDeviceWordStream.DEFAULT_QUEUE_CAPACITY)
    }
}
