package dev.breaker.dictation.audio

import dev.breaker.dictation.core.port.AudioListener
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The port contract: what comes out of a capture, in what shape, and when.
 *
 * The device itself is a [FakeMicSource] — a script of samples the test
 * controls — so these tests say what the pipeline does with audio, not what a
 * particular phone's microphone does.
 */
class MicCaptureTest {

    // ── the port contract ───────────────────────────────────────────────

    @Test
    fun `capture delivers 16 kHz mono float frames to the listener`() {
        val source = FakeMicSource(script = silenceThenSpeech(totalMs = 400))
        val capture = MicCapture(source)
        val frames = collectFrames(capture, expectedFrames = 18)

        assertTrue("no frames were delivered", frames.isNotEmpty())
        frames.forEach { frame ->
            assertEquals(
                "frames should be ${MicCapture.DEFAULT_FRAME_SAMPLES} samples",
                MicCapture.DEFAULT_FRAME_SAMPLES,
                frame.size,
            )
            frame.forEach { sample ->
                assertTrue(
                    "a sample was $sample, which is not 16-bit float PCM in -1..1",
                    sample >= -1f && sample <= 1f,
                )
            }
        }
        assertEquals(
            "the frames should carry the speech that was captured",
            true,
            frames.any { AudioSignals.rms(it) > AudioSignals.NOISE_AMPLITUDE * 0.5f },
        )
    }

    @Test
    fun `start returns without waiting for audio and never calls the listener on its own thread`() {
        val source = FakeMicSource(script = silenceThenSpeech(totalMs = 300), readDelayMs = 5)
        val capture = MicCapture(source)
        val callerThread = Thread.currentThread()
        val listenerThread = CopyOnWriteArrayList<Thread>()
        val started = CountDownLatch(1)

        val began = System.nanoTime()
        capture.start(AudioListener { listenerThread.add(Thread.currentThread()) })
        val elapsedMs = (System.nanoTime() - began) / 1_000_000

        try {
            assertTrue(
                "start() blocked for ${elapsedMs}ms; it must return immediately and " +
                    "capture on its own thread",
                elapsedMs < 500,
            )
            started.countDown()
            // Give the capture thread the bounded moment it needs to deliver
            // its first frame. start() returns before any audio has moved, so
            // stopping in the same breath can tear the session down with the
            // listener never called - which says nothing about which thread
            // the listener runs on. The wait is a ceiling, not an expectation.
            val firstFrameBy = System.currentTimeMillis() + 10_000
            while (listenerThread.isEmpty() && System.currentTimeMillis() < firstFrameBy) {
                Thread.sleep(2)
            }
            capture.stop()

            assertTrue("the listener was never called", listenerThread.isNotEmpty())
            listenerThread.forEach { thread ->
                assertFalse(
                    "the listener ran on the caller's thread; capture must not " +
                        "deliver audio on the thread that called start()",
                    thread === callerThread,
                )
            }
        } finally {
            capture.stop()
        }
    }

