package dev.breaker.dictation.audio

import dev.breaker.dictation.core.model.AudioFormat
import dev.breaker.dictation.core.port.AudioListener
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a stop owes the take's tail.
 *
 * The last fraction of a millisecond a capture produces is held back by the
 * resampler — its window reaches half a kernel ahead of the read point — and
 * [CaptureLoop] recovers it with [CapturePcmPipeline.drainTail] as the take
 * ends. A take of 400 ms at 16 kHz is 6 400 samples; the held tail is 15 of
 * them.
 *
 * The drain runs on the capture thread's way out of its loop, and that thread
 * leaves the loop when the session flag drops. A stop that dropped the flag
 * itself, before the capture thread had drained, let the dispatcher — which
 * reads the same flag to decide the take is finished — find an empty ring and
 * conclude, so the tail was written to a ring nobody was reading and the take
 * came up short by exactly the held tail. Which thread reached the flag first
 * was a scheduling coin flip, so the symptom was a test that failed once and
 * passed on a re-run.
 *
 * These tests put an ordering point between the stop and the drain rather than
 * racing them. The device parks the capture thread inside the read that
 * follows its script — the read whose return would take the thread to the
 * drain — so a stop can be placed at exactly that instant, and the device's
 * close, which a stop reaches after it has decided what to do with the flag,
 * is the signal that it has.
 */
class MicCaptureStopTailTest {

    @Test
    fun `a stop does not end the take before the capture thread has drained its tail`() {
        val script = silenceThenSpeech(totalMs = 400)
        val source = ParkBeforeDrainSource(script)
        val indicator = RecordingIndicator()
        val capture = MicCapture(source = source, indicator = indicator)

        // The first start is the shape the flake was reported through: a
        // listener that throws on the way up, so start() unwinds and the
        // capture is started again below. It is here because the report was
        // made through it, and it must not change the answer — the ordering
        // this test is about is between the SECOND start's stop and its drain,
        // and a failed first start does not touch it.
        val offender: (Boolean) -> Unit = { if (it) throw IllegalStateException("boom") }
        indicator.addListener(offender)
        try {
            capture.start(AudioListener { })
        } catch (_: Throwable) {
            // The strand, if any, is asserted by the test this one regresses.
        }
        indicator.removeListener(offender)

        val frames = CopyOnWriteArrayList<FloatArray>()
        capture.start(AudioListener { frames.add(it) })

        // The device has handed over its whole script. The capture thread is
        // now inside the read that follows it — the read whose return would
        // take it to the loop's exit and its drain — and the resampler's held
        // tail has reached the listener not at all. This is the instant a stop
        // must survive.
        assertTrue(
            "the capture thread never reached the read after the script, so the " +
                "stop below is not arriving at the instant this test is about",
            source.parkedBeforeDrain.await(WAIT_SECONDS, TimeUnit.SECONDS),
        )

        val stopReturned = CountDownLatch(1)
        Thread({
            capture.stop()
            stopReturned.countDown()
        }, "stop-under-test").apply { isDaemon = true }.start()

        try {
            // The device is closed by a stop after it has decided what to do
            // with the session flag — on both the fixed and the unfixed code —
            // so waiting for the close puts this test at exactly the point the
            // two differ, with no clock involved.
            assertTrue(
                "stop() never reached the device, so the ordering this test is " +
                    "about never happened",
                source.closed.await(WAIT_SECONDS, TimeUnit.SECONDS),
            )

            // The claim. The capture thread is still holding the tail, so the
            // take is not over: the flag the dispatcher reads to decide the
            // take is finished must still be up. A stop that drops it here lets
            // the dispatcher finish on an empty ring, and the take comes up
            // short by exactly the held tail — 15 samples of this 400 ms take —
            // whenever the dispatcher's read wins the race.
            assertTrue(
                "the capture was declared over while the thread that must recover " +
                    "the take's tail was still inside its last read " +
                    "(isCapturing=${capture.isCapturing}). The dispatcher reads that " +
                    "flag to decide the take is finished, so dropping it before the " +
                    "drain lets the dispatcher finish on an empty ring and the take " +
                    "loses the resampler's held tail",
                capture.isCapturing,
            )

            // Let the capture thread reach its drain. The whole take must then
            // arrive, tail included.
            source.releaseBeforeDrain.countDown()
            assertTrue(
                "stop() never returned after the capture thread was released, so " +
                    "the take was never finished",
                stopReturned.await(WAIT_SECONDS, TimeUnit.SECONDS),
            )

            assertEquals(
                "the start came to ${frames.sumOf { it.size }} samples but the " +
                    "device produced ${source.script.size}, so the tail the " +
                    "resampler was still holding when the stop arrived was lost",
                source.script.size,
                frames.sumOf { it.size },
            )
        } finally {
            // Never leave the capture thread parked: a failure above must not
            // strand a thread the next test would have to share a JVM with.
            source.releaseBeforeDrain.countDown()
        }
    }

