package dev.breaker.dictation.training.client

import dev.breaker.dictation.core.model.PhraseKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the recorder keeps and what it refuses: the length bounds, the loudness bound, the
 * order the checks run in, and the state after each outcome. Written by reading only: not
 * compiled, not run.
 */
class PhraseRecorderTest {

    private val source = ScriptedAudioSource()
    private val recorder = PhraseRecorder(source)

    private fun record(total: Int, value: Float = 0.5f): RecordResult {
        assertEquals(RecorderStart.STARTED, recorder.start(PhraseKind.WAKE))
        source.deliverSamples(total, value)
        return recorder.finish()
    }

    private fun rejected(reason: RejectReason) = RecordResult.Rejected(reason)

    private fun accepted(result: RecordResult) = (result as RecordResult.Accepted).sample

    @Test
    fun `a recording of exactly the minimum length is accepted`() {
        val sample = accepted(record(RecorderBounds.MIN_SAMPLES))

        assertEquals(RecorderBounds.MIN_SAMPLES, sample.pcm.size)
    }

    @Test
    fun `a recording one sample under the minimum is too short`() {
        assertEquals(rejected(RejectReason.TOO_SHORT), record(RecorderBounds.MIN_SAMPLES - 1))
    }

    @Test
    fun `a recording of exactly the maximum length is accepted`() {
        val sample = accepted(record(RecorderBounds.MAX_SAMPLES))

        assertEquals(RecorderBounds.MAX_SAMPLES, sample.pcm.size)
    }

    @Test
    fun `a recording one sample over the maximum is too long`() {
        assertEquals(rejected(RejectReason.TOO_LONG), record(RecorderBounds.MAX_SAMPLES + 1))
    }

    @Test
    fun `frames past the maximum plus the margin are too long and the buffer stays at the cap`() {
        val cap = RecorderBounds.MAX_SAMPLES + RecorderBounds.BUFFER_MARGIN_SAMPLES
        recorder.start(PhraseKind.WAKE)
        source.deliverSamples(cap + 8_000)

        assertTrue(source.samplesDelivered > cap)
        assertEquals(cap, recorder.bufferedSamples())
        assertEquals(rejected(RejectReason.TOO_LONG), recorder.finish())
    }

    @Test
    fun `a single frame that crosses the cap is kept only up to the cap`() {
        val cap = RecorderBounds.MAX_SAMPLES + RecorderBounds.BUFFER_MARGIN_SAMPLES
        recorder.start(PhraseKind.WAKE)
        source.deliver(FloatArray(cap + 1_000) { 0.5f })

        assertEquals(cap, recorder.bufferedSamples())
        assertEquals(rejected(RejectReason.TOO_LONG), recorder.finish())
    }

    @Test
    fun `an all zero recording is silent`() {
        assertEquals(rejected(RejectReason.SILENT), record(RecorderBounds.MIN_SAMPLES, value = 0f))
    }

    @Test
    fun `a recording whose peak is below the threshold is silent`() {
        val quiet = RecorderBounds.SILENT_PEAK - 0.001f

        assertEquals(rejected(RejectReason.SILENT), record(RecorderBounds.MIN_SAMPLES, value = quiet))
    }

    @Test
    fun `a recording whose peak is exactly the threshold is not silent`() {
        recorder.start(PhraseKind.WAKE)
        source.deliver(FloatArray(RecorderBounds.MIN_SAMPLES).also { it[0] = RecorderBounds.SILENT_PEAK })

        val sample = accepted(recorder.finish())

        assertEquals(RecorderBounds.MIN_SAMPLES, sample.pcm.size)
    }

    @Test
    fun `a negative peak counts by its absolute value`() {
        val sample = accepted(record(RecorderBounds.MIN_SAMPLES, value = -0.5f))

        assertEquals(-0.5f, sample.pcm[0], 0f)
    }

    @Test
    fun `a recording that is both silent and too short is too short`() {
        assertEquals(rejected(RejectReason.TOO_SHORT), record(RecorderBounds.MIN_SAMPLES - 1, value = 0f))
    }

    @Test
    fun `a recording that is both silent and too long is too long`() {
        assertEquals(rejected(RejectReason.TOO_LONG), record(RecorderBounds.MAX_SAMPLES + 1, value = 0f))
    }

