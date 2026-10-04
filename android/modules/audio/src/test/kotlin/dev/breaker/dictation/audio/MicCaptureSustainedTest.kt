package dev.breaker.dictation.audio

import dev.breaker.dictation.core.model.AudioFormat
import dev.breaker.dictation.core.port.AudioListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
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
        //
        // ### How the consumer is made slow
        //
        // By a gate, not by a pace. The consumer blocks inside its first
        // callback until this test opens the gate, and the gate is opened only
        // after the DEVICE says it has handed over its whole script — its
        // scriptSpent latch, which is the one signal on the device side that
        // means the take is complete. So the loss is not something a
        // scheduling race produced: the device produced all 64 000 samples into
        // a 1600-sample buffer while the consumer provably had taken none of
        // them, and the arithmetic below says how much of that had nowhere to
        // go.
        //
        // A consumer that slept a fixed time per frame instead would make the
        // same claim by hoping: whether it outran the buffer would depend on
        // how fast the machine scheduled two threads against each other, and
        // the assertion would have to be "something was dropped" because
        // nothing could say how much. Here the amount is derived from the
        // sizes, so the assertion can be an exact one.
        //
        // The device holds the take open once its script is spent rather than
        // reporting a dead one. That keeps the take from ending by itself: the
        // only thing that can end it is the stop below, so the count read
        // afterwards is the loss this construction caused rather than a loss
        // mixed in with a capture that gave up on a device it decided was
        // silent.
        val producedSamples = 64_000
        val frameSamples = 320
        val bufferSamples = 1_600
        val source = FakeMicSource(
            script = rampOf(producedSamples),
            samplesPerRead = 3_200,
            holdsOpenWhenScriptSpent = true,
        )
        val capture = MicCapture(
            source = source,
            frameSamples = frameSamples,
            bufferSamples = bufferSamples,
        )
        val consumerMayProceed = CountDownLatch(1)
        val delivered = AtomicInteger()
        capture.start(AudioListener { frame ->
            delivered.addAndGet(frame.size)
            // Bounded, so a test that fails before opening the gate reports its
            // own failure instead of leaving the dispatcher parked forever.
            consumerMayProceed.await(WAIT_SECONDS, TimeUnit.SECONDS)
        })

        assertTrue(
            "the fake did not hand over its whole $producedSamples-sample script, so " +
                "the consumer was never held off long enough for the buffer to overrun " +
                "and this never got into the state it is about",
            source.scriptSpent.await(WAIT_SECONDS, TimeUnit.SECONDS),
        )
        consumerMayProceed.countDown()
        capture.stop()

        // ### The floor
        //
        // Somewhere other than the drop counter, a sample can only be in one
        // of six places, and each has a size the code fixes rather than the
        // schedule. Each term below names the line that fixes it:
        //
        //   1600  the ring, which never holds more than its capacity
        //   +1280 the capture thread's own read buffer, holding one read of
        //         the device that has not been converted, downmixed,
        //         resampled, suppressed or written yet. CaptureLoop.kt:30
        //         allocates it from CaptureSessionLifecycle.kt:490-493,
        //         which sizes it at DEFAULT_FRAME_SAMPLES *
        //         READ_BUFFER_MULTIPLIER, so 320 * 4. The device hands over
        //         at most min(lengthInShorts, samplesPerRead, remaining), so
        //         this buffer's own 1280 binds, not samplesPerRead's 3200.
        //   +640  the dispatcher's read buffer, which it fills with at most two
        //         frames per read of the ring (DispatchLoop.kt:30)
        //   +320  the frame it has partly filled and not yet handed over
        //         (DispatchLoop.kt:31)
        //   +320  the frame the consumer was given and is sitting in, since
        //         every callback blocks on the gate this test has not opened
        //   +16   the resampler's held-back tail: the next read point and the
        //         half kernel ahead of it that no output has claimed, kept
        //         for the next call (AudioResampler.kt:255, with half =
        //         taps / 2 over DEFAULT_TAPS of 32, so 16).
        //         CapturePcmPipeline.kt:39-47 documents the same held-back
        //         samples from the other side: they are samples the device
        //         produced and the listener was promised.
        //   ----
        //   4176  samples that can have escaped being dropped
        //
        // The device produced 64 000 and the drop counter only ever grows, so
        // once the whole script is over at least 64 000 - 4 176 = 59 824
        // samples have been dropped. A smaller count would be saying the buffer
        // held audio it had no room for.
        //
        // The floor, not an exact figure, on purpose: how much the dispatcher
        // managed to pull out between the consumer's first callback and the
        // consumer blocking is a race between two threads, and this test does
        // not need to win it to make its point. What it needs is for the loss
        // to follow from the sizes rather than from a delay. The exact
        // accounting is asserted separately below, where nothing is racing.
        val ringSamples = bufferSamples
        val captureReadBufferSamples = 1_280
        val dispatcherReadBufferSamples = 2 * frameSamples
        val dispatcherPartialFrameSamples = frameSamples
        val consumerFrameSamples = frameSamples
        val resamplerHeldTailSamples = 16
        val inFlightOutsideTheCounter = ringSamples +
            captureReadBufferSamples +
            dispatcherReadBufferSamples +
            dispatcherPartialFrameSamples +
            consumerFrameSamples +
            resamplerHeldTailSamples
        val lossFloor = producedSamples - inFlightOutsideTheCounter
        assertTrue(
            "a consumer held off until the device had produced all $producedSamples " +
                "samples into a $bufferSamples-sample buffer lost only " +
                "${capture.droppedSamples} of them, but at most " +
                "$inFlightOutsideTheCounter could have escaped being dropped " +
                "($ringSamples in the ring, $captureReadBufferSamples in the capture " +
                "thread's own read buffer, $dispatcherReadBufferSamples in the " +
                "dispatcher's read, $dispatcherPartialFrameSamples in its part-filled " +
                "frame, $consumerFrameSamples in the frame the consumer was given, " +
                "$resamplerHeldTailSamples in the resampler's held-back tail), so the " +
                "loss has to be at least $lossFloor. A consumer that cannot keep up " +
                "loses the oldest audio, and this capture has to say how much",
            capture.droppedSamples >= lossFloor,
        )
        // And the count is the whole truth rather than a lower bound that
        // could hide unaccounted samples. Every sample the device produced
        // either reached the listener or was counted as dropped: the ring
        // counts on the way in and keeps everything it does not drop, and the
        // stop drains what is left into the take's last short frame.
        assertEquals(
            "the take lost audio without accounting for it: the device produced " +
                "$producedSamples samples, $delivered reached the listener and " +
                "${capture.droppedSamples} were counted as dropped, which is " +
                "${delivered.get() + capture.droppedSamples}. A capture that reports a " +
                "loss must report all of it, or a caller is left with a recording that " +
                "sounds complete",
            producedSamples.toLong(),
            delivered.get() + capture.droppedSamples,
        )
    }
}
