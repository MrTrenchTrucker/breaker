package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the saved icon-launch flag of the launcher activity in source text, because no JVM test can run
 * the activity. The flag is read from the saved state right after the super call, saved in
 * onSaveInstanceState from the rule's isDone, and both sides use one key. The rules read the real file
 * with comments and literal text removed by the shared scanner; each rule holds on the real file, is
 * broken by at least two edited samples, and stays quiet on harmless edits; an edit whose target text is
 * missing fails by name.
 */
internal class IconLaunchOnceGateTest {

    private val activity: String = "kotlin/dev/breaker/dictation/SettingsLauncherActivity.kt"

    private class Rule(val name: String, val holds: (String) -> Boolean)
    private class Sample(val rule: String, val old: String, val new: String)
    private class Quiet(val old: String, val new: String)

    /** The pattern must match the code exactly once. */
    private fun once(name: String, pattern: String): Rule =
        Rule(name) { Regex(pattern).findAll(it).count() == 1 }

    private val rules: List<Rule> = listOf(
        once(
            "ONCREATE_BUILDS_THE_ONCE_RULE_FROM_THE_SAVED_FLAG",
            """\boverride\s+fun\s+onCreate\s*\(\s*savedInstanceState\s*:\s*Bundle\?\s*\)\s*\{\s*super\s*\.\s*onCreate\s*\(\s*savedInstanceState\s*\)\s*iconLaunch\s*=\s*IconLaunchOnce\s*\(\s*savedInstanceState\s*\?\s*\.\s*getBoolean\s*\(\s*ICON_LAUNCH_DONE_KEY\s*,\s*false\s*\)\s*\?:\s*false\s*\)""",
        ),
        once(
            "ONSAVEINSTANCESTATE_SAVES_THE_FLAG",
            """\boverride\s+fun\s+onSaveInstanceState\s*\(\s*outState\s*:\s*Bundle\s*\)\s*\{\s*super\s*\.\s*onSaveInstanceState\s*\(\s*outState\s*\)\s*outState\s*\.\s*putBoolean\s*\(\s*ICON_LAUNCH_DONE_KEY\s*,\s*iconLaunch\s*\.\s*isDone\s*\)\s*\}""",
        ),
        Rule("THE_SAVED_FLAG_USES_ONE_KEY") { code ->
            Regex("""\bgetBoolean\s*\(\s*ICON_LAUNCH_DONE_KEY\s*,""").findAll(code).count() == 1 &&
                Regex("""\bputBoolean\s*\(\s*ICON_LAUNCH_DONE_KEY\s*,""").findAll(code).count() == 1
        },
    )

    private val firing: List<Sample> = listOf(
        Sample("ONCREATE_BUILDS_THE_ONCE_RULE_FROM_THE_SAVED_FLAG", "savedInstanceState?.getBoolean(ICON_LAUNCH_DONE_KEY, false) ?: false", "false"),
        Sample("ONCREATE_BUILDS_THE_ONCE_RULE_FROM_THE_SAVED_FLAG", "getBoolean(ICON_LAUNCH_DONE_KEY, false)", "getInt(ICON_LAUNCH_DONE_KEY, 0)"),
        Sample("ONCREATE_BUILDS_THE_ONCE_RULE_FROM_THE_SAVED_FLAG", "super.onCreate(savedInstanceState)", ""),
        Sample("ONSAVEINSTANCESTATE_SAVES_THE_FLAG", "outState.putBoolean(ICON_LAUNCH_DONE_KEY, iconLaunch.isDone)", "outState.putBoolean(ICON_LAUNCH_DONE_KEY, false)"),
        Sample("ONSAVEINSTANCESTATE_SAVES_THE_FLAG", "super.onSaveInstanceState(outState)", ""),
        Sample("ONSAVEINSTANCESTATE_SAVES_THE_FLAG", "outState.putBoolean(ICON_LAUNCH_DONE_KEY, iconLaunch.isDone)", ""),
        Sample("THE_SAVED_FLAG_USES_ONE_KEY", "outState.putBoolean(ICON_LAUNCH_DONE_KEY,", "outState.putBoolean(ICON_LAUNCH_DONE_FLAG,"),
        Sample("THE_SAVED_FLAG_USES_ONE_KEY", "getBoolean(ICON_LAUNCH_DONE_KEY,", "getBoolean(\"icon-launch-done\","),
        Sample("THE_SAVED_FLAG_USES_ONE_KEY", "putBoolean(ICON_LAUNCH_DONE_KEY,", "putBoolean(\"icon-launch-done\","),
    )

    private val quiet: List<Quiet> = listOf(
        Quiet("\"icon-launch-done\"", "\"launch-flag-saved\""),
        Quiet("super.onCreate(savedInstanceState)", "super . onCreate ( savedInstanceState )"),
        Quiet("super.onSaveInstanceState(outState)", "super . onSaveInstanceState ( outState )"),
        Quiet("getBoolean(ICON_LAUNCH_DONE_KEY, false)", "getBoolean( ICON_LAUNCH_DONE_KEY , false )"),
        Quiet("iconLaunch.isDone)", "iconLaunch.isDone )"),
    )

    /** [old] replaced by [new] at its first place; fails by name when it is not there. */
    private fun edit(text: String, old: String, new: String): String {
        val at = text.indexOf(old)
        check(at >= 0) { "app: an icon launch gate sample lost its text '$old'" }
        return text.substring(0, at) + new + text.substring(at + old.length)
    }

    private fun codeOf(old: String? = null, new: String = ""): String {
        val text: String = AppSourceFiles.mainFile(activity)
        return AppSourceFiles.strip(if (old == null) text else edit(text, old, new)).code
    }

    @Test
    fun `every rule holds on the real activity file`() {
        assertTrue("app: the activity was read as empty", codeOf().isNotBlank())
        for (rule in rules) {
            assertTrue("app: the activity breaks rule ${rule.name}", rule.holds(codeOf()))
        }
    }

    @Test
    fun `the firing samples cover each rule at least twice and name a known rule`() {
        val names = rules.map { it.name }
        assertEquals("app: an icon launch rule name is used twice", names.toSet().size, names.size)
        assertEquals("app: a firing sample belongs to an unknown rule or a rule has none", names.toSet(), firing.map { it.rule }.toSet())
        for (rule in rules) {
            val count = firing.count { it.rule == rule.name }
            assertTrue("app: icon launch rule ${rule.name} needs at least two firing samples, has $count", count >= 2)
        }
    }

    @Test
    fun `every rule fires on each of its edited samples`() {
        val byName = rules.associateBy { it.name }
        for (sample in firing) {
            assertFalse(
                "app: icon launch rule ${sample.rule} must fire on the edit '${sample.old}' => '${sample.new}'",
                byName.getValue(sample.rule).holds(codeOf(sample.old, sample.new)),
            )
        }
    }

    @Test
    fun `no rule fires on a harmless edit`() {
        for (sample in quiet) {
            for (rule in rules) {
                assertTrue(
                    "app: icon launch rule ${rule.name} must stay quiet on the harmless edit '${sample.old}' => '${sample.new}'",
                    rule.holds(codeOf(sample.old, sample.new)),
                )
            }
        }
    }

    @Test
    fun `a sample whose target text is missing fails by name`() {
        val thrown = assertThrows("app: a missing target must fail", IllegalStateException::class.java) { edit("val a = 1", "no such text", "x") }
        assertTrue("app: the failure must name the lost text, got ${thrown.message}", thrown.message!!.contains("no such text"))
    }
}
