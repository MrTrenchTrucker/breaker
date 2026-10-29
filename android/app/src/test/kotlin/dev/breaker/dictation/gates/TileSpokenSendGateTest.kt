package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the spoken send route through the three files that carry it: the host wiring, the tile coordinator and
 * the phrase send handler. The rules read the real files with comments and literal text removed by the shared
 * scanner. Each rule holds on the real files, fires on at least two edited samples, and stays quiet on harmless
 * edits; an edit whose target text is missing fails by name, so a sample cannot go quiet by a typo.
 */
internal class TileSpokenSendGateTest {

    private val dir: String = "kotlin/dev/breaker/dictation/"
    private val files: Map<String, String> = mapOf(
        "host" to dir + "host/TileHost.kt",
        "coordinator" to dir + "wiring/TileCoordinator.kt",
        "handler" to dir + "wiring/PhraseSendHandler.kt",
    )

    private class Rule(val name: String, val file: String, val holds: (String) -> Boolean)
    private class Sample(val rule: String, val old: String, val new: String)
    private class Quiet(val file: String, val old: String, val new: String)

    /** The pattern must match the code exactly [times] times. */
    private fun rx(name: String, file: String, pattern: String, times: Int = 1) =
        Rule(name, file) { Regex(pattern).findAll(it).count() == times }

    private val rules: List<Rule> = listOf(
        rx("TILE_SEND_GOES_THROUGH_SPOKEN_SEND", "host", """\btileSend\s*=\s*\{\s*trim\s*->\s*toCoordinator\s*\{\s*it\s*\.\s*onSpokenSend\s*\(\s*trim\s*,\s*phraseUnavailable\s*\)\s*\}\s*\}"""),
        rx("UNAVAILABLE_IS_THE_SAME_HOOK", "host", """\bonUnavailable\s*=\s*phraseUnavailable\s*,"""),
        rx("PHRASE_UNAVAILABLE_HOOK_IS_DECLARED_ONCE", "host", """\bprivate\s+val\s+phraseUnavailable\s*:\s*\(\s*\)\s*->\s*Unit\s*=\s*\{\s*root\s*\.\s*phraseWarn\s*\?\.\s*invoke\s*\(\s*PHRASE_UNAVAILABLE_REASON\s*\)\s*\}"""),
        rx("TILE_SEND_IS_NOT_A_PLAIN_SEND", "host", """\bonSend\s*\(\s*trim\b""", 0),
        rx("HOME_SEND_IS_UNCHANGED", "host", """\bhomeSend\s*=\s*\{\s*trim\s*->\s*takeOwner\s*\.\s*sendToHome\s*\(\s*trim\s*\)\s*\}"""),
        rx("SPOKEN_SEND_IS_IMPORTED", "host", """\bimport\s+dev\s*\.\s*breaker\s*\.\s*dictation\s*\.\s*wiring\s*\.\s*onSpokenSend\b"""),
        rx("TILE_SHOWN_IS_READABLE_AND_WRITTEN_INSIDE", "coordinator", """\binternal\s+var\s+tileShown\s*:\s*Boolean\s*=\s*false\s*;\s*private\s+set\b"""),
        rx("SPOKEN_SEND_CHECKS_THE_SHOWN_TILE", "handler", """\binternal\s+fun\s+TileCoordinator\s*\.\s*onSpokenSend\s*\(\s*trimBeforeMs\s*:\s*Long\?\s*,\s*onHidden\s*:\s*\(\s*\)\s*->\s*Unit\s*\)\s*\{\s*if\s*\(\s*tileShown\s*\)\s*onSend\s*\(\s*trimBeforeMs\s*\)\s*else\s+onHidden\s*\(\s*\)\s*\}"""),
    )

