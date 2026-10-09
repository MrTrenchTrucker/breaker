package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins what the composition root puts in its dictation slots, because nothing that runs can show it:
 * every dictation through the root ends at a failing engine, so the formatters, the committer and
 * the encoder are never called. The text gate reads the real BreakerCompositionRoot.kt (comments and
 * literal text removed by the shared scanner) and holds these facts: both formatter slots are the
 * rule-based formatter and nothing in the file is a lambda formatter or a throw; the committer is the
 * unavailable committer; the encoder is the 16-bit encoder; the on-device engine slot is the
 * unavailable engine with the model-missing error and the server engine slot the one with the other
 * error. Each rule holds on the real file, is broken by at least one edited sample, and stays quiet on
 * harmless edits; a sample whose target text is missing fails by name, so it cannot go quiet by a typo.
 */
internal class RootSlotsGateTest {

    private class Rule(val name: String, val holds: (String) -> Boolean)

    private val rootPath: String = "kotlin/dev/breaker/dictation/BreakerCompositionRoot.kt"

    /** [old] replaced by [new] at its first place; fails by name when it is not there. */
    private fun edit(text: String, old: String, new: String): String {
        val at = text.indexOf(old)
        check(at >= 0) { "app: a gate sample lost its text '$old'" }
        return text.substring(0, at) + new + text.substring(at + old.length)
    }

    private fun has(code: String, pattern: String): Boolean = Regex(pattern).containsMatchIn(code)

    private fun count(code: String, pattern: String): Int = Regex(pattern).findAll(code).count()

    /** The slot named [slot] is given exactly [value] and nothing is chained onto it. */
    private fun bound(slot: String, value: String): String = """\b$slot\s*=\s*$value(?=\s*[,)])"""

    private val ruleBased: String = """RuleBasedFormatter\s*\(\s*\)"""
    private val unavailableEngine: String = """UnavailableSttEngine\s*\(\s*SttError\s*\.\s*"""

    private val rules: List<Rule> = listOf(
        Rule("LOCAL_FORMATTER_IS_RULE_BASED") { has(it, bound("localFormatter", ruleBased)) },
        Rule("SERVER_FORMATTER_IS_RULE_BASED") { has(it, bound("serverFormatter", ruleBased)) },
        Rule("EXACTLY_TWO_RULE_BASED_FORMATTERS") { count(it, """\b$ruleBased""") == 2 },
        Rule("NO_LAMBDA_FORMATTER") { !has(it, """\bFormatter\s*\{""") },
        Rule("NO_ERROR_CALL") { !has(it, """\berror\s*\(""") },
        Rule("NO_THROW") { !has(it, """\bthrow\b""") },
        Rule("COMMITTER_IS_UNAVAILABLE") { has(it, bound("committer", """UnavailableTextCommitter\s*\(\s*\)""")) },
        Rule("ENCODER_IS_PCM16") { has(it, bound("wavEncoder", """Pcm16WavEncoder\s*\(\s*\)""")) },
        Rule("LOCAL_ENGINE_IS_MODEL_MISSING") {
            has(it, bound("localEngine", """${unavailableEngine}LOCAL_MODEL_MISSING\s*,\s*LOCAL_UNAVAILABLE_DETAIL\s*\)"""))
        },
        Rule("SERVER_ENGINE_IS_OTHER") {
            has(it, bound("serverEngine", """${unavailableEngine}OTHER\s*,\s*SERVER_UNAVAILABLE_DETAIL\s*\)"""))
        },
    )

    private val localLine = "localFormatter = RuleBasedFormatter()"
    private val serverLine = "serverFormatter = RuleBasedFormatter()"
    private val committerLine = "committer = UnavailableTextCommitter()"
    private val encoderLine = "wavEncoder = Pcm16WavEncoder()"
    private val lazyOpen = "val dictation: DictationComponent by lazy {"
    private val helperFun = "private fun neverArmedController()"

