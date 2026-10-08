package dev.breaker.dictation.stt.ondevice

import dev.breaker.dictation.core.model.SttSegment

/**
 * A [SherpaRecognizer] over a native streaming recognizer.
 *
 * One call to [decode] opens one native stream, feeds the whole clip and a block of
 * silence, tells the engine the audio is finished, polls the engine until nothing is
 * left to decode, and reads the text. The stream is always released again. The
 * transcript is one segment that spans the clip, or no segment when the text is blank.
 *
 * Every refusal (already released, a sample rate other than 16 kHz, an empty clip, an
 * engine that never stops asking for more steps) and every engine failure is reported
 * as a [SherpaTranscriptionException] with a fixed short message. No message carries a
 * file path or the engine's own text; the engine's failure travels as the cause.
 * Out-of-memory and stack-overflow errors are not caught: the engine that calls this
 * class has its own arms for them.
 *
 * [release] frees the native recognizer. It does nothing the second time and never throws.
 *
 * The silence length and the step slack are placeholders until they are measured on a
 * device (see [TAIL_PADDING_SAMPLES]).
 */
internal class SherpaOnnxRecognizer(
    private val native: NativeStreamingRecognizer,
    private val language: String,
    private val tailPaddingSamples: Int = TAIL_PADDING_SAMPLES,
    private val maxSteps: (clipSamples: Int) -> Int = { clipSamples -> defaultMaxSteps(clipSamples, tailPaddingSamples) },
) : SherpaRecognizer {

    // The only mutable field. It is a plain field on purpose: every access is ordered by a
    // hand-over that the decode bound already makes, so no lock and no volatile is needed.
    //   1. The slot thread creates this object (inside the loader) and then hands decode to a
    //      worker by submitting a task to the worker dispatcher. Submitting a task happens
    //      before the task runs, so the worker sees what the slot thread wrote.
    //   2. The worker finishes decode and completes the outcome future. Completing a future
    //      happens before the slot thread's get() returns, so the slot thread sees what the
    //      worker wrote before it calls release().
    //   3. If the decode was abandoned after a timeout, the slot thread returns from its own
    //      release() without touching this object. From then on the worker is the only thread
    //      that touches it, and it calls release() itself once the native call has returned.
    // So decode and release of one object never overlap and never race on this field.
    private var released = false

    override fun decode(pcm: FloatArray, sampleRateHz: Int): SherpaTranscript {
        // These three checks come first, so a refused call never reaches the native engine.
        if (released) throw SherpaTranscriptionException(MESSAGE_RELEASED)
        if (sampleRateHz != SAMPLE_RATE_HZ) throw SherpaTranscriptionException(MESSAGE_RATE)
        if (pcm.isEmpty()) throw SherpaTranscriptionException(MESSAGE_EMPTY)
        return try {
            val stream = native.createStream()
            try {
                transcribe(stream, pcm)
            } finally {
                // A failing release must neither replace a good transcript nor hide the real failure.
                try {
                    stream.release()
                } catch (ignored: Exception) {
                    // the first failure is the one reported; a failing stream release adds nothing
                } catch (ignored: LinkageError) {
                    // the first failure is the one reported; a failing stream release adds nothing
                }
            }
        } catch (e: SherpaTranscriptionException) {
            throw e
        } catch (e: Exception) {
            throw SherpaTranscriptionException(MESSAGE_FAILED, e)
        } catch (e: LinkageError) {
            throw SherpaTranscriptionException(MESSAGE_FAILED, e)
        }
    }

    override fun release() {
        if (released) return
        // Set before the native call, so a native failure cannot let a second call release again.
        released = true
        try {
            native.release()
        } catch (ignored: Exception) {
            // release must never throw; the flag is already set, so nothing is retried
        } catch (ignored: LinkageError) {
            // release must never throw; the flag is already set, so nothing is retried
        }
    }

    private fun transcribe(stream: NativeStream, pcm: FloatArray): SherpaTranscript {
        stream.acceptWaveform(pcm, SAMPLE_RATE_HZ)
        stream.acceptWaveform(FloatArray(tailPaddingSamples), SAMPLE_RATE_HZ)
        stream.inputFinished()
        val limit = maxSteps(pcm.size)
        var steps = 0
        while (stream.isReady()) {
            steps++
            if (steps > limit) throw SherpaTranscriptionException(MESSAGE_NOT_FINISHED)
            stream.decode()
        }
        val text = stream.text().trim()
        if (text.isEmpty()) return SherpaTranscript("", emptyList(), language)
        val durationMs = pcm.size * 1000L / SAMPLE_RATE_HZ
        return SherpaTranscript(text, listOf(SttSegment(0L, durationMs, text)), language)
    }

    internal companion object {
        /**
         * Zero samples fed after the clip so the engine can finish the last word: 0.66 s at
         * 16 kHz. A placeholder value, to be measured on a device.
         */
        const val TAIL_PADDING_SAMPLES = 10_560

        /** The only sample rate the adapter accepts. Taken from the model description; to be confirmed on a device. */
        const val SAMPLE_RATE_HZ = 16_000

        private const val SAMPLES_PER_FRAME = 160
        private const val STEP_SLACK = 16

        private const val MESSAGE_RELEASED = "the on-device recognizer was already released"
        private const val MESSAGE_RATE = "the on-device recognizer needs 16 kHz audio"
        private const val MESSAGE_EMPTY = "there is no audio to transcribe"
        private const val MESSAGE_NOT_FINISHED = "the on-device decode did not finish"
        private const val MESSAGE_FAILED = "the on-device decode failed"

        /**
         * The most native decode steps one clip may take: the frames in the clip and the
         * silence (100 frames per second) plus a little slack. Every step advances at least
         * one frame, so a healthy decode stays under it.
         */
        internal fun defaultMaxSteps(clipSamples: Int, paddingSamples: Int): Int =
            ((clipSamples.toLong() + paddingSamples) / SAMPLES_PER_FRAME + STEP_SLACK).toInt()
    }
}
