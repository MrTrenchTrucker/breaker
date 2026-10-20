package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the wiring of the microphone level path in the three Android files that compose it, because no
 * JVM test runs them: the component puts a level tap on the microphone, the root holds a level hook
 * and forwards it into the component, and the tile host sets that hook to a feed that reads the take's
 * state on the main looper. The rules read the real files with comments and literal text removed by the
 * shared scanner. Each rule holds on the real file, is broken by at least two edited samples, and stays
 * quiet on a comment-only edit; an edit whose target text is missing fails by naming that text.
 */
internal class LevelWiringGateTest {

    private val dir: String = "kotlin/dev/breaker/dictation/"
    private val files: Map<String, String> = mapOf(
        "component" to dir + "wiring/DictationComponent.kt",
        "root" to dir + "BreakerCompositionRoot.kt",
        "host" to dir + "host/TileHost.kt",
    )

    private class Rule(val name: String, val file: String, val holds: (String) -> Boolean)
    private class Sample(val rule: String, val old: String, val new: String)
    private class Quiet(val file: String, val old: String, val new: String)

    /** The pattern must match the code exactly [times] times. */
    private fun rx(name: String, file: String, pattern: String, times: Int = 1) =
        Rule(name, file) { Regex(pattern).findAll(it).count() == times }

    private val rules: List<Rule> = listOf(
        rx("COMPONENT_WRAPS_THE_MIC_IN_A_LEVEL_TAP", "component", """\bLevelTap\s*\(\s*MicCapture\s*\([\s\S]*?\)\s*,\s*levelSink\s*,?\s*\)"""),
        rx("COMPONENT_NAMES_THE_LEVEL_TAP_ONCE", "component", """\bLevelTap\s*\("""),
        rx("COMPONENT_TAKES_A_QUIET_LEVEL_SINK_BY_DEFAULT", "component", """\bprivate\s+val\s+levelSink\s*:\s*LevelSink\s*=\s*LevelSink\s*\{\s*\}"""),
        rx("ROOT_HOLDS_A_LEVEL_HOOK_THE_HOST_SETS", "root", """\bvar\s+levelSink\s*:\s*LevelSink\?\s*=\s*null\b"""),
        rx("ROOT_FORWARDS_ITS_LEVEL_HOOK_INTO_THE_COMPONENT", "root", """\blevelSink\s*=\s*LevelSink\s*\{\s*level\s*->\s*levelSink\s*\?\.\s*publish\s*\(\s*level\s*\)\s*\}"""),
        rx("HOST_SETS_A_COMPOSITE_LEVEL_SINK", "host", """\broot\s*\.\s*levelSink\s*=\s*compositeLevelSink"""),
        rx("HOST_FEEDS_THE_HOOK_BY_A_MAIN_POST_OF_THE_TAKE_STATE", "host", """\bcompositeLevelSink\.add\s*\(\s*LevelFeed\s*\(\s*noticePort\s*,\s*main\s*\)\s*\{\s*root\s*\.\s*dictation\s*\.\s*runner\s*\.\s*sessionState\s*==\s*DictationState\s*\.\s*RECORDING\s*\}\s*\)"""),
        rx("HOST_READS_THE_TAKE_STATE_IN_ONE_PLACE", "host", """\bsessionState\s*==\s*DictationState\s*\.\s*RECORDING"""),
    )

