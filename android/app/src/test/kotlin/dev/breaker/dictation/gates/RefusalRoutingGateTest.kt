package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the icon-launch refusal routing: the refusal (pendingRefusal + refusalHold)
 * goes to HOME's status line (HomeControl.showLine), not to the hidden Settings column
 * or the first-run view.
 */
internal class RefusalRoutingGateTest {

    private val dir: String = "kotlin/dev/breaker/dictation/"
    private val files: Map<String, String> = mapOf(
        "activity" to dir + "SettingsLauncherActivity.kt",
    )

    private class Rule(val name: String, val file: String, val holds: (String) -> Boolean)
    private class Sample(val rule: String, val old: String, val new: String)
    private class Quiet(val file: String, val old: String, val new: String)

    private fun rx(name: String, file: String, pattern: String, times: Int = 1) =
        Rule(name, file) { Regex(pattern).findAll(it).count() == times }

    private val rules: List<Rule> = listOf(
        rx("REFUSAL_GOES_TO_HOME_SHOW_LINE", "activity",
            """\(homeView\s+as\?\s+dev\s*\.\s*breaker\s*\.\s*dictation\s*\.\s*ui\s*\.\s*HomeControl\s*\)\s*\?\s*\.\s*showLine\s*\("""),
        rx("REFUSAL_IS_READ_FROM_PENDING_REFUSAL", "activity",
            """\bval\s+refusal\s*=\s*pendingRefusal\b"""),
        rx("REFUSAL_IS_READ_FROM_REFUSAL_HOLD", "activity",
            """\bapp\s*\.\s*refusalHold\s*\.\s*take\s*\(\s*\)"""),
    )

    private val firing: List<Sample> = listOf(
        Sample("REFUSAL_GOES_TO_HOME_SHOW_LINE",
            "(homeView as? dev.breaker.dictation.ui.HomeControl)?.showLine(refusal)",
            "(settingsView as? dev.breaker.dictation.ui.DownloadControl)?.refresh()"),
        Sample("REFUSAL_GOES_TO_HOME_SHOW_LINE",
            "(homeView as? dev.breaker.dictation.ui.HomeControl)?.showLine(refusal)",
            "(firstRunView as? dev.breaker.dictation.ui.ModelStepControl)?.showSwitchRefused(refusal)"),
        Sample("REFUSAL_IS_READ_FROM_PENDING_REFUSAL",
            "val refusal = pendingRefusal ?: app.refusalHold.take()",
            "val refusal = app.refusalHold.take()"),
        Sample("REFUSAL_IS_READ_FROM_PENDING_REFUSAL",
            "val refusal = pendingRefusal ?: app.refusalHold.take()",
            "val refusal = null ?: app.refusalHold.take()"),
        Sample("REFUSAL_IS_READ_FROM_REFUSAL_HOLD",
            "app.refusalHold.take()",
            "null"),
        Sample("REFUSAL_IS_READ_FROM_REFUSAL_HOLD",
            "app.refusalHold.take()",
            "pendingRefusal"),
    )

    private val quiet: List<Quiet> = listOf(
        Quiet("activity", "val refusal = pendingRefusal ?: app.refusalHold.take()",
            "val refusal = pendingRefusal ?: app.refusalHold.take() // the refusal"),
        Quiet("activity", "(homeView as? dev.breaker.dictation.ui.HomeControl)?.showLine(refusal)",
            "(homeView as? dev.breaker.dictation.ui.HomeControl)?.showLine(  refusal  )"),
    )

    private fun edit(text: String, old: String, new: String): String {
        val at = text.indexOf(old)
        check(at >= 0) { "app: a refusal routing sample lost its text '$old'" }
        return text.substring(0, at) + new + text.substring(at + old.length)
    }
    private fun raw(key: String): String = AppSourceFiles.mainFile(files.getValue(key))
    private fun codeOf(key: String, old: String? = null, new: String = ""): String =
        AppSourceFiles.strip(if (old == null) raw(key) else edit(raw(key), old, new)).code

    @Test
    fun `every rule holds on the real files`() {
        for (key in files.keys) assertTrue("app: ${files.getValue(key)} was read as empty", codeOf(key).isNotBlank())
        for (rule in rules) assertTrue("app: ${files.getValue(rule.file)} breaks rule ${rule.name}", rule.holds(codeOf(rule.file)))
    }

    @Test
    fun `the firing samples cover each rule at least twice`() {
        val names = rules.map { it.name }
        assertEquals("app: a refusal routing rule name is used twice", names.toSet().size, names.size)
        assertEquals("app: a firing sample belongs to an unknown rule or a rule has none", names.toSet(), firing.map { it.rule }.toSet())
        for (rule in rules) {
            val count = firing.count { it.rule == rule.name }
            assertTrue("app: refusal routing rule ${rule.name} needs at least two firing samples, has $count", count >= 2)
        }
    }

    @Test
    fun `every rule fires on each of its edited samples`() {
        val byName = rules.associateBy { it.name }
        for (sample in firing) {
            val rule = byName.getValue(sample.rule)
            assertFalse("app: refusal routing rule ${sample.rule} must fire on the edit '${sample.old}' => '${sample.new}'", rule.holds(codeOf(rule.file, sample.old, sample.new)))
        }
    }

    @Test
    fun `no rule fires on a harmless edit`() {
        for (sample in quiet) {
            for (rule in rules.filter { it.file == sample.file }) {
                assertTrue("app: refusal routing rule ${rule.name} must stay quiet on the harmless edit '${sample.old}' => '${sample.new}'", rule.holds(codeOf(sample.file, sample.old, sample.new)))
            }
        }
    }

    @Test
    fun `a sample whose target text is missing fails by name`() {
        val thrown = assertThrows("app: a missing target must fail", IllegalStateException::class.java) { edit("val a = 1", "no such text", "x") }
        assertTrue("app: the failure must name the lost text, got ${thrown.message}", thrown.message!!.contains("no such text"))
    }
}
