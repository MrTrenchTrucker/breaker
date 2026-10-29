package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the wake policy of the tile host: one policy is built over the settings store, the tile
 * coordinator and the keyboard wiring both receive that same policy, and the shake trigger line stays as
 * it is. The rules read the comment-stripped host source; each rule holds on the real file, is broken by
 * edited samples, stays quiet on a comment-only edit, and an edit whose target text is missing fails by name.
 */
internal class TileHostWakePolicyGateTest {

    private val path: String = "kotlin/dev/breaker/dictation/host/TileHost.kt"

    private class Rule(val name: String, val holds: (String) -> Boolean)
    private class Sample(val rule: String, val old: String, val new: String)

    /** The pattern must match the code exactly [times] times. */
    private fun rx(name: String, pattern: String, times: Int = 1) =
        Rule(name) { Regex(pattern).findAll(it).count() == times }

    private val rules: List<Rule> = listOf(
        Rule("THE_POLICY_IS_BUILT_ONCE_OVER_THE_SETTINGS_STORE") { code ->
            Regex("""\bprivate\s+val\s+wake\s*:\s*WakePolicy\s*=\s*WakePolicy\s*\{\s*root\s*\.\s*settingsStore\s*\.\s*load\s*\(\s*\)\s*\}""").findAll(code).count() == 1 &&
                Regex("""\bWakePolicy\s*[({]""").findAll(code).count() == 1
        },
        rx("THE_COORDINATOR_GETS_THE_SAME_POLICY", """\bc\s*\.\s*wake\s*=\s*wake\b"""),
        rx("THE_KEYBOARD_WIRING_GETS_THE_SAME_POLICY", """\bKeyboardTileWiring\s*\(\s*\{[^}]*\}\s*,\s*main\s*,\s*wake\s*\)"""),
        rx("THE_SHAKE_TRIGGER_IS_UNCHANGED", """\bgesture\s*\.\s*start\s*\{\s*toCoordinator\s*\{\s*it\s*\.\s*onShake\s*\(\s*\)\s*\}\s*\}"""),
    )

    /** Edits that break one rule each; every rule has at least two. */
    private val firing: List<Sample> = listOf(
        Sample("THE_POLICY_IS_BUILT_ONCE_OVER_THE_SETTINGS_STORE", "WakePolicy { root.settingsStore.load() }", "WakePolicy { AppSettings() }"),
        Sample("THE_POLICY_IS_BUILT_ONCE_OVER_THE_SETTINGS_STORE", "private val wake: WakePolicy = WakePolicy { root.settingsStore.load() }", "private val wake: WakePolicy = WakePolicy.permissive()"),
        Sample("THE_COORDINATOR_GETS_THE_SAME_POLICY", "c.wake = wake", "c.wake = WakePolicy.permissive()"),
        Sample("THE_COORDINATOR_GETS_THE_SAME_POLICY", "        c.wake = wake\n", ""),
        Sample("THE_KEYBOARD_WIRING_GETS_THE_SAME_POLICY", "main, wake)", "main)"),
        Sample("THE_KEYBOARD_WIRING_GETS_THE_SAME_POLICY", "main, wake)", "main, WakePolicy.permissive())"),
        Sample("THE_SHAKE_TRIGGER_IS_UNCHANGED", "gesture.start { toCoordinator { it.onShake() } }", "gesture.start { toCoordinator { it.onBegin() } }"),
        Sample("THE_SHAKE_TRIGGER_IS_UNCHANGED", "gesture.start { toCoordinator { it.onShake() } }", "gesture.start { toCoordinator { it.onShake() } }\n        gesture.start { toCoordinator { it.onShake() } }"),
    )

    /** Edits that change only a comment or the spacing; no rule may fire on them. */
    private val quiet: List<Sample> = listOf(
        Sample("THE_COORDINATOR_GETS_THE_SAME_POLICY", "c.wake = wake", "c.wake = wake // the same policy"),
        Sample("THE_COORDINATOR_GETS_THE_SAME_POLICY", "c.wake = wake", "c.wake   =   wake"),
        Sample("THE_SHAKE_TRIGGER_IS_UNCHANGED", "gesture.start { toCoordinator { it.onShake() } }", "gesture.start {   toCoordinator {  it.onShake()  }   }"),
    )

    /** The comment-stripped host source with [old] replaced by [new]; fails by name when the text is absent. */
    private fun codeOf(old: String? = null, new: String = ""): String {
        val text: String = AppSourceFiles.mainFile(path)
        if (old == null) return AppSourceFiles.strip(text).code
        val at: Int = text.indexOf(old)
        check(at >= 0) { "app: a tile host wake policy sample lost its text '$old' in $path" }
        return AppSourceFiles.strip(text.substring(0, at) + new + text.substring(at + old.length)).code
    }

    @Test
    fun `every wake policy rule holds on the real tile host`() {
        assertTrue("app: $path was read as empty", codeOf().isNotBlank())
        for (rule in rules) assertTrue("app: $path breaks rule ${rule.name}", rule.holds(codeOf()))
    }

    @Test
    fun `the firing samples cover each wake policy rule at least twice`() {
        val names: List<String> = rules.map { it.name }
        assertEquals("app: a wake policy rule name is used twice", names.toSet().size, names.size)
        assertEquals("app: a firing sample belongs to an unknown rule or a rule has none", names.toSet(), firing.map { it.rule }.toSet())
        for (rule in rules) {
            val count: Int = firing.count { it.rule == rule.name }
            assertTrue("app: wake policy rule ${rule.name} needs at least two firing samples, has $count", count >= 2)
        }
    }

    @Test
    fun `every wake policy rule fires on each of its edited samples`() {
        val byName: Map<String, Rule> = rules.associateBy { it.name }
        for (sample in firing) {
            val rule: Rule = byName.getValue(sample.rule)
            assertFalse(
                "app: wake policy rule ${sample.rule} must fire on the edit '${sample.old}' => '${sample.new}'",
                rule.holds(codeOf(sample.old, sample.new)),
            )
        }
    }

    @Test
    fun `no wake policy rule fires on a comment-only edit`() {
        for (sample in quiet) {
            for (rule in rules) {
                assertTrue(
                    "app: wake policy rule ${rule.name} must stay quiet on the harmless edit '${sample.old}' => '${sample.new}'",
                    rule.holds(codeOf(sample.old, sample.new)),
                )
            }
        }
    }

    @Test
    fun `a wake policy sample whose target text is missing fails by name`() {
        val thrown = assertThrows("app: a missing target must fail", IllegalStateException::class.java) {
            codeOf("no such text in the tile host", "x")
        }
        assertTrue("app: the failure must name the lost text, got ${thrown.message}", thrown.message!!.contains("no such text in the tile host"))
    }
}
