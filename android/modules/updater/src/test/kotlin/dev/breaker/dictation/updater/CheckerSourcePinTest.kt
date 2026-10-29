package dev.breaker.dictation.updater

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reads the checker's main sources as text and pins the rules that a call record cannot show: the session
 * token stays out of the code, only the current session is read, the byte cap is the named constant, and the
 * fixed sentences are plain text. Each scan has a firing control and a quiet control.
 */
internal class CheckerSourcePinTest {

    private val mainRoot = File("src/main/kotlin")

    private val checkerFile = "ReleaseUpdateChecker.kt"

    /** The text of the file with block and line comments removed, so that only code is scanned. */
    private fun stripComments(text: String): String =
        text.replace(Regex("/\\*[\\s\\S]*?\\*/"), " ").replace(Regex("//[^\\n]*"), " ")

    /** The words of the token family that the text holds in lower case. */
    private fun tokenWordsIn(text: String): List<String> {
        val lower = text.lowercase()
        return listOf("token", "bearer", "authorization").filter { lower.contains(it) }
    }

    /** The main sources of the module as text, by file name; fails when the folder holds none. */
    private fun mainSources(): Map<String, String> {
        val texts = mainRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .associate { it.name to it.readText() }
        assertTrue("the checker sources were not found under the main folder", texts.containsKey(checkerFile))
        return texts
    }

    /** The problems with one sentence, by name; empty when the sentence is plain ASCII text with no link. */
    private fun sentenceProblems(sentence: String): List<String> = listOfNotNull(
        if (sentence.any { it.code > 127 }) "not ascii" else null,
        if (sentence.contains("http")) "a link" else null,
        if (sentence.contains("://")) "a scheme" else null,
        if (Regex("[0-9a-f]{40,}").containsMatchIn(sentence)) "a long hex run" else null,
        if (sentence.contains("PRIVATE")) "a private marker" else null,
    )

    @Test
    fun `no main source holds the session token words in its code`() {
        assertTrue(tokenWordsIn(stripComments("val token = 1")).isNotEmpty())
        assertTrue(tokenWordsIn(stripComments("// token in a comment\nval count = 1")).isEmpty())
        for ((name, text) in mainSources()) {
            assertTrue("$name holds a token word in its code", tokenWordsIn(stripComments(text)).isEmpty())
        }
    }

    @Test
    fun `the checker reads only the current session from the auth service`() {
        val text = stripComments(mainSources().getValue(checkerFile))
        val used = Regex("auth\\.[A-Za-z]+").findAll(text).map { it.value }.toSet()
        assertEquals(setOf("auth.currentSession"), used)
        assertEquals(setOf("auth.login"), Regex("auth\\.[A-Za-z]+").findAll("auth.login(x)").map { it.value }.toSet())
    }

    @Test
    fun `the byte cap is the named constant of 256 MiB`() {
        assertEquals(268_435_456L, MAX_APK_BYTES)
    }

    @Test
    fun `the checker takes the byte cap as a constructor default of the named constant`() {
        val text = mainSources().getValue(checkerFile)
        assertTrue(text.contains("private val apkByteCap: Long = MAX_APK_BYTES,"))
    }

    @Test
    fun `every fixed sentence is plain ASCII text with no link, long hex run or private marker`() {
        assertTrue(sentenceProblems("see https://example.test/x").isNotEmpty())
        assertTrue(sentenceProblems("The update file could not be opened.").isEmpty())
        assertTrue(UpdateSentences.ALL.isNotEmpty())
        for (sentence in UpdateSentences.ALL) {
            assertTrue("a fixed sentence has a problem: ${sentenceProblems(sentence)}", sentenceProblems(sentence).isEmpty())
        }
    }

    @Test
    fun `a canonical version code text parses and prints back unchanged`() {
        for (text in listOf("1", "12", "2100000000")) {
            assertEquals(text, VersionCode.parse(text)?.toString())
        }
    }
}
