package dev.breaker.dictation.sync

import java.io.File
import java.io.StringWriter

/**
 * The phone adapter's source as text, for rules about code that cannot run on a plain JVM.
 *
 * Tests run with the module folder as the working directory, so the main sources are at
 * `src/main/kotlin`. Nothing here treats "not found" as an answer: a missing file, or a missing
 * function, comes back as a failure or as a null the caller must report.
 */
internal object AdapterSource {

    private const val FILE_NAME = "SqliteSyncQueueDatabase.kt"

    private val LITERAL = Regex("\"\"\"[\\s\\S]*?\"\"\"|\"(?:\\\\.|[^\"\\\\\\n])*\"")

    /** The adapter file's text. Fails, naming the folder, when there is not exactly one such file. */
    fun load(): String {
        val root = File("src/main/kotlin")
        val files = root.walkTopDown().filter { it.isFile && it.name == FILE_NAME }.toList()
        check(files.size == 1) {
            "android_sync: expected exactly one $FILE_NAME under ${root.absolutePath}, found ${files.size}"
        }
        val text = files.single().readText()
        check(text.contains("class SqliteSyncQueueDatabase")) {
            "android_sync: ${files.single().path} does not declare SqliteSyncQueueDatabase"
        }
        return text
    }

    /**
     * The source with block comments (nested too), KDoc and line comments replaced by spaces, line
     * breaks kept. String literals and character literals are copied as they are, so a `//` inside
     * a string is not taken for a comment.
     */
    fun code(source: String): String {
        val out = StringWriter()
        var i = 0
        while (i < source.length) {
            val c = source[i]
            val next = if (i + 1 < source.length) source[i + 1] else ' '
            if (source.startsWith("\"\"\"", i)) {
                val close = source.indexOf("\"\"\"", i + 3)
                val end = if (close < 0) source.length else close + 3
                out.append(source, i, end)
                i = end
            } else if (c == '"' || c == '\'') {
                val end = endOfQuoted(source, i, c)
                out.append(source, i, end)
                i = end
            } else if (c == '/' && next == '/') {
                while (i < source.length && source[i] != '\n') {
                    out.append(' ')
                    i++
                }
            } else if (c == '/' && next == '*') {
                var depth = 0
                while (i < source.length) {
                    if (source.startsWith("/*", i)) {
                        depth++
                        out.append("  ")
                        i += 2
                    } else if (source.startsWith("*/", i)) {
                        depth--
                        out.append("  ")
                        i += 2
                        if (depth == 0) break
                    } else {
                        out.append(if (source[i] == '\n') '\n' else ' ')
                        i++
                    }
                }
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private fun endOfQuoted(source: String, start: Int, quote: Char): Int {
        var j = start + 1
        while (j < source.length) {
            when (source[j]) {
                '\\' -> j++
                quote -> return j + 1
                '\n' -> return j
            }
            j++
        }
        return source.length
    }

    /** Every string literal in [code], quotes included. [code] is expected to be comment free. */
    fun literals(code: String): List<String> = LITERAL.findAll(code).map { it.value }.toList()

    /** The text from `fun name(` up to the next function or class declaration, or null when there is none. */
    fun segment(code: String, name: String): String? {
        val start = Regex("\\bfun\\s+(?:<[^>]*>\\s*)?$name\\s*\\(").find(code) ?: return null
        val next = Regex("\\n\\s*(?:(?:override|private|internal)\\s+)*(?:fun|class)\\b").find(code, start.range.last)
        return code.substring(start.range.first, next?.range?.first ?: code.length)
    }

    /** The index of the `}` that closes the `{` at [open], or -1 when it never closes. */
    fun closeOf(text: String, open: Int): Int {
        var depth = 0
        for (index in open until text.length) {
            if (text[index] == '{') depth++
            if (text[index] == '}' && --depth == 0) return index
        }
        return -1
    }
}