    /** Per rule: edits (old to new) that must break it. */
    private val firing: Map<String, List<Pair<String, String>>> = mapOf(
        "LOCAL_FORMATTER_IS_RULE_BASED" to listOf(
            localLine to "localFormatter = Formatter { it }",
            localLine to "localFormatter = PassThroughFormatter()",
            localLine to "localFormatter = RuleBasedFormatter().also { }",
            localLine to "formatterForLocal = RuleBasedFormatter()",
            "$localLine," to "",
        ),
        "SERVER_FORMATTER_IS_RULE_BASED" to listOf(
            serverLine to "serverFormatter = Formatter { error(\"x\") }",
            serverLine to "serverFormatter = ThrowingFormatter()",
            serverLine to "serverFormatter = RuleBasedFormatter().also { }",
            serverLine to "formatterForServer = RuleBasedFormatter()",
            "$serverLine," to "",
        ),
        "EXACTLY_TWO_RULE_BASED_FORMATTERS" to listOf(
            encoderLine to "$encoderLine, spare = RuleBasedFormatter()",
            serverLine to "serverFormatter = other",
        ),
        "NO_LAMBDA_FORMATTER" to listOf(
            helperFun to "private val lambdaFormatter = Formatter { it }\n$helperFun",
            localLine to "localFormatter = object : Formatter {}",
            "ids = UuidIdSource()" to "ids = UuidIdSource(), spare = Formatter{ it }",
        ),
        "NO_ERROR_CALL" to listOf(
            lazyOpen to "$lazyOpen\n        error(\"slot\")",
            serverLine to "serverFormatter = RuleBasedFormatter().also { error(\"x\") }",
        ),
        "NO_THROW" to listOf(
            lazyOpen to "$lazyOpen\n        throw IllegalStateException()",
            helperFun to "private fun boom(): Nothing = throw IllegalStateException()\n$helperFun",
        ),
        "COMMITTER_IS_UNAVAILABLE" to listOf(
            committerLine to "committer = FakeCommitter()",
            committerLine to "committer = UnavailableTextCommitter().also { }",
            committerLine to "textCommitter = UnavailableTextCommitter()",
            "$committerLine," to "",
        ),
        "ENCODER_IS_PCM16" to listOf(
            encoderLine to "wavEncoder = SilentEncoder()",
            encoderLine to "wavEncoder = Pcm16WavEncoder().also { }",
            encoderLine to "encoder = Pcm16WavEncoder()",
            "$encoderLine," to "",
        ),
        "LOCAL_ENGINE_IS_MODEL_MISSING" to listOf(
            "SttError.LOCAL_MODEL_MISSING" to "SttError.OTHER",
            "localEngine = UnavailableSttEngine(" to "localEngine = FakeSttEngine(",
            "LOCAL_UNAVAILABLE_DETAIL)" to "SERVER_UNAVAILABLE_DETAIL)",
        ),
        "SERVER_ENGINE_IS_OTHER" to listOf(
            "SttError.OTHER" to "SttError.LOCAL_MODEL_MISSING",
            "serverEngine = UnavailableSttEngine(" to "serverEngine = FakeSttEngine(",
            "SERVER_UNAVAILABLE_DETAIL)" to "LOCAL_UNAVAILABLE_DETAIL)",
        ),
    )

    /** Edits that change nothing a rule reads: spacing, comments and literal text. */
    private val quiet: List<Pair<String, String>> = listOf(
        localLine to "localFormatter   =\n            RuleBasedFormatter( )",
        "ids = UuidIdSource()," to "ids = UuidIdSource(), // error( throw Formatter { RuleBasedFormatter()",
        "clock = SystemClockAdapter," to "clock = SystemClockAdapter, /* throw error( Formatter { RuleBasedFormatter() */",
        helperFun to "private const val NOTE = \"error( throw Formatter { RuleBasedFormatter()\"\n$helperFun",
        "micSource = micSource," to "micSource = micSource, // the real source drops in here",
        committerLine to "committer =\n            UnavailableTextCommitter( )",
    )

    private fun realCode(): String = AppSourceFiles.strip(AppSourceFiles.mainFile(rootPath)).code

    private fun codeOf(change: Pair<String, String>): String =
        AppSourceFiles.strip(edit(AppSourceFiles.mainFile(rootPath), change.first, change.second)).code

    @Test
    fun `every rule holds on the real composition root`() {
        val code = realCode()
        assertTrue("app: the composition root was read as empty", code.isNotBlank())
        for (rule in rules) {
            assertTrue("app: the composition root breaks rule ${rule.name}", rule.holds(code))
        }
    }

    @Test
    fun `the firing samples cover exactly the rules`() {
        assertEquals("app: a firing sample belongs to an unknown rule or a rule has none", rules.map { it.name }.toSet(), firing.keys)
        for ((name, samples) in firing) {
            assertTrue("app: rule $name has no firing sample", samples.isNotEmpty())
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
