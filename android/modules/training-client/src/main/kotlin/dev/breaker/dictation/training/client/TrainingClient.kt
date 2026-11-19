package dev.breaker.dictation.training.client

import dev.breaker.dictation.core.model.PhraseKind
import dev.breaker.dictation.core.model.PhraseModel
import dev.breaker.dictation.core.model.TrainedPhraseModel

/**
 * Runs one training round over a [SampleStore] and a [TrainingServer].
 *
 * Nothing here starts background work, waits or reads a clock: every call blocks and the caller owns the
 * context it runs in and the pace of polling. The ONLY call that uploads anything is [startTraining]:
 * building the client, polling, fetching and deleting never send a sample. Calls must not overlap.
 *
 * Status: written without a compiler, not built, not tried on a phone.
 */
class TrainingClient(private val store: SampleStore, private val server: TrainingServer) {

    /**
     * Uploads the stored samples and starts the job. In order: (1) a server delete that is still pending is
     * retried first, and while it fails nothing is uploaded; a ledger that cannot be read answers
     * [StartResult.StoreFailed] before any server call or upload; (2) both kinds must have at least
     * [SampleCounts.MIN_PER_PHRASE] distinct ids counting local and server-confirmed ones, checked for both
     * kinds before the first upload; (3) each local sample is uploaded in turn: a sample the ledger already
     * confirms is not sent, only its local file is deleted; a larger sample than
     * [TrainingLimits.MAX_UPLOAD_BYTES] is refused here; after an Ok the id is marked confirmed FIRST and the
     * local file is deleted AFTER; the first failure stops the run and leaves that sample local; (4) the job is
     * started and its id saved.
     */
    fun startTraining(): StartResult {
        val pending = retryPendingDelete()
        if (pending != null) return pending
        for (kind in PhraseKind.entries) {
            val have = distinctCount(kind)
            if (have < SampleCounts.MIN_PER_PHRASE) {
                return StartResult.TooFewSamples(kind, have, SampleCounts.MIN_PER_PHRASE)
            }
        }
        var uploaded = 0
        for (kind in PhraseKind.entries) {
            when (val step = uploadKind(kind)) {
                is StartResult.Started -> uploaded += step.uploadedCount
                else -> return step
            }
        }
        return server.startJob().fold<JobId, StartResult>(
            onOk = { job -> saveStarted(job, uploaded) },
            onFailed = { failure -> notOfferedOr(failure, StartResult.JobNotStarted(failure)) },
        )
    }

    /** Reads where the saved job stands. One call, no loop: the caller decides when to ask again. */
    fun pollJob(): PollResult {
        val job = savedJob() ?: return PollResult.NoJob
        return server.jobStatus(job).fold<JobStatus, PollResult>(
            onOk = { status -> pollResultOf(status) },
            onFailed = { failure -> PollResult.Failed(failure) },
        )
    }

    /**
     * Downloads the model for the saved job and checks it (see [ModelVerifier.check]). The current server
     * returns the same combined model for both kinds, so it is fetched once, with kind WAKE, and the
     * installer ignores the kind: the result carries kind WAKE. Only a model that passed every check is
     * returned; a refused one is dropped. This call stores no model.
     */
    fun fetchModel(): FetchResult {
        val job = savedJob() ?: return FetchResult.NoJob
        return server.downloadModel(job, PhraseKind.WAKE).fold<ModelReply, FetchResult>(
            onOk = { reply -> verified(PhraseKind.WAKE, reply) },
            onFailed = { failure -> FetchResult.Failed(failure) },
        )
    }

    /**
     * Deletes everything: the user's intent wins locally. (1) Local samples, ledger and job id go first;
     * (2) the pending mark is set BEFORE the server is asked, and when the mark cannot be stored the server is
     * NOT called at all and [DeleteResult.LocalFailed] with a null failure is returned, because a delete that
     * failed afterwards could not be retried; (3) the server samples and then the server
     * model are deleted, BOTH calls always made so one failure never leaves the other copy behind, and the
     * first failure is the one reported; (4) only when both are Ok is the mark cleared and [DeleteResult.Done]
     * given. A local failure with the mark stored is reported as [DeleteResult.LocalFailed] but the server
     * delete is still tried and the mark handled the same way. Done is never given while a server delete is unconfirmed.
     */
    fun deleteAll(): DeleteResult {
        val local = store.deleteAll()
        if (store.setServerDeletePending(true) !is StoreResult.Ok) return DeleteResult.LocalFailed(null)
        val failure = deleteOnServer()
        if (failure != null) {
            return if (local is StoreResult.Ok) DeleteResult.ServerDeletePending(failure) else DeleteResult.LocalFailed(failure)
        }
        val cleared = store.setServerDeletePending(false)
        return if (local is StoreResult.Ok && cleared is StoreResult.Ok) DeleteResult.Done else DeleteResult.LocalFailed(null)
    }

