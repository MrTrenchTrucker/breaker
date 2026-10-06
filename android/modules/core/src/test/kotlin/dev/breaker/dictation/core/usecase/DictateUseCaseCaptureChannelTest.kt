package dev.breaker.dictation.core.usecase

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.AudioFormat
import dev.breaker.dictation.core.model.DictationResult
import dev.breaker.dictation.core.model.DictationSession
import dev.breaker.dictation.core.model.SttMode
import dev.breaker.dictation.core.testing.FakeAudioSource
import dev.breaker.dictation.core.testing.FakeWavEncoder
import dev.breaker.dictation.core.testing.FixedClock
import dev.breaker.dictation.core.testing.InMemorySettingsStore
import dev.breaker.dictation.core.testing.PassThroughFormatter
import dev.breaker.dictation.core.testing.RecordingSttEngine
import dev.breaker.dictation.core.testing.ScriptedConnectivityProbe
import dev.breaker.dictation.core.testing.SequentialIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capture buffer is a channel: the capture thread appends frames and the
 * draining thread takes them out in one go. These tests hold the guarantee that
 * migration must keep — every frame the source produced reaches the engine in
 * order, nothing is lost or duplicated across the cut, a fresh start or a cancel
 * leaves no audio behind, and each captured frame is a copy, not the caller's
 * array. Each one is written to fail on the regression it guards against.
 */
class DictateUseCaseCaptureChannelTest {
    private val clock = FixedClock()
    private val ids = SequentialIds()
    private val local = RecordingSttEngine.succeeding("local words")
    private val server = RecordingSttEngine.succeeding("server words")

    private fun useCase() = DictateUseCase(
        settings = InMemorySettingsStore(AppSettings(mode = SttMode.LOCAL)),
        probe = ScriptedConnectivityProbe(reachable = false),
        localEngine = local,
        serverEngine = server,
        serverFormatter = PassThroughFormatter,
        wavEncoder = FakeWavEncoder,
        clock = clock,
        ids = ids,
        localFormatter = PassThroughFormatter,
    )

    private fun recording() = DictationSession().arm().startRecording()

    /** A frame of [size] samples, all equal to [value], so order is checkable. */
    private fun frame(size: Int, value: Float) = FloatArray(size) { value }

    @Test
    fun `every frame the source produced reaches the engine in order`() {
        val useCase = useCase()
        val audio = FakeAudioSource()
        val session = useCase.startCapture(recording(), audio)

        // Three frames of 100 samples, each a distinct value, so a reordering or
        // a dropped frame shows up in the flattened audio the engine receives.
        audio.emit(frame(100, 0.10f))
        audio.emit(frame(100, 0.11f))
        audio.emit(frame(100, 0.12f))

        val result = useCase.stopCapture(session, audio) as DictationResult.Success
        val pcm = local.requests.single().pcm

        assertEquals("all three frames must reach the engine", 300, pcm.size)
        // The cut must keep order: frame 0, then frame 1, then frame 2.
        assertEquals(0.10f, pcm[0], 0f)
        assertEquals(0.10f, pcm[99], 0f)
        assertEquals(0.11f, pcm[100], 0f)
        assertEquals(0.11f, pcm[199], 0f)
        assertEquals(0.12f, pcm[200], 0f)
        assertEquals(0.12f, pcm[299], 0f)
    }

