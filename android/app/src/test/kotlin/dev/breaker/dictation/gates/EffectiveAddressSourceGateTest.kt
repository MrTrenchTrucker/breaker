package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one effective-address decision has exactly one reader in the app's main sources.
 *
 * [dev.breaker.dictation.effectiveServerUrl] is called only by `EffectiveAddress.serverUrl`,
 * the one provider the composition root hands to every reader; every other reader (the sync uploader, the older-rows and pull transports, the sign-in
 * start view and the service-status home) is handed that provider. So no main file may read the
 * stored entry 1 itself: `settingsStore.load().serverUrl` is the entry-1 read this gate forbids.
 * The one provider does not read `.serverUrl` either — it passes the whole settings to
 * [dev.breaker.dictation.effectiveServerUrl] — so the rule is zero reads in every main file, and a
 * second rule pins the provider that replaced them.
 *
 * A mutant the reader tests cannot see: one reader left on entry 1 (the sync uploader, the older-rows
 * wiring, the pull wiring, the sign-in browser or the service-status home), or the provider reverted
 * to the stored entry 1. Each rule reads the real main sources with comments and literal text
 * removed by the shared scanner; each sample is the real text with one edit, and the rule must fire
 * on it. A sample whose target text is missing fails by name, so a sample cannot go quiet by a typo.
 */
internal class EffectiveAddressSourceGateTest {

    private val addressFile: String = "EffectiveAddress.kt"

    /** The entry-1 read: the stored `serverUrl` taken straight from the settings store. */
    private val entryOneReader: Regex = Regex("""\bsettingsStore\s*\.\s*load\s*\(\s*\)\s*\.\s*serverUrl\b""")

    /** The one provider: the whole settings go to the effective-address decision, never entry 1 alone. */
    private val provider: Regex = Regex(
        """\bval\s+serverUrl\s*:\s*\(\)\s*->\s*String\s*=\s*\{\s*""" +
            """effectiveServerUrl\s*\(\s*settingsStore\s*\.\s*load\s*\(\s*\)\s*\)\s*\{\s*address\s*->\s*probe\s*\.\s*cachedAnswer\s*\(\s*address\s*\)\s*==\s*true\s*\}\s*\}""",
    )

    private class Rule(val name: String, val holds: (Map<String, String>) -> Boolean)

    /** An edit of [file] (old text to new text) that must break the rule named [rule]. */
    private class Sample(val rule: String, val file: String, val old: String, val new: String)

    /** An edit of [file] that leaves both rules true. */
    private class Quiet(val file: String, val old: String, val new: String)

    private val rules: List<Rule> = listOf(
        Rule("NO_ENTRY_ONE_READER_IN_ANY_MAIN_FILE") { sources ->
            sources.all { (_, code) -> entryOneReader.findAll(code).count() == 0 }
        },
        Rule("THE_ONE_PROVIDER_READS_THE_EFFECTIVE_ADDRESS") { sources ->
            val code: String = sources[addressFile] ?: return@Rule false
            provider.containsMatchIn(code) && entryOneReader.findAll(code).count() == 0
        },
    )

    private val firing: List<Sample> = listOf(
        Sample(
            "NO_ENTRY_ONE_READER_IN_ANY_MAIN_FILE",
            "BreakerApp.kt",
            "JdkHttpTransport(compositionRoot.serverUrl)",
            "JdkHttpTransport({ settingsStore.load().serverUrl })",
        ),
        Sample(
            "NO_ENTRY_ONE_READER_IN_ANY_MAIN_FILE",
            "wiring/OlderRowsWiring.kt",
            "JdkHttpTransport(serverUrl)",
            "JdkHttpTransport({ settingsStore.load().serverUrl })",
        ),
        Sample(
            "NO_ENTRY_ONE_READER_IN_ANY_MAIN_FILE",
            "wiring/PullChainWiring.kt",
            "JdkHttpTransport(serverUrl)",
            "JdkHttpTransport({ settingsStore.load().serverUrl })",
        ),
        Sample(
            "NO_ENTRY_ONE_READER_IN_ANY_MAIN_FILE",
            "host/SignInBrowser.kt",
            "{ app.compositionRoot.serverUrl() }",
            "{ app.settingsStore.load().serverUrl }",
        ),
        Sample(
            "NO_ENTRY_ONE_READER_IN_ANY_MAIN_FILE",
            "ServiceStatusHomeWire.kt",
            "JdkHttpTransport(app.compositionRoot.serverUrl)",
            "JdkHttpTransport({ app.settingsStore.load().serverUrl })",
        ),
        Sample(
            "THE_ONE_PROVIDER_READS_THE_EFFECTIVE_ADDRESS",
            addressFile,
            "val serverUrl: () -> String = {\n        effectiveServerUrl(settingsStore.load()) { address -> probe.cachedAnswer(address) == true }\n    }",
            "internal val serverUrl: () -> String = { settingsStore.load().serverUrl }",
        ),
        Sample(
            "THE_ONE_PROVIDER_READS_THE_EFFECTIVE_ADDRESS",
            addressFile,
            "val serverUrl: () -> String = {\n        effectiveServerUrl(settingsStore.load()) { address -> probe.cachedAnswer(address) == true }\n    }",
            "internal val serverUrl: () -> String = { effectiveServerUrl(settingsStore.load(), probe::isReachable) }",
        ),
    )

