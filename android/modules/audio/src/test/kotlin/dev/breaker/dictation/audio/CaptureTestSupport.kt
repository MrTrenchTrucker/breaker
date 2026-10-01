package dev.breaker.dictation.audio

import dev.breaker.dictation.core.model.AudioFormat
import dev.breaker.dictation.core.port.AudioListener
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * Shared fixtures for the capture tests: the scripts they feed the fake device,
 * the waits that put a test at a particular instant of a take, and the signals
 * whose every sample says where it belongs.
 *
 * Every capture test needs the same three things — audio to capture, a way to
 * collect what comes back, and a bounded wait that stops rather than hangs the
 * suite — so they live here rather than being restated per test class.
 */

/** Speech for [totalMs], so the VAD and suppressor have something to chew on. */
internal fun silenceThenSpeech(totalMs: Long): FloatArray {
    val total = (totalMs * AudioFormat.SAMPLE_RATE_HZ / 1000L).toInt()
    val pcm = FloatArray(total)
    AudioSignals.speech(total / 2).copyInto(pcm, 0)
    return pcm
}

internal fun speech(samples: Int): FloatArray = AudioSignals.speech(samples)

internal fun collectFrames(capture: MicCapture, expectedFrames: Int): List<FloatArray> =
    collect(capture, expectedFrames) { it }

internal fun collectFrameSizes(capture: MicCapture, expectedFrames: Int): List<Int> =
    collect(capture, expectedFrames) { it.size }

/**
 * Waits for a capture to end by itself, and reports nothing.
 *
 * The only end-of-capture signal on the listener side is
 * [MicCapture.isCapturing] dropping, which is a statement about the device
 * no longer being read and nothing at all about the session being torn
 * down — the device is still open at that point, and the take's last
 * frame is still behind the dispatcher's read. That gap is what the tests
 * that call [MicCapture.stop] afterwards are here to pin, so this waits
 * for exactly that signal and no further: it never waits for the
 * dispatcher, because the dispatcher is the thing the caller is supposed
 * to be getting back from stop().
 */
internal fun awaitSelfEnded(capture: MicCapture) {
    val deadline = System.currentTimeMillis() + SELF_END_TIMEOUT_MS
    while (capture.isCapturing && System.currentTimeMillis() < deadline) {
        Thread.sleep(2)
    }
    assertFalse(
        "the capture was still reading after ${SELF_END_TIMEOUT_MS}ms, so the " +
            "test never reached the state it is about",
        capture.isCapturing,
    )
}

/**
 * The capture's own threads that are running right now.
 *
 * Found by name rather than by a handle the capture hands out, because
 * what is under test is that no thread is left behind — a caller cannot
 * join a thread it was never given, so the caller can only check that
 * there is nothing left to wait for. Only these two names count: another
 * test's straggler is not this session's problem, and matching on "any
 * live thread" would report other captures' threads as this one's.
 */
internal fun liveSessionThreads(): List<Thread> =
    Thread.getAllStackTraces().keys.filter {
        it.isAlive &&
            (it.name == MicCapture.CAPTURE_THREAD_NAME ||
                it.name == MicCapture.DISPATCH_THREAD_NAME)
    }.toList()

/**
 * Collects until the capture ends by itself rather than being stopped.
 *
 * A test that stops the capture the moment it holds the frames it expects
 * races whatever the dispatcher had not delivered yet: a frame already in
 * flight when [MicCapture.stop] runs can still land, and a third frame in
 * the list is enough for a "the last frame is short" assertion to be about
 * the wrong frame. Letting the take run to its own end removes the race —
 * the tail is the tail because it is the last frame the take ever
 * produced, not because the test happened to look at the right instant.
 *
 * The end of a capture is the only signal [MicCapture] offers on the
 * listener side: [AudioListener] carries frames and nothing else, so there
 * is no per-frame latch to count down. [MicCapture.isCapturing] dropping
 * to false is that signal — the device is no longer being read. It is not a
 * signal that the session is over: the device is still open and the
 * take's last frame is still behind the dispatcher's next read at that
 * moment, which is why [MicCapture.stop] has to close and join rather than
 * return, and why the frame count this returns is exact rather than
 * approximate. The wait is bounded so a capture that never ends is stopped
 * and reported rather than hanging the suite.
 */
internal fun collectFrameSizesUntilCaptureEnds(
    capture: MicCapture,
    expectedFrames: Int,
): List<Int> {
    val collected = CopyOnWriteArrayList<Int>()
    capture.start(AudioListener { collected.add(it.size) })
    val deadline = System.currentTimeMillis() + SELF_END_TIMEOUT_MS
    while (capture.isCapturing && System.currentTimeMillis() < deadline) {
        Thread.sleep(2)
    }
    capture.stop()
    assertTrue(
        "the capture was still running after ${SELF_END_TIMEOUT_MS}ms, so the " +
            "frames it would have produced are not in the list: $collected",
        collected.size >= expectedFrames,
    )
    return collected.toList()
}

