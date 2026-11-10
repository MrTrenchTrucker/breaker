package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins that the pull transport adapter is not wired yet: nothing in BreakerApp.kt or
 * BreakerCompositionRoot.kt names PullHttpTransport (the wiring is Router's, later), and that the
 * adapter hands the request's path and query to fetchPath unchanged. The rules read the real files
 * with comments and literal text removed by the shared scanner. Each rule holds on the real file,
 * fires on its edited samples and stays quiet on harmless edits.
 */
internal class PullTransportNotWiredGateTest {

    private val dir: String = "kotlin/dev/breaker/dictation/"
    private val files: Map<String, String> = mapOf(
        "app" to dir + "BreakerApp.kt",
        "root" to dir + "BreakerCompositionRoot.kt",
        "adapter" to dir + "wiring/PullHttpTransport.kt",
    )

    private class Rule(val name: String, val file: String, val pattern: String, val times: Int) {
        fun holds(code: String): Boolean = Regex(pattern).findAll(code).count() == times
    }

    private class Sample(val rule: String, val old: String, val new: String)
    private class Quiet(val file: String, val old: String, val new: String)

    private val delegate: String = "fetchPath(request.path(), request.query())"

    private val rules: List<Rule> = listOf(
        Rule("BREAKER_APP_DOES_NOT_NAME_THE_PULL_ADAPTER", "app", """\bPullHttpTransport\b""", 0),
        Rule("COMPOSITION_ROOT_DOES_NOT_NAME_THE_PULL_ADAPTER", "root", """\bPullHttpTransport\b""", 0),
        Rule("ADAPTER_HANDS_PATH_AND_QUERY_TO_FETCH_PATH", "adapter",
            """\bfetchPath\s*\(\s*request\s*\.\s*path\s*\(\s*\)\s*,\s*request\s*\.\s*query\s*\(\s*\)\s*\)""", 1),
    )

    private val firing: List<Sample> = listOf(
        Sample("BREAKER_APP_DOES_NOT_NAME_THE_PULL_ADAPTER", "super.onCreate()", "super.onCreate()\n        val pull = PullHttpTransport(a, b, c)"),
        Sample("BREAKER_APP_DOES_NOT_NAME_THE_PULL_ADAPTER", "import java.io.File", "import java.io.File\nimport dev.breaker.dictation.wiring.PullHttpTransport"),
        Sample("COMPOSITION_ROOT_DOES_NOT_NAME_THE_PULL_ADAPTER", "package dev.breaker.dictation", "package dev.breaker.dictation\nimport dev.breaker.dictation.wiring.PullHttpTransport"),
        Sample("COMPOSITION_ROOT_DOES_NOT_NAME_THE_PULL_ADAPTER", "import dev.breaker.dictation.audio.MicSource", "import dev.breaker.dictation.audio.MicSource\nval pull = PullHttpTransport(a, b, c)"),
        Sample("ADAPTER_HANDS_PATH_AND_QUERY_TO_FETCH_PATH", delegate, "fetchPath(request.path(), \"\")"),
        Sample("ADAPTER_HANDS_PATH_AND_QUERY_TO_FETCH_PATH", delegate, "fetchPath(\"/v1/transcriptions\", request.query())"),
    )

    private val quiet: List<Quiet> = listOf(
        Quiet("app", "super.onCreate()", "super.onCreate() // PullHttpTransport is not wired here"),
        Quiet("app", "import java.io.File", "import java.io.File /* PullHttpTransport */"),
        Quiet("root", "package dev.breaker.dictation", "package dev.breaker.dictation // PullHttpTransport"),
        Quiet("adapter", delegate, "fetchPath( request.path() ,\n            request.query() )"),
    )

    private fun edit(text: String, old: String, new: String): String {
        val at = text.indexOf(old)
        check(at >= 0) { "app: a pull transport gate sample lost its text '$old'" }
        return text.substring(0, at) + new + text.substring(at + old.length)
    }

    private fun codeOf(key: String, old: String? = null, new: String = ""): String {
        val text = AppSourceFiles.mainFile(files.getValue(key))
        return AppSourceFiles.strip(if (old == null) text else edit(text, old, new)).code
    }

    @Test
    fun `every pull transport gate rule holds on the real files`() {
        for (key in files.keys) assertTrue("app: ${files.getValue(key)} was read as empty", codeOf(key).isNotBlank())
        for (rule in rules) assertTrue("app: ${files.getValue(rule.file)} breaks rule ${rule.name}", rule.holds(codeOf(rule.file)))
    }

    @Test
    fun `the firing samples cover each pull transport gate rule at least twice`() {
        val names = rules.map { it.name }
        assertEquals("app: a pull transport gate rule name is used twice", names.toSet().size, names.size)
        assertEquals("app: a firing sample belongs to an unknown rule or a rule has none", names.toSet(), firing.map { it.rule }.toSet())
        for (rule in rules) {
            val count = firing.count { it.rule == rule.name }
            assertTrue("app: pull transport gate rule ${rule.name} needs at least two firing samples, has $count", count >= 2)
        }
    }

    @Test
    fun `every pull transport gate rule fires on each of its edited samples`() {
        val byName = rules.associateBy { it.name }
        for (sample in firing) {
            val rule = byName.getValue(sample.rule)
            assertFalse(
                "app: pull transport gate rule ${sample.rule} must fire on the edit '${sample.old}' => '${sample.new}'",
                rule.holds(codeOf(rule.file, sample.old, sample.new)),
            )
        }
    }

    @Test
    fun `no pull transport gate rule fires on a harmless edit`() {
        for (sample in quiet) {
            for (rule in rules.filter { it.file == sample.file }) {
                assertTrue(
                    "app: pull transport gate rule ${rule.name} must stay quiet on the harmless edit '${sample.old}' => '${sample.new}'",
                    rule.holds(codeOf(sample.file, sample.old, sample.new)),
                )
            }
        }
    }

    @Test
    fun `a sample whose target text is missing fails by name`() {
        val failure = assertThrows(IllegalStateException::class.java) { codeOf("app", "no such text here", "x") }
        assertTrue("app: the failure names the lost text", failure.message!!.contains("no such text here"))
    }
}
