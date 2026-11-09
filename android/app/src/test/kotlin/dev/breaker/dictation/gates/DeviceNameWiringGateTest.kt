package dev.breaker.dictation.gates

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wiring of the device name into the hand-off finish, which no JVM test runs: the root's
 * lazy hand-off block passes the platform reader to the finish's device name argument. The rule
 * reads the real file with comments and literal text removed; it holds on the real file, is
 * broken by the two edited samples below, and stays quiet on a harmless edit. An edit whose
 * target text is missing fails by name.
 */
internal class DeviceNameWiringGateTest {

    private val file: String = "kotlin/dev/breaker/dictation/BreakerCompositionRoot.kt"

    /** The root's lazy hand-off block, from its declaration to its first closing brace. */
    private fun blockOf(text: String): String {
        val start = text.indexOf("private val handoffFinish: HandoffFinish by lazy {")
        assertTrue("app: the root has no lazy hand-off block", start >= 0)
        val end = text.indexOf("}", start)
        assertTrue("app: the lazy hand-off block has no closing brace", end >= 0)
        return text.substring(start, end + 1)
    }

    /** The rule: the block passes the platform reader as the device name argument. */
    private fun holds(text: String): Boolean =
        blockOf(text).contains("deviceName = ::appDeviceName")

    private fun codeOf(old: String? = null, new: String = ""): String {
        val source = AppSourceFiles.mainFile(file)
        val text = if (old == null) source else edit(source, old, new)
        return AppSourceFiles.strip(text).code
    }

    private fun edit(source: String, old: String, new: String): String {
        val at = source.indexOf(old)
        assertTrue("app: the device name wiring sample lost its text '$old'", at >= 0)
        return source.substring(0, at) + new + source.substring(at + old.length)
    }

    /** The real source with the line removed from the block and set back before it. */
    private fun movedOutside(): String {
        var raw = AppSourceFiles.mainFile(file)
        raw = edit(raw, "            deviceName = ::appDeviceName,\n", "")
        raw = edit(raw, "    private val handoffFinish: HandoffFinish by lazy {",
            "    val deviceName = ::appDeviceName\n    private val handoffFinish: HandoffFinish by lazy {")
        return AppSourceFiles.strip(raw).code
    }

    @Test
    fun `the root passes the reader as the device name inside its lazy hand-off block`() {
        assertTrue("app: the root file is empty", codeOf().isNotBlank())
        assertTrue("app: the root does not pass the device name in the block", holds(codeOf()))
    }

    @Test
    fun `the rule fires when the device name line is dropped`() {
        val dropped = codeOf("            deviceName = ::appDeviceName,\n", "")
        assertFalse("app: the rule must fire when the line is dropped", holds(dropped))
    }

    @Test
    fun `the rule fires when the device name line moves outside the lazy block`() {
        assertTrue("app: the moved line sits outside the lazy block", !holds(movedOutside()))
    }

    @Test
    fun `the rule stays quiet on a comment inside the block`() {
        val edited = codeOf("        HandoffFinish(\n            serverUrl,",
            "        HandoffFinish(\n            serverUrl, // the address")
        assertTrue("app: the rule must stay quiet on a comment edit", holds(edited))
    }

    @Test
    fun `the rule stays quiet on extra whitespace inside the block`() {
        val edited = codeOf("            credentialVault,\n            sessionVault,",
            "            credentialVault,\n            \n            sessionVault,")
        assertTrue("app: the rule must stay quiet on extra whitespace", holds(edited))
    }

    @Test
    fun `a sample whose target text is missing fails by name`() {
        val thrown = runCatching { edit("val a = 1", "no such text", "x") }.exceptionOrNull()
        assertTrue("app: a missing target must fail by name, got ${thrown?.message}",
            thrown?.message.orEmpty().contains("no such text"))
    }
}
