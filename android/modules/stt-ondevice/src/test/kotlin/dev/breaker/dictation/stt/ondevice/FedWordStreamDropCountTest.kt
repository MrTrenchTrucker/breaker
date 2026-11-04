package dev.breaker.dictation.stt.ondevice

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Reads the dropped-chunk and dropped-sample counts through the [FedWordStream] interface itself, with no
 * cast to OnDeviceWordStream. It builds the stream exactly as FedWordStreamFeedTest.streamOver does, holds
 * the fake on open so the queue fills without a clock, then asserts the counts first after a full queue and
 * again after a fresh run resets them. The fixtures it needs are duplicated locally from FedWordStreamFeedTest
 * (streamOver, modelDir) because they are private there; the timed helpers come from FakeTimedNative.kt.
 */
class FedWordStreamDropCountTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A model folder with the four files and the install stamp that covers them, as an install leaves it. */
    private fun modelDir(): File {
        val dir = tmp.newFolder()
        val names = listOf("encoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.onnx",
            "joiner-epoch-99-avg-1.int8.onnx", "tokens.txt")
        for (name in names) File(dir, name).writeText("x")
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

    /** The chunk fed to a full queue is dropped once and counted with its samples, read through the interface. */
    @Test
    fun `a full queue drops the 65th chunk and counts it with its samples`() {
        val fake = FakeTimedNative(listOf(TIMED_NO_WORDS))
        val hold = CompletableDeferred<Unit>()
        fake.holdOn("open", hold)
        val stream = streamOver(fake)
        stream.start { }
        runBlocking { fake.entered("open").await() }
        try {
            repeat(64) { stream.feed(timedChunk()) }
            assertEquals("the first 64 chunks fit the queue", 0L, stream.droppedChunks)
            assertEquals("no chunk dropped before the full-queue drop", 0L, stream.droppedSamples)
            stream.feed(timedChunk(160))
            assertEquals("the 65th chunk is counted once through the interface", 1L, stream.droppedChunks)
            assertEquals("the dropped samples are the 160 of the 65th chunk", 160L, stream.droppedSamples)
        } finally {
            stream.stop()
            hold.complete(Unit)
        }
        runBlocking { fake.recognizerReleased.await() }
    }

    /** A fresh start after stop resets the counts to zero; one fed chunk fits without a drop. */
    @Test
    fun `a fresh run after stop reports no dropped chunk and no dropped sample`() {
        val fake = FakeTimedNative(listOf(TIMED_NO_WORDS))
        val firstHold = CompletableDeferred<Unit>()
        val secondHold = CompletableDeferred<Unit>()
        fake.holdOn("open", firstHold)
        val stream = streamOver(fake)
        try {
            // First run on the same instance: sixty-four chunks fit the queue, then the 65th is dropped.
            stream.start { }
            runBlocking { fake.entered("open").await() }
            repeat(64) { stream.feed(timedChunk()) }
            assertEquals("the first 64 chunks fit the queue", 0L, stream.droppedChunks)
            assertEquals("no chunk dropped before the full-queue drop", 0L, stream.droppedSamples)
            stream.feed(timedChunk(160))
            assertEquals("the 65th chunk is counted once through the interface", 1L, stream.droppedChunks)
            assertEquals("the dropped samples are the 160 of the 65th chunk", 160L, stream.droppedSamples)

            // End the first run and wait for its recognizer release exactly once.
            stream.stop()
            firstHold.complete(Unit)
            runBlocking { fake.recognizerReleased.await() }
        } finally {
            stream.stop()
            firstHold.complete(Unit)
        }

        try {
            // Same instance: a fresh start claims a new run and resets both counters to zero.
            stream.start { }
            runBlocking { fake.entered("open", 2).await() }
            stream.feed(timedChunk())
            assertEquals("the new run reports no dropped chunk", 0L, stream.droppedChunks)
            assertEquals("the new run reports no dropped sample", 0L, stream.droppedSamples)
        } finally {
            secondHold.complete(Unit)
            stream.stop()
        }

        // The single recognizer release (already signalled on the first release); await it to mirror the module teardown.
        runBlocking { fake.recognizerReleased.await() }
    }
}