    @Test
    fun `the accepted sample carries the kind it was started with and exactly the samples heard`() {
        recorder.start(PhraseKind.SEND)
        source.deliverSamples(RecorderBounds.MIN_SAMPLES + 123)

        val sample = accepted(recorder.finish())

        assertEquals(PhraseKind.SEND, sample.kind)
        assertEquals(RecorderBounds.MIN_SAMPLES + 123, sample.pcm.size)
    }

    @Test
    fun `starting twice answers already recording and does not touch the source`() {
        assertEquals(RecorderStart.STARTED, recorder.start(PhraseKind.WAKE))

        assertEquals(RecorderStart.ALREADY_RECORDING, recorder.start(PhraseKind.SEND))
        assertEquals(1, source.startCalls)
        assertEquals(0, source.stopCalls)
    }

    @Test
    fun `finish without start is rejected as not recording and does not touch the source`() {
        assertEquals(rejected(RejectReason.NOT_RECORDING), recorder.finish())
        assertEquals(0, source.startCalls)
        assertEquals(0, source.stopCalls)
    }

    @Test
    fun `a source that throws in start answers source failed and leaves the state clean`() {
        source.failOnStart = true

        assertEquals(RecorderStart.SOURCE_FAILED, recorder.start(PhraseKind.WAKE))
        assertEquals(rejected(RejectReason.NOT_RECORDING), recorder.finish())
    }

    @Test
    fun `a start after a failed start works`() {
        source.failOnStart = true
        recorder.start(PhraseKind.WAKE)
        source.failOnStart = false

        val sample = accepted(record(RecorderBounds.MIN_SAMPLES))

        assertEquals(RecorderBounds.MIN_SAMPLES, sample.pcm.size)
    }

    @Test
    fun `stop is called once on every finish whatever the verdict`() {
        record(RecorderBounds.MIN_SAMPLES)
        assertEquals(1, source.stopCalls)
        record(RecorderBounds.MIN_SAMPLES - 1)
        assertEquals(2, source.stopCalls)
        record(RecorderBounds.MIN_SAMPLES, value = 0f)
        assertEquals(3, source.stopCalls)
        record(RecorderBounds.MAX_SAMPLES + 1)
        assertEquals(4, source.stopCalls)
    }

    @Test
    fun `a source that throws in stop still gives the verdict`() {
        source.failOnStop = true

        assertEquals(rejected(RejectReason.TOO_SHORT), record(RecorderBounds.MIN_SAMPLES - 1))
        assertEquals(1, source.stopCalls)
    }

    @Test
    fun `finish ends the recording so the next start is accepted`() {
        record(RecorderBounds.MIN_SAMPLES)

        assertEquals(RecorderStart.STARTED, recorder.start(PhraseKind.WAKE))
        assertEquals(2, source.startCalls)
    }

    @Test
    fun `a rejected recording leaves no samples for the next one`() {
        record(RecorderBounds.MIN_SAMPLES - 1)

        val sample = accepted(record(RecorderBounds.MIN_SAMPLES))

        assertEquals(RecorderBounds.MIN_SAMPLES, sample.pcm.size)
    }

    @Test
    fun `a frame delivered after finish is not part of the next recording`() {
        recorder.start(PhraseKind.WAKE)
        source.deliverSamples(RecorderBounds.MIN_SAMPLES)
        recorder.finish()

        source.deliverTo(0, FloatArray(20_000) { 0.9f })
        recorder.start(PhraseKind.WAKE)
        source.deliverTo(0, FloatArray(20_000) { 0.9f })
        source.deliverTo(1, FloatArray(RecorderBounds.MIN_SAMPLES) { 0.1f })

        val sample = accepted(recorder.finish())

        assertEquals(RecorderBounds.MIN_SAMPLES, sample.pcm.size)
        assertTrue(sample.pcm.all { it == 0.1f })
    }

    @Test
    fun `a frame of length zero is ignored`() {
        recorder.start(PhraseKind.WAKE)
        source.deliver(FloatArray(0))

        assertEquals(0, recorder.bufferedSamples())
        source.deliverSamples(RecorderBounds.MIN_SAMPLES)
        assertEquals(RecorderBounds.MIN_SAMPLES, accepted(recorder.finish()).pcm.size)
    }
}
