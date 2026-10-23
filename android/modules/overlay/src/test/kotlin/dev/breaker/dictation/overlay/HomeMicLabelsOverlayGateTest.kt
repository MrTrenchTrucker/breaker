package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Home mic's three labels are the app's ui's, never the overlay's: the overlay draws the mic
 * from the words the app passes in, so no overlay main source may retype the tap-to-talk words,
 * the cancel word or the done word as its own string literal. A retyped word would draw the same
 * and pass a shape check, so the fact is read from source. The rule reads every overlay main source
 * with comments and KDoc blanked and only the string literals kept, and needs none of the three to
 * appear as a literal: a word in a comment or in a code name (such as a cancel callback) is not a
 * use of it. Each check runs on a made-up body that obeys it (quiet), then on a made-up body that
 * breaks it (fires), and only then on the real overlay source (quiet). [ModuleFiles] supplies the
 * module's main sources; [SourceText] blanks comments, and [literalTexts] pulls the literal texts
 * back out so the rule reads content, not code.
 */
class HomeMicLabelsOverlayGateTest {

    private val labels: List<String> = listOf("Tap to talk", "Cancel", "Done")

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("overlay: control: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("overlay: control: $what must not be reported", emptyList<String>(), problems)

    /**
     * The text of every string literal in [source], comments and KDoc already blanked by [SourceText.code].
     * Raw strings, normal strings and template holes are read through; back-tick names and code are skipped,
     * and a character literal is skipped because it cannot hold one of the three multi-character words.
     */
    private fun literalTexts(source: String): List<String> {
        val text = SourceText.code(source)
        val out = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '"') {
                if (text.startsWith("\"\"\"", i)) {
                    val close = text.indexOf("\"\"\"", i + 3)
                    out.add(text.substring(i + 3, if (close < 0) text.length else close))
                    i = if (close < 0) text.length else close + 3
                } else {
                    val sb = StringBuilder()
                    i++
                    while (i < text.length && text[i] != '"' && text[i] != '\n') {
                        if (text[i] == '\\' && i + 1 < text.length) {
                            sb.append(text[i + 1]); i += 2
                        } else {
                            sb.append(text[i]); i++
                        }
                    }
                    i++
                    out.add(sb.toString())
                }
            } else if (c == '\'') {
                i++
                if (i < text.length && text[i] == '\\') i++
                while (i < text.length && text[i] != '\'' && text[i] != '\n') i++
                i++
            } else {
                i++
            }
        }
        return out
    }

    /** The labels retyped as string literals in [source] (comments blanked), or none. */
    private fun retypedIn(source: String): List<String> = labels.filter { it in literalTexts(source) }

    /** Every overlay main source with a label retyped, as "file: 'word'"; empty when none does. */
    private fun realOffences(): List<String> =
        ModuleFiles.mainTexts().toSortedMap().flatMap { (name, text) -> retypedIn(text).map { "$name: '$it'" } }

    // Made-up bodies for the in-memory controls. The one that breaks the rule carries a label on purpose.
    private val obeys: String = "fun f() = listOf(\"Not downloaded\", \"Menu\", \"About\")"
    private val breaks: String = "fun f() = listOf(\"Not downloaded\", \"Done\", \"About\")"
    private val onlyInComment: String = "// Cancel\nfun f() = 1"
    private val onlyInCode: String = "fun onCancel() { }"

    @Test
    fun `no overlay source retypes a home mic label`() {
        assertTrue("an overlay main source retypes a home mic label: ${realOffences()}", realOffences().isEmpty())
    }

    @Test
    fun `the no-retyped-label rule fires on a retyped literal`() {
        assertQuiet("a source with no label", retypedIn(obeys))
        assertFires("a source that retypes a label", retypedIn(breaks), "Done")
    }

    @Test
    fun `the rule ignores a label written only in a comment`() {
        assertQuiet("a label written in a comment", retypedIn(onlyInComment))
    }

    @Test
    fun `the rule ignores a code name that carries the word`() {
        assertQuiet("a code name that carries the word", retypedIn(onlyInCode))
    }

    @Test
    fun `the rule is quiet on the real overlay sources`() {
        assertQuiet("the real overlay sources", realOffences())
    }

}