    @Test
    fun `a stop that gives up on a stuck capture thread still reports the capture stopped`() {
        // The flag must be DOWN when stop() returns even if the capture thread
        // never got the chance to drop it. A capture thread parked inside a read
        // that will not return is joined for joinTimeoutMs and then given up on;
        // stop() has told the caller the capture is stopped, so isCapturing has
        // to say so. stop() drops the flag itself on that path, because the
        // thread that would normally drop it is the one that is stuck.
        val script = silenceThenSpeech(totalMs = 400)
        val source = ParkBeforeDrainSource(script)
        val capture = MicCapture(source = source, joinTimeoutMs = 150L)

        capture.start(AudioListener { })
        assertTrue(
            "the capture thread never reached the read after the script, so it " +
                "is not parked where this test needs it",
            source.parkedBeforeDrain.await(WAIT_SECONDS, TimeUnit.SECONDS),
        )
        // A handle to join on afterwards, taken while the thread is provably
        // running, so the cleanup is a join rather than a wait on a clock.
        val captureThread = liveSessionThreads()
            .firstOrNull { it.name == MicCapture.CAPTURE_THREAD_NAME }

        capture.stop()

        assertFalse(
            "stop() returned with isCapturing still true after giving up on a " +
                "capture thread parked in a read that will not return. A caller " +
                "told the capture stopped is holding one it cannot restart, and " +
                "has no handle to fix it with",
            capture.isCapturing,
        )

        // Let the parked read finish so the thread can retire, then join it.
        source.releaseBeforeDrain.countDown()
        captureThread?.join(WAIT_SECONDS * 1000)
    }
}

/**
 * A device that hands over its script and then parks the capture thread inside
 * the read that follows, until the test releases it.
 *
 * The park is the ordering point. It holds the capture thread at the exact
 * instant before [CaptureLoop] leaves its loop and recovers the take's tail, so
 * a test can place a stop there without racing it.
 *
 * [parkedBeforeDrain] counts down once the thread is inside that read;
 * [releaseBeforeDrain] is what lets it finish; [closed] counts down when the
 * device is closed, which a stop does after it has decided what to do with the
 * session flag.
 */
internal class ParkBeforeDrainSource(
    val script: FloatArray,
    override val sampleRateHz: Int = AudioFormat.SAMPLE_RATE_HZ,
    override val channelCount: Int = 1,
) : MicSource {

    private val delegate = FakeMicSource(script = script)

    /** Counted down once the capture thread is parked in the read after the script. */
    val parkedBeforeDrain = CountDownLatch(1)

    /** Counted down by the test to let the parked read finish. */
    val releaseBeforeDrain = CountDownLatch(1)

    /** Counted down when the device is closed. */
    val closed = CountDownLatch(1)

    override fun open() = delegate.open()

    override fun read(buffer: ShortArray, offset: Int, lengthInShorts: Int): Int {
        val read = delegate.read(buffer, offset, lengthInShorts)
        if (read > 0) return read
        // The script is spent. Park here: this is the read whose return takes
        // the capture thread to the loop's exit and its drain.
        parkedBeforeDrain.countDown()
        releaseBeforeDrain.await(WAIT_SECONDS, TimeUnit.SECONDS)
        return 0
    }

    override fun close() {
        closed.countDown()
        delegate.close()
    }
}
