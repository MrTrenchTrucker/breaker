package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The overlay holds no words of its own: every sentence the user sees or a screen reader reads comes from the app.
 *
 * The main sources are read as text and every string literal that holds a letter is reported, except the
 * message of a failed `require`, `check` or `error`, which a developer reads and the user never does.
 * The allow list of whole literals is empty. Comments are blanked and string literals are read.
 */
class MainSourceWordsGateTest {

    private class Literal(val start: Int, val end: Int, val text: String)

    private val allowedCalls = Regex("""(?<![\w.])(?:require|check|error|requireNotNull|checkNotNull)\s*\(""")
    private val templateParts = Regex("""\$\{[^}]*\}|\$\w+|\\.""")
    private val letter = Regex("""[A-Za-z]""")

    /** Every string literal of [code] with its offsets; the text excludes the quotes. Character literals are skipped. */
    private fun literals(code: String): List<Literal> {
        val found = ArrayList<Literal>()
        var i = 0
        while (i < code.length) {
            when {
                code.startsWith("\"\"\"", i) -> {
                    val close = code.indexOf("\"\"\"", i + 3).let { if (it < 0) code.length else it }
                    found.add(Literal(i, minOf(close + 3, code.length), code.substring(i + 3, close)))
                    i = close + 3
                }
                code[i] == '"' -> {
                    var j = i + 1
                    while (j < code.length && code[j] != '"' && code[j] != '\n') j += if (code[j] == '\\') 2 else 1
                    found.add(Literal(i, minOf(j + 1, code.length), code.substring(i + 1, minOf(j, code.length))))
                    i = j + 1
                }
                code[i] == '\'' -> {
                    var j = i + 1
                    while (j < code.length && code[j] != '\'' && code[j] != '\n') j += if (code[j] == '\\') 2 else 1
                    i = j + 1
                }
                else -> i++
            }
        }
        return found
    }

    /** [code] with the inside of every literal replaced by x, so brackets in a sentence cannot confuse the matching. */
    private fun masked(code: String, literals: List<Literal>): String {
        val chars = code.toCharArray()
        for (literal in literals) for (k in literal.start + 1 until minOf(literal.end - 1, chars.size)) if (chars[k] != '\n') chars[k] = 'x'
        return String(chars)
    }

    /** The stretches of [masked] that are the brackets (and the braces after them) of a require, check or error call. */
    private fun allowedStretches(masked: String): List<IntRange> = allowedCalls.findAll(masked).mapNotNull { call ->
        var depth = 0
        var end = call.range.last
        while (end < masked.length) {
            if (masked[end] == '(') depth++
            if (masked[end] == ')' && --depth == 0) break
            end++
        }
        if (end >= masked.length) return@mapNotNull null
        var last = end
        val after = masked.substring(end + 1).trimStart()
        if (after.startsWith("{")) last = masked.indexOf('{', end + 1).let { open -> SourceText.closeOf(masked, open).let { if (it < 0) masked.length - 1 else it } }
        call.range.first..last
    }.toList()

    /** The literals of [source] that hold words outside a require, check or error message, as "line N: text". */
    private fun wordProblems(source: String): List<String> {
        val code = SourceText.code(source)
        val all = literals(code)
        val allowed = allowedStretches(masked(code, all))
        return all.filter { literal ->
            letter.containsMatchIn(literal.text.replace(templateParts, "")) && allowed.none { literal.start in it }
        }.map { "line ${code.substring(0, it.start).count { c -> c == '\n' } + 1}: \"${it.text.take(40)}\"" }
    }

    private fun assertFires(what: String, source: String) =
        assertTrue("overlay: control: $what must be reported, got ${wordProblems(source)}", wordProblems(source).isNotEmpty())

    private fun assertQuiet(what: String, source: String) =
        assertEquals("overlay: control: $what must not be reported", emptyList<String>(), wordProblems(source))

    /** A failure here means a main source holds a sentence or a word of its own, which belongs to the app. */
    @Test
    fun `no main source holds a literal with words outside a failed require, check or error`() {
        assertFires("a label", "val label = \"Microphone\"\n")
        assertFires("a fallback description", "contentDescription = face.description ?: \"Mic\"\n")
        assertFires("a default sentence for a blank notice", "fun f(t: String) = t.ifBlank { \"Say something\" }\n")
        assertFires("a literal in a function that also has a require", "fun f() {\n require(a > 0) { \"a is not positive\" }\n val s = \"Cancel\"\n}\n")
        assertFires("a raw string", "val s = \"\"\"Hello there\"\"\"\n")
        assertFires("a template with words around it", "val s = \"Level \$level\"\n")
        assertFires("a literal after a char literal holding a quote", "val q = '\"'\nval s = \"Send\"\n")
        assertFires("a message in a function called ensure", "fun f() { ensure(ok) { \"not ok\" } }\n")
        assertFires("a member that is only called check", "fun f() { ui.check(\"Cancel\") }\n")
        assertFires("a literal right after the closed message of a require", "fun f() { require(ok) { \"bad\" }; val s = \"Send\" }\n")
        assertQuiet("a require message", "fun f() { require(right >= left) { \"right edge \$right is left of the left edge \$left\" } }\n")
        assertQuiet("a check message with brackets in it", "fun f() { check(ok) { \"not ok (really) {here}\" } }\n")
        assertQuiet("an error message in the brackets", "fun f(): Nothing = error(\"bad state\")\n")
        assertQuiet("a require with a literal inside its condition", "fun f() { require(name.contains(\"x\")) { \"no x\" } }\n")
        assertQuiet("a require over several lines", "fun f() {\n requireNotNull(value) {\n \"value is missing\"\n }\n}\n")
        assertQuiet("empty literals, numbers, templates and escapes", "val a = \"\"\nval b = \"123\"\nval c = \"\$x\${y}\"\nval d = \"\\n\\t\"\nval e = joinToString(\"\")\nval f = 'a'\n")
        assertQuiet("words only in comments", "// val s = \"Microphone\"\n/* \"Cancel\" */\nval x = 1\n")

        val texts = ModuleFiles.mainTexts()
        assertTrue("overlay: the scan did not read the main folder: ${texts.keys}", texts.containsKey("FloatingTile.kt") && texts.containsKey("TileController.kt") && texts.containsKey("TileView.kt"))
        assertEquals(
            "overlay: main sources must hold no literal with words outside a failed require, check or error, by file",
            emptyMap<String, List<String>>(),
            texts.mapValues { wordProblems(it.value) }.filterValues { it.isNotEmpty() },
        )
    }
}
