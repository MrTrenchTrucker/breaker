package dev.breaker.dictation.stt.ondevice

import dev.breaker.dictation.core.model.SttSegment

/**
 * A [SherpaRecognizer] over a native offline recognizer.
 *
 * One call to [decode] hands the whole clip to the engine in one native call and reads the
 * text. There is no silence added after the clip and no polling loop: an offline engine
 * sees the clip as a whole. The transcript is one segment that spans the clip, or no
 * segment when the text is blank. The language is the one the engine reports when it
 * reports one, else [language]; a blank [language] means the model decides, and the engine
 * wrap then fills in the language of the request.
 *
 * Every refusal (already released, a sample rate other than 16 kHz, an empty clip) and
 * every engine failure is reported as a [SherpaTranscriptionException] with a fixed short
 * message. No message carries a file path or the engine's own text; the engine's failure
 * travels as the cause. Out-of-memory and stack-overflow errors are not caught: the engine
 * wrap that calls this class has its own arms for them.
 *
 * [release] frees the native recognizer. It does nothing the second time and never throws.
 *
 * The real native engine behind this class is OfflineRecognizerBinding.kt (see NativeOffline.kt):
 * compiled against the library by the project's CI build and never run on a device, so its
 * runtime behaviour is UNVERIFIED. Only tests with a fake engine have run this class.
 */
internal class SherpaOfflineRecognizer(
    private val native: NativeOfflineRecognizer,
    private val language: String,
) : SherpaRecognizer {

    // The only mutable field. It is a plain field on purpose, for the same reason as in
    // SherpaOnnxRecognizer: the decode bound hands the object from the slot thread to one
    // worker and back, and every hand-over orders the accesses, so decode and release of one
    // object never overlap and never race on this field.
    private var released = false

    override fun decode(pcm: FloatArray, sampleRateHz: Int): SherpaTranscript {
        // These three checks come first, so a refused call never reaches the native engine.
        if (released) throw SherpaTranscriptionException(MESSAGE_RELEASED)
        if (sampleRateHz != SherpaOnnxRecognizer.SAMPLE_RATE_HZ) throw SherpaTranscriptionException(MESSAGE_RATE)
        if (pcm.isEmpty()) throw SherpaTranscriptionException(MESSAGE_EMPTY)
        val result = try {
            native.decode(pcm, SherpaOnnxRecognizer.SAMPLE_RATE_HZ)
        } catch (e: Exception) {
            throw SherpaTranscriptionException(MESSAGE_FAILED, e)
        } catch (e: LinkageError) {
            throw SherpaTranscriptionException(MESSAGE_FAILED, e)
        }
        val reported = result.language?.trim().orEmpty()
        val label = reported.ifEmpty { language }
        val text = TranscriptCase.normaliseText(result.text.trim())
        if (text.isEmpty()) return SherpaTranscript("", emptyList(), label)
        val durationMs = pcm.size * 1000L / SherpaOnnxRecognizer.SAMPLE_RATE_HZ
        return SherpaTranscript(text, listOf(SttSegment(0L, durationMs, text)), label)
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

    private companion object {
        private const val MESSAGE_RELEASED = "the on-device recognizer was already released"
        private const val MESSAGE_RATE = "the on-device recognizer needs 16 kHz audio"
        private const val MESSAGE_EMPTY = "there is no audio to transcribe"
        private const val MESSAGE_FAILED = "the on-device decode failed"
    }
}
