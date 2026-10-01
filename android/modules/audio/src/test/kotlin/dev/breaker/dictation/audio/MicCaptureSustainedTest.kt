package dev.breaker.dictation.audio

import dev.breaker.dictation.core.model.AudioFormat
import dev.breaker.dictation.core.port.AudioListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sustained capture: a long take arriving whole and in order, a consumer too
 * slow to keep up, and what that costs.
 */
class MicCaptureSustainedTest {

    @Test
    fun `a 60 second capture delivers every sample with no drop and no gap`() {
        // The card's "continuous 60 s without glitches" invariant, as far as a
        // JVM test can hold it: the pipeline is fed 60 s of audio and every
        // sample must come out, in order, with nothing dropped.
        val seconds = 60
        val sampleCount = seconds * AudioFormat.SAMPLE_RATE_HZ
        val consumerHas = AtomicInteger(0)
        val source = FakeMicSource(
            script = rampOf(sampleCount),
            samplesPerRead = 3_200,
            pacedBy = consumerHas::get,
            holdsOpenWhenScriptSpent = true,
        )
        val capture = MicCapture(source = source, bufferSamples = 64_000)
        val received = ArrayList<FloatArray>()
        val sampleBudgetMs = 60_000L

        capture.start(AudioListener { frame ->
            synchronized(received) { received.add(frame) }
            consumerHas.addAndGet(frame.size)
        })
        // The take is waited for on the DEVICE handing over its script, not on
        // a frame count. A frame count would be waiting for the last of the
        // take's audio to come back out, and the last of it is held by the
        // pipeline — the resampler keeps back the forward half of its window
        // because in the middle of a take those samples are one more read away,
        // and at the end of a take they are released only as the session ends.
        // So a take that ended by itself is a take that ends by concluding the
        // device is dead, which is a failure, and waiting on a frame that can
        // only arrive through a clean stop() waits for the thing the stop does.
        //
        // Waiting on the script instead is the device saying its part is over,
        // and stopping there is the caller doing what a caller does. The tail
        // the pipeline was holding is then released by the same teardown, and
        // the assertions below are free to ask for every sample of the take
        // rather than the ones that happened to come out before the end.
        //
        // The device is not dead while it waits, only idle: it reports nothing
        // rather than inventing audio, so a stop() is what ends the take and
        // nothing in this device can end it on the capture's own.
        assertTrue(
            "the fake did not hand over its whole script within 60 s",
            source.scriptSpent.await(sampleBudgetMs, TimeUnit.MILLISECONDS),
        )
        capture.stop()

        val frames = synchronized(received) { received.toList() }
        val deliveredSamples = frames.sumOf { it.size }
        assertEquals(
            "the take was $sampleCount samples of audio but $deliveredSamples arrived, " +
                "so ${sampleCount - deliveredSamples} samples of the 60 s were lost " +
                "rather than delivered",
            sampleCount,
            deliveredSamples,
        )
        assertEquals(
            "the ring buffer dropped ${capture.droppedSamples} samples during a " +
                "capture with a listener that keeps up",
            0L,
            capture.droppedSamples,
        )

        // The ramp encodes its own position, so every delivered sample is
        // checked against the value its own index calls for rather than
        // against the sample before it. The index is the running count of
        // samples delivered so far: frames are appended to `frames` in the
        // order the listener was called, and the listener is called once per
        // frame in order, so walking the frames in order and counting samples
        // as they go reconstructs the position each sample should occupy in
        // the take. A gap, a duplicate or a reordering all move a sample away
        // from the value its index names, so the expected value is exact and
        // the only tolerance is the 16-bit rounding the pipeline puts every
        // sample through on its way from the device's shorts to the float the
        // listener sees.
        var index = 0
        var mismatches = 0
        var firstMismatch = -1
        var firstActual = 0f
        var firstExpected = 0f
        for (frame in frames) {
            for (sample in frame) {
                val expected = rampValueAt(index)
                if (kotlin.math.abs(sample - expected) > SHORT_ROUNDING_TOLERANCE) {
                    if (firstMismatch < 0) {
                        firstMismatch = index
                        firstActual = sample
                        firstExpected = expected
                    }
                    mismatches++
                }
                index++
            }
        }
        assertEquals(
            "the delivered audio has $mismatches samples that are not the value " +
                "their own index calls for, so samples were dropped, duplicated " +
                "or reordered inside the ring buffer; the first was at index " +
                "$firstMismatch, expected $firstExpected but got $firstActual",
            0,
            mismatches,
        )
        // Checked last, after the audio itself, so a take that arrived whole
        // and in order reports as such even when the session left a thread
        // behind — the audio assertions are the ones that describe the
        // invariant, and a stop that gave up waiting says which thread.
        assertNull(
            "the 60 s take arrived whole, but the capture did not shut down " +
                "cleanly: ${capture.failure}",
            capture.failure,
        )
    }

    @Test
    fun `a slow consumer loses the oldest audio and is told how much`() {
        // The alternative is a stalled capture thread, which the platform turns
        // into invisible audio loss. Here the loss is counted.
        val source = FakeMicSource(script = rampOf(64_000), samplesPerRead = 3_200)
        val capture = MicCapture(
            source = source,
            frameSamples = 320,
            bufferSamples = 1_600,
        )
        val delivered = AtomicInteger()
        capture.start(AudioListener {
            delivered.incrementAndGet()
            Thread.sleep(4)
        })
        val deadline = System.currentTimeMillis() + 5_000
        while (delivered.get() < 8 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        capture.stop()

        assertTrue(
            "a consumer sleeping 4 ms per 320-sample frame should have outrun a " +
                "1600-sample buffer, but nothing was dropped",
            capture.droppedSamples > 0,
        )
    }
}
