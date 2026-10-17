package dev.breaker.dictation.stt.ondevice

import java.io.File

/**
 * Makes the word stream for a verified, unpacked model.
 *
 * [create] checks everything it can before anything is opened: the model has a
 * streaming profile, its directory holds the four files, and the install stamp
 * covers those files with the same sizes and modified times. A refusal is a
 * [SherpaTranscriptionException] with a fixed message that carries no path, file
 * name or model id, and it leaves nothing behind, because nothing was opened.
 * The check never hashes and deletes nothing: a stamp that differs only in a
 * modified time is refused here, and the loader re-checks it and refreshes it.
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
     * The install stamp of the model directory must exist and cover the four
     * files, with every size and modified time still equal to the stamp. Nothing
     * is hashed here and nothing is deleted.
     *
     * @throws SherpaTranscriptionException if the model has no streaming profile, its
     *   directory lacks one of the files, or the install stamp is missing or does not
     *   match. The messages are fixed and carry no path, file name or model id.
     */
    fun create(model: SherpaModel): FedWordStream {
        if (StreamingProfiles.forModel(model.modelId) == null) {
            throw SherpaTranscriptionException("no streaming profile for this model")
        }
        val files = TransducerFileLocator.locate(model.directory)
            ?: throw SherpaTranscriptionException("the model files are missing or empty")
        if (!stampMatches(model.directory, files)) {
            throw SherpaTranscriptionException("the model files are missing or empty")
        }
        return OnDeviceWordStream(files, numThreads, opener)
    }

    /** True when the install stamp covers the four chosen files and every size and modified time still matches. */
    private fun stampMatches(directory: File, files: TransducerFiles): Boolean {
        val stamp = UnpackedStamp.read(directory) ?: return false
        val names = listOf(files.encoder.name, files.decoder.name, files.joiner.name, files.tokens.name)
        return stamp.covers(names) && stamp.check(directory) is UnpackedStamp.Check.Match
    }

    companion object {
        /** The same value as the recognizer factory: a placeholder, to be measured on a device. */
        const val DEFAULT_NUM_THREADS = SherpaOnnxRecognizerFactory.DEFAULT_NUM_THREADS
    }
}
