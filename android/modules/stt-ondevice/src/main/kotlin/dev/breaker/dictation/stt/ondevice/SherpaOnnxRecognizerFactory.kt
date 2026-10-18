package dev.breaker.dictation.stt.ondevice

/**
 * Creates the on-device recognizer for a verified, unpacked model.
 *
 * [create] checks everything it can before the native engine is touched: the
 * model has a streaming profile, and its directory holds the four files. Opening
 * the engine is the last step that can fail, so a failure there leaves nothing
 * behind to release. A native library that fails to link is reported the same
 * way as any other open failure: as a [SherpaTranscriptionException].
 *
 * The public constructor opens the real engine; it refers to the binding only
 * inside a lambda, so this class loads without the native classes present.
 *
 * @property numThreads threads the engine may use for one decode (at least 1).
 */
class SherpaOnnxRecognizerFactory internal constructor(
    private val opener: NativeStreamingOpener,
    private val numThreads: Int,
) : SherpaRecognizerFactory {

    /** The factory that opens the real engine with [numThreads] threads. */
    constructor(numThreads: Int = DEFAULT_NUM_THREADS) : this(
        NativeStreamingOpener { files, n -> SherpaOnnxBinding.open(files, n) },
        numThreads,
    )

    init {
        require(numThreads >= 1) { "numThreads must be at least 1" }
    }

    /**
     * Creates a recognizer for [model].
     *
     * @throws SherpaTranscriptionException if the model has no streaming profile,
     *   its directory lacks a file, or the engine cannot be opened. The messages
     *   are fixed and carry no path or file name.
     */
    override fun create(model: SherpaModel): SherpaRecognizer {
        val profile = StreamingProfiles.forModel(model.modelId)
            ?: throw SherpaTranscriptionException("no streaming profile for this model")
        val files = TransducerFileLocator.locate(model.directory)
            ?: throw SherpaTranscriptionException("the model files are missing or empty")
        val native = try {
            opener.open(files, numThreads)
        } catch (e: Exception) {
            throw SherpaTranscriptionException(OPEN_FAILED, e)
        } catch (e: LinkageError) {
            throw SherpaTranscriptionException(OPEN_FAILED, e)
        }
        return SherpaOnnxRecognizer(native, profile.language)
    }

    companion object {
        /** A placeholder: the real value is to be measured on a device. */
        const val DEFAULT_NUM_THREADS = 2

        private const val OPEN_FAILED = "the speech engine could not be opened"
    }
}
