package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The connectivity probe of the dictation path is built exactly once, in [EffectiveAddress], and every
 * other main file takes it from the composition root.
 *
 * A mutant the app's own wiring tests cannot see: a second `TcpConnectivityProbe(` constructed anywhere
 * else on main - the sync service, the composition root, any wiring - so the refresh warms a probe nobody
 * asks (S4C-M01), or a second construction planted inside [EffectiveAddress] itself so the one legal file
 * holds two probes instead of one. Each rule reads the real main sources with comments and literal text
 * removed by the shared scanner; each sample is the real text with one edit, and the rule must fire on it.
 * A sample whose target text is missing fails by name, so a sample cannot go quiet by a typo.
 */
internal class TcpConnectivityProbeConstructionGateTest {

    private val addressFile: String = "EffectiveAddress.kt"

    /** The construction itself, minus the class declaration: a `TcpConnectivityProbe(` call. */
    private val construction: Regex = Regex("""(?<!class )\bTcpConnectivityProbe\s*\(""")

    private class Rule(val name: String, val holds: (Map<String, String>) -> Boolean)

    /** An edit of [file] (old text to new text) that must break the rule named [rule]. */
    private class Sample(val rule: String, val file: String, val old: String, val new: String)

    /** An edit of [file] that leaves both rules true. */
    private class Quiet(val file: String, val old: String, val new: String)

    private val rules: List<Rule> = listOf(
        Rule("NO_PROBE_CONSTRUCTION_IN_ANY_MAIN_FILE_BUT_THE_ADDRESS") { sources ->
            sources.all { (path, code) -> path == addressFile || construction.findAll(code).count() == 0 }
        },
        Rule("THE_ADDRESS_FILE_HOLDS_EXACTLY_ONE_CONSTRUCTION") { sources ->
            val code: String = sources[addressFile] ?: return@Rule false
            construction.findAll(code).count() == 1
        },
    )

    /** A fully-qualified second construction: the S4C-M01 survivor shape (no import needed). */
    private val secondInApp: String =
        "dev.breaker.dictation.transport.TcpConnectivityProbe(serverUrlProvider = compositionRoot.serverUrl, clock = SystemClockAdapter)"

    /** The same construction planted where the composition root itself is in scope. */
    private val secondInRoot: String =
        "dev.breaker.dictation.transport.TcpConnectivityProbe(serverUrlProvider = serverUrl, clock = SystemClockAdapter)"

    private val firing: List<Sample> = listOf(
        Sample(
            "NO_PROBE_CONSTRUCTION_IN_ANY_MAIN_FILE_BUT_THE_ADDRESS",
            "BreakerApp.kt",
            "probe = compositionRoot.probe,",
            "probe = $secondInApp,",
        ),
        Sample(
            "NO_PROBE_CONSTRUCTION_IN_ANY_MAIN_FILE_BUT_THE_ADDRESS",
            "BreakerCompositionRoot.kt",
            "probe = this.probe,",
            "probe = $secondInRoot,",
        ),
        Sample(
            "THE_ADDRESS_FILE_HOLDS_EXACTLY_ONE_CONSTRUCTION",
            addressFile,
            "        recordLastGood = { persistServerLastGood(settingsStore, it) },\n    )",
            "        recordLastGood = { persistServerLastGood(settingsStore, it) },\n    )\n" +
                "    val second: TcpConnectivityProbe = TcpConnectivityProbe(serverUrlProvider = serverUrl, clock = clock)",
        ),
    )

    private val quiet: List<Quiet> = listOf(
        Quiet(
            addressFile,
            "    val probe: TcpConnectivityProbe = TcpConnectivityProbe(",
            "    // The one probe, built once.\n    val probe: TcpConnectivityProbe = TcpConnectivityProbe( // one instance",
        ),
        Quiet(
            addressFile,
            "        serverUrlProvider = serverUrl,\n        clock = clock,",
            "        serverUrlProvider =  serverUrl ,\n        clock =   clock ,",
        ),
    )

    /** [old] replaced by [new] at its first place; fails by name when it is not there. */
    private fun edit(text: String, old: String, new: String): String {
        val at = text.indexOf(old)
        check(at >= 0) { "app: a probe-construction gate sample lost its text '$old'" }
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
        assertTrue("app: the address file was not read", real[addressFile]?.isNotBlank() == true)
        for (rule in rules) assertTrue("app: ${rule.name} does not hold on the real sources", rule.holds(real))
    }

    @Test
    fun `a second probe built in BreakerApp breaks the gate`() {
        val sample = firing.single { it.file == "BreakerApp.kt" }
        assertFalse(
            "app: ${sample.rule} did not fire on '${sample.new}' in ${sample.file}",
            rules.single { it.name == sample.rule }.holds(sourcesWith(sample)),
        )
    }

    @Test
    fun `a second probe built in the composition root breaks the gate`() {
        val sample = firing.single { it.file == "BreakerCompositionRoot.kt" }
        assertFalse(
            "app: ${sample.rule} did not fire on '${sample.new}' in ${sample.file}",
            rules.single { it.name == sample.rule }.holds(sourcesWith(sample)),
        )
    }

    @Test
    fun `a second construction planted in the address file breaks the exactly-one rule`() {
        val sample = firing.single { it.file == addressFile }
        assertFalse(
            "app: ${sample.rule} did not fire on '${sample.new}' in ${sample.file}",
            rules.single { it.name == sample.rule }.holds(sourcesWith(sample)),
        )
    }

    @Test
    fun `spacing and a comment around the legal construction break nothing`() {
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

    @Test
    fun `the scan saw every main file and found exactly one legal construction`() {
        val real = sources()
        assertTrue("app: the scan saw too few main files (${real.size}) to be non-vacuous", real.size >= 138)
        val hits: Int = real.values.sumOf { construction.findAll(it).count() }
        assertEquals(
            "app: the scan saw ${real.size} main files but found $hits legal constructions, not exactly one",
            1,
            hits,
        )
        assertEquals("app: the one legal construction is not in $addressFile", 1, construction.findAll(real.getValue(addressFile)).count())
    }
}
