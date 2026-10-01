package dev.breaker.dictation.audio

import dev.breaker.dictation.core.model.AudioFormat
import dev.breaker.dictation.core.port.AudioListener
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The failure paths: a device that dies, one that goes quiet, and a listener
 * that throws. Capture runs on its own thread, so each of these has nowhere
 * to throw and must be recorded in [MicCapture.failure] instead.
 */
class MicCaptureFailureTest {

    @Test
    fun `a device that dies mid-capture is recorded rather than swallowed`() {
        // Capture runs on its own thread, so a driver failure has nowhere to
        // throw. A take that quietly stopped early would sound complete.
        val source = FakeMicSource(script = silenceThenSpeech(totalMs = 200))
        source.failAfterReads = 2
        source.readErrorCode = -3
        val capture = MicCapture(source = source)
        val frames = collectFrames(capture, expectedFrames = 40)
        val failure = capture.failure

        assertTrue("the device failure was swallowed; $frames frames arrived", frames.isNotEmpty())
        assertNotNull("capture.failure was null after the driver reported an error", failure)
        assertTrue(
            "the failure should name the driver error code, was: ${failure!!.message}",
            failure.message!!.contains("-3"),
        )
    }

    @Test
    fun `a device that stops delivering audio at all is reported`() {
        // A driver that returns nothing forever is a hung microphone, not a
        // quiet room; treating it as silence would hang the capture thread.
        val source = FakeMicSource(script = speech(320))
        source.stallAfterReads = 1
        val capture = MicCapture(source = source)
        capture.start(AudioListener { })
        val deadline = System.currentTimeMillis() + 10_000
        while (capture.failure == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        capture.stop()
        assertNotNull("a microphone that stopped delivering audio was not reported", capture.failure)
        assertTrue(
            "the failure should say the device produced no audio, was: ${capture.failure!!.message}",
            capture.failure!!.message!!.contains("no audio"),
        )
    }

    @Test
    fun `a listener that throws ends the capture instead of taking the thread down`() {
        val source = FakeMicSource(script = silenceThenSpeech(totalMs = 400))
        val capture = MicCapture(source = source)
        capture.start(AudioListener { throw IllegalStateException("engine exploded") })
        val deadline = System.currentTimeMillis() + 5_000
        while (capture.failure == null && System.currentTimeMillis() < deadline) Thread.sleep(20)
        capture.stop()
        assertNotNull("a throwing listener was not recorded", capture.failure)
        assertEquals(
            "the recorded failure should be the listener's own",
            "engine exploded",
            capture.failure!!.message,
        )
    }

    @Test
    fun `a suppressor that throws while draining is reported and still ends the take`() {
        // The drain is the one thing the capture thread does on its way out
        // that runs somebody else's code: the suppressor is a pluggable seam
        // and may be native. Everything else on the exit path is arithmetic on
        // buffers this module owns.
        //
        // So the drain is where a take can end with the session flag still up.
        // The dispatcher reads that flag to decide the take is over, and a
        // take whose flag never drops is a take that never ends: the caller
        // waits on a capture that is finished, and nothing anywhere says why.
        // That is the whole shape of this failure, and it is why both halves
        // are asserted — the exception recorded AND the flag dropped. Either
        // one alone would pass against half the bug.
        //
        // The loop is ended by an interrupt rather than by a device failure on
        // purpose. A device failure records itself in the failure slot before
        // the drain ever runs, so the drain's exception would find the slot
        // already full and the assertion below could not tell which of the two
        // it read. An interrupt is the other normal exit: nothing recorded, and
        // straight to the drain with the flag still up.
        val suppressor = DrainThrowingSuppressor()
        val session = AtomicLong(CAPTURE_SESSION)
        val running = AtomicBoolean(true)
        val failureRef = AtomicReference<Throwable?>(null)
        val ring = PcmRingBuffer(capacitySamples = MicCapture.DEFAULT_BUFFER_SAMPLES)
        val capture = CaptureLoop(
            source = InterruptingSource(script = speech(READ_BUFFER_SAMPLES * 3)),
            pipeline = CapturePcmPipeline(
                channelCount = 1,
                sampleRateHz = AudioFormat.SAMPLE_RATE_HZ,
                suppressor = suppressor,
            ),
            running = running,
            session = session,
            failureRef = failureRef,
            readBufferSamples = READ_BUFFER_SAMPLES,
        )

        // Run it off the test's own thread: an escaped throw must be something
        // this test can ASSERT about rather than something that aborts it,
        // because both halves of the claim have to be reported together.
        val escaped = AtomicReference<Throwable?>(null)
        val captureThread = Thread({
            try {
                capture.run(ring, CAPTURE_SESSION)
            } catch (e: Throwable) {
                escaped.set(e)
            }
        })
        captureThread.start()
        captureThread.join(JOIN_TIMEOUT_MS)

        // The fixture itself, checked before either claim: the throw has to be
        // the drain's and only the drain's, or the test is asserting nothing
        // about the drain.
        assertTrue(
            "the suppressor saw no full-size frame at all, so this test never " +
                "reached the drain; it saw frames of ${suppressor.seenSizes}",
            suppressor.seenSizes.any { it >= READ_BUFFER_SAMPLES },
        )
        assertTrue(
            "the suppressor never saw a short frame, so the drain never " +
                "arrived; it saw frames of ${suppressor.seenSizes}",
            suppressor.seenSizes.any { it < MicCapture.DEFAULT_FRAME_SAMPLES },
        )

        assertNull(
            "the exception from the drain escaped the capture thread entirely " +
                "instead of being recorded, so a caller reading failure would " +
                "see a null and believe the take ended cleanly: ${escaped.get()}",
            escaped.get(),
        )
        assertTrue(
            "the capture thread never finished, so the take is still being " +
                "read ${JOIN_TIMEOUT_MS}ms after the drain threw",
            !captureThread.isAlive,
        )
        assertNotNull(
            "the drain's exception was swallowed: the take ended with nothing " +
                "recorded, which is the same invisible failure with the thread " +
                "no longer stuck",
            failureRef.get(),
        )
        assertTrue(
            "the recorded failure should be the suppressor's own, was: " +
                "${failureRef.get()}",
            failureRef.get()!!.message!!.contains(DRAIN_FAILURE_MESSAGE),
        )
        // And the flag. A recorded failure is not enough: the dispatcher reads
        // the flag to decide the take is over, so a thread that records and
        // then dies with the flag up has still left the caller waiting.
        assertFalse(
            "the drain threw and the capture flag was never dropped, so the " +
                "take can never end: the dispatcher reads this flag to decide " +
                "the take is finished",
            running.get(),
        )
    }

    @Test
    fun `a buffer smaller than a frame is refused at construction`() {
        try {
            MicCapture(
                source = FakeMicSource(script = speech(320)),
                frameSamples = 320,
                bufferSamples = 160,
            )
            fail("expected a buffer smaller than one frame to be refused")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "the message should name both sizes, was: ${e.message}",
                e.message!!.contains("frame"),
            )
        }
    }

