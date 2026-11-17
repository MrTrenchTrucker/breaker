package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The account-key wiring file, read as text: it names no password and no logging token. Each rule
 * carries a firing sample, so a rule that stops matching is caught by its own sample. The scan
 * reads code only: comments, string literals and names in backticks are blanked first.
 */
internal class AccountKeyWiringSourceGateTest {

    private class Rule(val label: String, val pattern: Regex, val sample: String)

    /** Every rule the gate must hold, by label: a rule dropped from [rules] is caught here. */
    private val requiredRuleLabels: List<String> = listOf(
        "password parameter",
        "a char array the password would ride in",
        "Log",
        "println",
        "print call",
        "System out or err",
    )

    private val rules: List<Rule> = listOf(
        Rule(
            "password parameter",
            Regex("""\bpassword\b"""),
            "internal fun accountKeyPorts(credentials: CredentialVault, crypto: CryptoService, password: CharArray): " +
                "AccountKeyPorts = AccountKeyPorts(AccountKeyServiceImpl(), crypto, credentials)",
        ),
        Rule(
            "a char array the password would ride in",
            Regex("""\bCharArray\b"""),
            "internal fun accountKeyPorts(credentials: CredentialVault, crypto: CryptoService, secret: CharArray): " +
                "AccountKeyPorts = AccountKeyPorts(AccountKeyServiceImpl(), crypto, credentials)",
        ),
        Rule(
            "Log",
            Regex("""\bLog\b"""),
            "internal fun accountKeyPorts(credentials: CredentialVault, crypto: CryptoService): AccountKeyPorts = " +
                "AccountKeyPorts(AccountKeyServiceImpl(), crypto, credentials).also { Log.d(t, m) }",
        ),
        Rule(
            "println",
            Regex("""\bprintln\b"""),
            "internal fun accountKeyPorts(credentials: CredentialVault, crypto: CryptoService): AccountKeyPorts = " +
                "AccountKeyPorts(AccountKeyServiceImpl(), crypto, credentials).also { println(m) }",
        ),
        Rule(
            "print call",
            Regex("""\bprint\s*\("""),
            "internal fun accountKeyPorts(credentials: CredentialVault, crypto: CryptoService): AccountKeyPorts = " +
                "AccountKeyPorts(AccountKeyServiceImpl(), crypto, credentials).also { print(m) }",
        ),
        Rule(
            "System out or err",
            Regex("""System\s*\.\s*(out|err)\b"""),
            "internal fun accountKeyPorts(credentials: CredentialVault, crypto: CryptoService): AccountKeyPorts = " +
                "AccountKeyPorts(AccountKeyServiceImpl(), crypto, credentials).also { System.out.print(m) }",
        ),
    )

    private val ruleMap: Map<String, Regex> = rules.associate { it.label to it.pattern }

    private val quiet: Map<String, String> = mapOf(
        "a line comment" to "// password CharArray Log println\nobject A",
        "a KDoc" to "/** Never takes a password or a CharArray, and never calls Log. */\nobject A",
        "a string" to "object A { val s = \"password CharArray Log println\" }",
        "a raw string" to "object A { val s = \"\"\"password Log System.out\"\"\" }",
        "a name in backticks" to "class T { @Test fun `a test that mentions password and Log(`() {} }",
        "longer words" to "object A { val passwords = 1; val passwordText = 2; val CharArrays = 3; " +
            "val Login = 4; val printed = 5 }",
    )

    private val wiringFile: String = "kotlin/dev/breaker/dictation/wiring/AccountKeyPortsWiring.kt"

    @Test
    fun `the account key wiring file names no password and no logging token`() {
        assertEquals(
            "app: the account key wiring file could take a password or send text out",
            emptyList<String>(),
            AppSourceFiles.offences(mapOf("wiring/AccountKeyPortsWiring.kt" to AppSourceFiles.mainFile(wiringFile)), ruleMap),
        )
    }

    @Test
    fun `each rule fires on its own sample`() {
        assertEquals("app: two account key rules share a name", rules.size, ruleMap.size)
        for (rule in rules) {
            val found: List<String> = AppSourceFiles.offences(mapOf("Sample.kt" to rule.sample), ruleMap)
            assertTrue(
                "app: the account key rule ${rule.label} did not fire on its own sample, found $found",
                found.any { it.startsWith("Sample.kt: ${rule.label} x") },
            )
        }
    }

    @Test
    fun `the gate holds a rule for the password and a rule for a log`() {
        assertTrue(
            "app: the gate must hold a rule for the password",
            ruleMap.keys.any { it.contains("password") },
        )
        assertTrue(
            "app: the gate must hold a rule for a logging token",
            ruleMap.keys.any { it.contains("Log") || it.contains("println") || it.contains("print call") },
        )
    }

    @Test
    fun `the gate holds every required rule`() {
        val missing: List<String> = requiredRuleLabels.filterNot { ruleMap.containsKey(it) }
        assertEquals(
            "app: the gate is missing the required rule(s): $missing",
            emptyList<String>(),
            missing,
        )
    }

    @Test
    fun `the same names in comments, strings, backticks or longer words are not reported`() {
        for ((label, source) in quiet) {
            assertEquals(
                "app: the account key rule flagged $label",
                emptyList<String>(),
                AppSourceFiles.offences(mapOf("Sample.kt" to source), ruleMap),
            )
        }
    }
}
