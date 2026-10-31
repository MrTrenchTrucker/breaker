package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the server link in the composition root: where the stored address and the session seam come from,
 * that the probe and the engine share the one address provider, that the default factory is the real
 * engine, that the seam defaults are the empty session, that the root names no placeholder for the server
 * slot, and that no other main file names the engine. The rules read the real root with comments and
 * literal text removed by the shared scanner. Each rule holds on the real file, is broken by at least two
 * edited samples, and stays quiet on harmless edits; an edit whose target text is missing fails by name.
 */
internal class ServerLinkGateTest {

    private val rootPath: String = "kotlin/dev/breaker/dictation/BreakerCompositionRoot.kt"

    private class Rule(val name: String, val holds: (Stripped) -> Boolean)

    private fun has(code: String, pattern: String): Boolean = Regex(pattern).containsMatchIn(code)

    private fun count(code: String, pattern: String): Int = Regex(pattern).findAll(code).count()

    private val rules: List<Rule> = listOf(
        Rule("SETTINGS_ADDRESS_READ_ONCE") {
            count(it.code, """\bsettingsStore\s*\.\s*load\s*\(\s*\)\s*\.\s*serverUrl\b""") == 1
        },
        Rule("PROBE_TAKES_THE_SHARED_ADDRESS") { has(it.code, """\bserverUrlProvider\s*=\s*serverUrl\s*,""") },
        Rule("ENGINE_TAKES_THE_SHARED_ADDRESS_AND_THE_KEY") {
            has(it.code, """\bserverEngineFactory\s*\(\s*serverUrl\s*,\s*serverApiKey\s*\)""")
        },
        Rule("ONE_KEY_PROVIDER_FROM_THE_SEAM") {
            has(it.code, """\bprivate\s+val\s+serverApiKey\s*:\s*\(\)\s*->\s*String\?\s*=\s*\{\s*sessionToken\(\)\s*\}""") &&
                count(it.code, """\bserverApiKey\s*:""") == 1
        },
        Rule("SEAM_READ_INSIDE_A_LAMBDA") {
            count(it.code, """\bsessionToken\(\)""") == 1 && count(it.code, """\{\s*sessionToken\(\)\s*\}""") == 1
        },
        Rule("SEAM_DEFAULTS") {
            count(it.code, """=\s*NO_SESSION\b""") == 3 &&
                has(it.code, """\bprivate\s+val\s+NO_SESSION\s*:\s*\(\)\s*->\s*String\?\s*=\s*\{\s*null\s*\}""")
        },
        Rule("ENGINE_DEFAULTS") {
            count(it.code, """=\s*REAL_SERVER_ENGINE\b""") == 3 &&
                has(it.code, """\bprivate\s+val\s+REAL_SERVER_ENGINE\b""") &&
                count(it.code, """\bSttServerEngine\s*\(""") == 1 &&
                has(it.code, """\{\s*serverUrl\s*,\s*apiKey\s*->\s*SttServerEngine\s*\(\s*serverUrl\s*,\s*apiKey\s*\)\s*\}""")
        },
        Rule("NO_PLACEHOLDER_SERVER_SLOT") {
            count(it.code, """\bUnavailableSttEngine\b""") == 0 && count(it.code, """\bSERVER_UNAVAILABLE_DETAIL\b""") == 0
        },
        Rule("NO_SCHEME_IN_THE_ROOT") {
            !has(it.code, """\bhttps?\b""") && it.literals.none { literal -> literal.contains("http") }
        },
        Rule("ROOT_LITERALS_ARE_THE_FILE_NAMES_ONLY") {
            it.literals == listOf("credential-ref", "settings.properties", "models")
        },
    )

