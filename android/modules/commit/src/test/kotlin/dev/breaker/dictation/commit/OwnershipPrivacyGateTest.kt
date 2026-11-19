package dev.breaker.dictation.commit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the privacy shape of the take-start anchor: `TakeStartWindow` stores only the window id
 * and the package, its `toString` is a fixed text that carries no value, and no log call names
 * the anchor. The anchor is identifying metadata, never screen content, so a node or a text in
 * it, or a log line carrying it, is a leak.
 *
 * R2 reads the raw file because it pins the literal text of `toString`; the other two read the
 * comment free code. Every rule says which text it reads in its comment. Each rule holds on the
 * real files, is broken by at least two edited samples, and stays quiet on a harmless edit; an
 * edit whose target text is missing or occurs twice fails by name.
 */
internal class OwnershipPrivacyGateTest {

    /** The anchor and its value type. */
    private val anchor: String = "TakeStartAnchor.kt"

    private class Rule(
        val name: String,
        val pattern: String,
        val times: Int = 1,
        val file: String? = null,
        /** True when the rule reads the literal text too, not only the comment free code. */
        val raw: Boolean = false,
    ) {
        fun holds(code: String): Boolean = Regex(pattern).findAll(code).count() == times
        fun file(): String = file ?: "ANCHOR"
    }

    private class Sample(val rule: String, val old: String, val new: String)

    private class Quiet(val file: String, val old: String, val new: String)

    /**
     * R1 reads [anchor] stripped: the exact two member `TakeStartWindow` constructor. R2 reads
     * [anchor] raw: the exact fixed `toString`. R3 reads every main source of the module joined:
     * no `Log.x(...)` or `println(...)` whose arguments name `TakeStartAnchor`.
     */
    private val rules: List<Rule> = listOf(
        Rule(
            name = "ANCHOR_STORES_NO_NODE_AND_NO_TEXT",
            pattern = """\bdata\s+class\s+TakeStartWindow\s*\(\s*val\s+windowId\s*:\s*Int\s*\?\s*,\s*""" +
                """val\s+packageName\s*:\s*String\s*\?\s*\)""",
            file = "ANCHOR",
        ),
        Rule(
            name = "ANCHOR_TOSTRING_IS_A_FIXED_TEXT",
            pattern = "override fun toString\\(\\): String = \"TakeStartWindow\"",
            file = "ANCHOR",
            raw = true,
        ),
        Rule(
            name = "NO_LOG_CALL_NAMES_THE_ANCHOR",
            pattern = """\b(?:Log\s*\.\s*\w+|println)\s*\([^)]*\bTakeStartAnchor\b""",
            times = 0,
            file = "COMMIT_MAIN_ALL",
        ),
    )

    /** Harmless edits: comments only, each of which keeps every rule of its file true. */
    private val quiet: List<Quiet> = listOf(
        Quiet(
            "ANCHOR",
            "    /** The generation of the anchor held right now, or 0L when nothing is held (INACTIVE). */",
            "    // the generation\n    /** The generation of the anchor held right now, or 0L when nothing is held (INACTIVE). */",
        ),
        Quiet(
            "COMMIT_MAIN_ALL",
            "        TakeStartAnchor.capture(window)",
            "        // the anchor is stored\n        TakeStartAnchor.capture(window)",
        ),
    )