private fun <T> collect(
    capture: MicCapture,
    expectedFrames: Int,
    project: (FloatArray) -> T,
): List<T> {
    val collected = CopyOnWriteArrayList<T>()
    capture.start(AudioListener { collected.add(project(it)) })
    val deadline = System.currentTimeMillis() + 15_000
    while (collected.size < expectedFrames && System.currentTimeMillis() < deadline) {
        Thread.sleep(5)
    }
    capture.stop()
    return collected.toList()
}

/**
 * The ramp's step, and so the resolution at which a sample says where
 * it belongs. Big enough that the ramp stays inside the -1..1 a float
 * sample is allowed to occupy over its whole period, and far enough
 * above the 16-bit grid a real device rounds to that a sample shifted
 * by a single position is not the value its index names.
 */
internal const val RAMP_STEP = 1e-3f

/** How long the ramp takes to come back round to its first value. */
internal const val RAMP_PERIOD = 997

/**
 * The frame size and take length the short-tail test uses.
 *
 * 100 samples in 64-sample frames is deliberately not a whole number of
 * frames, so the take has a tail to be short about: one whole frame and
 * a 36-sample remainder. A length that divided evenly would have no tail
 * and the contract would go untested.
 */
internal const val FRAME_SAMPLES = 64
internal const val TAIL_SAMPLES = 100

/**
 * How long a take that is expected to end by itself is given to end.
 *
 * Generous, because the device in these tests does not stop when the
 * script runs out: it reports nothing more, and the capture only
 * concludes from that silence. This is a ceiling on a wait that
 * normally returns in a few hundred milliseconds, not an expectation
 * that it takes this long — a take that is still running when the
 * ceiling passes has its frames reported as short rather than hanging
 * the suite.
 */
internal const val SELF_END_TIMEOUT_MS = 10_000L

/**
 * How far a sample may sit from the value its index calls for.
 *
 * A device hands audio over as 16-bit shorts and the pipeline hands
 * the listener floats, so a value goes out as `v * 32767` truncated to
 * a short and comes back as that short over 32768. Truncation costs up to
 * one 1/32768, and the 32767/32768 scaling costs a further
 * `v / 32768` that grows with the sample, so at the top of this ramp
 * the two together come to about 6.1e-5. The bound allows for that
 * and no more: it is a thirteenth of a ramp step, so a sample that has
 * moved even one position in the take is still caught, while the
 * rounding the device's own format imposes is not mistaken for a
 * hole in the audio.
 */
internal const val SHORT_ROUNDING_TOLERANCE = 2.5f / 32_768f

/**
 * A signal whose every sample encodes its own index, so a dropped,
 * duplicated or reordered sample is detectable by looking at what a
 * sample says about where it belongs.
 */
internal fun rampOf(samples: Int): FloatArray = FloatArray(samples) { rampValueAt(it) }

/** The value the ramp holds at [index]. */
internal fun rampValueAt(index: Int): Float = (index % RAMP_PERIOD) * RAMP_STEP

/**
 * A microphone whose [MicSource.open] waits on a latch the test opens.
 *
 * Opening a real device takes long enough — tens of milliseconds on a phone,
 * and a permission prompt on a cold start — that a caller's stop() can land in
 * the middle of it, and no fake that opens instantly can put a test inside that
 * window. It delegates everything else to a [FakeMicSource], so the audio a
 * test scripts is still the audio that would be captured.
 *
 * [insideOpen] counts down once the open is actually under way, so a test knows
 * it is in the window rather than hoping it is; [releaseOpen] is what lets the
 * open finish.
 */
internal class OpenGateSource(script: FloatArray) : MicSource {

    /** The device the audio really comes from. */
    val delegate = FakeMicSource(script = script)

    /** Counted down once [open] is under way and waiting. */
    val insideOpen = CountDownLatch(1)

    /** Counted down by the test to let [open] finish. */
    val releaseOpen = CountDownLatch(1)

    override val sampleRateHz: Int get() = delegate.sampleRateHz

    override val channelCount: Int get() = delegate.channelCount

    /** How many times [close] has run on the device underneath. */
    val closeCalls: Int get() = delegate.closeCalls

    override fun open() {
        insideOpen.countDown()
        // Bounded, so a test that fails before releasing the latch reports its
        // own failure instead of hanging the suite on a device that never opens.
        releaseOpen.await(WAIT_SECONDS, TimeUnit.SECONDS)
        delegate.open()
    }

    override fun read(buffer: ShortArray, offset: Int, lengthInShorts: Int): Int =
        delegate.read(buffer, offset, lengthInShorts)

    override fun close() = delegate.close()
}

/**
 * How long a test waits for something a background thread has to do.
 *
 * A ceiling, not an expectation: every wait on one of these is a loop that ends
 * as soon as the thing happens, and this only decides how long a test that
 * never gets there takes to say so.
 */
internal const val WAIT_SECONDS = 10L