    @Test
    fun `a frame size of zero is refused at construction`() {
        try {
            MicCapture(source = FakeMicSource(script = speech(320)), frameSamples = 0)
            fail("expected a zero frame size to be refused")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("frameSamples"))
        }
    }

    private companion object {
        /**
         * A whole number of capture reads' worth of audio, so the script is
         * spent exactly as the last read hands it over and the capture thread
         * sees its end rather than a short tail read.
         */
        const val READ_BUFFER_SAMPLES = 1_280

        /** The take this test's loop belongs to. Any value; only equality matters. */
        const val CAPTURE_SESSION = 7L

        const val JOIN_TIMEOUT_MS = 5_000L
    }
}

/** The message the drain-throwing suppressor fails with, so a test can name it. */
private const val DRAIN_FAILURE_MESSAGE = "the noise suppressor failed"

/**
 * A suppressor that works normally until the drain arrives, then throws.
 *
 * The drain is the last thing the pipeline does and the only call a capture
 * makes with a frame SHORTER than a full read, because it is the fraction of
 * one read the resampler was still holding back. That shortness is how this
 * fixture tells the drain apart from ordinary processing, rather than counting
 * calls: a count would be a statement about this test's own arithmetic, while
 * the shortness is a property of the drain itself.
 */
private class DrainThrowingSuppressor : NoiseSuppressor {

    override val isActive: Boolean = true

    /** Every frame handed over, in order, so a test can prove the drain arrived. */
    val seenSizes: CopyOnWriteArrayList<Int> = CopyOnWriteArrayList()

    override fun process(frame: FloatArray): FloatArray {
        seenSizes.add(frame.size)
        if (frame.size < MicCapture.DEFAULT_FRAME_SAMPLES) {
            throw MicSourceException(DRAIN_FAILURE_MESSAGE)
        }
        return frame.copyOf()
    }

    override fun reset() = Unit
}

/**
 * A device whose reads are interrupted once the script is spent.
 *
 * The interrupt is what ends the capture loop through its normal path without
 * recording anything, which is what leaves the drain free to be the only
 * failure in the take. A device that FAILED would have recorded itself first
 * and the test could no longer tell whose exception it was reading.
 */
private class InterruptingSource(
    private val script: FloatArray,
    override val sampleRateHz: Int = AudioFormat.SAMPLE_RATE_HZ,
    override val channelCount: Int = 1,
) : MicSource {

    private var offset = 0

    override fun open() {
        offset = 0
    }

    override fun read(buffer: ShortArray, offsetInBuffer: Int, lengthInShorts: Int): Int {
        if (offset >= script.size) {
            Thread.currentThread().interrupt()
            throw InterruptedException("the capture was interrupted at the end of the script")
        }
        val count = minOf(lengthInShorts, script.size - offset)
        for (i in 0 until count) {
            buffer[offsetInBuffer + i] = (script[offset + i] * Short.MAX_VALUE).toInt().toShort()
        }
        offset += count
        return count
    }

    override fun close() = Unit
}
