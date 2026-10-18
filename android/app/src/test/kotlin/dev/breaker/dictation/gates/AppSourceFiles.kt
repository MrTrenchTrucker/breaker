package dev.breaker.dictation.gates

import java.io.File

/**
 * Source text reduced to what a rule may read: [code] has no comments and no
 * string literal text, and [literals] lists the text of every string literal
 * (the parts of a template that are code are cut out of it and kept in [code]).
 */
internal class Stripped(val code: String, val literals: List<String>)

/**
 * The app module's own files, found from the test working directory, and the
 * scanner the rule tests share.
 *
 * A rule about names is about code, so comments and literals are blanked before
 * a rule reads anything: a name in a sentence or in a string is not a use. A
 * template hole is code inside a literal, so it is kept. Every read is fresh and
 * nothing is cached, so tests share no state. Same technique as the commit
 * sub-module's gate helpers.
 */
internal object AppSourceFiles {
    private const val PACKAGE_PATH: String = "dev/breaker/dictation"

    /** The nearest folder at or above the working directory with a build script and a manifest. */
    val moduleRoot: File
        get() = findModuleRoot()

    /** The text of the file at [path] below `src/main` (the manifest or a resource), or null when there is none. */
    fun mainFileOrNull(path: String): String? {
        val file = File(moduleRoot, "src/main/$path")
        return if (file.isFile) file.readText(Charsets.UTF_8) else null
    }

    /** The text of the file at [path] below `src/main`; fails by name when the file is not there. */
    fun mainFile(path: String): String = mainFileOrNull(path) ?: error("app: the file src/main/$path is missing")

    /** Main Kotlin sources by path below the package folder, with forward slashes. */
    fun mainKotlinSources(): Map<String, String> =
        readAll(File(moduleRoot, "src/main/kotlin/$PACKAGE_PATH"), setOf("kt"))

    /** Main Kotlin and XML text by path below `src/main`, with forward slashes. */
    fun mainTextFiles(): Map<String, String> = readAll(File(moduleRoot, "src/main"), setOf("kt", "xml"))

    /** [source] without comments and literal text; fails on code that cannot be read. */
    fun strip(source: String): Stripped {
        val lexer = Lexer(source)
        val code: String = lexer.run()
        return Stripped(code, lexer.literals.toList())
    }

    /** One line per file and rule that has a match in the file's code: "file: rule xCOUNT". */
    fun offences(sources: Map<String, String>, rules: Map<String, Regex>): List<String> {
        val found: MutableList<String> = ArrayList()
        for ((name, text) in sources.toSortedMap()) {
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

    private fun findModuleRoot(): File {
        val start: File = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var folder: File? = start
        while (folder != null) {
            val hasBuildScript: Boolean = File(folder, "build.gradle.kts").isFile
            val hasManifest: Boolean = File(folder, "src/main/AndroidManifest.xml").isFile
            if (hasBuildScript && hasManifest) {
                return folder
            }
            folder = folder.parentFile
        }
        error("app: no folder with build.gradle.kts and src/main/AndroidManifest.xml at or above $start")
    }

    private fun readAll(root: File, extensions: Set<String>): Map<String, String> {
        check(root.isDirectory) { "app: no source folder at $root" }
        val files: List<File> = root.walkTopDown().filter { it.isFile && it.extension in extensions }.toList()
        val result: MutableMap<String, String> = LinkedHashMap()
        for (file in files.sortedBy { it.path }) {
            result[file.relativeTo(root).path.replace(File.separatorChar, '/')] = file.readText(Charsets.UTF_8)
        }
        return result
    }
}

/** One pass over Kotlin text: drops comments and literal text, keeps code and template holes. */
private class Lexer(private val text: String) {
    private var pos: Int = 0
    val literals: MutableList<String> = ArrayList()

    fun run(): String {
        val sink = StringBuilder()
        scanCode(sink, false)
        return sink.toString()
    }

    private fun scanCode(sink: StringBuilder, inHole: Boolean) {
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
        check(!inHole) { "app: a string template hole is never closed" }
    }

    /** Block comments nest in Kotlin. */
    private fun skipBlockComment(sink: StringBuilder) {
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
        error("app: a block comment is never closed")
    }

    /** A name in backticks, such as a test name, is a sentence and not code. */
    private fun skipQuotedName(sink: StringBuilder) {
        val close: Int = text.indexOf('`', pos + 1)
        check(close >= 0) { "app: a name in backticks is never closed" }
        pos = close + 1
        sink.append("``")
    }

    private fun skipCharLiteral(sink: StringBuilder) {
        pos += 1
        pos += if (pos < text.length && text[pos] == '\\') 2 else 1
        while (pos < text.length && text[pos] != '\'' && text[pos] != '\n') {
            pos += 1
        }
        check(pos < text.length && text[pos] == '\'') { "app: a character literal is never closed" }
        pos += 1
        sink.append("''")
    }

    private fun scanString(sink: StringBuilder, raw: Boolean) {
        val literal = StringBuilder()
        while (pos < text.length) {
            val c: Char = text[pos]
            if (raw && c == '"') {
                // A raw string ends at the last three quotes of a run, so a run of four is a quote and the end.
                var run = 0
                while (pos + run < text.length && text[pos + run] == '"') {
                    run += 1
                }
                pos += run
                if (run >= 3) {
                    literal.append("\"".repeat(run - 3))
                    literals.add(literal.toString())
                    sink.append("\"\"")
                    return
                }
                literal.append("\"".repeat(run))
            } else if (!raw && c == '"') {
                pos += 1
                literals.add(literal.toString())
                sink.append("\"\"")
                return
            } else {
                check(raw || c != '\n') { "app: a string literal is never closed" }
                if (!raw && c == '\\') {
                    literal.append(text, pos, minOf(pos + 2, text.length))
                    pos += 2
                } else if (c == '$' && pos + 1 < text.length && text[pos + 1] == '{') {
                    pos += 2
                    val hole = StringBuilder()
                    scanCode(hole, true)
                    sink.append(' ').append(hole.toString()).append(' ')
                } else if (c == '$' && pos + 1 < text.length && (text[pos + 1].isLetter() || text[pos + 1] == '_')) {
                    var end: Int = pos + 1
                    while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_')) {
                        end += 1
                    }
                    sink.append(' ').append(text.substring(pos + 1, end)).append(' ')
                    pos = end
                } else {
                    literal.append(c)
                    pos += 1
                }
            }
        }
        error("app: a string literal is never closed")
    }
}
