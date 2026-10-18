package dev.breaker.dictation.service

import dev.breaker.dictation.gates.AppSourceFiles
import dev.breaker.dictation.gates.Stripped
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two of the three thin platform adapters run on no JVM test, so a text gate holds the facts they carry.
 * The launcher turns a null answer of the platform into a refusal, asks for the arm action, stops the
 * service in halt and catches the platform's runtime failures; the permission check compares with
 * "granted" and asks for the microphone. The notification builder has its own gate in
 * PlatformNotificationGateTest. Rules read code only (comments and literal text are removed by the
 * shared scanner).
 * Each rule holds on the real file, is broken by at least one edited sample and stays quiet on a
 * harmless edit; a sample whose target text is missing fails by name, so it cannot go quiet by a typo.
 */
internal class PlatformAdapterGateTest {

    internal class Rule(val name: String, val holds: (Stripped) -> Boolean)

    /** [old] replaced by [new] at its [occurrence]-th place (0 is the first); fails by name when it is not there. */
    private fun edit(text: String, old: String, new: String, occurrence: Int = 0): String {
        var at = text.indexOf(old)
        var seen = 0
        while (at >= 0 && seen < occurrence) {
            at = text.indexOf(old, at + 1)
            seen++
        }
        check(at >= 0) { "app: a gate sample lost its text '$old' (occurrence $occurrence)" }
        return text.substring(0, at) + new + text.substring(at + old.length)
    }

    /** The text inside each bracket pair that opens at the last character of a match of [open]. */
    private fun inside(code: String, open: Regex, closer: Char): List<String> {
        val opener = if (closer == ')') '(' else '{'
        val found: MutableList<String> = ArrayList()
        for (m in open.findAll(code)) {
            val start = m.range.last
            check(code[start] == opener) { "app: the pattern ${open.pattern} must end at its opening $opener" }
            var depth = 0
            for (i in start until code.length) {
                if (code[i] == opener) depth++
                if (code[i] == closer) depth--
                if (depth == 0) {
                    found.add(code.substring(start + 1, i))
                    break
                }
            }
        }
        return found
    }

    internal fun args(code: String, call: String): List<String> = inside(code, Regex("""$call\s*\("""), ')')

    internal fun has(text: String, pattern: String): Boolean = Regex(pattern).containsMatchIn(text)

    /** One edit written as "old => new", or "old => new @n" for the n-th place of old (0 is the first). */
    internal fun build(real: String, line: String): String {
        val place = Regex(""" @(\d+)\z""").find(line)
        val body = if (place == null) line else line.substring(0, place.range.first)
        val parts = body.split(" => ")
        check(parts.size == 2) { "app: the gate sample '$line' is not written as old => new" }
        return edit(real, parts[0], parts[1], place?.groupValues?.get(1)?.toInt() ?: 0)
    }

    internal fun runGate(file: String, rules: List<Rule>, firing: Map<String, List<String>>, quiet: List<String>) {
        val real = AppSourceFiles.mainFile("kotlin/dev/breaker/dictation/service/$file")
        val byName = rules.associateBy { it.name }
        assertEquals("app: $file has a firing sample for an unknown rule or a rule without one", byName.keys, firing.keys)
        for (rule in rules) {
            assertTrue("app: $file breaks rule ${rule.name}", rule.holds(AppSourceFiles.strip(real)))
        }
        for ((ruleName, lines) in firing) {
            assertTrue("app: rule $ruleName of $file has no firing sample", lines.isNotEmpty())
            for (line in lines) {
                val sample = AppSourceFiles.strip(build(real, line))
                assertFalse("app: rule $ruleName of $file must fire on the sample '$line'", byName.getValue(ruleName).holds(sample))
            }
        }
        for (line in quiet) {
            val sample = AppSourceFiles.strip(build(real, line))
            for (rule in rules) {
                assertTrue("app: rule ${rule.name} of $file must stay quiet on the harmless edit '$line'", rule.holds(sample))
            }
        }
    }

    // ---- AndroidServiceLauncher.kt ----

    private val launchOpen = Regex("""\boverride\s+fun\s+launch\s*\(\s*\)\s*:\s*LaunchResult\s*\{""")
    private val haltOpen = Regex("""\boverride\s+fun\s+halt\s*\(\s*\)\s*\{""")
    private val catchRuntime = """catch\s*\(\s*\w+\s*:\s*RuntimeException\s*\)"""

    private val launcherRules = listOf(
        Rule("LAUNCH_ANSWERS_REFUSED_ON_NULL") { s ->
            val b = inside(s.code, launchOpen, '}')
            b.size == 1 && has(
                b[0],
                """val\s+(\w+)\s*=\s*context\s*\.\s*startForegroundService\s*\([\s\S]*?\)\s*return\s+if\s*\(\s*\1\s*==\s*null\s*\)""" +
                    """\s*LaunchResult\s*\.\s*Refused\s+else\s+LaunchResult\s*\.\s*Launched\b""",
            )
        },
        Rule("LAUNCH_ASKS_FOR_THE_ARM_ACTION") { s ->
            val b = inside(s.code, launchOpen, '}')
            b.size == 1 && args(b[0], """\bcontext\s*\.\s*startForegroundService""").let { a ->
                a.size == 1 && has(a[0], """\.\s*setAction\s*\(\s*ACTION_ARM\s*\)""")
            }
        },
        Rule("LAUNCH_CATCHES_RUNTIME_FAILURES") { s ->
            val b = inside(s.code, launchOpen, '}')
            b.size == 1 && has(b[0], """$catchRuntime\s*\{\s*return\s+LaunchResult\s*\.\s*Refused\s*\}""")
        },
        Rule("HALT_STOPS_THE_SERVICE") { s ->
            val b = inside(s.code, haltOpen, '}')
            b.size == 1 && has(b[0], """\bcontext\s*\.\s*stopService\s*\(""")
        },
        Rule("HALT_CATCHES_RUNTIME_FAILURES") { s ->
            val b = inside(s.code, haltOpen, '}')
            b.size == 1 && has(b[0], catchRuntime)
        },
    )

