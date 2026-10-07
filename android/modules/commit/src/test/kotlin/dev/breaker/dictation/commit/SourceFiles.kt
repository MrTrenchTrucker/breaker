package dev.breaker.dictation.commit

import java.io.File

/**
 * Source text reduced to what a rule may read: [code] has no comments and no
 * string literal text, and [templates] lists the code of every string template
 * (the part after a dollar sign) found inside a literal.
 */
internal class Stripped(val code: String, val templates: List<String>)

/**
 * The module's own Kotlin sources, found from the test working directory, and the
 * scanner that the structure and rule tests share.
 *
 * A rule about references is about code, so comments and literals are removed
 * before a rule reads anything: a name in a sentence or in a string is not a
 * reference. A template hole is code inside a literal, so it is kept.
 */
internal object SourceFiles {
    private const val PACKAGE_PATH: String = "dev/breaker/dictation/commit"

    private val workingDirectory: File = File(System.getProperty("user.dir") ?: ".").absoluteFile

    /** The nearest folder at or above the working directory with a build script and the main sources. */
    val moduleRoot: File by lazy { findModuleRoot() }

    /** Main sources by path below the package folder, with forward slashes. */
    fun mainSources(): Map<String, String> = readAll(File(moduleRoot, "src/main/kotlin/$PACKAGE_PATH"))

    /** Test sources by path below the package folder, with forward slashes. */
    fun testSources(): Map<String, String> = readAll(File(moduleRoot, "src/test/kotlin/$PACKAGE_PATH"))

    /** [source] without comments and literal text; fails on code that cannot be read. */
    fun strip(source: String): Stripped {
        val lexer = Lexer(source)
        val code: String = lexer.run()
        return Stripped(code, lexer.templates.toList())
    }

    /** One line per file and rule that has a match in the file's code: "file: rule xCOUNT". */
    fun offences(sources: Map<String, String>, rules: Map<String, Regex>): List<String> {
        val found: MutableList<String> = ArrayList()
        for ((name, text) in sources) {
            val code: String = strip(text).code
            for ((label, rule) in rules) {
                val hits: Int = rule.findAll(code).count()
                if (hits > 0) {
                    found.add("$name: $label x$hits")
                }
            }
        }
        return found
    }

    /** Names of the files whose code matches [rule], in name order. */
    fun filesMatching(sources: Map<String, String>, rule: Regex): List<String> =
        sources.keys.sorted().filter { name -> rule.containsMatchIn(strip(sources.getValue(name)).code) }

    /** How many times the code of [source] matches [rule]. */
    fun countIn(source: String, rule: Regex): Int = rule.findAll(strip(source).code).count()

    private fun findModuleRoot(): File {
        var folder: File? = workingDirectory
        while (folder != null) {
            val hasBuildScript: Boolean = File(folder, "build.gradle.kts").isFile
            val hasMain: Boolean = File(folder, "src/main/kotlin/$PACKAGE_PATH").isDirectory
            if (hasBuildScript && hasMain) {
                return folder
            }
            folder = folder.parentFile
        }
        error("commit: no folder with a build script and main sources at or above $workingDirectory")
    }

    private fun readAll(root: File): Map<String, String> {
        check(root.isDirectory) { "commit: no source folder at $root" }
        val files: List<File> = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val result: MutableMap<String, String> = LinkedHashMap()
        for (file in files.sortedBy { it.path }) {
            result[file.relativeTo(root).path.replace(File.separatorChar, '/')] = file.readText()
        }
        return result
    }
}

/** One pass over Kotlin text: drops comments and literal text, keeps code and template holes. */
private class Lexer(private val text: String) {
    private var pos: Int = 0
    val templates: MutableList<String> = ArrayList()

    fun run(): String {
        val sink = StringBuffer()
        scanCode(sink, false)
        return sink.toString()
    }

    private fun scanCode(sink: StringBuffer, inHole: Boolean) {
        var depth = 0
        while (pos < text.length) {
            val c: Char = text[pos]
            if (text.startsWith("//", pos)) {
                while (pos < text.length && text[pos] != '\n') {
                    pos += 1
                }
            } else if (text.startsWith("/*", pos)) {
                skipBlockComment(sink)
            } else if (text.startsWith("\"\"\"", pos)) {
                pos += 3
                scanString(sink, true)
            } else if (c == '"') {
                pos += 1
                scanString(sink, false)
            } else if (c == '`') {
                skipQuotedName(sink)
            } else if (c == '\'') {
                skipCharLiteral(sink)
            } else if (inHole && c == '}' && depth == 0) {
                pos += 1
                return
            } else {
                if (c == '{') {
                    depth += 1
                } else if (c == '}') {
                    depth -= 1
                }
                sink.append(c)
                pos += 1
            }
        }
        check(!inHole) { "commit: a string template hole is never closed" }
    }

    /** Block comments nest in Kotlin. */
    private fun skipBlockComment(sink: StringBuffer) {
        var depth = 0
        while (pos < text.length) {
            if (text.startsWith("/*", pos)) {
                depth += 1
                pos += 2
            } else if (text.startsWith("*/", pos)) {
                depth -= 1
                pos += 2
                if (depth == 0) {
                    sink.append(' ')
                    return
                }
            } else {
                if (text[pos] == '\n') {
                    sink.append('\n')
                }
                pos += 1
            }
        }
        error("commit: a block comment is never closed")
    }

    /** A name in backticks, such as a test name, is a sentence and not code. */
    private fun skipQuotedName(sink: StringBuffer) {
        val close: Int = text.indexOf('`', pos + 1)
        check(close >= 0) { "commit: a name in backticks is never closed" }
        pos = close + 1
        sink.append("``")
    }

    private fun skipCharLiteral(sink: StringBuffer) {
        pos += 1
        pos += if (pos < text.length && text[pos] == '\\') 2 else 1
        while (pos < text.length && text[pos] != '\'' && text[pos] != '\n') {
            pos += 1
        }
        check(pos < text.length && text[pos] == '\'') { "commit: a character literal is never closed" }
        pos += 1
        sink.append("''")
    }

    private fun scanString(sink: StringBuffer, raw: Boolean) {
        while (pos < text.length) {
            val c: Char = text[pos]
            if (raw && text.startsWith("\"\"\"", pos)) {
                pos += 3
                sink.append("\"\"")
                return
            }
            if (!raw && c == '"') {
                pos += 1
                sink.append("\"\"")
                return
            }
            check(raw || c != '\n') { "commit: a string literal is never closed" }
            if (!raw && c == '\\') {
                pos += 2
            } else if (c == '$' && pos + 1 < text.length && text[pos + 1] == '{') {
                pos += 2
                val hole = StringBuffer()
                scanCode(hole, true)
                templates.add(hole.toString())
                sink.append(' ').append(hole.toString()).append(' ')
            } else if (c == '$' && pos + 1 < text.length && (text[pos + 1].isLetter() || text[pos + 1] == '_')) {
                var end: Int = pos + 1
                while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_')) {
                    end += 1
                }
                val name: String = text.substring(pos + 1, end)
                templates.add(name)
                sink.append(' ').append(name).append(' ')
                pos = end
            } else {
                pos += 1
            }
        }
        error("commit: a string literal is never closed")
    }
}
