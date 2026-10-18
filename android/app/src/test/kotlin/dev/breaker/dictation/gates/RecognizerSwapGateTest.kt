package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins what the recognizer swap point holds, because no test can read the engine's thread count and
 * nothing that runs can tell the real factory from a wrapper around it: the point is declared once,
 * typed as the factory interface, and given the real engine factory built with no arguments, so the
 * thread count is the module's own default. No placeholder factory is named in the file. Each rule
 * holds on the real file, is broken by at least two edited samples, and stays quiet on harmless edits;
 * a sample whose target text is missing fails by name, so it cannot go quiet by a typo.
 */
internal class RecognizerSwapGateTest {

    private class Rule(val name: String, val holds: (String) -> Boolean)

    private val swapsPath: String = "kotlin/dev/breaker/dictation/wiring/Swaps.kt"

    private fun edit(text: String, old: String, new: String): String {
        val at = text.indexOf(old)
        check(at >= 0) { "app: a gate sample lost its text '$old'" }
        return text.substring(0, at) + new + text.substring(at + old.length)
    }

    private fun has(code: String, pattern: String): Boolean = Regex(pattern).containsMatchIn(code)

    private val rules: List<Rule> = listOf(
        Rule("RECOGNIZER_IS_THE_REAL_FACTORY_WITH_NO_ARGUMENTS") {
            has(it, """\bval\s+RECOGNIZER_FACTORY\s*:\s*SherpaRecognizerFactory\s*=\s*SherpaOnnxRecognizerFactory\s*\(\s*\)[ \t]*(?:\r?\n|$)""")
        },
        Rule("RECOGNIZER_IS_DECLARED_ONCE") { Regex("""\bRECOGNIZER_FACTORY\b""").findAll(it).count() == 1 },
        Rule("NO_PLACEHOLDER_RECOGNIZER_NAMED") { !has(it, """\bUnavailable\w*RecognizerFactory\b""") },
    )

    private val declaration = "val RECOGNIZER_FACTORY: SherpaRecognizerFactory = SherpaOnnxRecognizerFactory()"
    private val importLine = "import dev.breaker.dictation.stt.ondevice.SherpaRecognizerFactory\n"

    private val firing: Map<String, List<Pair<String, String>>> = mapOf(
        "RECOGNIZER_IS_THE_REAL_FACTORY_WITH_NO_ARGUMENTS" to listOf(
            "SherpaOnnxRecognizerFactory()\n" to "SherpaOnnxRecognizerFactory(4)\n",
            "SherpaOnnxRecognizerFactory()\n" to "SherpaOnnxRecognizerFactory(numThreads = 1)\n",
            "SherpaOnnxRecognizerFactory()\n" to "UnavailableRecognizerFactory\n",
            "SherpaOnnxRecognizerFactory()\n" to "SherpaOnnxRecognizerFactory().also { }\n",
            "SherpaOnnxRecognizerFactory()\n" to "SherpaOnnxRecognizerFactory() as SherpaRecognizerFactory\n",
            "val RECOGNIZER_FACTORY:" to "val RECOGNIZER:",
            ": SherpaRecognizerFactory =" to ": Any =",
        ),
        "RECOGNIZER_IS_DECLARED_ONCE" to listOf(
            declaration to "$declaration\nval RECOGNIZER_FACTORY2 = RECOGNIZER_FACTORY",
            declaration to "$declaration\nfun recognizerFactory() = RECOGNIZER_FACTORY",
        ),
        "NO_PLACEHOLDER_RECOGNIZER_NAMED" to listOf(
            importLine to "${importLine}import dev.breaker.dictation.stt.ondevice.UnavailableRecognizerFactory\n",
            declaration to "val spare: SherpaRecognizerFactory = UnavailableRecognizerFactory\n$declaration",
        ),
    )

    private val quiet: List<Pair<String, String>> = listOf(
        declaration to "val RECOGNIZER_FACTORY: SherpaRecognizerFactory =\n    SherpaOnnxRecognizerFactory( )",
        declaration to "$declaration // not UnavailableRecognizerFactory(4) RECOGNIZER_FACTORY",
        " * Builds the speech recognizer" to " * UnavailableRecognizerFactory SherpaOnnxRecognizerFactory(2) RECOGNIZER_FACTORY.\n * Builds the speech recognizer",
        "fun appGesture()" to "const val NOTE: String = \"UnavailableRecognizerFactory RECOGNIZER_FACTORY\"\nfun appGesture()",
    )

    private fun realCode(): String = AppSourceFiles.strip(AppSourceFiles.mainFile(swapsPath)).code

    private fun codeOf(change: Pair<String, String>): String =
        AppSourceFiles.strip(edit(AppSourceFiles.mainFile(swapsPath), change.first, change.second)).code

    @Test
    fun `every rule holds on the real swap file`() {
        val code = realCode()
        assertTrue("app: the swap file was read as empty", code.isNotBlank())
        for (rule in rules) {
            assertTrue("app: the swap file breaks rule ${rule.name}", rule.holds(code))
        }
    }

    @Test
    fun `the firing samples cover exactly the rules`() {
        assertEquals("app: a firing sample belongs to an unknown rule or a rule has none", rules.map { it.name }.toSet(), firing.keys)
        for ((name, samples) in firing) {
            assertTrue("app: rule $name has no firing sample", samples.size >= 2)
        }
    }

    @Test
    fun `every rule fires on each of its edited samples`() {
        val byName = rules.associateBy { it.name }
        for ((name, samples) in firing) {
            for (sample in samples) {
                assertFalse(
                    "app: rule $name must fire on the edit '${sample.first}' => '${sample.second}'",
                    byName.getValue(name).holds(codeOf(sample)),
                )
            }
        }
    }

    @Test
    fun `no rule fires on a harmless edit`() {
        for (sample in quiet) {
            val code = codeOf(sample)
            for (rule in rules) {
                assertTrue("app: rule ${rule.name} must stay quiet on the harmless edit '${sample.first}'", rule.holds(code))
            }
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
