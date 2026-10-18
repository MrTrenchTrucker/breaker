package dev.breaker.dictation.stt.ondevice

import java.io.File

/**
 * Picks the four files of a streaming transducer model out of an unpacked directory.
 *
 * The choice goes by which files are present, not by a fixed list of names:
 * - the encoder takes the int8 file when there is one, else the plain one;
 * - the decoder and the joiner take the plain file when there is one, else the int8 one;
 * - the token table must be named exactly `tokens.txt`.
 *
 * A file belongs to a role when its name starts with the role word followed by
 * a dash or a dot and ends with `.onnx`. A name is int8 when it ends with
 * `.int8.onnx`. When two names fit the same slot the one that sorts first is
 * taken, so the answer never depends on the order the names are listed in.
 */
internal object TransducerFileLocator {

    private const val TOKENS_NAME = "tokens.txt"
    private const val ONNX_SUFFIX = ".onnx"
    private const val INT8_SUFFIX = ".int8.onnx"

    /** The four chosen file names, not yet checked against the disk. */
    class Choice(val encoder: String, val decoder: String, val joiner: String, val tokens: String)

    /**
     * Chooses the four names from [names], or returns null when a role has no
     * file or `tokens.txt` is missing. Pure: it reads nothing from the disk.
     */
    fun choose(names: List<String>): Choice? {
        val sorted = names.sorted()
        val encoder = pick(sorted, "encoder", preferInt8 = true) ?: return null
        val decoder = pick(sorted, "decoder", preferInt8 = false) ?: return null
        val joiner = pick(sorted, "joiner", preferInt8 = false) ?: return null
        if (TOKENS_NAME !in sorted) return null
        return Choice(encoder, decoder, joiner, TOKENS_NAME)
    }

    /**
     * Lists [dir], chooses the four names, and returns the files only when each
     * one is a regular file with at least one byte. Returns null when [dir] is
     * not a directory, a role has no file, or a chosen file is empty, a
     * directory, or gone.
     */
    fun locate(dir: File): TransducerFiles? {
        val names = dir.list()?.toList() ?: return null
        val choice = choose(names) ?: return null
        val files = TransducerFiles(
            encoder = File(dir, choice.encoder),
            decoder = File(dir, choice.decoder),
            joiner = File(dir, choice.joiner),
            tokens = File(dir, choice.tokens),
        )
        val all = listOf(files.encoder, files.decoder, files.joiner, files.tokens)
        if (!all.all { it.isFile && it.length() > 0L }) return null
        return files
    }

    /** The first name of [role] in sorted [names], with the wanted kind first and the other kind as the fallback. */
    private fun pick(names: List<String>, role: String, preferInt8: Boolean): String? {
        val candidates = names.filter { isRoleFile(it, role) }
        val int8 = candidates.firstOrNull { it.endsWith(INT8_SUFFIX) }
        val plain = candidates.firstOrNull { !it.endsWith(INT8_SUFFIX) }
        return if (preferInt8) int8 ?: plain else plain ?: int8
    }

    private fun isRoleFile(name: String, role: String): Boolean {
        if (!name.startsWith(role) || !name.endsWith(ONNX_SUFFIX)) return false
        val next = name[role.length]
        return next == '-' || next == '.'
    }
}