    /** Each sample edits one file so that the rule it names stops holding. */
    private val firing: List<Sample> = listOf(
        Sample(
            "ANCHOR_STORES_NO_NODE_AND_NO_TEXT",
            "data class TakeStartWindow(val windowId: Int?, val packageName: String?)",
            "data class TakeStartWindow(val windowId: Int?, val packageName: String?, val text: String?)",
        ),
        Sample(
            "ANCHOR_STORES_NO_NODE_AND_NO_TEXT",
            "data class TakeStartWindow(val windowId: Int?, val packageName: String?)",
            "data class TakeStartWindow(val windowId: Int?, val packageName: String?, val node: Any?)",
        ),
        Sample(
            "ANCHOR_TOSTRING_IS_A_FIXED_TEXT",
            "    override fun toString(): String = \"TakeStartWindow\"",
            "    override fun toString(): String = \"TakeStartWindow \" + windowId",
        ),
        Sample(
            "ANCHOR_TOSTRING_IS_A_FIXED_TEXT",
            "    override fun toString(): String = \"TakeStartWindow\"",
            "    override fun toString(): String = windowId.toString()",
        ),
        Sample(
            "NO_LOG_CALL_NAMES_THE_ANCHOR",
            "        TakeStartAnchor.capture(window)",
            "        println(TakeStartAnchor.capture(window))",
        ),
        Sample(
            "NO_LOG_CALL_NAMES_THE_ANCHOR",
            "        state.update { Holder(counter.incrementAndGet(), window ?: noFocusedField) }",
            "        println(TakeStartAnchor.toString())\n        state.update { Holder(counter.incrementAndGet(), window ?: noFocusedField) }",
        ),
    )

    /** [old] replaced by [new] once. Fails by name when the text is missing or occurs twice. */
    private fun edit(text: String, old: String, new: String): String {
        val at = text.indexOf(old)
        check(at >= 0) { "commit: a sample lost its text '$old'" }
        check(text.indexOf(old, at + 1) < 0) { "commit: a sample text occurs twice '$old'" }
        return text.substring(0, at) + new + text.substring(at + old.length)
    }

    /** Every main Kotlin source of the module, joined in path order. */
    private fun commitMainCode(): String {
        val sources = SourceFiles.mainSources()
        check(sources.isNotEmpty()) { "commit: no main source to scan" }
        return sources.keys.sorted().joinToString("\n") { sources.getValue(it) }
    }

    /** The text a rule reads, by key. */
    private fun textOf(key: String): String = when (key) {
        "ANCHOR" -> SourceFiles.mainSources()[anchor] ?: error("commit: the main source $anchor is missing")
        "COMMIT_MAIN_ALL" -> commitMainCode()
        else -> error("commit: no source named $key")
    }

    /** The code a rule reads, with [old] replaced by [new] once; raw rules keep the literal text. */
    private fun codeOf(rule: Rule, old: String? = null, new: String = ""): String {
        val text = textOf(rule.file())
        val edited = if (old == null) text else edit(text, old, new)
        return if (rule.raw) edited else SourceFiles.strip(edited).code
    }

    @Test
    fun `every ownership privacy rule holds on the real sources`() {
        val broken = rules.filter { rule -> !rule.holds(codeOf(rule)) }.map { it.name }
        assertTrue("commit: the anchor breaks rules $broken", broken.isEmpty())
    }

    @Test
    fun `the ownership privacy rules fire on their edited samples`() {
        val byName = rules.associateBy { it.name }
        val unfired = firing.filter { sample ->
            val rule = byName.getValue(sample.rule)
            rule.holds(codeOf(rule, sample.old, sample.new))
        }.map { "${it.rule}: '${it.old}' => '${it.new}'" }
        assertTrue("commit: samples that leave their rule true: $unfired", unfired.isEmpty())
    }

    @Test
    fun `the ownership privacy samples cover each rule at least twice`() {
        val names = rules.map { it.name }
        assertEquals("commit: a rule name is used twice", names.toSet().size, names.size)
        val unknown = firing.map { it.rule }.filter { it !in names }
        assertTrue("commit: samples of unknown rules: $unknown", unknown.isEmpty())
        val thin = names.filter { name -> firing.count { it.rule == name } < 2 }
        assertTrue("commit: rules with fewer than two firing samples: $thin", thin.isEmpty())
    }

    @Test
    fun `no ownership privacy rule fires on a harmless edit`() {
        val broken = quiet.flatMap { q ->
            val edited = edit(textOf(q.file), q.old, q.new)
            rules.filter { it.file() == q.file }.filter { rule ->
                !rule.holds(if (rule.raw) edited else SourceFiles.strip(edited).code)
            }.map { "${it.name} on '${q.old}'" }
        }
        assertTrue("commit: a harmless edit broke a rule: $broken", broken.isEmpty())
    }
}
