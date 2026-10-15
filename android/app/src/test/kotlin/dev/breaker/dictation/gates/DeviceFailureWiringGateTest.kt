package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the device failure wiring, because no JVM test can run the host. `host/TileHost.kt` offers a
 * cut to the coordinator through the root hook, and runs the fallback only when the coordinator does
 * not take it. `wiring/DictationComponent.kt` hands the handler the fallback, and the one owed-stop
 * call sits in the fallback lambda. The rules read the real files with comments and literal text
 * removed by the shared scanner. Each rule holds on the real file, fires on its edited samples and
 * stays quiet on harmless edits.
 */
internal class DeviceFailureWiringGateTest {

    private val dir: String = "kotlin/dev/breaker/dictation/"
    private val files: Map<String, String> = mapOf(
        "host" to dir + "host/TileHost.kt",
        "component" to dir + "wiring/DictationComponent.kt",
    )

    private class Rule(val name: String, val file: String, val holds: (String) -> Boolean)
    private class Sample(val rule: String, val old: String, val new: String)
    private class Quiet(val file: String, val old: String, val new: String)

    /** The pattern must match the code exactly [times] times. */
    private fun rx(name: String, file: String, pattern: String, times: Int = 1) =
        Rule(name, file) { Regex(pattern).findAll(it).count() == times }

    private val hostLine: String =
        "root.onDeviceFailure = { fallback -> toCoordinator { if (!it.onTakeFailed()) fallback() } }"

    private val rules: List<Rule> = listOf(
        rx("DEVICE_FAILURE_IS_OFFERED_TO_THE_COORDINATOR", "host",
            """\broot\s*\.\s*onDeviceFailure\s*=\s*\{\s*fallback\s*->\s*toCoordinator\s*\{\s*if\s*\(\s*!\s*it\s*\.\s*onTakeFailed\s*\(\s*\)\s*\)\s*fallback\s*\(\s*\)\s*\}\s*\}"""),
        rx("DEVICE_FAILURE_HANDLER_GETS_THE_FALLBACK", "component",
            """\bval\s+handler\s*:\s*\(\(\(\)\s*->\s*Unit\)\s*->\s*Unit\)\?\s*=\s*onDeviceFailure\s+if\s*\(\s*handler\s*!=\s*null\s*\)\s*handler\s*\(\s*fallback\s*\)\s*else\s*fallback\s*\(\s*\)"""),
        rx("DEVICE_FAILURE_FALLBACK_IS_THE_OLD_END", "component",
            """\bval\s+fallback\s*:\s*\(\)\s*->\s*Unit\s*=\s*\{\s*runnerHolder\s*\.\s*get\s*\(\s*\)\s*\?\.\s*onCaptureEnded\s*\(\s*\)\s+onTakeEnded\s*\(\s*\)\s*\}"""),
        rx("DEVICE_FAILURE_OWES_THE_STOP_ONLY_IN_THE_FALLBACK", "component", """\bonCaptureEnded\s*\(""", 1),
    )

    private val firing: List<Sample> = listOf(
        Sample("DEVICE_FAILURE_IS_OFFERED_TO_THE_COORDINATOR", "if (!it.onTakeFailed()) fallback()", "if (it.onTakeFailed()) fallback()"),
        Sample("DEVICE_FAILURE_IS_OFFERED_TO_THE_COORDINATOR", hostLine, ""),
        Sample("DEVICE_FAILURE_IS_OFFERED_TO_THE_COORDINATOR", "root.onDeviceFailure = {", "root.onDeviceFailed = {"),
        Sample("DEVICE_FAILURE_HANDLER_GETS_THE_FALLBACK", "if (handler != null) handler(fallback) else fallback()", "fallback()"),
        Sample("DEVICE_FAILURE_HANDLER_GETS_THE_FALLBACK", "val handler: ((() -> Unit) -> Unit)? = onDeviceFailure", "val handler: ((() -> Unit) -> Unit)? = null"),
        Sample("DEVICE_FAILURE_FALLBACK_IS_THE_OLD_END", "val fallback: () -> Unit = {", "val fallback: () -> Unit = { onTakeEnded()"),
        Sample("DEVICE_FAILURE_FALLBACK_IS_THE_OLD_END", "runnerHolder.get()?.onCaptureEnded()", "Unit"),
        Sample("DEVICE_FAILURE_OWES_THE_STOP_ONLY_IN_THE_FALLBACK", "if (handler != null) handler(fallback) else fallback()", "runnerHolder.get()?.onCaptureEnded()\n                                if (handler != null) handler(fallback) else fallback()"),
        Sample("DEVICE_FAILURE_OWES_THE_STOP_ONLY_IN_THE_FALLBACK", "val handler: ((() -> Unit) -> Unit)? = onDeviceFailure", "val handler: ((() -> Unit) -> Unit)? = onDeviceFailure\n                                onCaptureEnded()"),
    )

    private val quiet: List<Quiet> = listOf(
        Quiet("host", hostLine, hostLine + " // onTakeFailed() fallback()"),
        Quiet("component", "val handler: ((() -> Unit) -> Unit)? = onDeviceFailure", "val handler: ((() -> Unit) -> Unit)? = onDeviceFailure // runnerHolder.get()?.onCaptureEnded()"),
    )

    private fun edit(text: String, old: String, new: String): String {
        val at = text.indexOf(old)
        check(at >= 0) { "app: a device failure sample lost its text '$old'" }
        return text.substring(0, at) + new + text.substring(at + old.length)
    }

    private fun raw(key: String): String = AppSourceFiles.mainFile(files.getValue(key))

    private fun codeOf(key: String, old: String? = null, new: String = ""): String =
        AppSourceFiles.strip(if (old == null) raw(key) else edit(raw(key), old, new)).code

    @Test
    fun `every device failure rule holds on the real files`() {
        for (key in files.keys) assertTrue("app: ${files.getValue(key)} was read as empty", codeOf(key).isNotBlank())
        for (rule in rules) assertTrue("app: ${files.getValue(rule.file)} breaks rule ${rule.name}", rule.holds(codeOf(rule.file)))
    }

    @Test
    fun `the firing samples cover each device failure rule at least twice`() {
        val names = rules.map { it.name }
        assertEquals("app: a device failure rule name is used twice", names.toSet().size, names.size)
        assertEquals("app: a firing sample belongs to an unknown rule or a rule has none", names.toSet(), firing.map { it.rule }.toSet())
        for (rule in rules) {
            val count = firing.count { it.rule == rule.name }
            assertTrue("app: device failure rule ${rule.name} needs at least two firing samples, has $count", count >= 2)
        }
    }

    @Test
    fun `every device failure rule fires on each of its edited samples`() {
        val byName = rules.associateBy { it.name }
        for (sample in firing) {
            val rule = byName.getValue(sample.rule)
            assertFalse("app: device failure rule ${sample.rule} must fire on the edit '${sample.old}' => '${sample.new}'", rule.holds(codeOf(rule.file, sample.old, sample.new)))
        }
    }

    @Test
    fun `no device failure rule fires on a harmless edit`() {
        for (sample in quiet) {
            for (rule in rules.filter { it.file == sample.file }) {
                assertTrue("app: device failure rule ${rule.name} must stay quiet on the harmless edit '${sample.old}' => '${sample.new}'", rule.holds(codeOf(sample.file, sample.old, sample.new)))
            }
        }
    }

    @Test
    fun `a sample whose target text is missing fails by name`() {
        val failure = assertThrows(IllegalStateException::class.java) { codeOf("host", "no such text here", "x") }
        assertTrue("app: the failure names the lost text", failure.message!!.contains("no such text here"))
    }
}
