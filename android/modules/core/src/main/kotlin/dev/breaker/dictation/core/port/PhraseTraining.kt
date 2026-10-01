package dev.breaker.dictation.core.port

import dev.breaker.dictation.core.model.PhraseKind
import dev.breaker.dictation.core.model.PhraseSample
import dev.breaker.dictation.core.model.TrainedPhraseModel

/**
 * Records phrase samples and fetches the trained model.
 *
 * Implemented by the training module: it records the user's own voice, uploads
 * it, and downloads the trained model back. A download is verified against its
 * digest before it is installed, because a model file is content arriving from
 * somewhere else. Until a model is installed, phrase detection falls back to
 * matching the transcript stream.
 */
interface PhraseTraining {
    /** Record one sample of [kind]. */
    fun recordSample(kind: PhraseKind): PhraseSample

    /** Upload [samples] for training. */
    fun upload(samples: List<PhraseSample>)

    /** Download the trained model for [kind], verified. Null when not ready. */
    fun downloadModel(kind: PhraseKind): TrainedPhraseModel?
}
