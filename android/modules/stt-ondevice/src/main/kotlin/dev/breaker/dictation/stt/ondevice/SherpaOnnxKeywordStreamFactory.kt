package dev.breaker.dictation.stt.ondevice

import java.io.File
import java.io.IOException

/**
 * Makes the word stream that listens for the fixed phrases, for a verified, unpacked keyword model.
 *
 * [create] checks everything it can before anything is written or opened: the model is the keyword model and has
 * an unpack profile, its directory holds the four files, the install stamp covers them with the same sizes and
 * modified times, and the fixed phrases fit the model's own token table. Only then does it ask the writer for
 * the keywords file and build the stream. A refusal is a [SherpaTranscriptionException] with a fixed message
 * that carries no path, file name or model id, and it leaves nothing behind. Nothing is hashed and nothing is
 * deleted here.
 *
 * [create] opens no engine. The stream opens the spotter itself in its start(). The stream is the same
 * single-slot word stream the speech engine uses, so the matcher downstream reads the heard words as it does
 * today. This class only builds the stream: nothing here downloads the keyword model, nothing turns spoken send
 * on, and waking by voice while idle is not built.
 *
 * The public constructor opens the real spotter. It names the binding only inside a lambda, so this class loads
 * without the native classes present.
 *
 * @property numThreads threads the engine may use for one decode (at least 1).
 */
class SherpaOnnxKeywordStreamFactory internal constructor(
    private val writer: KeywordsFileWriter,
    private val numThreads: Int,
    private val openerFor: (File) -> NativeStreamingOpener,
) {

    /** The factory that opens the real spotter with [numThreads] threads and keywords written by [writer]. */
    constructor(writer: KeywordsFileWriter, numThreads: Int = DEFAULT_NUM_THREADS) : this(
        writer,
        numThreads,
        { file -> KeywordSpotterBinding(file) },
    )

    init {
        require(numThreads >= 1) { "numThreads must be at least 1" }
    }

    /**
     * Creates a word stream for [model]. The stream is a [FedWordStream], so audio can be fed to it with feed().
     *
     * @throws SherpaTranscriptionException if the model is not the keyword model or has no unpack profile, its
     *   directory lacks one of the files, the install stamp is missing or does not match, the token table is
     *   unreadable or too large or lacks a token of the fixed phrases, or the keywords file cannot be written.
     *   The messages are fixed and carry no path, file name or model id.
     */
    fun create(model: KeywordModel): FedWordStream {
        if (model.modelId != KEYWORD_MODEL_ID || ExtractionProfiles.forModel(model.modelId) == null) {
            throw SherpaTranscriptionException(MESSAGE_PROFILE)
        }
        val files = TransducerFileLocator.locate(model.directory)
            ?: throw SherpaTranscriptionException(MESSAGE_FILES)
        if (!stampMatches(model.directory, files)) throw SherpaTranscriptionException(MESSAGE_FILES)
        val tokens = readTokens(files.tokens) ?: throw SherpaTranscriptionException(MESSAGE_TOKENS)
        val text = KeywordsFileText.render(FixedPhrases.all())
        if (KeywordsFileText.refusal(text, tokens) != null) throw SherpaTranscriptionException(MESSAGE_TOKENS)
        val keywordsFile = try {
            writer.write(text)
        } catch (failed: IOException) {
            throw SherpaTranscriptionException(MESSAGE_WRITE)
        }
        return OnDeviceWordStream(files, numThreads, openerFor(keywordsFile))
    }

    /** True when the install stamp covers the four chosen files and every size and modified time still matches. */
    private fun stampMatches(directory: File, files: TransducerFiles): Boolean {
        val stamp = UnpackedStamp.read(directory) ?: return false
        val names = listOf(files.encoder.name, files.decoder.name, files.joiner.name, files.tokens.name)
        return stamp.covers(names) && stamp.check(directory) is UnpackedStamp.Check.Match
    }

    /** The tokens of the table, or null when it is larger than [MAX_TOKENS_BYTES] or cannot be read. */
    private fun readTokens(file: File): Set<String>? {
        return try {
            if (file.length() > MAX_TOKENS_BYTES) null else KeywordTokenFile.tokensOf(file.readText(Charsets.UTF_8))
        } catch (failed: IOException) {
            null
        }
    }

    companion object {
        /** The same value as the speech factory: a placeholder, to be measured on a device. */
        const val DEFAULT_NUM_THREADS = SherpaOnnxRecognizerFactory.DEFAULT_NUM_THREADS

        /** The largest token table read, in bytes; the shipped table is about 5 KB, so this is a wide margin. */
        internal const val MAX_TOKENS_BYTES = 1_048_576L

        internal const val MESSAGE_PROFILE = "no keyword profile for this model"
        internal const val MESSAGE_FILES = "the keyword model files are missing or empty"
        internal const val MESSAGE_TOKENS = "the keyword list does not fit the keyword model"
        internal const val MESSAGE_WRITE = "the keyword list could not be written"
    }
}
