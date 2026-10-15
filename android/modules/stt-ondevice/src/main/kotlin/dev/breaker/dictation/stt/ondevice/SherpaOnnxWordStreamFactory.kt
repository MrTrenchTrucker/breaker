package dev.breaker.dictation.stt.ondevice

/**
 * Makes the word stream for a verified, unpacked model.
 *
 * [create] checks everything it can before anything is opened: the model has a
 * streaming profile, and its directory holds the four files. A refusal is a
 * [SherpaTranscriptionException] with a fixed message that carries no path, file
 * name or model id, and it leaves nothing behind, because nothing was opened.
 *
 * [create] opens nothing. The stream opens the engine itself, in its start(), so
 * each call gives a new stream that is not running yet.
 *
 * The public constructor opens the real engine. It names the binding only inside
 * a lambda, so this class loads without the native classes present.
 *
 * @property numThreads threads the engine may use for one decode (at least 1).
 */
class SherpaOnnxWordStreamFactory internal constructor(
    private val opener: NativeStreamingOpener,
    private val numThreads: Int,
) {

    /** The factory that opens the real engine with [numThreads] threads. */
    constructor(numThreads: Int = DEFAULT_NUM_THREADS) : this(
        NativeStreamingOpener { files, n -> SherpaOnnxBinding.open(files, n) },
        numThreads,
    )

    init {
        require(numThreads >= 1) { "numThreads must be at least 1" }
    }

    /**
     * Creates a word stream for [model]. The stream is a [FedWordStream], so audio can be fed to it with feed().
     *
     * @throws SherpaTranscriptionException if the model has no streaming profile, or
     *   its directory lacks one of the files. The messages are fixed and carry no path,
     *   file name or model id.
     */
    fun create(model: SherpaModel): FedWordStream {
        if (StreamingProfiles.forModel(model.modelId) == null) {
            throw SherpaTranscriptionException("no streaming profile for this model")
        }
        val files = TransducerFileLocator.locate(model.directory)
            ?: throw SherpaTranscriptionException("the model files are missing or empty")
        return OnDeviceWordStream(files, numThreads, opener)
    }

    companion object {
        /** The same value as the recognizer factory: a placeholder, to be measured on a device. */
        const val DEFAULT_NUM_THREADS = SherpaOnnxRecognizerFactory.DEFAULT_NUM_THREADS
    }
}