    /** Per rule: edits (old to new) that must break it. */
    private val firing: Map<String, List<Pair<String, String>>> = mapOf(
        "SETTINGS_ADDRESS_READ_ONCE" to listOf(
            "serverUrlProvider = serverUrl," to "serverUrlProvider = { settingsStore.load().serverUrl },",
            "private val serverUrl: () -> String = { settingsStore.load().serverUrl }" to
                "private val serverUrl: () -> String = { \"https://example.invalid\" }",
        ),
        "PROBE_TAKES_THE_SHARED_ADDRESS" to listOf(
            "serverUrlProvider = serverUrl," to "serverUrlProvider = fixedUrl,",
            "serverUrlProvider = serverUrl," to "",
        ),
        "ENGINE_TAKES_THE_SHARED_ADDRESS_AND_THE_KEY" to listOf(
            "serverEngine = serverEngineFactory(serverUrl, serverApiKey)" to "serverEngine = serverEngineFactory(serverApiKey, serverUrl)",
            "serverEngine = serverEngineFactory(serverUrl, serverApiKey)" to "serverEngine = serverEngineFactory(serverUrl, { null })",
        ),
        "ONE_KEY_PROVIDER_FROM_THE_SEAM" to listOf(
            "private val serverApiKey: () -> String? = { sessionToken() }" to
                "private val serverApiKey: () -> String? = { \"stand-in-token\" }",
            "private val serverApiKey: () -> String? = { sessionToken() }" to
                "private val serverApiKey: () -> String? = { sessionToken() }\n    private val serverApiKey: () -> String? = { sessionToken() }",
        ),
        "SEAM_READ_INSIDE_A_LAMBDA" to listOf(
            "private val serverApiKey: () -> String? = { sessionToken() }" to
                "private val serverApiKey: () -> String? = sessionToken().let { key -> { key } }",
            "private val serverApiKey: () -> String? = { sessionToken() }" to
                "private val serverApiKey: () -> String? = { sessionToken() }\n    private val seenSession = sessionToken()",
        ),
        "SEAM_DEFAULTS" to listOf(
            "private val NO_SESSION: () -> String? = { null }" to "private val NO_SESSION: () -> String? = { \"stand-in-token\" }",
            "private val sessionToken: () -> String? = NO_SESSION," to "private val sessionToken: () -> String? = { null },",
        ),
        "ENGINE_DEFAULTS" to listOf(
            "{ serverUrl, apiKey -> SttServerEngine(serverUrl, apiKey) }" to
                "{ serverUrl, apiKey -> SttServerEngine(serverUrl, apiKey, ServerLimits()) }",
            "private val serverEngineFactory: (serverUrl: () -> String, apiKey: () -> String?) -> SttEngine = REAL_SERVER_ENGINE," to
                "private val serverEngineFactory: (serverUrl: () -> String, apiKey: () -> String?) -> SttEngine = REAL_SERVER_ENGINE_OTHER,",
        ),
        "NO_PLACEHOLDER_SERVER_SLOT" to listOf(
            "serverEngine = serverEngineFactory(serverUrl, serverApiKey)" to "serverEngine = UnavailableSttEngine(SttError.OTHER, \"x\")",
            "private val serverUrl: () -> String = { settingsStore.load().serverUrl }" to
                "private val SERVER_UNAVAILABLE_DETAIL = \"x\"\n    private val serverUrl: () -> String = { settingsStore.load().serverUrl }",
        ),
        "NO_SCHEME_IN_THE_ROOT" to listOf(
            "private val NO_SESSION: () -> String? = { null }" to
                "private val NO_SESSION: () -> String? = { null }\n    private val scheme = \"https://\"",
            "private val serverUrl: () -> String = { settingsStore.load().serverUrl }" to
                "private val serverUrl: () -> String = { settingsStore.load().serverUrl.replace(\"http://\", \"https://\") }",
        ),
        "ROOT_LITERALS_ARE_THE_FILE_NAMES_ONLY" to listOf(
            "\"models\"" to "\"models-folder\"",
            "\"settings.properties\"" to "\"settings.txt\"",
        ),
    )

