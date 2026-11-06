package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine's key comes from the one read of the addressed session and from nowhere else. The names of
 * the old key paths are not used in the code of the composition root or of any file under wiring/: no
 * session seam parameter, no default session placeholder, no separate key provider, and no direct
 * read of the stored session for a key. Comments and string literals are removed by the shared scanner
 * first, so a word in a sentence is not a use. Each word has a firing sample and a quiet sample, and a
 * hit is reported with the name of its file.
 */
internal class ServerKeyFromOneReadGateTest {

    private val rootName: String = "BreakerCompositionRoot.kt"

    /** The words, by the label a report carries. */
    private val words: Map<String, Regex> = linkedMapOf(
        "sessionToken" to Regex("""\bsessionToken\b"""),
        "NO_SESSION" to Regex("""\bNO_SESSION\b"""),
        "serverApiKey" to Regex("""\bserverApiKey\b"""),
        "currentSession(" to Regex("""\bcurrentSession\s*\("""),
    )

    /** One code sample per word, each using the word the way the old code did. */
    private val firing: Map<String, String> = linkedMapOf(
        "sessionToken" to "class A(private val sessionToken: () -> String?)",
        "NO_SESSION" to "private val NO_SESSION: () -> String? = { null }",
        "serverApiKey" to "class A { private val serverApiKey: () -> String? = { null } }",
        "currentSession(" to "class A { fun f(s: S) = s.currentSession()?.session }",
    )

    /** Samples that hold the words only in text, or hold longer names; none may be reported. */
    private val quiet: Map<String, String> = linkedMapOf(
        "a line comment" to "// sessionToken NO_SESSION serverApiKey currentSession(\nclass A",
        "a KDoc" to "/** Never uses sessionToken, NO_SESSION, serverApiKey or currentSession(). */\nclass A",
        "a block comment" to "/* sessionToken NO_SESSION serverApiKey currentSession( */ class A",
        "a string" to "class A { val s = \"sessionToken NO_SESSION serverApiKey currentSession(\" }",
        "a raw string" to "class A { val s = \"\"\"sessionToken NO_SESSION serverApiKey currentSession(\"\"\" }",
        "a name in backticks" to "class T { fun `reads sessionToken and currentSession(`() {} }",
        "longer names" to "class A { val sessionTokens = 1; val NO_SESSION_KIND = 2; val serverApiKeyOf = 3; " +
            "fun currentSessionOf() = 4; val mysessionToken = 5 }",
        "the addressed session" to "class A { fun f(a: () -> S?) = a() }",
    )

    /** The files the rule reads: the root and everything under wiring/. */
    private fun scanned(sources: Map<String, String>): Map<String, String> =
        sources.filterKeys { it == rootName || it.startsWith("wiring/") }

    private fun offences(sources: Map<String, String>): List<String> = AppSourceFiles.offences(scanned(sources), words)

    @Test
    fun `no old key path is named in the root or in a wiring file`() {
        val main: Map<String, String> = AppSourceFiles.mainKotlinSources()
        assertTrue("app: the scan did not reach the composition root", rootName in main.keys)
        assertTrue("app: the scan found no wiring file", main.keys.any { it.startsWith("wiring/") })
        assertEquals("app: an old key path is named in code", emptyList<String>(), offences(main))
    }

    @Test
    fun `each word is reported on its own sample with the name of the file`() {
        assertEquals("app: a word has no sample or a sample has no word", words.keys, firing.keys)
        for ((label, source) in firing) {
            assertEquals(
                "app: the scan missed $label in the root",
                listOf("$rootName: $label x1"),
                offences(mapOf(rootName to source)),
            )
            assertEquals(
                "app: the scan missed $label in a wiring file",
                listOf("wiring/Deep/A.kt: $label x1"),
                offences(mapOf("wiring/Deep/A.kt" to source)),
            )
        }
    }

    @Test
    fun `the same words in comments or strings or backticks or longer names are not reported`() {
        for ((label, source) in quiet) {
            assertEquals(
                "app: the scan flagged $label",
                emptyList<String>(),
                offences(mapOf(rootName to source, "wiring/A.kt" to source)),
            )
        }
    }

    @Test
    fun `a word inside a template hole is code and is reported`() {
        val source = "class A { fun f() = \"x \${sessionToken()}\" }"
        assertEquals(
            "app: a word inside a template hole was not reported",
            listOf("wiring/A.kt: sessionToken x1"),
            offences(mapOf("wiring/A.kt" to source)),
        )
    }

    @Test
    fun `only the root and the wiring folder are read`() {
        val source = firing.getValue("sessionToken")
        val elsewhere: Map<String, String> = mapOf("service/A.kt" to source, "host/A.kt" to source, "Other.kt" to source)
        assertEquals("app: a file outside the root and wiring was read", emptyList<String>(), offences(elsewhere))
        assertEquals(
            "app: a hit in wiring was not reported with its file",
            listOf("wiring/A.kt: sessionToken x1"),
            offences(elsewhere + ("wiring/A.kt" to source)),
        )
    }
}
