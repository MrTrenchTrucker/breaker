package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the production argument that gives the tile coordinator its main-thread delayed post. The tile
 * host passes its main looper post as the coordinator's last argument, and that post is the main-looper
 * type that also implements the delayed post. The coordinator's constructor default is inert, so a
 * forgotten argument compiles and runs; this gate is the check that turns red. The rules read the real
 * file with comments and literal text removed by the shared scanner. Each rule holds on the real file,
 * is broken by at least two edited samples, and stays quiet on a comment-only edit; an edit whose target
 * text is missing fails by name.
 */
internal class TileHostDelayedPostGateTest {

    private val path: String = "kotlin/dev/breaker/dictation/host/TileHost.kt"

    private class Rule(val name: String, val holds: (String) -> Boolean)
    private class Sample(val rule: String, val old: String, val new: String)

    /** The pattern must match the code exactly [times] times. */
    private fun rx(name: String, pattern: String, times: Int = 1) =
        Rule(name) { Regex(pattern).findAll(it).count() == times }

    private val rules: List<Rule> = listOf(
        rx(
            "TILE_HOST_PASSES_MAIN_AS_THE_LAST_ARGUMENT",
            """TileCoordinator\s*\(\s*noticePort\s*,[\s\S]*?\broot\s*\.\s*historyStore\s*,\s*main\s*,\s*\)""",
        ),
        rx("MAIN_IS_THE_MAIN_LOOPER_POST", """private\s+val\s+main\s*=\s*MainLooperPost\s*\(\s*\)"""),
    )

    /** Edits that break one rule each; every rule has at least two. */
    private val firing: List<Sample> = listOf(
        Sample(
            "TILE_HOST_PASSES_MAIN_AS_THE_LAST_ARGUMENT",
            "root.historyStore,\n            main,\n        )",
            "root.historyStore,\n        )",
        ),
        Sample(
            "TILE_HOST_PASSES_MAIN_AS_THE_LAST_ARGUMENT",
            "root.historyStore,\n            main,\n        )",
            "root.historyStore,\n            null,\n        )",
        ),
        Sample("MAIN_IS_THE_MAIN_LOOPER_POST", "private val main = MainLooperPost()", "private val main = Unit"),
        Sample("MAIN_IS_THE_MAIN_LOOPER_POST", "private val main = MainLooperPost()", "private val mainPost = MainLooperPost()"),
    )

    /** An edit that changes only a comment; no rule may fire on it. */
    private val quietOld: String = "        val c = TileCoordinator("
    private val quietNew: String =
        "        // TileCoordinator(noticePort, main, takeWorker, root.historyStore, main)\n        val c = TileCoordinator("

    /** The comment-stripped code of the real file, with [old] replaced by [new] when given; fails by name when the text is absent. */
    private fun codeOf(old: String? = null, new: String = ""): String {
        val text: String = AppSourceFiles.mainFile(path)
        if (old == null) return AppSourceFiles.strip(text).code
        val at: Int = text.indexOf(old)
        check(at >= 0) { "app: a tile host delayed post sample lost its text '$old' in $path" }
        return AppSourceFiles.strip(text.substring(0, at) + new + text.substring(at + old.length)).code
    }

    @Test
    fun `every rule holds on the real file`() {
        assertTrue("app: $path was read as empty", codeOf().isNotBlank())
        for (rule in rules) assertTrue("app: $path breaks rule ${rule.name}", rule.holds(codeOf()))
    }

    @Test
    fun `the firing samples cover each rule at least twice`() {
        val names: List<String> = rules.map { it.name }
        assertEquals("app: a tile host delayed post rule name is used twice", names.toSet().size, names.size)
        assertEquals("app: a firing sample belongs to an unknown rule or a rule has none", names.toSet(), firing.map { it.rule }.toSet())
        for (rule in rules) {
            val count: Int = firing.count { it.rule == rule.name }
            assertTrue("app: tile host delayed post rule ${rule.name} needs at least two firing samples, has $count", count >= 2)
        }
    }

    @Test
    fun `every rule fires on each of its edited samples`() {
        val byName: Map<String, Rule> = rules.associateBy { it.name }
        for (sample in firing) {
            val rule: Rule = byName.getValue(sample.rule)
            assertFalse(
                "app: tile host delayed post rule ${sample.rule} must fire on the edit '${sample.old}' => '${sample.new}'",
                rule.holds(codeOf(sample.old, sample.new)),
            )
        }
    }

    @Test
    fun `no rule fires on a comment-only edit`() {
        for (rule in rules) {
            assertTrue(
                "app: tile host delayed post rule ${rule.name} must stay quiet on the comment-only edit",
                rule.holds(codeOf(quietOld, quietNew)),
            )
        }
    }

    @Test
    fun `a sample whose target text is missing fails by name`() {
        val thrown = assertThrows("app: a missing target must fail", IllegalStateException::class.java) {
            codeOf("no such text in the tile host", "x")
        }
        assertTrue("app: the failure must name the lost text, got ${thrown.message}", thrown.message!!.contains("no such text in the tile host"))
    }
}