    /** Edits that change nothing a rule reads: spacing, comments and literal text. */
    private val quiet: List<Pair<String, String>> = listOf(
        "serverUrlProvider = serverUrl," to "serverUrlProvider =\n                    serverUrl,",
        "private val serverUrl: () -> String = { settingsStore.load().serverUrl }" to
            "private val serverUrl: () -> String =\n        { settingsStore.load().serverUrl }",
        "private val NO_SESSION: () -> String? = { null }" to
            "private val NO_SESSION: () -> String? = { null } // SttServerEngine( UnavailableSttEngine \"https://",
        "private val serverApiKey: () -> String? = { sessionToken() }" to
            "private val serverApiKey: () -> String? = { sessionToken() /* sessionToken() */ }",
    )

    private fun rootText(): String = AppSourceFiles.mainFile(rootPath)

    private fun codeOf(text: String): Stripped = AppSourceFiles.strip(text)

    /** [old] replaced by [new] at its first place; fails by name when it is not there. */
    private fun edit(text: String, old: String, new: String): String {
        val at = text.indexOf(old)
        check(at >= 0) { "app: a server link gate sample lost its text '$old'" }
        return text.substring(0, at) + new + text.substring(at + old.length)
    }

    /** True when the engine or its factory is named by the code of [text]. */
    private fun namesEngine(text: String): Boolean =
        has(AppSourceFiles.strip(text).code, """\bSttServerEngine\b|\bserverEngineFactory\b|\bdev\.breaker\.dictation\.stt\.server\b""")

    @Test
    fun `every rule holds on the real composition root`() {
        val real: Stripped = codeOf(rootText())
        assertTrue("app: the composition root was read as empty", real.code.isNotBlank())
        for (rule in rules) {
            assertTrue("app: the composition root breaks rule ${rule.name}", rule.holds(real))
        }
    }

    @Test
    fun `the firing samples cover each rule at least twice`() {
        assertEquals("app: a firing sample belongs to an unknown rule or a rule has none", rules.map { it.name }.toSet(), firing.keys)
        for ((name, samples) in firing) {
            assertTrue("app: rule $name needs at least two firing samples, has ${samples.size}", samples.size >= 2)
        }
    }

    @Test
    fun `every rule fires on each of its edited samples`() {
        val byName = rules.associateBy { it.name }
        for ((name, samples) in firing) {
            for (sample in samples) {
                val edited: Stripped = codeOf(edit(rootText(), sample.first, sample.second))
                assertFalse("app: rule $name must fire on the edit '${sample.first}' => '${sample.second}'", byName.getValue(name).holds(edited))
            }
        }
    }

    @Test
    fun `no rule fires on a harmless edit`() {
        for (sample in quiet) {
            val edited: Stripped = codeOf(edit(rootText(), sample.first, sample.second))
            for (rule in rules) {
                assertTrue("app: rule ${rule.name} must stay quiet on the harmless edit '${sample.first}'", rule.holds(edited))
            }
        }
    }

    @Test
    fun `no main file other than the composition root names the server engine or its factory`() {
        for ((path, text) in AppSourceFiles.mainKotlinSources()) {
            if (path == "BreakerCompositionRoot.kt") continue
            assertFalse("app: $path names the server engine or its factory", namesEngine(text))
        }
    }

    @Test
    fun `the engine-naming check fires on code and stays quiet on comments and literal text`() {
        assertTrue("app: a file that names the engine in code must be caught", namesEngine("class Host { val e = SttServerEngine(url, key) }"))
        assertTrue("app: an import of the engine package must be caught", namesEngine("import dev.breaker.dictation.stt.server.ServerLimits"))
        assertFalse("app: the engine named in a comment must not be caught", namesEngine("class Host { /* SttServerEngine */ }"))
        assertFalse("app: the engine named in literal text must not be caught", namesEngine("val text = \"SttServerEngine\""))
    }

    @Test
    fun `a sample whose target text is missing fails by name`() {
        val thrown = assertThrows("app: a missing target must fail", IllegalStateException::class.java) {
            edit("val a = 1", "no such text", "x")
        }
        assertTrue("app: the failure must name the lost text, got ${thrown.message}", thrown.message!!.contains("no such text"))
    }
}
