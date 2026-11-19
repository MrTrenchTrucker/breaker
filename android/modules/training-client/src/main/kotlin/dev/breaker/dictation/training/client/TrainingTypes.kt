package dev.breaker.dictation.training.client

/**
 * The server's name for one training job. A blank value is refused when the
 * object is made, so a blank id never reaches a call.
 */
@JvmInline
value class JobId(val value: String) {
    init {
        require(value.isNotBlank()) { "A job id cannot be blank" }
    }
}

/** Where a training job stands. A closed set. */
sealed interface JobStatus {
    /** The job is waiting for a free slot. */
    data object Queued : JobStatus

    /** The job is training. */
    data object Running : JobStatus

    /** The job finished and a model can be downloaded. */
    data object Done : JobStatus

    /** The job ended without a model; [code] says why. */
    data class Failed(val code: JobFailure) : JobStatus
}

/** Why a job ended without a model. Codes only: no sentence is carried. */
enum class JobFailure { TOO_FEW_SAMPLES, TRAINING_ERROR }

/**
 * What the server says a downloaded model is, plus the bytes. Nothing here is
 * trusted yet: the client checks size, digest and sample count before it hands
 * a model on.
 *
 * This is a plain class, not a data class, on purpose. A data class compares
 * [bytes] by reference, so two replies with the same content could look
 * different, or the same array could make a tampered reply look equal; here
 * equality is plainly by reference and nobody can read more into it.
 * [toString] never prints the bytes: a model is large and is executable content.
 */
class ModelReply(
    val sha256: String,
    val sizeBytes: Long,
    val samplesUsed: Int,
    val bytes: ByteArray,
) {
    override fun toString(): String = "ModelReply(${bytes.size} bytes)"
}

/** The size limits of the training exchange. */
object TrainingLimits {
    /**
     * The largest sample the client uploads and the server accepts: 128 KiB.
     * A 3.0 second sample is 96,000 bytes of 16 bit PCM plus a 44 byte WAV
     * header, 96,044 bytes, so this leaves about 1.36 times headroom.
     */
    const val MAX_UPLOAD_BYTES: Int = 131_072

    /**
     * The largest model the client accepts: 32 MiB. The shipped keyword models
     * are about 15 to 20 MB, so this is about 1.6 times the largest.
     */
    const val MAX_MODEL_BYTES: Long = 33_554_432L
}
