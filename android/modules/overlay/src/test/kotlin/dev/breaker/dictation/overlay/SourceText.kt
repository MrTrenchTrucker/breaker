package dev.breaker.dictation.overlay

import java.io.StringWriter

/**
 * Source text as the text rules read it: comments and KDoc are blanked, string and
 * character literals are kept.
 *
 * A rule word in a sentence is not a use of it, so comments go. A rule word inside a
 * string is content the program carries, so literals stay and a rule can see them.
 * Blanked characters become spaces and line breaks stay, so offsets and line counts
 * of the blanked text match the original.
 */
internal object SourceText {

    /** [source] with block comments (nested too), KDoc and line comments replaced by spaces. */
    fun code(source: String): String {
        val out = StringWriter()
        var i = 0
        while (i < source.length) {
            val c = source[i]
            if (source.startsWith("\"\"\"", i)) {
                i = keep(source, out, i, endOfRaw(source, i))
            } else if (c == '"' || c == '\'') {
                i = keep(source, out, i, endOfQuoted(source, i, c))
            } else if (source.startsWith("//", i)) {
                i = blank(source, out, i, endOfLine(source, i))
            } else if (source.startsWith("/*", i)) {
                i = blank(source, out, i, endOfBlock(source, i))
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /** The index of the `}` that closes the `{` at [open], or -1 when it never closes. */
    fun closeOf(text: String, open: Int): Int {
        var depth = 0
        for (index in open until text.length) {
            if (text[index] == '{') depth++
            if (text[index] == '}') {
                depth--
                if (depth == 0) return index
            }
        }
        return -1
    }

    /** The number of lines of [text]; a final line break does not start a new line. */
    fun lineCount(text: String): Int {
        val parts = text.split('\n')
        return if (parts.last().isEmpty()) parts.size - 1 else parts.size
    }

    /** The tokens of [tokens] that occur in [code], in the order listed. */
    fun hits(code: String, tokens: List<String>): List<String> = tokens.filter { code.contains(it) }

    /** Copies `source[from until end]` as it is and returns [end]. */
    private fun keep(source: String, out: StringWriter, from: Int, end: Int): Int {
        out.append(source, from, end)
        return end
    }

    /** Appends one space per character of `source[from until end]`, keeping line breaks, and returns [end]. */
    private fun blank(source: String, out: StringWriter, from: Int, end: Int): Int {
        for (k in from until end) out.append(if (source[k] == '\n') '\n' else ' ')
        return end
    }

    private fun endOfRaw(source: String, start: Int): Int {
        val close = source.indexOf("\"\"\"", start + 3)
        return if (close < 0) source.length else close + 3
    }

    private fun endOfQuoted(source: String, start: Int, quote: Char): Int {
        var j = start + 1
        while (j < source.length) {
            val c = source[j]
            if (c == '\\') {
                j += 2
            } else if (c == quote) {
                return j + 1
            } else if (c == '\n') {
                return j
            } else {
                j++
            }
        }
        return source.length
    }

    private fun endOfLine(source: String, start: Int): Int {
        val newline = source.indexOf('\n', start)
        return if (newline < 0) source.length else newline
    }

    private fun endOfBlock(source: String, start: Int): Int {
        var depth = 0
        var j = start
        while (j < source.length) {
            if (source.startsWith("/*", j)) {
                depth++
                j += 2
            } else if (source.startsWith("*/", j)) {
                depth--
                j += 2
                if (depth == 0) return j
            } else {
                j++
            }
        }
        return source.length
    }
}
