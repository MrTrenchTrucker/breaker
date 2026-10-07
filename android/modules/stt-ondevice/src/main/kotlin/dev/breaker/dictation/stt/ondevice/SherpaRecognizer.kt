package dev.breaker.dictation.stt.ondevice

import dev.breaker.dictation.core.model.SttSegment
import java.io.File

/**
 * A verified, on-device sherpa-onnx model ready for transcription.
 *
 * [modelId] is the registry id (e.g. "tiny"), [directory] is the installed
 * model directory on disk, and [digest] is the SHA-256 hex string that pins
 * the verified archive; the archive is checked against it when it is installed
 * and again on every load.
 */
data class SherpaModel(val modelId: String, val directory: File, val digest: String)

/**
 * The result of decoding PCM audio through the sherpa-onnx engine.
 *
 * [text] is the full transcript, [segments] are the timed pieces, and
 * [language] is the detected or configured language code.
 */
data class SherpaTranscript(val text: String, val segments: List<SttSegment>, val language: String)

/**
 * Thrown when the sherpa-onnx engine fails to initialise or decode.
 *
 * The [message] is a short, safe explanation; [cause] carries the underlying
 * engine error for diagnostics.
 */
class SherpaTranscriptionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The seam between this module and the native sherpa-onnx engine.
 *
 * The concrete adapter is wired later - this interface is the contract the
 * rest of the module programs against. Implementations must be safe for
 * concurrent decode calls, or document their concurrency model.
 */
interface SherpaRecognizer {
    /**
     * Decode [pcm] audio at [sampleRateHz] into a transcript.
     *
     * @throws SherpaTranscriptionException if the engine fails to decode.
     */
    fun decode(pcm: FloatArray, sampleRateHz: Int): SherpaTranscript

    /**
     * Release native resources held by this recognizer.
     *
     * After release the recognizer must not be used again.
     */
    fun release()
}

/**
 * Factory for [SherpaRecognizer] instances.
 *
 * The module's dependency wiring provides the real implementation; tests
 * supply their own so no test opens a native engine.
 */
fun interface SherpaRecognizerFactory {
    /**
     * Create a recognizer for the given verified [model].
     *
     * @throws Exception if the engine cannot be initialised for this model; the loader
     *   reports it as a refusal. [SherpaTranscriptionException] is the expected kind.
     */
    fun create(model: SherpaModel): SherpaRecognizer
}

/**
 * The default [SherpaRecognizerFactory] used when no engine is wired in.
 *
 * It refuses to create a recognizer rather than silently producing an empty
 * transcript - a module with no engine wired must refuse to transcribe.
 */
object UnavailableRecognizerFactory : SherpaRecognizerFactory {
    override fun create(model: SherpaModel): SherpaRecognizer =
        throw IllegalStateException("no on-device engine is wired in")
}
