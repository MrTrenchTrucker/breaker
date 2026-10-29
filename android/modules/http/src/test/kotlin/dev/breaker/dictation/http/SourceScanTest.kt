package dev.breaker.dictation.http

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins three rules by reading the module's own main sources as text: no parameter has a
 * default value, no timeout or cap number is set outside CallLimits, and no log, print or
 * crypto name appears. Comments and string literals are blanked first, so only code is read.
 * The last test proves each scan fires on a planted violation and stays quiet on clean code.
 */
class SourceScanTest {

    private val expectedFiles = setOf(
        "HttpTransport.kt",
        "CallMethod.kt",
        "CallLimits.kt",
        "CallRequest.kt",
        "BytesReply.kt",
        "StreamReply.kt",
        "HttpRefusal.kt",
    )

    /** The main Kotlin files of the module, as name and text. */
    private fun mainSources(): List<Pair<String, String>> {
        val root = File("src/main/kotlin/dev/breaker/dictation/http")
        assertTrue("the main source tree was not found at ${root.absolutePath}", root.isDirectory)
        val files: List<File> = root.listFiles()?.toList() ?: emptyList()
        return files
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.name }
            .map { it.name to it.readText() }
    }

    @Test
    fun `main sources are found and the module holds the expected files`() {
        val names = mainSources().map { it.first }.toSet()
        assertTrue("expected module files are missing from $names", names.containsAll(expectedFiles))
    }

    @Test
    fun `no constructor or function parameter has a default value`() {
        val found = mainSources().flatMap { (name, text) -> defaultsIn(codeOf(text)).map { "$name: $it" } }
        assertTrue("a parameter has a default value: $found", found.isEmpty())
    }

    @Test
    fun `no timeout or cap literal sits outside CallLimits`() {
        val found = mainSources()
            .filter { it.first != "CallLimits.kt" }
            .flatMap { (name, text) -> literalsIn(codeOf(text)).map { "$name: $it" } }
        assertTrue("a timeout or cap is set outside CallLimits: $found", found.isEmpty())
    }

    @Test
    fun `no identifier in the main code names a default`() {
        val found = mainSources().flatMap { (name, text) -> defaultWordsIn(codeOf(text)).map { "$name: $it" } }
        assertTrue("an identifier names a default: $found", found.isEmpty())
    }

    @Test
    fun `no log print or crypto name appears in the main sources`() {
        val found = mainSources().flatMap { (name, text) -> forbiddenIn(codeOf(text)).map { "$name: $it" } }
        assertTrue("a log, print or crypto name appears: $found", found.isEmpty())
    }

    @Test
    fun `each scan fires on a planted violation and stays quiet on clean code`() {
        assertTrue("the default scan missed a class default", defaultsIn(codeOf("class A(val x: Int = 1)")).isNotEmpty())
        assertTrue(
            "the default scan missed a function default",
            defaultsIn(codeOf("fun f(a: Long, b: Int = 2): Unit {}")).isNotEmpty(),
        )
        assertTrue(
            "the default scan flagged a generic type",
            defaultsIn(codeOf("class A(val x: Map<String, Int>, val y: Int)")).isEmpty(),
        )
        assertTrue("the literal scan missed a timeout", literalsIn(codeOf("val connectTimeoutMs = 1_500")).isNotEmpty())
        assertTrue("the literal scan missed a cap", literalsIn(codeOf("private val maxCap: Long = 65536L")).isNotEmpty())
        assertTrue("the literal scan flagged a plain count", literalsIn(codeOf("val count = 42")).isEmpty())
        assertTrue("the forbidden scan missed a print", forbiddenIn(codeOf("fun f() { println(\"x\") }")).isNotEmpty())
        assertTrue("the forbidden scan missed a crypto import", forbiddenIn(codeOf("import javax.crypto.Cipher")).isNotEmpty())
        assertTrue("the forbidden scan flagged a comment", forbiddenIn(codeOf("// println(\"x\") in a comment")).isEmpty())
        assertTrue("the forbidden scan flagged a string", forbiddenIn(codeOf("val s = \"Log.d\"")).isEmpty())
        assertTrue(
            "the default-name scan missed a default constant",
            defaultWordsIn(codeOf("const val DEFAULT_READ_TIMEOUT_MS = 1")).isNotEmpty(),
        )
        assertTrue(
            "the default-name scan flagged a default word inside a comment",
            defaultWordsIn(codeOf("// const val DEFAULT_READ_TIMEOUT_MS = 1")).isEmpty(),
        )
        assertTrue(
            "the default-name scan flagged a default word inside a string",
            defaultWordsIn(codeOf("val s = \"DEFAULT_READ_TIMEOUT_MS\"")).isEmpty(),
        )
    }
}

