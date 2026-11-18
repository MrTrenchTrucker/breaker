package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The install code is a credential: the wiring file may take it as a parameter and pass it to the
 * register call, but it may not log it, print it, turn it into text or store it in a field. Each
 * rule reads the wiring file with comments and string literals removed by the shared scanner, so a
 * reformat does not move it. The two rules are broken by the planted edits below, and a reformat
 * that only wraps the register call leaves both true.
 */
internal class InstallCodeLeakGateTest {

    private val file: String = "kotlin/dev/breaker/dictation/wiring/SignInHostWiring.kt"

    private class ScanRule(val name: String, val holds: (String) -> Boolean)

    private class Planted(val rule: String, val old: String, val new: String)

    private val codeWord: Regex = Regex("""\binstallCode\b""")
    private val registerCall: Regex = Regex("""\bauthService\s*\.\s*register\s*\(([^)]*)\)""")

    /** Tokens that would carry the code out of the process or into a stored field. */
    private val leakTokens: List<Regex> = listOf(
        Regex("""\bLog\s*\.\s*\w+\s*\("""),
        Regex("""\bprintln\s*\("""),
        Regex("""\bprint\s*\("""),
        Regex("""System\s*\.\s*(out|err)\s*\.\s*print"""),
        Regex("""\.\s*toString\s*\("""),
        Regex("""\bthrow\b"""),
        Regex("""\binstallCode\s*=(?!=)"""),
    )

    /** No line that names the code is a log, a print, a toString, a throw or a field assignment. */
    private fun noLeakLine(code: String): Boolean =
        code.lineSequence()
            .filter { codeWord.containsMatchIn(it) }
            .all { line -> leakTokens.none { it.containsMatchIn(line) } }

    /** The register call still hands the code over as its third argument. */
    private fun registerTakesCode(code: String): Boolean {
        val call: MatchResult = registerCall.find(code) ?: return false
        val args: List<String> = call.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return args.size == 3 && args[2] == "installCode"
    }

    private val rules: List<ScanRule> = listOf(
        ScanRule("INSTALL_CODE_IS_NEVER_LOGGED_PRINTED_OR_STORED") { noLeakLine(it) },
        ScanRule("REGISTER_CALL_STILL_CARRIES_THE_INSTALL_CODE") { registerTakesCode(it) },
    )

    private val firing: List<Planted> = listOf(
        Planted(
            "INSTALL_CODE_IS_NEVER_LOGGED_PRINTED_OR_STORED",
            "                if (register) authService.register(username, password, installCode) else authService.login(username, password)\n",
            "                if (register) authService.register(username, password, installCode) else authService.login(username, password)\n" +
                "                Log.d(TAG, \"code \" + installCode)\n",
        ),
        Planted(
            "REGISTER_CALL_STILL_CARRIES_THE_INSTALL_CODE",
            "authService.register(username, password, installCode)",
            "authService.register(username, password)",
        ),
    )

    /** A reformat that only wraps the register call and its neighbours; both rules must stay true. */
    private val quiet: List<Planted> = listOf(
        Planted(
            "",
            "                if (register) authService.register(username, password, installCode) else authService.login(username, password)\n",
            "                if (register) {\n" +
                "                    authService.register(\n" +
                "                        username,\n" +
                "                        password,\n" +
                "                        installCode,\n" +
                "                    )\n" +
                "                } else {\n" +
                "                    authService.login(username, password)\n" +
                "                }\n",
        ),
    )

    private fun edit(text: String, old: String, new: String): String {
        val at: Int = text.indexOf(old)
        check(at >= 0) { "app: an install code scan sample lost its text '$old'" }
        return text.substring(0, at) + new + text.substring(at + old.length)
    }

    private fun codeOf(old: String? = null, new: String = ""): String {
        val raw: String = AppSourceFiles.mainFile(file)
        return AppSourceFiles.strip(if (old == null) raw else edit(raw, old, new)).code
    }

    @Test
    fun `every install code rule holds on the real wiring file`() {
        assertTrue("app: the wiring file was read as empty", codeOf().isNotBlank())
        val broken: List<String> = rules.filter { !it.holds(codeOf()) }.map { it.name }
        assertTrue("app: the install code gate breaks rules $broken", broken.isEmpty())
    }

    @Test
    fun `each install code rule fires on its own planted edit`() {
        val names: List<String> = rules.map { it.name }
        assertEquals(
            "app: a firing sample belongs to an unknown rule",
            names.toSet(),
            firing.map { it.rule }.toSet(),
        )
        for (sample in firing) {
            val rule: ScanRule = rules.single { it.name == sample.rule }
            assertFalse(
                "app: install code rule ${sample.rule} must fire on '${sample.old}' => '${sample.new}'",
                rule.holds(codeOf(sample.old, sample.new)),
            )
        }
    }

    @Test
    fun `a reformat that only wraps the register call leaves every install code rule true`() {
        for (sample in quiet) {
            for (rule in rules) {
                assertTrue(
                    "app: install code rule ${rule.name} must stay quiet on the harmless edit '${sample.old}'",
                    rule.holds(codeOf(sample.old, sample.new)),
                )
            }
        }
    }
}
