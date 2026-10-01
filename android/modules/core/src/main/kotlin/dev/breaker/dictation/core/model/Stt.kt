package dev.breaker.dictation.core.model

/**
 * Why a transcription attempt failed.
 *
 * `LOCAL_MODEL_MISSING` is the important one: the phone was told to transcribe
 * locally and has no verified model, so the attempt stops here. It never quietly
 * turns into a network attempt — the domain reports this and returns.
 */
enum class SttError { LOCAL_MODEL_MISSING, SERVER_UNREACHABLE, TIMEOUT, OTHER }

/** The audio shape every engine is fed: 16 kHz, one channel, 32-bit float PCM. */
object AudioFormat {
    const val SAMPLE_RATE_HZ: Int = 16_000
    const val CHANNEL_COUNT: Int = 1
}

/** One timed piece of a transcript. */
data class SttSegment(val startMs: Long, val endMs: Long, val text: String) {
    init {
        require(startMs >= 0) { "segment startMs cannot be negative: $startMs" }
        require(endMs >= startMs) { "segment endMs ($endMs) precedes startMs ($startMs)" }
    }

    /** Prints the timing and the length of the text, never the text. */
    override fun toString(): String = "SttSegment(startMs=$startMs, endMs=$endMs, ${text.length} chars)"
}

/**
 * One transcription request.
 *
 * The request carries the audio in both shapes the two engines need: [pcm] is
 * the raw 16 kHz mono float buffer the on-device engine reads, and [wavBytes]
 * is the same audio wrapped in a WAV container for upload to the Local Server.
 * Encoding is the audio module's job, so the domain only carries the bytes.
 */
class SttRequest(
    val pcm: FloatArray,
    val wavBytes: ByteArray,
    val model: String,
    val language: String,
    val sampleRateHz: Int = AudioFormat.SAMPLE_RATE_HZ,
) {
    init {
        require(pcm.isNotEmpty()) { "A transcription request needs audio" }
        require(wavBytes.isNotEmpty()) { "A transcription request needs a WAV encoding of the audio" }
        require(model.isNotBlank()) { "A transcription request needs a model name" }
        require(language.isNotBlank()) { "A transcription request needs a language" }
        require(sampleRateHz > 0) { "sampleRateHz must be positive: $sampleRateHz" }
    }

    /** Length of [pcm] in milliseconds. */
    val durationMs: Long
        get() = pcm.size * 1000L / sampleRateHz

    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is SttRequest &&
                    pcm.contentEquals(other.pcm) &&
                    wavBytes.contentEquals(other.wavBytes) &&
                    model == other.model &&
                    language == other.language &&
                    sampleRateHz == other.sampleRateHz
                )

    override fun hashCode(): Int {
        var result = pcm.contentHashCode()
        result = 31 * result + wavBytes.contentHashCode()
        result = 31 * result + model.hashCode()
        result = 31 * result + language.hashCode()
        result = 31 * result + sampleRateHz
        return result
    }

    /** Never prints the audio: a transcript of a log line is a data leak. */
    override fun toString(): String =
        "SttRequest(model=$model, language=$language, sampleRateHz=$sampleRateHz, " +
            "pcm=${pcm.size} samples, wavBytes=${wavBytes.size} bytes)"
}

/** What an engine returns: a transcript, or a reason there isn't one. */
sealed class SttResult {
    abstract val isSuccess: Boolean

    /** The audio was transcribed. Formatting is somebody else's job. */
    data class Success(
        val text: String,
        val segments: List<SttSegment> = emptyList(),
        val language: String = "",
    ) : SttResult() {
        override val isSuccess: Boolean get() = true

        /** Prints the length of the text and the segment count, never the text or the segments' text. */
        override fun toString(): String =
            "Success(${text.length} chars, ${segments.size} segments, language=$language)"
    }

    /**
     * Transcription failed. [detail] is a short, safe explanation for the user
     * — it may never contain dictated text.
     */
    data class Failure(val error: SttError, val detail: String? = null) : SttResult() {
        override val isSuccess: Boolean get() = false
    }

    companion object {
        /** Build a failure. Engines use this so a failure is one call, not a shape. */
        fun failure(error: SttError, detail: String? = null): SttResult = Failure(error, detail)
    }
}
