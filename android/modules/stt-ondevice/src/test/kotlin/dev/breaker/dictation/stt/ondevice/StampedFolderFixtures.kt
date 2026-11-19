package dev.breaker.dictation.stt.ondevice

import java.io.File

/**
 * Stamped folders for the user keyword check, loader and factory tests: the four file names of the keyword profile,
 * a token table that holds every fixed phrase token, and a stamp written over the files as they stand. The stamp
 * uses a made-up pin, because the stamp rules never read the pin.
 */
internal object StampedFolderFixtures {

    const val ENCODER: String = "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
    const val DECODER: String = "decoder-epoch-12-avg-2-chunk-16-left-64.onnx"
    const val JOINER: String = "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
    const val TOKENS: String = "tokens.txt"

    /** The four names, in the order the keyword profile lists them. */
    val NAMES: List<String> = listOf(ENCODER, DECODER, JOINER, TOKENS)

    /** The token table that holds every token of the fixed phrases; the shared test table. */
    fun tokenText(): String = KeywordsTestTokens.FILE_TEXT

    /** The token table with the line of [token] left out, so the fixed phrases cannot all be served from it. */
    fun tokenTextWithout(token: String): String =
        KeywordsTestTokens.FILE_TEXT.split('\n')
            .filter { it.isNotEmpty() && it.substringBeforeLast(' ') != token }
            .joinToString("\n", postfix = "\n")

    /** Writes the four files into [dir], one byte each, the token table as [tokens], and a stamp over them as they are. */
    fun stampedDir(dir: File, tokens: String = tokenText()): File {
        dir.mkdirs()
        for (name in NAMES) File(dir, name).writeText(if (name == TOKENS) tokens else "x")
        writeStamp(dir, NAMES)
        return dir
    }

    /** Writes the stamp of [dir] over the files [names], as they are now. */
    fun writeStamp(dir: File, names: List<String>) {
        val stamp = checkNotNull(UnpackedStamp.measure(dir, names, Fixtures.VALID_PIN) { ModelIntegrity.sha256(it) })
        File(dir, UnpackedStamp.FILE_NAME).writeText(stamp.encode())
    }
}