    @Test
    fun `the frames a producer pushes are conserved across the cut, none lost none twice`() {
        val useCase = useCase()
        val audio = FakeAudioSource()
        val session = useCase.startCapture(recording(), audio)

        // N frames of 50 samples each. The producer pushes them on its own
        // thread while the main thread stops the capture, so the cut lands
        // somewhere in the middle of the push.
        val n = 8
        val frameSize = 50
        val producer = Thread(object : Runnable {
            override fun run() {
                repeat(n) { i -> audio.emit(frame(frameSize, 0.5f + i * 0.001f)) }
            }
        })
        producer.start()

        // Stop while the producer is still pushing: the cut is mid-stream. The
        // cut may take any number of frames (0 to N) depending on the race, so it
        // is read without a cast — an empty cut is a Failure, a non-empty one a
        // Success, and both are counted by the samples the engine actually got.
        val first = useCase.stopCapture(session, audio)
        // The producer has now pushed all N frames; whatever the first cut did
        // not take is still in the channel.
        producer.join()

        // A second cut (no fresh start in between, so nothing is discarded) takes
        // whatever the first cut left behind. Together the two cuts must account
        // for every frame the producer pushed.
        val second = useCase.stopCapture(recording(), audio)

        val firstSamples = if (first is DictationResult.Success) local.requests[0].pcm.size else 0
        val secondSamples = if (second is DictationResult.Success) local.requests[local.requests.size - 1].pcm.size else 0
        val total = n * frameSize

        // Conservation: every frame the producer pushed is in exactly one of the
        // two cuts. A drain that loses a frame (clears without returning it, or a
        // bounded channel that drops a burst) makes the total fall short; a drain
        // that hands a frame to both cuts makes it exceed. A single-read drain
        // leaves the rest in the channel, so the total falls far short.
        assertEquals("frames are conserved across the cut, none lost and none twice", total, firstSamples + secondSamples)
    }

    @Test
    fun `a fresh start discards the previous capture's leftovers`() {
        val useCase = useCase()
        val audio = FakeAudioSource()

        // A first capture is started and a frame is emitted, but it is never
        // stopped or cancelled: the frame is still in the buffer when the next
        // capture begins.
        val first = useCase.startCapture(recording(), audio)
        audio.emit(frame(100, 0.20f))

        // A fresh start — a new RECORDING session, not derived from the first, so
        // nothing has drained the buffer yet — must discard that leftover before
        // the new capture's audio is collected.
        val second = useCase.startCapture(recording(), audio)
        audio.emit(frame(100, 0.30f))
        val result = useCase.stopCapture(second, audio) as DictationResult.Success

        val pcm = local.requests.single().pcm
        assertEquals("only the new capture's frame reaches the engine", 100, pcm.size)
        assertEquals(0.30f, pcm[0], 0f)
    }

    @Test
    fun `a cancel discards the buffered audio`() {
        val useCase = useCase()
        val audio = FakeAudioSource()
        val session = useCase.startCapture(recording(), audio)
        audio.emit(frame(100, 0.40f))

        val idle = useCase.cancel(session, audio)

        // The cancel stopped the source and returned an idle session.
        assertEquals(1, audio.stopCount)
        assertTrue("a cancel returns to idle", idle.state == dev.breaker.dictation.core.model.DictationState.IDLE)

        // The buffered audio is gone. A stop on a fresh RECORDING session, with
        // no fresh start in between to re-clear the channel, sees an empty
        // buffer: if the cancel had not drained it, the cancelled frame would be
        // transcribed and the result would be a Success.
        val result = useCase.stopCapture(recording(), audio)
        assertTrue("the cancelled frame must not be transcribed", result is DictationResult.Failure)
        assertEquals("the cancelled audio is never handed to the engine", 0, local.callCount)
    }

    @Test
    fun `the captured frame is a copy, not the caller's array`() {
        val useCase = useCase()
        val audio = FakeAudioSource()
        val session = useCase.startCapture(recording(), audio)

        // The capture thread stores a copy of each frame. Emit a frame, then
        // mutate the SAME array the source handed over: if the use case had kept
        // the reference instead of copying it, the captured audio would carry the
        // mutation, not the value at the moment the frame arrived.
        val samples = frame(100, 0.10f)
        audio.emit(samples)
        for (i in samples.indices) samples[i] = 0.99f

        val result = useCase.stopCapture(session, audio) as DictationResult.Success
        val pcm = local.requests.single().pcm

        assertEquals("the captured frame is the value at emit time", 0.10f, pcm[0], 0f)
        assertEquals(0.10f, pcm[99], 0f)
    }
}