    private val quiet: List<Quiet> = listOf(
        Quiet(
            addressFile,
            "val serverUrl: () -> String = {\n        effectiveServerUrl(settingsStore.load()) { address -> probe.cachedAnswer(address) == true }\n    }",
            "val serverUrl: () -> String =\n            { effectiveServerUrl( settingsStore.load() ) { address -> probe.cachedAnswer( address ) == true } }",
        ),
        Quiet(addressFile, "serverUrlProvider = serverUrl,", "serverUrlProvider =\n            serverUrl,"),
        Quiet("BreakerApp.kt", "JdkHttpTransport(compositionRoot.serverUrl)", "JdkHttpTransport( compositionRoot . serverUrl )"),
        Quiet("wiring/OlderRowsWiring.kt", "JdkHttpTransport(serverUrl)", "JdkHttpTransport( serverUrl )"),
        Quiet("ServiceStatusHomeWire.kt", "app.compositionRoot.serverUrl().isNotBlank()", "app . compositionRoot . serverUrl ( ) . isNotBlank()"),
    )

    /** [old] replaced by [new] at its first place; fails by name when it is not there. */
    private fun edit(text: String, old: String, new: String): String {
        val at = text.indexOf(old)
        check(at >= 0) { "app: an effective-address gate sample lost its text '$old'" }
        return text.substring(0, at) + new + text.substring(at + old.length)
    }

    /** The stripped code of every main Kotlin file, by path below the package folder. */
    private fun sources(): Map<String, String> =
        AppSourceFiles.mainKotlinSources().mapValues { (_, text) -> AppSourceFiles.strip(text).code }

    private fun sourcesWith(sample: Sample? = null): Map<String, String> {
        val real = sources()
        if (sample == null) return real
        val text = real[sample.file] ?: error("app: the main file ${sample.file} is missing")
        return real + (sample.file to AppSourceFiles.strip(edit(text, sample.old, sample.new)).code)
    }

    @Test
    fun `every rule holds on the real main sources`() {
        val real = sources()
        assertTrue("app: no main Kotlin file was read", real.isNotEmpty())
        assertTrue("app: the effective-address file was not read", real[addressFile]?.isNotBlank() == true)
        for (rule in rules) assertTrue("app: ${rule.name} does not hold on the real sources", rule.holds(real))
    }

    @Test
    fun `a reader left on entry 1 in any of the five sites breaks the gate`() {
        for (sample in firing) {
            assertFalse(
                "app: ${sample.rule} did not fire on '${sample.new}' in ${sample.file}",
                rules.single { it.name == sample.rule }.holds(sourcesWith(sample)),
            )
        }
    }

    @Test
    fun `spacing and a line break break no rule`() {
        for (sample in quiet) {
            for (rule in rules) {
                assertTrue(
                    "app: ${rule.name} fired on the harmless edit '${sample.old}' in ${sample.file}",
                    rule.holds(sourcesWith(Sample(rule.name, sample.file, sample.old, sample.new))),
                )
            }
        }
    }

    @Test
    fun `the rule names are unique and every rule has a sample`() {
        assertEquals("app: a rule name is repeated", rules.size, rules.map { it.name }.toSet().size)
        for (rule in rules) assertTrue("app: ${rule.name} has no sample", firing.any { it.rule == rule.name })
    }

    @Test
    fun `a sample whose target text is missing fails by name`() {
        val thrown = assertThrows("app: a missing target must fail", IllegalStateException::class.java) {
            edit("val a = 1", "no such text", "x")
        }
        assertTrue("app: the failure must name the lost text, got ${thrown.message}", thrown.message!!.contains("no such text"))
    }
}