private val HEADER = Regex("""\b(fun|class|constructor)\b[^(){};=]*\(""")

private val LITERAL = Regex("""(?i)\b\w*(timeout|cap|limit|max)\w*\b\s*(:\s*[\w?]+\s*)?=\s*\d""")

private val FORBIDDEN = Regex(
    """\b(print|println|printf|printStackTrace|Logger|Timber|MessageDigest|Cipher|SecureRandom)\b""" +
        """|\bLog\.|\bSystem\.(out|err)\b|java\.util\.logging|javax\.crypto|java\.security|android\.util""",
)

/** Parameter lists in [code] that hold an equals sign outside nested parentheses, which is a default value. */
private fun defaultsIn(code: String): List<String> =
    HEADER.findAll(code).mapNotNull { match ->
        val close = closingParen(code, match.range.last) ?: return@mapNotNull null
        val params = code.substring(match.range.last + 1, close)
        if (topLevelEquals(params)) params.trim() else null
    }.toList()

/** Index of the parenthesis that closes the one at [open], or null when the text ends first. */
private fun closingParen(code: String, open: Int): Int? {
    var depth = 0
    for (i in open until code.length) {
        when (code[i]) {
            '(' -> depth++
            ')' -> {
                depth--
                if (depth == 0) return i
            }
        }
    }
    return null
}

/** Whether [params] holds an equals sign outside any nested parentheses. */
private fun topLevelEquals(params: String): Boolean {
    var depth = 0
    for (c in params) {
        when (c) {
            '(' -> depth++
            ')' -> depth--
            '=' -> if (depth == 0) return true
        }
    }
    return false
}

/** Named timeouts and caps that are assigned a number in [code]. */
private fun literalsIn(code: String): List<String> = LITERAL.findAll(code).map { it.value.trim() }.toList()

/** Log, print and crypto names found in [code]. */
private fun forbiddenIn(code: String): List<String> = FORBIDDEN.findAll(code).map { it.value }.toList()

private val DEFAULT_WORD = Regex("""(?i)\w*default\w*""")

/** Identifiers in [code] that contain the word default, in any letter case. */
private fun defaultWordsIn(code: String): List<String> = DEFAULT_WORD.findAll(code).map { it.value }.toList()

/**
 * The text of [source] with comments and string or char literals blanked out, so the scans
 * read code only. A literal ends at its quote or at a line break.
 *
 * Known limit: a triple-quoted raw string is not recognised. A single-line one is blanked, but
 * the lines of a raw string that spans lines are read as code, so the main code must not use one.
 */
private fun codeOf(source: String): String {
    val out = StringBuilder(source.length)
    var i = 0
    while (i < source.length) {
        val c = source[i]
        val next = source.getOrElse(i + 1) { ' ' }
        when {
            c == '/' && next == '/' -> {
                while (i < source.length && source[i] != '\n') i++
            }
            c == '/' && next == '*' -> {
                val end = source.indexOf("*/", i + 2)
                i = if (end < 0) source.length else end + 2
                out.append(' ')
            }
            c == '"' || c == '\'' -> {
                i = endOfLiteral(source, i)
                out.append(' ')
            }
            else -> {
                out.append(c)
                i++
            }
        }
    }
    return out.toString()
}

/** The index just past the literal that opens at [start]. */
private fun endOfLiteral(source: String, start: Int): Int {
    val quote = source[start]
    var i = start + 1
    while (i < source.length && source[i] != quote && source[i] != '\n') {
        i += if (source[i] == '\\') 2 else 1
    }
    return if (i < source.length && source[i] == quote) i + 1 else minOf(i, source.length)
}
