package dev.breaker.dictation.stt.ondevice

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout

/** The guard for a wait that must end: a failure names what did not happen, it is not a timer the code relies on. */
private const val FEED_GUARD_MS = 10_000L

/**
 * What feed() does before a start, during a run, after a stop and on a full queue, through the factory's
 * stream. The worker is held inside the native open, so the queue fills without any clock. Every wait is
 * a signal, except the bounded guards that name the failure they catch.
 */
class FedWordStreamFeedTest {

    @get:Rule
    val safetyNet: Timeout = Timeout.seconds(20)

    @get:Rule
    val tmp = TemporaryFolder()

    private val callers = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @After
    fun cancelCallers() {
        callers.cancel()
    }

    /** A model folder with the four files and the install stamp that covers them, as an install leaves it. */
    private fun modelDir(): File {
        val dir = tmp.newFolder()
        val names = listOf("encoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.onnx", "joiner-epoch-99-avg-1.int8.onnx", "tokens.txt")
        for (name in names) {
            File(dir, name).writeText("x")
        }
        val choice = checkNotNull(TransducerFileLocator.choose(names))
        val picked = listOf(choice.encoder, choice.decoder, choice.joiner, choice.tokens)
        val stamp = checkNotNull(UnpackedStamp.measure(dir, picked, Fixtures.VALID_PIN) { ModelIntegrity.sha256(it) })
        File(dir, UnpackedStamp.FILE_NAME).writeText(stamp.encode())
        return dir
    }

    /** A stream from the factory over [fake], opened through an injected opener; the queue keeps the default capacity. */
    private fun streamOver(fake: FakeTimedNative): FedWordStream {
        val opener = NativeStreamingOpener { _, threads -> fake.open(threads) }
        return SherpaOnnxWordStreamFactory(opener, 1).create(SherpaModel("small", modelDir(), "digest"))
    }

    private fun droppedChunks(stream: FedWordStream): Long = (stream as OnDeviceWordStream).droppedChunks

    private fun droppedSamples(stream: FedWordStream): Long = (stream as OnDeviceWordStream).droppedSamples

    @Test
    fun `a chunk fed before start is dropped and the engine takes only the chunk fed after start`() {
        val fake = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}hi"), listOf(0.0f))))
        val stream = streamOver(fake)
        stream.feed(timedChunk(100))
        val sink = UpdateSink(1)
        stream.start { sink.record(it) }
        runBlocking { fake.entered("createStream").await() }
        stream.feed(timedChunk())
        sink.awaitExpected()
        stream.stop()
        assertEquals("the engine took the 160 sample chunk and the tail silence only", listOf(160, 10_560), fake.sizes())
        assertEquals("a chunk fed before start is not counted", 0L, droppedChunks(stream))
    }

    @Test
    fun `a chunk fed after stop has returned is ignored and not counted`() {
        val fake = FakeTimedNative(listOf(timedResult(listOf("${TIMED_MARK}hi"), listOf(0.0f))))
        val stream = streamOver(fake)
        val sink = UpdateSink(1)
        stream.start { sink.record(it) }
        stream.feed(timedChunk())
        sink.awaitExpected()
        val stopped = callers.launch { stream.stop() }
        val returned = runBlocking { withTimeoutOrNull(FEED_GUARD_MS) { stopped.join() } }
        if (returned == null) fail("stop did not return: the queue of the stream stayed open")
        runBlocking { fake.recognizerReleased.await() }
        val callsAfterStop = fake.calls.size
        repeat(70) { stream.feed(timedChunk()) }
        assertEquals("no native call follows the chunks fed after stop", callsAfterStop, fake.calls.size)
        assertEquals("a closed queue is not counted: no dropped chunk", 0L, droppedChunks(stream))
        assertEquals("a closed queue is not counted: no dropped sample", 0L, droppedSamples(stream))
    }

    @Test
    fun `a chunk fed to a full queue is dropped and counted with its samples`() {
        val fake = FakeTimedNative(listOf(TIMED_NO_WORDS))
        val hold = CompletableDeferred<Unit>()
        fake.holdOn("open", hold)
        val stream = streamOver(fake)
        stream.start { }
        runBlocking { fake.entered("open").await() }
        try {
            repeat(64) { stream.feed(timedChunk()) }
            assertEquals("the first 64 chunks fit the queue", 0L, droppedChunks(stream))
            stream.feed(timedChunk(200))
            assertEquals("the 65th chunk is counted once", 1L, droppedChunks(stream))
            assertEquals("the dropped samples are the 200 of the 65th chunk", 200L, droppedSamples(stream))
        } finally {
            stream.stop()
            hold.complete(Unit)
        }
        runBlocking { fake.recognizerReleased.await() }
    }

    @Test
    fun `feed returns at once on a full queue and counts each of the five chunks`() {
        val fake = FakeTimedNative(listOf(TIMED_NO_WORDS))
        val hold = CompletableDeferred<Unit>()
        fake.holdOn("open", hold)
        val stream = streamOver(fake)
        stream.start { }
        runBlocking { fake.entered("open").await() }
        try {
            repeat(64) { stream.feed(timedChunk()) }
            val helperDone = CompletableDeferred<Unit>()
            callers.launch {
                repeat(5) { stream.feed(timedChunk()) }
                helperDone.complete(Unit)
            }
            val returned = runBlocking { withTimeoutOrNull(FEED_GUARD_MS) { helperDone.await() } }
            if (returned == null) fail("feed blocked on a full queue")
            assertEquals("the five feeds on the full queue were dropped and counted", 5L, droppedChunks(stream))
        } finally {
            stream.stop()
            hold.complete(Unit)
        }
        runBlocking { fake.recognizerReleased.await() }
    }

    @Test
    fun `an empty array is ignored and is not counted even when the queue is full`() {
        val fake = FakeTimedNative(listOf(TIMED_NO_WORDS))
        val hold = CompletableDeferred<Unit>()
        fake.holdOn("open", hold)
        val stream = streamOver(fake)
        stream.start { }
        runBlocking { fake.entered("open").await() }
        repeat(64) { stream.feed(timedChunk()) }
        stream.feed(FloatArray(0))
        assertEquals("the empty array is not counted as a dropped chunk", 0L, droppedChunks(stream))
        hold.complete(Unit)
        runBlocking { fake.entered("acceptWaveform", 64).await() }
        stream.stop()
        assertEquals(
            "the engine took the 64 queued chunks and the tail silence, and no empty block",
            List(64) { 160 } + 10_560,
            fake.sizes(),
        )
    }
}
