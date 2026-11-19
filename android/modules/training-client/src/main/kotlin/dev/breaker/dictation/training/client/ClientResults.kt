package dev.breaker.dictation.training.client

import dev.breaker.dictation.core.model.PhraseKind
import dev.breaker.dictation.core.model.TrainedPhraseModel

/**
 * What [TrainingClient.startTraining] answered. Every case is a value: no expected outcome throws.
 *
 * Status: written without a compiler, not built, not tried on a phone.
 */
sealed interface StartResult {
    /** The job was started and its id saved. [uploadedCount] is the samples uploaded by THIS call (not those confirmed earlier). */
    data class Started(val uploadedCount: Int) : StartResult

    /** Fewer than the minimum distinct samples (local plus confirmed) for [kind]. Nothing was uploaded. */
    data class TooFewSamples(val kind: PhraseKind, val have: Int, val need: Int) : StartResult

    /** The local sample [id] is larger than the upload limit and was not sent. */
    data class SampleTooLarge(val kind: PhraseKind, val id: String) : StartResult

    /** A local read, the confirmed-ids ledger or the delete-pending mark could not be read or written. */
    data object StoreFailed : StartResult

    /** An upload failed for [failure]; the sample stays local and unconfirmed and a later start continues. */
    data class UploadFailed(val failure: TrainingFailure) : StartResult

    /** Signed out or no server ([reason] is one of those two): training is not offered, nothing local was lost. */
    data class NotOffered(val reason: TrainingFailure) : StartResult

    /** A server delete asked for earlier is still unconfirmed ([failure]); nothing was uploaded: the delete wins. */
    data class ServerDeletePending(val failure: TrainingFailure) : StartResult

    /** Every sample is confirmed but the server did not start the job ([failure]); a later start only starts the job. */
    data class JobNotStarted(val failure: TrainingFailure) : StartResult

    /**
     * The server started a job but its id could not be saved here, so it cannot be polled. The job id is
     * deliberately not returned. A later start uploads nothing new (all samples are confirmed) and starts another job.
     */
    data object JobNotSaved : StartResult
}

/** What [TrainingClient.pollJob] answered. */
sealed interface PollResult {
    /** No job id is saved. */
    data object NoJob : PollResult

    /** The job is queued ([running] false) or training ([running] true). */
    data class InProgress(val running: Boolean) : PollResult

    /** The job finished: [TrainingClient.fetchModel] can be called. */
    data object Ready : PollResult

    /** The job ended without a model. */
    data class JobFailed(val code: JobFailure) : PollResult

    /** The status call failed. */
    data class Failed(val failure: TrainingFailure) : PollResult
}

/** Why a downloaded model was refused, in the order the checks run. Nothing is returned or stored for a refused model. */
enum class FetchRefusal { SIZE_OUT_OF_RANGE, SIZE_MISMATCH, BAD_DIGEST_FORMAT, DIGEST_MISMATCH, BAD_SAMPLES_COUNT }

/** What [TrainingClient.fetchModel] answered. */
sealed interface FetchResult {
    /** No job id is saved. */
    data object NoJob : FetchResult

    /** The download call failed. */
    data class Failed(val failure: TrainingFailure) : FetchResult

    /** The download arrived but failed a check. */
    data class Refused(val refusal: FetchRefusal) : FetchResult

    /** The model passed every check. */
    data class Ready(val model: TrainedPhraseModel) : FetchResult
}

/** What [TrainingClient.deleteAll] answered. [Done] is never given while a server delete is unconfirmed. */
sealed interface DeleteResult {
    /** The local data is gone and the server confirmed both deletes. */
    data object Done : DeleteResult

    /** The local data is gone; the server delete failed ([failure]) and the pending mark stays for a retry. */
    data class ServerDeletePending(val failure: TrainingFailure) : DeleteResult

    /**
     * Something local failed (files, ledger or mark). A non-null [serverFailure] means the pending mark was
     * stored, the server delete was tried and that is its failure. A null [serverFailure] carries two cases that
     * one value cannot tell apart: the pending mark could not be stored, so the server was NOT called; or the
     * server confirmed both deletes but a local part or the clearing of the mark failed. Both mean "not
     * confirmed": call deleteAll again.
     */
    data class LocalFailed(val serverFailure: TrainingFailure?) : DeleteResult
}