    private val firing: List<Sample> = listOf(
        Sample("TILE_SEND_GOES_THROUGH_SPOKEN_SEND", "tileSend = { trim -> toCoordinator { it.onSpokenSend(trim, phraseUnavailable) } },", "tileSend = { trim -> toCoordinator { it.onSend(trim) } },"),
        Sample("TILE_SEND_GOES_THROUGH_SPOKEN_SEND", "it.onSpokenSend(trim, phraseUnavailable)", "it.onSpokenSend(trim, { })"),
        Sample("UNAVAILABLE_IS_THE_SAME_HOOK", "onUnavailable = phraseUnavailable,", "onUnavailable = { },"),
        Sample("UNAVAILABLE_IS_THE_SAME_HOOK", "onUnavailable = phraseUnavailable,", "onUnavailable = { root.phraseWarn?.invoke(PHRASE_UNAVAILABLE_REASON) },"),
        Sample("PHRASE_UNAVAILABLE_HOOK_IS_DECLARED_ONCE", "private val phraseUnavailable: () -> Unit = { root.phraseWarn?.invoke(PHRASE_UNAVAILABLE_REASON) }", "private val phraseUnavailable: () -> Unit = { }"),
        Sample("PHRASE_UNAVAILABLE_HOOK_IS_DECLARED_ONCE", "private val phraseUnavailable: () -> Unit = { root.phraseWarn?.invoke(PHRASE_UNAVAILABLE_REASON) }", "private val phraseUnavailable: () -> Unit = { root.phraseWarn?.invoke(\"x\") }"),
        Sample("TILE_SEND_IS_NOT_A_PLAIN_SEND", "homeSend = { trim -> takeOwner.sendToHome(trim) },", "homeSend = { trim -> onSend(trim) },"),
        Sample("TILE_SEND_IS_NOT_A_PLAIN_SEND", "it.onSpokenSend(trim, phraseUnavailable) } },", "it.onSpokenSend(trim, phraseUnavailable) }; onSend(trim) },"),
        Sample("HOME_SEND_IS_UNCHANGED", "homeSend = { trim -> takeOwner.sendToHome(trim) },", "homeSend = { trim -> toCoordinator { it.onSpokenSend(trim, phraseUnavailable) } },"),
        Sample("HOME_SEND_IS_UNCHANGED", "homeSend = { trim -> takeOwner.sendToHome(trim) },", "homeSend = { trim -> takeOwner.sendToHome(null) },"),
        Sample("SPOKEN_SEND_IS_IMPORTED", "import dev.breaker.dictation.wiring.onSpokenSend\n", ""),
        Sample("SPOKEN_SEND_IS_IMPORTED", "import dev.breaker.dictation.wiring.onSpokenSend", "import dev.breaker.dictation.wiring.onSend"),
        Sample("TILE_SHOWN_IS_READABLE_AND_WRITTEN_INSIDE", "internal var tileShown: Boolean = false; private set", "internal var tileShown: Boolean = false"),
        Sample("TILE_SHOWN_IS_READABLE_AND_WRITTEN_INSIDE", "internal var tileShown: Boolean = false; private set", "private var tileShown: Boolean = false"),
        Sample("SPOKEN_SEND_CHECKS_THE_SHOWN_TILE", "if (tileShown) onSend(trimBeforeMs) else onHidden()", "onSend(trimBeforeMs)"),
        Sample("SPOKEN_SEND_CHECKS_THE_SHOWN_TILE", "if (tileShown) onSend(trimBeforeMs) else onHidden()", "if (!tileShown) onSend(trimBeforeMs) else onHidden()"),
        Sample("SPOKEN_SEND_CHECKS_THE_SHOWN_TILE", "else onHidden()", "else Unit"),
    )

    private val quiet: List<Quiet> = listOf(
        Quiet("host", "tileSend = { trim -> toCoordinator { it.onSpokenSend(trim, phraseUnavailable) } },", "tileSend = { trim -> toCoordinator { it.onSpokenSend(trim, phraseUnavailable) } }, // it.onSend(trim)"),
        Quiet("host", "onUnavailable = phraseUnavailable,", "onUnavailable = phraseUnavailable, // onUnavailable = { }"),
        Quiet("host", "homeSend = { trim -> takeOwner.sendToHome(trim) },", "homeSend = { trim -> takeOwner.sendToHome(trim) }, // onSend(trim)"),
        Quiet("coordinator", "True while the last show answered SHOWN;", "True while the last show answered SHOWN; the tile may be hidden;"),
        Quiet("handler", "if (tileShown) onSend(trimBeforeMs) else onHidden()", "if (tileShown) onSend(trimBeforeMs) else onHidden() // the hidden case sends nothing"),
    )

    private fun edit(text: String, old: String, new: String): String {
        val at = text.indexOf(old)
        check(at >= 0) { "app: a spoken send gate sample lost its text '$old'" }
        return text.substring(0, at) + new + text.substring(at + old.length)
    }

    private fun raw(key: String): String = AppSourceFiles.mainFile(files.getValue(key))

    private fun codeOf(key: String, old: String? = null, new: String = ""): String =
        AppSourceFiles.strip(if (old == null) raw(key) else edit(raw(key), old, new)).code

    @Test
    fun `the spoken send rules hold on the real host, coordinator and handler files`() {
        for (key in files.keys) assertTrue("app: ${files.getValue(key)} was read as empty", codeOf(key).isNotBlank())
        for (rule in rules) assertTrue("app: ${files.getValue(rule.file)} breaks rule ${rule.name}", rule.holds(codeOf(rule.file)))
    }

    @Test
    fun `each spoken send rule has at least two firing samples`() {
        val names = rules.map { it.name }
        assertEquals("app: a spoken send rule name is used twice", names.toSet().size, names.size)
        assertEquals("app: a firing sample belongs to an unknown rule or a rule has none", names.toSet(), firing.map { it.rule }.toSet())
        for (rule in rules) {
            val count = firing.count { it.rule == rule.name }
            assertTrue("app: spoken send rule ${rule.name} needs at least two firing samples, has $count", count >= 2)
        }
    }

    @Test
    fun `each spoken send rule fires on each of its edited samples`() {
        val byName = rules.associateBy { it.name }
        for (sample in firing) {
            val rule = byName.getValue(sample.rule)
            assertFalse("app: spoken send rule ${sample.rule} must fire on the edit '${sample.old}' => '${sample.new}'", rule.holds(codeOf(rule.file, sample.old, sample.new)))
        }
    }

    @Test
    fun `each spoken send rule stays quiet on its harmless edits`() {
        for (sample in quiet) {
            for (rule in rules.filter { it.file == sample.file }) {
                assertTrue("app: spoken send rule ${rule.name} fires on a harmless edit '${sample.new}'", rule.holds(codeOf(sample.file, sample.old, sample.new)))
            }
        }
    }

    @Test
    fun `a spoken send sample whose target text is missing fails by name`() {
        val error = assertThrows(IllegalStateException::class.java) {
            codeOf("host", "no such text in the host file", "x")
        }
        assertTrue("app: the missing target must be named", error.message?.contains("lost its text") == true)
    }
}
