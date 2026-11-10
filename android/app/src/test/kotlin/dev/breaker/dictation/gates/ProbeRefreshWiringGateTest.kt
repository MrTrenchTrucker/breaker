package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The connectivity probe of the dictation path is built once in the composition root, and the refresh
 * after a changed server address reaches that same instance on the root's own background.
 *
 * A mutant the unit tests of the decorator cannot see: a second probe built for the dictation
 * component (the refresh would warm a probe nobody asks), a refresh that is not the probe's `refresh`,
 * or a refresh that runs on the caller's thread. Each rule reads the real composition root with comments
 * and literal text removed by the shared scanner; each sample is the real text with one edit, and the
 * rule must fire on it.
 */
internal class ProbeRefreshWiringGateTest {

    private val root: String = "kotlin/dev/breaker/dictation/BreakerCompositionRoot.kt"

    private class Rule(val name: String, val holds: (String) -> Boolean)

    private val probeDeclaration = Regex(
        """\bprivate\s+val\s+probe\s*:\s*TcpConnectivityProbe\s*=\s*TcpConnectivityProbe\s*""" +
            """\(\s*serverUrlProvider\s*=\s*serverUrl\s*,\s*clock\s*=\s*SystemClockAdapter\s*,?\s*\)""",
    )
    private val dictationUsesIt = Regex("""\bprobe\s*=\s*this\s*\.\s*probe\s*,""")
    private val refreshOnBackground = Regex(
        """\bfun\s+refreshProbeInBackground\s*\(\s*\)\s*\{\s*background\s*\.\s*submit\s*\{\s*probe\s*\.\s*refresh\s*\(\s*\)\s*\}\s*\}""",
    )
    private val constructions = Regex("""(?<!class )\bTcpConnectivityProbe\s*\(""")

    private val rules: List<Rule> = listOf(
        Rule("PROBE_IS_BUILT_ONCE_OVER_THE_SAVED_ADDRESS") { code ->
            probeDeclaration.containsMatchIn(code) && constructions.findAll(code).count() == 1
        },
        Rule("DICTATION_COMPONENT_GETS_THAT_PROBE") { code -> dictationUsesIt.containsMatchIn(code) },
        Rule("REFRESH_RUNS_THE_PROBES_REFRESH_ON_THE_ROOTS_BACKGROUND") { code -> refreshOnBackground.containsMatchIn(code) },
    )

    private class Sample(val rule: String, val old: String, val new: String)

    private val samples: List<Sample> = listOf(
        Sample("PROBE_IS_BUILT_ONCE_OVER_THE_SAVED_ADDRESS", "serverUrlProvider = serverUrl,\n        clock = SystemClockAdapter,\n    )\n", "serverUrlProvider = { \"\" },\n        clock = SystemClockAdapter,\n    )\n"),
        Sample("PROBE_IS_BUILT_ONCE_OVER_THE_SAVED_ADDRESS", "probe = this.probe,", "probe = TcpConnectivityProbe(serverUrlProvider = serverUrl, clock = SystemClockAdapter),"),
        Sample("DICTATION_COMPONENT_GETS_THAT_PROBE", "probe = this.probe,", "probe = otherProbe,"),
        Sample("REFRESH_RUNS_THE_PROBES_REFRESH_ON_THE_ROOTS_BACKGROUND", "background.submit { probe.refresh() }", "probe.refresh()"),
        Sample("REFRESH_RUNS_THE_PROBES_REFRESH_ON_THE_ROOTS_BACKGROUND", "probe.refresh() }", "probe.isServerReachable() }"),
        Sample("REFRESH_RUNS_THE_PROBES_REFRESH_ON_THE_ROOTS_BACKGROUND", "background.submit { probe.refresh() }", "background.submit { }"),
    )

    private val quiet: List<Sample> = listOf(
        Sample("DICTATION_COMPONENT_GETS_THAT_PROBE", "probe = this.probe,", "probe = this . probe , // the one probe"),
        Sample("REFRESH_RUNS_THE_PROBES_REFRESH_ON_THE_ROOTS_BACKGROUND", "background.submit { probe.refresh() }", "background.submit {\n            probe.refresh()\n        }"),
    )

    /** [old] replaced by [new] at its first place; fails by name when it is not there. */
    private fun edit(text: String, old: String, new: String): String {
        val at = text.indexOf(old)
        check(at >= 0) { "app: a probe wiring sample lost its text '$old'" }
        return text.substring(0, at) + new + text.substring(at + old.length)
    }

    private fun codeOf(sample: Sample? = null): String {
        val text = AppSourceFiles.mainFile(root)
        return AppSourceFiles.strip(if (sample == null) text else edit(text, sample.old, sample.new)).code
    }

    private fun ruleNamed(name: String): Rule = rules.first { it.name == name }

    @Test
    fun `every probe wiring rule holds on the real composition root`() {
        assertTrue("app: the composition root was read as empty", codeOf().isNotBlank())
        for (rule in rules) assertTrue("app: ${rule.name} does not hold on the real file", rule.holds(codeOf()))
    }

    @Test
    fun `each planted edit breaks the rule it names`() {
        for (sample in samples) {
            assertFalse("app: ${sample.rule} did not fire on '${sample.new}'", ruleNamed(sample.rule).holds(codeOf(sample)))
        }
    }

    @Test
    fun `spacing and a trailing comment break no rule`() {
        for (sample in quiet) {
            assertTrue("app: ${sample.rule} fired on a harmless edit", ruleNamed(sample.rule).holds(codeOf(sample)))
        }
    }

    @Test
    fun `the rule names are unique and every rule has a sample`() {
        assertEquals("app: a rule name is repeated", rules.size, rules.map { it.name }.toSet().size)
        for (rule in rules) assertTrue("app: ${rule.name} has no sample", samples.any { it.rule == rule.name })
    }

    @Test
    fun `a sample whose target text is missing fails by name`() {
        val thrown = assertThrows("app: a missing target must fail", IllegalStateException::class.java) {
            edit("val a = 1", "no such text", "x")
        }
        assertTrue("app: the failure must name the lost text, got ${thrown.message}", thrown.message!!.contains("no such text"))
    }
}
