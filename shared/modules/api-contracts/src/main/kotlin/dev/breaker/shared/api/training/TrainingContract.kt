package dev.breaker.shared.api.training

/**
 * The limits and the response header names of the training operations in the OpenAPI spec.
 * This object holds constants only; it lives in its own package so the schema reader, which
 * accepts only data classes and enums in the api package, never counts it as a type.
 */
object TrainingContract {
    /** The most bytes one uploaded sample (a WAV) may hold. */
    const val MAX_SAMPLE_BYTES = 131072

    /** The most bytes a downloaded model may hold (32 MiB). */
    const val MAX_MODEL_BYTES = 33554432

    /** The fewest samples of one kind a job needs to train; fewer ends the job as failed. */
    const val MIN_SAMPLES_PER_KIND = 10

    /** The most distinct samples of one kind the server keeps; a further one is refused. */
    const val MAX_SAMPLES_PER_KIND = 20

    /** Model download response header: the SHA-256 of the model bytes, 64 lower-case hex digits. */
    const val HEADER_MODEL_SHA256 = "X-Model-Sha256"

    /** Model download response header: the model size in bytes. */
    const val HEADER_MODEL_SIZE_BYTES = "X-Model-Size-Bytes"

    /** Model download response header: how many samples the model was trained on. */
    const val HEADER_MODEL_SAMPLES_USED = "X-Model-Samples-Used"
}
