package dev.breaker.dictation.stt.ondevice

import dev.breaker.shared.models.ModelEntry
import dev.breaker.shared.models.ModelKind
import dev.breaker.shared.models.ModelRegistry

/**
 * Creates the on-device recognizer for a verified, unpacked model.
 *
 * [create] checks everything it can before the native engine is touched: the
 * model's registry entry is a usable speech model (see [SpeechModels.usable]), and its
 * directory holds the four files the entry names. Opening the engine is the last step
 * that can fail, so a failure there leaves nothing behind to release. A native library
 * that fails to link is reported the same way as any other open failure: as a
 * [SherpaTranscriptionException].
 *
 * The decision in [create] is made from the registry entry, never from the model id:
 *  1. an entry of kind STREAMING takes the online path, opened through the streaming
 *     opener, labelled with the entry's language;
 *  2. an entry of kind OFFLINE takes the offline path: the files are opened through the
 *     offline opener and decoded in one call; a blank language means the model decides,
 *     so the engine wrap fills in the request's language;
 *  3. anything else (an entry whose kind and model type are not a supported pair, an
 *     entry without file names or limits, an id the registry does not know) is refused
 *     with "no streaming profile for this model", and no opener is called.
 * The entry's model type reaches the engine through [TransducerFiles.modelType].
 * The internal constructor's default offline opener is [UnavailableOfflineOpener], which always
 * fails, so a test that does not pass one gets an unusable engine for an offline model.
 *
 * The public constructor opens the real engines (streaming and offline); it refers to each
 * binding only inside a lambda, so this class loads without the native classes present.
 * The offline binding has not been compiled against the library yet.
 *
 * @property numThreads threads the engine may use for one decode (at least 1).
 */
class SherpaOnnxRecognizerFactory internal constructor(
    private val opener: NativeStreamingOpener,
    private val numThreads: Int,
    private val offlineOpener: NativeOfflineOpener = UnavailableOfflineOpener,
    private val lookup: (String) -> ModelEntry? = ModelRegistry::byId,
) : SherpaRecognizerFactory {

    /** The factory that opens the real engines with [numThreads] threads. */
    constructor(numThreads: Int = DEFAULT_NUM_THREADS) : this(
        NativeStreamingOpener { files, n -> SherpaOnnxBinding.open(files, n) },
        numThreads,
        NativeOfflineOpener { files, n -> OfflineRecognizerBinding.open(files, n) },
    )

    init {
        require(numThreads >= 1) { "numThreads must be at least 1" }
    }

    /**
     * Creates a recognizer for [model].
     *
     * @throws SherpaTranscriptionException if the model has no usable registry entry,
     *   its directory lacks a file, or the engine cannot be opened. The messages
     *   are fixed and carry no path or file name.
     */
    override fun create(model: SherpaModel): SherpaRecognizer {
        val entry = SpeechModels.usable(model.modelId, lookup)
            ?: throw SherpaTranscriptionException(NO_PROFILE_REFUSAL)
        val files = SpeechModels.locate(model.directory, entry)
            ?: throw SherpaTranscriptionException("the model files are missing or empty")
        val language = entry.language.orEmpty()
        return when (entry.kind) {
            ModelKind.STREAMING -> SherpaOnnxRecognizer(openOrRefuse { opener.open(files, numThreads) }, language)
            ModelKind.OFFLINE -> SherpaOfflineRecognizer(openOrRefuse { offlineOpener.open(files, numThreads) }, language)
        }
    }

    /** Runs [open] and turns any open failure, including a library that fails to link, into the fixed refusal. */
    private fun <T> openOrRefuse(open: () -> T): T = try {
        open()
    } catch (e: Exception) {
        throw SherpaTranscriptionException(OPEN_FAILED, e)
    } catch (e: LinkageError) {
        throw SherpaTranscriptionException(OPEN_FAILED, e)
    }

    companion object {
        /** A placeholder: the real value is to be measured on a device. */
        const val DEFAULT_NUM_THREADS = 2

        /** The fixed refusal text when a model has no usable registry entry (see [create]). */
        const val NO_PROFILE_REFUSAL = "no streaming profile for this model"

        private const val OPEN_FAILED = "the speech engine could not be opened"
    }
}
