package dev.breaker.dictation.stt.ondevice

import dev.breaker.shared.models.ModelEntry
import dev.breaker.shared.models.ModelRegistry
import java.io.File

/**
 * Loads the verified keyword spotting model from the local store.
 *
 * It runs exactly the checks [ModelLoader] runs for a speech model, because it
 * wraps one: registry lookup (through the keyword list, not the speech list),
 * family check, install check, stored checksums, the install stamp or the archive
 * check, the unpacked files, and the stamp write or refresh. The wrapped loader is
 * given a factory that opens no engine and creates no recognizer, so a ready load
 * only carries the directory and the digest. Speech loading is untouched.
 *
 * No engine for this model is built here, and nothing downloads the model.
 *
 * @param lookup resolves an id to its registry entry; the default is the keyword list.
 * @param debug receives the technical text of each refusal; the default drops it.
 * @param verify the archive check; a test may replace it to count calls.
 * @param digestOf digests one unpacked file; a test may replace it.
 */
class KeywordModelLoader(
    store: LocalModelStore,
    lookup: (String) -> ModelEntry? = ModelRegistry::byKeywordId,
    debug: ModelDebugSink = NoDebugSink,
    verify: (File, String, UpstreamChecksums.Checksums, String?) -> ModelIntegrity.Verdict =
        { file, pin, checksums, archiveName -> ModelIntegrity.verify(file, pin, checksums, archiveName) },
    digestOf: (File) -> String? = ModelIntegrity::sha256,
) {
    private val inner = ModelLoader(store, NoEngineFactory, lookup, debug, verify, digestOf)

    /**
     * Loads [KEYWORD_MODEL_ID]. A refusal keeps the category and the fixed sentence
     * the speech loader would give; a verification refusal deletes the model.
     */
    fun load(): KeywordLoad = when (val result = inner.load(KEYWORD_MODEL_ID)) {
        is ModelLoader.LoadResult.Ready ->
            KeywordLoad.Ready(KeywordModel(result.model.modelId, result.model.directory, result.model.digest))
        is ModelLoader.LoadResult.Refused -> KeywordLoad.Refused(result.refusal, result.detail)
    }

    /** The factory that opens nothing: the wrapped loader gets an inert stand-in, never a real engine. */
    private object NoEngineFactory : SherpaRecognizerFactory {
        override fun create(model: SherpaModel): SherpaRecognizer = NoEngine
    }

    /** Decodes nothing. It exists only so the wrapped loader has a value to hand back. */
    private object NoEngine : SherpaRecognizer {
        override fun decode(pcm: FloatArray, sampleRateHz: Int): SherpaTranscript =
            throw SherpaTranscriptionException("no engine is opened for the keyword model")

        override fun release() {}
    }
}
