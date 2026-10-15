package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the shake trigger of the tile host. A shake only shows the tile, and the tap on the tile is the only
 * call that begins a take. Each rule reads the comment-stripped source of the tile host, holds on the real
 * file, is broken by edited samples, and stays quiet on harmless edits.
 */
internal class ShakeTriggerGateTest {

    private val hostFile: String = "host/TileHost.kt"

    private class Rule(val name: String, val holds: (String) -> Boolean)
    private class Sample(val rule: String, val old: String, val new: String)
    private class Quiet(val old: String, val new: String)

    private val trigger: Regex = Regex("""\bgesture\s*\.\s*start\s*\{\s*toCoordinator\s*\{\s*it\s*\.\s*onShake\s*\(\s*\)\s*\}\s*\}""")
    private val triggerStart: Regex = Regex("""\bgesture\s*\.\s*start\s*\{""")
    private val beginCall: Regex = Regex("""\bit\s*\.\s*onBegin\s*\(\s*\)""")
    private val tapCallback: Regex = Regex("""\bonBegin\s*=\s*\{\s*toCoordinator\s*\{\s*it\s*\.\s*onBegin\s*\(\s*\)\s*\}\s*\}""")

    private val rules: List<Rule> = listOf(
        Rule("THE_SHAKE_TRIGGER_ONLY_SHOWS") { code ->
            triggerStart.findAll(code).count() == 1 && trigger.findAll(code).count() == 1
        },
        Rule("THE_TAP_IS_THE_ONLY_BEGIN") { code ->
            beginCall.findAll(code).count() == 1 && tapCallback.findAll(code).count() == 1
        },
    )

    private val firing: List<Sample> = listOf(
        Sample("THE_SHAKE_TRIGGER_ONLY_SHOWS", "gesture.start { toCoordinator { it.onShake() } }", "gesture.start { toCoordinator { it.onBegin() } }"),
        Sample("THE_SHAKE_TRIGGER_ONLY_SHOWS", "gesture.start { toCoordinator { it.onShake() } }", "gesture.start { toCoordinator { it.onShake(); it.onBegin() } }"),
        Sample("THE_SHAKE_TRIGGER_ONLY_SHOWS", "gesture.start { toCoordinator { it.onShake() } }", "gesture.start { toCoordinator { it.onShake() } }\n        gesture.start { toCoordinator { it.onBegin() } }"),
        Sample("THE_TAP_IS_THE_ONLY_BEGIN", "onBegin = { toCoordinator { it.onBegin() } }", "onBegin = { toCoordinator { it.onShake() } }"),
        Sample("THE_TAP_IS_THE_ONLY_BEGIN", "onBegin = { toCoordinator { it.onBegin() } }", "onBegin = { toCoordinator { it.onBegin() } }\n    private val extra = { toCoordinator { it.onBegin() } }"),
        Sample("THE_TAP_IS_THE_ONLY_BEGIN", "gesture.start { toCoordinator { it.onShake() } }", "gesture.start { toCoordinator { it.onBegin() } }"),
    )

    private val quiet: List<Quiet> = listOf(
        Quiet("gesture.start { toCoordinator { it.onShake() } }", "gesture.start { toCoordinator { it.onShake() } } // it.onBegin() is only a comment here"),
        Quiet("gesture.start { toCoordinator { it.onShake() } }", "gesture.start {   toCoordinator {  it.onShake()  }   }"),
        Quiet("onBegin = { toCoordinator { it.onBegin() } }", "onBegin = { toCoordinator { it.onBegin() } /* the tap begins */ }"),
        Quiet("onBegin = { toCoordinator { it.onBegin() } }", "onBegin = {  toCoordinator {  it.onBegin()  }  }"),
    )

    /** The comment-stripped source of the tile host, read fresh from the real main sources. */
    private fun realCode(): String = AppSourceFiles.strip(realText()).code

    private fun realText(): String =
        AppSourceFiles.mainKotlinSources()[hostFile] ?: error("app: the shake trigger gate did not find $hostFile")

    /** The comment-stripped tile host with one sample's edit applied; fails by name when the sample's text is not in the file. */
    private fun edited(sample: Sample): String = editedText(sample.old, sample.new)

    private fun editedText(old: String, new: String): String {
        val text: String = realText()
        val at: Int = text.indexOf(old)
        check(at >= 0) { "app: a shake trigger sample lost its text '$old' in $hostFile" }
        return AppSourceFiles.strip(text.substring(0, at) + new + text.substring(at + old.length)).code
    }

    @Test
    fun `every rule holds on the real tile host`() {
        val code: String = realCode()
        for (rule in rules) {
            assertTrue("app: the real tile host breaks rule ${rule.name}", rule.holds(code))
        }
    }

    @Test
    fun `the firing samples cover each rule at least twice`() {
        val names: List<String> = rules.map { it.name }
        assertEquals("app: a shake trigger rule name is used twice", names.toSet().size, names.size)
        assertEquals("app: a firing sample belongs to an unknown rule or a rule has none", names.toSet(), firing.map { it.rule }.toSet())
        for (rule in rules) {
            val count: Int = firing.count { it.rule == rule.name }
            assertTrue("app: shake trigger rule ${rule.name} needs at least two firing samples, has $count", count >= 2)
        }
    }

    @Test
    fun `every rule fires on each of its edited samples`() {
        val byName: Map<String, Rule> = rules.associateBy { it.name }
        for (sample in firing) {
            val rule: Rule = byName.getValue(sample.rule)
            assertFalse("app: shake trigger rule ${sample.rule} must fire on the edit '${sample.old}' => '${sample.new}'", rule.holds(edited(sample)))
        }
    }

    @Test
    fun `no rule fires on a harmless edit`() {
        for (sample in quiet) {
            val code: String = editedText(sample.old, sample.new)
            for (rule in rules) {
                assertTrue("app: shake trigger rule ${rule.name} must stay quiet on the harmless edit '${sample.old}' => '${sample.new}'", rule.holds(code))
            }
        }
    }

    @Test
    fun `a sample whose target text is missing fails by name`() {
        val thrown = assertThrows("app: a missing target must fail", IllegalStateException::class.java) {
            edited(Sample("THE_TAP_IS_THE_ONLY_BEGIN", "no such text", "x"))
        }
        assertTrue("app: the failure must name the lost text, got ${thrown.message}", thrown.message!!.contains("no such text"))
    }
}
