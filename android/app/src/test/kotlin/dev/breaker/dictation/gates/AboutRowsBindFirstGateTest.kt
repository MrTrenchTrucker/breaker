package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the About check wiring's one drawing rule: `bind()` is the only caller of `control.bind` and
 * the only place the first `show()` is reached, so nothing draws before the screen is bound. The real
 * file is read with comments and literal text removed by the shared scanner; each bad form is shown
 * to break a rule and the prose forms to hold them all.
 */
internal class AboutRowsBindFirstGateTest {

    private val path: String = "kotlin/dev/breaker/dictation/wiring/AboutControlWiring.kt"

    private class Rule(val name: String, val holds: (String) -> Boolean)

    private val bindCall: Regex = Regex("""\bcontrol\s*\.\s*bind\s*\(""")
    private val showCall: Regex = Regex("""\bcontrol\s*\.\s*show\s*\(""")
    private val initBlock: Regex = Regex("""\binit\s*\{""")

    private fun rules(): List<Rule> = listOf(
        Rule("CONTROL_BIND_IS_CALLED_EXACTLY_ONCE") { bindCall.findAll(it).count() == 1 },
        Rule("CONTROL_SHOW_IS_CALLED_EXACTLY_ONCE") { showCall.findAll(it).count() == 1 },
        Rule("NOTHING_DRAWS_BEFORE_BIND_THERE_IS_NO_INIT_BLOCK") { initBlock.findAll(it).count() == 0 },
        Rule("SHOW_IS_CALLED_FROM_BIND") {
            Regex("""\bfun\s+bind\s*\(\s*\)\s*\{[^}]*?\bshow\s*\(\s*\)""").containsMatchIn(it)
        },
        Rule("SHOW_IS_CALLED_FROM_ON_CHECK") {
            Regex("""\bfun\s+onCheck\s*\(\s*\)\s*\{[^}]*?\bshow\s*\(\s*\)""").containsMatchIn(it)
        },
        Rule("THE_ONLY_DRAW_CALL_IS_INSIDE_SHOW") {
            Regex("""\bfun\s+show\s*\(\s*\)\s*\{[^}]*?\bcontrol\s*\.\s*show\s*\(""").containsMatchIn(it)
        },
    )

    private fun codeOf(text: String): String = AppSourceFiles.strip(text).code

    private fun problems(code: String): List<String> = rules().filterNot { it.holds(code) }.map { it.name }

    private fun realCode(): String = codeOf(AppSourceFiles.mainFile(path))

    @Test
    fun `the real About wiring draws only from bind and from a check`() {
        val problems = problems(realCode())
        assertEquals("app: AboutControlWiring breaks a bind-first rule", emptyList<String>(), problems)
    }

    @Test
    fun `the gate names its rules`() {
        assertTrue("app: the bind-first gate must name at least four rules", rules().size >= 4)
    }

    @Test
    fun `a show moved into an init block is caught`() {
        val real = AppSourceFiles.mainFile(path)
        val moved = real
            .replace(
                "        control.bind(::onCheck, ::onInstall)\n        show()\n",
                "        control.bind(::onCheck, ::onInstall)\n",
            )
            .replace("    fun bind() {", "    init {\n        show()\n    }\n\n    fun bind() {")
        assertTrue("app: the planted edit did not change the file", moved != real)
        val problems = problems(codeOf(moved))
        assertTrue(
            "app: the planted init-block draw was not caught, found $problems",
            "NOTHING_DRAWS_BEFORE_BIND_THERE_IS_NO_INIT_BLOCK" in problems,
        )
        assertTrue(
            "app: the planted edit should also drop the draw from bind, found $problems",
            "SHOW_IS_CALLED_FROM_BIND" in problems,
        )
    }

    @Test
    fun `each bad form of the bind-first rule breaks it`() {
        val real = AppSourceFiles.mainFile(path)
        val bad: Map<String, String> = linkedMapOf(
            "an init block that draws" to real.replace(
                "    fun bind() {",
                "    init {\n        show()\n    }\n\n    fun bind() {",
            ),
            "an init block that calls the control directly" to real.replace(
                "    fun bind() {",
                "    init {\n        control.show(admin, updateAvailable, installReady, checking, installing, failed, upToDate)\n    }\n\n    fun bind() {",
            ),
            "a second draw call" to real.replace(
                "            upToDate = upToDate,\n        )",
                "            upToDate = upToDate,\n        )\n        control.show(admin, updateAvailable, installReady, checking, installing, failed, upToDate)",
            ),
            "no draw in bind" to real.replace(
                "        control.bind(::onCheck, ::onInstall)\n        show()\n",
                "        control.bind(::onCheck, ::onInstall)\n",
            ),
            "no draw in onCheck" to real.replace(
                "        upToDate = false\n        show()\n",
                "        upToDate = false\n",
            ),
        )
        for ((label, text) in bad) {
            assertTrue("app: the planted edit \"$label\" did not change the file", text != real)
            val problems = problems(codeOf(text))
            assertTrue("app: the bad form \"$label\" held every rule, found $problems", problems.isNotEmpty())
        }
    }

    @Test
    fun `prose and a trailing comment about the rule are not a break`() {
        val real = AppSourceFiles.mainFile(path)
        val quiet: List<String> = listOf(
            real + "\n// init { show() } control.show( control.bind(\n",
            real + "\n/** The init block never calls show(). */\n",
            real.replace("    private fun show() {", "    private fun show() { // show()"),
        )
        for (text in quiet) {
            assertEquals("app: a prose form broke a rule", emptyList<String>(), problems(codeOf(text)))
        }
    }
}