    @Test
    fun `the last frame is short rather than padded with silence`() {
        // A padded final frame would invent audio the user never spoke, and the
        // domain treats a short frame as the signal that capture ended. So a
        // 100-sample take in 64-sample frames is one whole frame and a 36-sample
        // tail — the tail's size is a statement about how much audio the device
        // produced, and padding it out to 64 would state something untrue.
        val script = FakeMicSource(script = speech(TAIL_SAMPLES))
        val capture = MicCapture(source = script, frameSamples = FRAME_SAMPLES)
        val sizes = collectFrameSizesUntilCaptureEnds(capture, expectedFrames = 2)

        assertTrue("no frames arrived", sizes.isNotEmpty())
        // The whole take, and exactly it, at the instant stop() returned. The
        // device produced 100 samples, which in 64-sample frames is two frames
        // and not one and not three, so a stop() that returned with the first
        // frame already delivered and the tail still in flight would deliver
        // one here. The count is what makes this a claim about the take rather
        // than about the frames that happened to arrive first: a stop() that
        // settles is a stop() that let a caller read a take still growing.
        assertEquals(
            "stop() returned with $sizes; the device produced $TAIL_SAMPLES samples, " +
                "which is ${TAIL_SAMPLES / FRAME_SAMPLES} whole frame(s) and a " +
                "${TAIL_SAMPLES % FRAME_SAMPLES}-sample tail, so the take is " +
                "${TAIL_SAMPLES / FRAME_SAMPLES + 1} frames and all of them must " +
                "have been delivered by the time stop() returned",
            TAIL_SAMPLES / FRAME_SAMPLES + 1,
            sizes.size,
        )
        // Every frame but the last is a whole frame. Anything else would mean
        // the dispatcher split a frame it had the audio for.
        sizes.dropLast(1).forEach { size ->
            assertEquals("a frame before the last was not whole: $sizes", FRAME_SAMPLES, size)
        }
        // The last frame is short, and short rather than absent: a zero-length
        // frame would report a tail the listener cannot use, and a full one
        // would be the padding this test exists to rule out.
        val tail = sizes.last()
        assertTrue(
            "the last frame was $tail samples; it must be short rather than padded " +
                "to $FRAME_SAMPLES, and must carry the tail's audio: $sizes",
            tail in 1 until FRAME_SAMPLES,
        )
        // The three assertions above divide the tail up, and none of them is
        // the general guard against tail loss:
        //   the frame COUNT pins a lost WHOLE frame — a tail that takes a
        //     whole frame with it fails there first, and never reaches here;
        //   this SUM pins a PARTIAL tail loss — a tail that arrives short
        //     without taking a whole frame with it, so the count still comes
        //     out right while samples go missing;
        //   the short-tail assertion pins PADDING — a final frame stretched
        //     to full length out of silence the device never produced.
        // So the sum is read as: the frame that arrived carries the remainder
        // and nothing else, rather than standing in for samples that were
        // dropped or for samples that were made up.
        assertEquals(
            "the take delivered ${sizes.sum()} samples but the device produced " +
                "$TAIL_SAMPLES, so the tail was either padded or lost audio: $sizes",
            TAIL_SAMPLES,
            sizes.sum(),
        )
    }

    @Test
    fun `a capture of exactly a whole number of frames ends with a full frame`() {
        val capture = MicCapture(source = FakeMicSource(script = speech(320)), frameSamples = 320)
        val sizes = collectFrameSizes(capture, expectedFrames = 10)
        assertEquals(1, sizes.size)
        assertEquals(320, sizes[0])
    }

    @Test
    fun `a plain capture that is stopped cleanly records no failure`() {
        // The everyday case: a capture nobody misuses. A failure here means a
        // session thread was still running when stop() gave up waiting, which
        // is not a capture problem the caller did anything about and cannot be
        // told apart from a real driver failure by reading capture.failure.
        // Before the buffer's read stopped waiting forever, the dispatcher sat
        // in an empty read at the end of every take and stop() timed out on it,
        // so capture.failure was non-null after every capture that had ended
        // the ordinary way.
        val capture = MicCapture(FakeMicSource(script = silenceThenSpeech(totalMs = 300)))
        val frames = collectFrames(capture, expectedFrames = 10)
        val failure = capture.failure

        assertTrue("the capture produced no frames at all", frames.isNotEmpty())
        assertNull(
            "a capture that was stopped cleanly and had nothing wrong with it " +
                "reported a failure: $failure",
            failure,
        )
    }

    @Test
    fun `the sample rate of the frames is the one the port promises`() {
        val source = FakeMicSource(script = rampOf(16_000), sampleRateHz = 48_000)
        val capture = MicCapture(source = source)
        val frames = collectFrames(capture, expectedFrames = 40)
        // 16 k of input at 48 kHz is a third of a second, so about 16 frames of
        // 20 ms. A capture that ignored the resample would deliver 50.
        val total = frames.sumOf { it.size }
        assertTrue(
            "16 kHz mono at the frame rate the port promises should give about " +
                "${16_000 * 16_000 / 48_000} samples, got $total",
            total in 4_000..7_000,
        )
    }

    @Test
    fun `the frame size can be chosen and is honoured`() {
        val capture = MicCapture(
            source = FakeMicSource(script = rampOf(16_000)),
            frameSamples = 160,
        )
        val frames = collectFrames(capture, expectedFrames = 60)
        assertTrue(frames.isNotEmpty())
        frames.forEach { assertEquals(160, it.size) }
    }
}