    private fun deleteOnServer(): TrainingFailure? {
        val samples = server.deleteMySamples().failureOrNull()
        val model = server.deleteMyModel().failureOrNull()
        return samples ?: model
    }

    /**
     * Null when no server delete is pending or the retry cleared it; otherwise the answer that stops the
     * start. A ledger that cannot be read stops the start with [StartResult.StoreFailed] before any server call.
     */
    private fun retryPendingDelete(): StartResult? = when (store.serverDeleteState()) {
        LedgerState.UNREADABLE -> StartResult.StoreFailed
        LedgerState.CLEAR -> null
        LedgerState.PENDING -> retryDeleteNow()
    }

    private fun retryDeleteNow(): StartResult? {
        val failure = deleteOnServer()
        if (failure != null) return StartResult.ServerDeletePending(failure)
        return if (store.setServerDeletePending(false) is StoreResult.Ok) null else StartResult.StoreFailed
    }

    private fun distinctCount(kind: PhraseKind): Int = (store.list(kind).toSet() + store.confirmed(kind)).size

    /** [StartResult.Started] with the count uploaded for [kind], or the answer that stops the run. */
    private fun uploadKind(kind: PhraseKind): StartResult {
        val confirmed = store.confirmed(kind)
        var uploaded = 0
        for (id in store.list(kind)) {
            if (id in confirmed) {
                store.deleteOne(kind, id)
                continue
            }
            val wav = when (val read = store.read(kind, id)) {
                is StoreResult.Ok -> read.value
                else -> return StartResult.StoreFailed
            }
            if (wav.size > TrainingLimits.MAX_UPLOAD_BYTES) return StartResult.SampleTooLarge(kind, id)
            val failure = server.uploadSample(kind, id, wav).failureOrNull()
            if (failure != null) return notOfferedOr(failure, StartResult.UploadFailed(failure))
            if (store.markConfirmed(kind, id) !is StoreResult.Ok) return StartResult.StoreFailed
            store.deleteOne(kind, id)
            uploaded += 1
        }
        return StartResult.Started(uploaded)
    }

    private fun saveStarted(job: JobId, uploaded: Int): StartResult =
        if (store.saveJob(job.value) is StoreResult.Ok) StartResult.Started(uploaded) else StartResult.JobNotSaved

    private fun notOfferedOr(failure: TrainingFailure, otherwise: StartResult): StartResult =
        if (failure == TrainingFailure.NOT_SIGNED_IN || failure == TrainingFailure.NO_SERVER) {
            StartResult.NotOffered(failure)
        } else {
            otherwise
        }

    /** The saved job, or null when none is saved or the saved text is blank (a blank id cannot be made). */
    private fun savedJob(): JobId? {
        val text = store.loadJob() ?: return null
        return if (text.isBlank()) null else JobId(text)
    }

    private fun pollResultOf(status: JobStatus): PollResult = when (status) {
        JobStatus.Queued -> PollResult.InProgress(false)
        JobStatus.Running -> PollResult.InProgress(true)
        JobStatus.Done -> PollResult.Ready
        is JobStatus.Failed -> PollResult.JobFailed(status.code)
    }

    private fun verified(kind: PhraseKind, reply: ModelReply): FetchResult {
        val refusal = ModelVerifier.check(reply)
        if (refusal != null) return FetchResult.Refused(refusal)
        val digest = ModelVerifier.normalised(reply.sha256)
        return FetchResult.Ready(TrainedPhraseModel(kind, PhraseModel(kind, reply.bytes, digest), reply.samplesUsed))
    }
}