    private val firing: List<Sample> = listOf(
        Sample("COMPONENT_WRAPS_THE_MIC_IN_A_LEVEL_TAP", "LevelTap(", "LevelTapper("),
        Sample("COMPONENT_WRAPS_THE_MIC_IN_A_LEVEL_TAP", "levelSink,\n            ),", "            ),"),
        Sample("COMPONENT_NAMES_THE_LEVEL_TAP_ONCE", "LevelTap(", "LevelTap(LevelTap("),
        Sample("COMPONENT_NAMES_THE_LEVEL_TAP_ONCE", "controller,\n            reporting,", "controller,\n            LevelTap(reporting, levelSink),"),
        Sample("COMPONENT_NAMES_THE_LEVEL_TAP_ONCE", "LevelTap(", "LevelTapper("),
        Sample("COMPONENT_TAKES_A_QUIET_LEVEL_SINK_BY_DEFAULT", "LevelSink = LevelSink {},", "LevelSink? = null,"),
        Sample("COMPONENT_TAKES_A_QUIET_LEVEL_SINK_BY_DEFAULT", "private val levelSink", "private val sinkHook"),
        Sample("ROOT_HOLDS_A_LEVEL_HOOK_THE_HOST_SETS", "var levelSink: LevelSink? = null", "val levelSink: LevelSink? = null"),
        Sample("ROOT_HOLDS_A_LEVEL_HOOK_THE_HOST_SETS", "var levelSink: LevelSink? = null", "var levelSink: LevelSink? = LevelSink {}"),
        Sample("ROOT_FORWARDS_ITS_LEVEL_HOOK_INTO_THE_COMPONENT", "levelSink = LevelSink { level -> levelSink?.publish(level) },", "levelSink = null,"),
        Sample("ROOT_FORWARDS_ITS_LEVEL_HOOK_INTO_THE_COMPONENT", "levelSink?.publish(level) }", "levelSink?.publish(0f) }"),
        Sample("HOST_FEEDS_THE_HOOK_BY_A_MAIN_POST_OF_THE_TAKE_STATE", "LevelFeed(noticePort, main) { root.dictation.runner.sessionState == DictationState.RECORDING }", "LevelFeed(noticePort, main) { root.dictation.runner.sessionState == DictationState.TRANSCRIBING }"),
        Sample("HOST_FEEDS_THE_HOOK_BY_A_MAIN_POST_OF_THE_TAKE_STATE", "compositeLevelSink.add(LevelFeed(noticePort, main)", "compositeLevelSink.add(LevelFeed(tile, null)"),
        Sample("HOST_SETS_A_COMPOSITE_LEVEL_SINK", "root.levelSink = compositeLevelSink", "root.levelSink = LevelSink {}"),
        Sample("HOST_SETS_A_COMPOSITE_LEVEL_SINK", "root.levelSink = compositeLevelSink", "root.levelSink = null"),
        Sample("HOST_READS_THE_TAKE_STATE_IN_ONE_PLACE", "compositeLevelSink.add(LevelFeed(noticePort, main)", "if (root.dictation.runner.sessionState == DictationState.RECORDING) Unit\n        compositeLevelSink.add(LevelFeed(noticePort, main)"),
        Sample("HOST_READS_THE_TAKE_STATE_IN_ONE_PLACE", "DictationState.RECORDING }", "DictationState.TRANSCRIBING }"),
    )

    private val quiet: List<Quiet> = listOf(
        Quiet("component", "private val levelSink: LevelSink = LevelSink {},", "/* private val levelSink: LevelSink = LevelSink {}, */\n    private val levelSink: LevelSink = LevelSink {},"),
        Quiet("component", "LevelTap(", "// LevelTap(reporting, levelSink)\n            LevelTap("),
        Quiet("component", "LevelTap(", "/* LevelTap(MicCapture(reporting), levelSink) */ LevelTap("),
        Quiet("root", "var levelSink: LevelSink? = null", "var levelSink: LevelSink? = null // val levelSink: LevelSink? = null"),
        Quiet("root", "levelSink?.publish(level) },", "levelSink?.publish(level) }, // levelSink = null"),
        Quiet("host", "compositeLevelSink.add(LevelFeed(noticePort, main)", "// compositeLevelSink.add(LevelFeed(tile, null)\n        compositeLevelSink.add(LevelFeed(noticePort, main)"),
        Quiet("host", "DictationState.RECORDING }", "DictationState.RECORDING /* TRANSCRIBING */ }"),
    )

    private fun edit(text: String, old: String, new: String): String {
        val at = text.indexOf(old)
        check(at >= 0) { "app: a level sample lost its text '$old'" }
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
        assertEquals("app: a level rule name is used twice", names.toSet().size, names.size)
        assertEquals("app: a firing sample belongs to an unknown rule or a rule has none", names.toSet(), firing.map { it.rule }.toSet())
        for (rule in rules) {
            val count = firing.count { it.rule == rule.name }
            assertTrue("app: level rule ${rule.name} needs at least two firing samples, has $count", count >= 2)
        }
    }

    @Test
    fun `every rule fires on each of its edited samples`() {
        val byName = rules.associateBy { it.name }
        for (sample in firing) {
            val rule = byName.getValue(sample.rule)
            assertFalse("app: level rule ${sample.rule} must fire on the edit '${sample.old}' => '${sample.new}'", rule.holds(codeOf(rule.file, sample.old, sample.new)))
        }
    }

    @Test
    fun `no rule fires on a comment-only edit`() {
        for (sample in quiet) {
            for (rule in rules.filter { it.file == sample.file }) {
                assertTrue("app: level rule ${rule.name} must stay quiet on the comment-only edit '${sample.old}' => '${sample.new}'", rule.holds(codeOf(sample.file, sample.old, sample.new)))
            }
        }
    }

    @Test
    fun `a sample whose target text is missing fails by naming that text`() {
        val error = assertThrows(IllegalStateException::class.java) { edit(raw("host"), "no such text in the host", "x") }
        assertTrue("app: the error did not name the missing text: ${error.message}", error.message!!.contains("no such text in the host"))
    }
}
