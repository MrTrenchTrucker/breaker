package dev.breaker.dictation.training.client

import dev.breaker.dictation.core.model.PhraseKind

/** One sample as the fake server holds it: the kind, the id and its own copy of the bytes. */
class StoredSample(val kind: PhraseKind, val sampleId: String, val wav: ByteArray) {
    override fun toString(): String = "StoredSample(kind=$kind, id=$sampleId, ${wav.size} bytes)"
}

/**
 * A scriptable stand-in for the training server, for tests only (not compiled
 * or run yet). It lives in the test folder so it never ships in the app.
 *
 * Each operation has a queue of scripted results, returned in order; when the
 * queue is empty the operation answers its default. The defaults: an operation
 * whose result carries nothing (uploadSample, deleteMySamples, deleteMyModel)
 * answers Ok, because with nothing to hand back there is nothing to invent; an
 * operation that must hand something back (startJob, jobStatus, downloadModel)
 * answers Unavailable, except downloadModel, which answers Ok with the model
 * set by [putModel] when there is one.
 *
 * The fake holds what a real server would hold, so a test can look at it:
 * [storedSamples], [uploadedIds] and [storedModel]. A sample is stored only when
 * the call would have worked, except with [loseReplyOnUpload], which stores the
 * sample and still answers Network, the lost reply. A scripted result is
 * consumed by a call even when [loseReplyOnUpload] overrides the answer.
 * uploadSample is idempotent per sample id, like the real port promises.
 */
class ScriptedTrainingServer : TrainingServer {

    private class Script<T>(private val fallback: () -> TrainingResult<T>) {
        private val queue = ArrayDeque<TrainingResult<T>>()

        fun add(results: Array<out TrainingResult<T>>) {
            results.forEach { queue.addLast(it) }
        }

        fun next(): TrainingResult<T> = queue.removeFirstOrNull() ?: fallback()
    }

    private var model: ModelReply? = null

    private val uploads = Script<Unit> { TrainingResult.Ok(Unit) }
    private val starts = Script<JobId> { TrainingResult.Unavailable }
    private val statuses = Script<JobStatus> { TrainingResult.Unavailable }
    private val downloads = Script<ModelReply> {
        val held = model
        if (held == null) TrainingResult.Unavailable else TrainingResult.Ok(held)
    }
    private val sampleDeletes = Script<Unit> { TrainingResult.Ok(Unit) }
    private val modelDeletes = Script<Unit> { TrainingResult.Ok(Unit) }

    private val log = mutableListOf<String>()
    private val ids = LinkedHashSet<String>()
    private val samples = mutableListOf<StoredSample>()

    /** When true, an upload is stored and the answer is Network: the reply was lost. */
    var loseReplyOnUpload: Boolean = false

    /** Every call so far, oldest first: the operation name and its arguments (a sample's byte count, not its bytes). */
    val calls: List<String> get() = log.toList()

    /** The ids the server holds, each once. */
    val uploadedIds: Set<String> get() = ids.toSet()

    /** The samples the server holds, one per id. */
    val storedSamples: List<StoredSample> get() = samples.toList()

    /** The model the server holds, if any. */
    val storedModel: ModelReply? get() = model

    /** Gives the server a model to serve. */
    fun putModel(reply: ModelReply) {
        model = reply
    }

    fun scriptUpload(vararg results: TrainingResult<Unit>) = uploads.add(results)

    fun scriptStartJob(vararg results: TrainingResult<JobId>) = starts.add(results)

    fun scriptJobStatus(vararg results: TrainingResult<JobStatus>) = statuses.add(results)

    fun scriptDownload(vararg results: TrainingResult<ModelReply>) = downloads.add(results)

    fun scriptDeleteSamples(vararg results: TrainingResult<Unit>) = sampleDeletes.add(results)

    fun scriptDeleteModel(vararg results: TrainingResult<Unit>) = modelDeletes.add(results)

    private fun store(kind: PhraseKind, sampleId: String, wav: ByteArray) {
        if (ids.add(sampleId)) {
            samples.add(StoredSample(kind, sampleId, wav.copyOf()))
        }
    }

    override fun uploadSample(kind: PhraseKind, sampleId: String, wav: ByteArray): TrainingResult<Unit> {
        log.add("uploadSample ${kind.name} $sampleId ${wav.size}")
        val scripted = uploads.next()
        if (loseReplyOnUpload) {
            store(kind, sampleId, wav)
            return TrainingResult.Network
        }
        if (scripted is TrainingResult.Ok<*>) {
            store(kind, sampleId, wav)
        }
        return scripted
    }

    override fun startJob(): TrainingResult<JobId> {
        log.add("startJob")
        return starts.next()
    }

    override fun jobStatus(job: JobId): TrainingResult<JobStatus> {
        log.add("jobStatus ${job.value}")
        return statuses.next()
    }

    override fun downloadModel(job: JobId, kind: PhraseKind): TrainingResult<ModelReply> {
        log.add("downloadModel ${job.value} ${kind.name}")
        return downloads.next()
    }

    override fun deleteMySamples(): TrainingResult<Unit> {
        log.add("deleteMySamples")
        val scripted = sampleDeletes.next()
        if (scripted is TrainingResult.Ok<*>) {
            ids.clear()
            samples.clear()
        }
        return scripted
    }

    override fun deleteMyModel(): TrainingResult<Unit> {
        log.add("deleteMyModel")
        val scripted = modelDeletes.next()
        if (scripted is TrainingResult.Ok<*>) {
            model = null
        }
        return scripted
    }
}
