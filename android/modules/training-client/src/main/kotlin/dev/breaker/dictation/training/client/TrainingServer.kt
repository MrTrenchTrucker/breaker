package dev.breaker.dictation.training.client

import dev.breaker.dictation.core.model.PhraseKind

/**
 * What the phone asks of the training server.
 *
 * Every call is blocking and the caller owns the threading: nothing here starts
 * a thread or waits on a timer. Every result is typed and an expected outcome
 * never throws. Every call needs the user's session, and answers
 * [TrainingResult.NotSignedIn] without one and [TrainingResult.NoServer] when no
 * server is configured. [TrainingResult.TooLarge] means the server refused a
 * size, [TrainingResult.Refused] any other refusal, [TrainingResult.Network] that
 * no reply arrived (the server may still have acted), and
 * [TrainingResult.Unavailable] that the server cannot serve the call now.
 *
 * This is the shape the shared contract and the server are built to. No
 * implementation in this module talks to a network. Status: shape only, not
 * built into a running app.
 */
interface TrainingServer {
    /**
     * Stores one recorded sample for the user. IDEMPOTENT per [sampleId]: the
     * same id twice is one sample and the second call is Ok, so a retry after a
     * lost reply is safe. [wav] is a complete WAV file no larger than
     * [TrainingLimits.MAX_UPLOAD_BYTES].
     */
    fun uploadSample(kind: PhraseKind, sampleId: String, wav: ByteArray): TrainingResult<Unit>

    /** Starts a training job on the samples the server holds and returns its id. */
    fun startJob(): TrainingResult<JobId>

    /** Reads where [job] stands. */
    fun jobStatus(job: JobId): TrainingResult<JobStatus>

    /** Downloads the model [job] trained for [kind], with the size and digest the server declares. */
    fun downloadModel(job: JobId, kind: PhraseKind): TrainingResult<ModelReply>

    /** Deletes every sample the server holds for the user. */
    fun deleteMySamples(): TrainingResult<Unit>

    /** Deletes the trained model the server holds for the user. */
    fun deleteMyModel(): TrainingResult<Unit>
}
