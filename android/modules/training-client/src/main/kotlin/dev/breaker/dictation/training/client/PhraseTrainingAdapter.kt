package dev.breaker.dictation.training.client

import dev.breaker.dictation.core.model.PhraseKind
import dev.breaker.dictation.core.model.PhraseSample
import dev.breaker.dictation.core.model.TrainedPhraseModel
import dev.breaker.dictation.core.port.PhraseTraining

/**
 * Lets the core [PhraseTraining] port reach a [TrainingClient].
 *
 * Through the core port:
 * - [recordSample] throws [UnsupportedOperationException] with a fixed text: recording is fed frame by frame, so use [PhraseRecorder].
 * - [upload] throws [UnsupportedOperationException] with a fixed text: uploading starts only through [TrainingClient.startTraining].
 * - [downloadModel] returns the checked model when [TrainingClient.fetchModel] is ready and null in every other case, as the port documents null as not ready. For either kind it is the same combined model (its kind is WAKE), fetched with one server call.
 *
 * Neither throwing call touches the store or the server.
 *
 * Status: written without a compiler, not built, not tried on a phone.
 */
class PhraseTrainingAdapter(private val client: TrainingClient) : PhraseTraining {
    override fun recordSample(kind: PhraseKind): PhraseSample =
        throw UnsupportedOperationException(RECORD_TEXT)

    override fun upload(samples: List<PhraseSample>) {
        throw UnsupportedOperationException(UPLOAD_TEXT)
    }

    override fun downloadModel(kind: PhraseKind): TrainedPhraseModel? =
        (client.fetchModel() as? FetchResult.Ready)?.model

    private companion object {
        const val RECORD_TEXT = "recording is frame-fed: use PhraseRecorder"
        const val UPLOAD_TEXT = "training starts only through startTraining"
    }
}
