package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the About hook in `InfoScreensWiring`: the view is cast with a safe cast before the wiring is
 * made, the wiring is bound exactly once when About opens, and the held rows are cleared when the
 * screen closes. The real file is read with comments and literal text removed by the shared scanner,
 * so a word in a sentence is not a use.
 */
internal class InfoScreensHookGateTest {

    private val path: String = "kotlin/dev/breaker/dictation/wiring/InfoScreensWiring.kt"

    private val safeCast: Regex = Regex("""\bas\?\s*AboutControl\b""")
    private val bindCall: Regex = Regex("""\bit\s*\.\s*bind\s*\(\s*\)""")
    private val clearRows: Regex = Regex("""\baboutRows\s*=\s*null\b""")

    private class Rule(val name: String, val holds: (String) -> Boolean)

    private fun rules(): List<Rule> = listOf(
        Rule("ABOUT_VIEW_IS_SAFELY_CAST") { safeCast.containsMatchIn(it) },
        Rule("ABOUT_ROWS_ARE_BOUND_EXACTLY_ONCE") { bindCall.findAll(it).count() == 1 },
        Rule("ABOUT_ROWS_ARE_CLEARED_ON_CLOSE") { clearRows.containsMatchIn(it) },
    )

    private fun codeOf(text: String): String = AppSourceFiles.strip(text).code

    private fun problems(code: String): List<String> = rules().filterNot { it.holds(code) }.map { it.name }

    private fun realCode(): String = codeOf(AppSourceFiles.mainFile(path))

    @Test
    fun `the real About hook safely casts, binds once and clears its rows`() {
        val problems = problems(realCode())
        assertEquals("app: the About hook breaks a rule: $problems", emptyList<String>(), problems)
    }

    @Test
    fun `each broken form of the About hook breaks a rule`() {
        val real = realCode()
        val broken = linkedMapOf(
            "an unchecked cast" to "as? AboutControl",
            "a dropped bind" to ".also { it.bind() }",
            "rows never cleared" to "aboutRows = null",
        )
        for ((label, from) in broken) {
            val to = when (label) {
                "an unchecked cast" -> "as AboutControl"
                "a dropped bind" -> ".also { }"
                else -> "aboutRows = aboutRows"
            }
            assertTrue("app: the planted edit \"$label\" did not change the file", real.contains(from))
            val code = real.replace(from, to)
            assertTrue("app: the planted edit \"$label\" changed nothing", code != real)
            assertTrue(
                "app: the planted edit \"$label\" held every rule",
                problems(code).isNotEmpty(),
            )
        }
    }
}