    private val launcherFiring: Map<String, List<String>> = mapOf(
        "LAUNCH_ANSWERS_REFUSED_ON_NULL" to listOf(
            "return if (started == null) LaunchResult.Refused else LaunchResult.Launched => return LaunchResult.Launched",
            "return if (started == null) LaunchResult.Refused else LaunchResult.Launched => " +
                "return if (started != null) LaunchResult.Refused else LaunchResult.Launched",
            "return if (started == null) LaunchResult.Refused else LaunchResult.Launched => " +
                "return if (started == null) LaunchResult.Launched else LaunchResult.Refused",
        ),
        "LAUNCH_ASKS_FOR_THE_ARM_ACTION" to listOf(
            "serviceIntent().setAction(ACTION_ARM) => serviceIntent()",
            "serviceIntent().setAction(ACTION_ARM) => serviceIntent().setAction(ACTION_DISARM)",
        ),
        "LAUNCH_CATCHES_RUNTIME_FAILURES" to listOf(
            "catch (e: RuntimeException) { => catch (e: IllegalStateException) {",
            "catch (e: RuntimeException) { => catch (e: Exception) {",
        ),
        "HALT_STOPS_THE_SERVICE" to listOf(
            "context.stopService(serviceIntent()) => serviceIntent()",
            "context.stopService(serviceIntent()) => // context.stopService(serviceIntent())",
        ),
        "HALT_CATCHES_RUNTIME_FAILURES" to listOf(
            "catch (e: RuntimeException) { => catch (e: IllegalStateException) { @1",
        ),
    )

    private val launcherQuiet = listOf(
        "private fun serviceIntent => // context.stopService(x) and ACTION_DISARM\n    private fun serviceIntent",
        "return if (started == null) => return if (\n                started == null\n            )",
    )

    @Test
    fun `the launcher answers a refusal on null, asks for the arm action, stops in halt and catches platform failures`() =
        runGate("AndroidServiceLauncher.kt", launcherRules, launcherFiring, launcherQuiet)

    // ---- AndroidMicPermission.kt ----

    private val permissionRules = listOf(
        Rule("PERMISSION_IS_COMPARED_WITH_GRANTED") { s ->
            has(s.code, """\bcheckSelfPermission\s*\([^()]*\)\s*==\s*PackageManager\s*\.\s*PERMISSION_GRANTED\b""")
        },
        Rule("PERMISSION_ASKED_IS_THE_MICROPHONE") { s ->
            has(s.code, """\bcheckSelfPermission\s*\(\s*Manifest\s*\.\s*permission\s*\.\s*RECORD_AUDIO\s*\)""")
        },
    )

    private val permissionFiring: Map<String, List<String>> = mapOf(
        "PERMISSION_IS_COMPARED_WITH_GRANTED" to listOf(
            "== PackageManager.PERMISSION_GRANTED => != PackageManager.PERMISSION_GRANTED",
            "== PackageManager.PERMISSION_GRANTED => == PackageManager.PERMISSION_DENIED",
            " == PackageManager.PERMISSION_GRANTED => ",
            "context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED => true",
        ),
        "PERMISSION_ASKED_IS_THE_MICROPHONE" to listOf(
            "Manifest.permission.RECORD_AUDIO => Manifest.permission.CAMERA",
            "Manifest.permission.RECORD_AUDIO => \"android.permission.RECORD_AUDIO\"",
            "context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED => true",
        ),
    )

    private val permissionQuiet = listOf(
        "override fun => // PackageManager.PERMISSION_DENIED\n    override fun",
        ") == PackageManager.PERMISSION_GRANTED => )\n            == PackageManager.PERMISSION_GRANTED",
    )

    @Test
    fun `the permission check compares the microphone answer with granted`() =
        runGate("AndroidMicPermission.kt", permissionRules, permissionFiring, permissionQuiet)

    // the gate's own helpers

    @Test
    fun `a sample whose target text is missing fails by name`() {
        val thrown = assertThrows("app: a missing target must fail", IllegalStateException::class.java) {
            edit("one two", "three", "x")
        }
        assertTrue("app: the failure must name the lost text: ${thrown.message}", thrown.message.orEmpty().contains("'three'"))
        val second = assertThrows("app: a missing occurrence must fail", IllegalStateException::class.java) {
            edit("one two", "one", "x", occurrence = 1)
        }
        assertTrue("app: the failure must name the occurrence: ${second.message}", second.message.orEmpty().contains("occurrence 1"))
        assertEquals("app: the n-th place is the one replaced", "a X a", edit("a a a", "a", "X", occurrence = 1))
        assertEquals("app: only the chosen place is replaced", "a a X", edit("a a a", "a", "X", occurrence = 2))
    }

    @Test
    fun `the bracket reader returns the text inside a nested pair and nothing for an unclosed one`() {
        assertEquals("app: nested parentheses must be matched", listOf("a(b)c"), inside("f(a(b)c)", Regex("""f\("""), ')'))
        assertEquals("app: an unclosed pair must give nothing", emptyList<String>(), inside("f(a(b)c", Regex("""f\("""), ')'))
    }
}
