package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the one wiring no JVM test can run: that the overlay port passes every callback it holds to
 * `FloatingTile.create`. The held callbacks are read from the port's own constructor parameters, not
 * from a list written here, so a callback the port gains tomorrow and forgets to pass is caught by
 * the same rule. The rule's pattern is rebuilt from whatever code it checks and matched against the
 * create call's own argument text. The gate reads the real file with comments and literal text
 * removed by the shared scanner, exactly like the other gates in this package.
 */
internal class OverlayTilePortGateTest {

    private val port: String = "kotlin/dev/breaker/dictation/host/OverlayTilePort.kt"

    /** A constructor parameter the port holds as a callback of any function type: `private val name: (...) -> Unit`. */
    private val heldCallback: Regex = Regex("""private val (\w+): \((.*?)\) -> (\w+)\??""")

    /** The held callbacks of [code], in the order the port's constructor declares them. */
    private fun heldCallbacks(code: String): List<String> =
        heldCallback.findAll(code).map { it.groupValues[1] }.toList()

    /** The argument text of the `FloatingTile.create(...)` call in [code], empty when there is none. */
    private fun createArgs(code: String): String {
        val at: Int = code.indexOf("FloatingTile.create(")
        if (at < 0) return ""
        val open: Int = code.indexOf('(', at)
        var depth = 0
        var i = open
        while (i < code.length) {
            when (code[i]) {
                '(' -> depth += 1
                ')' -> {
                    depth -= 1
                    if (depth == 0) return code.substring(open + 1, i)
                }
            }
            i += 1
        }
        return ""
    }

    /** The pattern that says each held callback of [code] reaches the create call by name, in order. */
    private fun passesEveryHeldPattern(code: String): String =
        heldCallbacks(code).joinToString("""[\s\S]*?""") { """\b$it\s*=\s*$it\b""" }

    /** A rule whose pattern is either stored or BUILT from the code it checks when [derive] is set. */
    private inner class Rule(val name: String, val file: String, val derive: ((String) -> String)? = null) {
        fun holds(code: String): Boolean {
            val uses: String = derive?.invoke(code) ?: return false
            return Regex(uses).findAll(createArgs(code)).count() == 1
        }
    }

    /** An edit of [file] (old text to new text) that must break the rule named [rule]. */
    private class Sample(val rule: String, val file: String, val old: String, val new: String)

    private val rules: List<Rule> = listOf(
        Rule(
            "OVERLAY_TILE_PORT_PASSES_EVERY_HELD_CALLBACK_TO_CREATE",
            port,
            derive = { passesEveryHeldPattern(it) },
        ),
    )

    private val firing: List<Sample> = listOf(
        Sample(
            "OVERLAY_TILE_PORT_PASSES_EVERY_HELD_CALLBACK_TO_CREATE",
            port,
            "            onSend = onSend,\n            onCloseRequested = onCloseRequested,\n        )",
            "            onSend = onSend,\n        )",
        ),
        Sample(
            "OVERLAY_TILE_PORT_PASSES_EVERY_HELD_CALLBACK_TO_CREATE",
            port,
            "            onCloseRequested = onCloseRequested,\n        )",
            "        )",
        ),
        Sample(
            "OVERLAY_TILE_PORT_PASSES_EVERY_HELD_CALLBACK_TO_CREATE",
            port,
            "    private val onCloseRequested: () -> Unit,",
            "    private val onCloseRequested: () -> Unit,\n    private val onDropRefused: () -> Unit,",
        ),
        Sample(
            "OVERLAY_TILE_PORT_PASSES_EVERY_HELD_CALLBACK_TO_CREATE",
            port,
            "    private val onCloseRequested: () -> Unit,",
            "    private val onCloseRequested: () -> Unit,\n    private val onDropRefused: (() -> Unit)?,",
        ),
    )

    /** [old] replaced by [new] at its first place; fails by name when it is not there. */
    private fun edit(text: String, old: String, new: String): String {
        val at = text.indexOf(old)
        check(at >= 0) { "app: a port gate sample lost its text '$old'" }
        return text.substring(0, at) + new + text.substring(at + old.length)
    }

    private fun codeOf(old: String? = null, new: String = ""): String {
        val text = AppSourceFiles.mainFile(port)
        return AppSourceFiles.strip(if (old == null) text else edit(text, old, new)).code
    }

    @Test
    fun `the rule holds on the real file`() {
        assertTrue("app: $port was read as empty", codeOf().isNotBlank())
        for (rule in rules) {
            assertTrue("app: $port breaks rule ${rule.name}", rule.holds(codeOf()))
        }
    }

    @Test
    fun `the firing samples cover each rule at least twice and use its own file`() {
        val names = rules.map { it.name }
        assertEquals("app: a rule name is used twice", names.toSet().size, names.size)
        assertEquals("app: a firing sample belongs to an unknown rule or a rule has none", names.toSet(), firing.map { it.rule }.toSet())
        for (rule in rules) {
            val samples = firing.filter { it.rule == rule.name }
            assertTrue("app: rule ${rule.name} needs at least two firing samples, has ${samples.size}", samples.size >= 2)
            for (sample in samples) {
                assertEquals("app: a sample of rule ${rule.name} edits another file", rule.file, sample.file)
            }
        }
    }

    @Test
    fun `every rule fires on each of its edited samples`() {
        val byName = rules.associateBy { it.name }
        for (sample in firing) {
            assertFalse(
                "app: rule ${sample.rule} must fire on the edit '${sample.old}' => '${sample.new}'",
                byName.getValue(sample.rule).holds(codeOf(sample.old, sample.new)),
            )
        }
    }

    @Test
    fun `a sample whose target text is missing fails by name`() {
        val thrown = assertThrows("app: a missing target must fail", IllegalStateException::class.java) {
            edit("val a = 1", "no such text", "x")
        }
        assertTrue("app: the failure must name the lost text, got ${thrown.message}", thrown.message!!.contains("no such text"))
    }
}
