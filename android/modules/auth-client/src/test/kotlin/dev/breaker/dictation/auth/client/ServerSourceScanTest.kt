package dev.breaker.dictation.auth.client

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reads the account-service main sources as text. They import no network, HTTP-client or crypto
 * package, and they print or log nothing. Comments and string literals are blanked first, so only
 * code is read. The planted-text test shows each pattern firing before the real sources are trusted.
 */
class ServerSourceScanTest {
    private val folder = "src/main/kotlin/dev/breaker/dictation/auth/client/"

    private val forbidden = Regex(
        """\b(print|println|printf|printStackTrace|Logger|Timber|MessageDigest|Cipher|SecureRandom|HttpURLConnection)\b""" +
            """|\bLog\.|\bSystem\.(out|err)\b|java\.util\.logging|javax\.crypto|java\.security|android\.util|java\.net\b""" +
            """|dev\.breaker\.dictation\.(http|crypto)\b""",
    )

    /** The main files whose names begin with Server, as name and text, sorted by name. */
    private fun serverSources(): List<Pair<String, String>> {
        val dir = File(folder)
        assertTrue("the main source folder must exist", dir.isDirectory)
        val files: List<File> = dir.listFiles()?.toList() ?: emptyList()
        return files
            .filter { it.isFile && it.name.startsWith("Server") && it.name.endsWith(".kt") }
            .sortedBy { it.name }
            .map { it.name to it.readText() }
    }

    @Test
    fun `the server service source imports no network or crypto package and prints nothing`() {
        val sources = serverSources()
        assertTrue("the service source ServerAuthService.kt must exist and be scanned", sources.any { it.first == "ServerAuthService.kt" })
        val found = sources.flatMap { (name, text) -> forbidden.findAll(codeOf(text)).map { "$name: ${it.value}" }.toList() }
        assertTrue("a forbidden name appears in the server sources: $found", found.isEmpty())
    }

    @Test
    fun `the scan fires on a planted violation and stays quiet on comments and strings`() {
        assertTrue("a network import was missed", forbidden.containsMatchIn(codeOf("import java.net.HttpURLConnection")))
        assertTrue("an HTTP-client import was missed", forbidden.containsMatchIn(codeOf("import dev.breaker.dictation.http.HttpTransport")))
        assertTrue("a crypto import was missed", forbidden.containsMatchIn(codeOf("import javax.crypto.Cipher")))
        assertTrue("a print call was missed", forbidden.containsMatchIn(codeOf("fun f() { println(\"x\") }")))
        assertTrue("a comment was flagged", !forbidden.containsMatchIn(codeOf("// println(\"x\") and java.net here")))
        assertTrue("a string was flagged", !forbidden.containsMatchIn(codeOf("val label = \"Log.d and java.net\"")))
    }
}

/**
 * The text of [source] with comments and string or char literals blanked out, so a scan reads code
 * only. A literal ends at its quote or at a line break.
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
