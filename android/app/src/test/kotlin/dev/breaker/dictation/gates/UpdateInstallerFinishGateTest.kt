package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source pins for the finish callback of the install adapter in wiring/UpdateInstallerAndroid.kt, the status
 * rule in wiring/InstallStatusRule.kt, and the wiring of the callback in wiring/AppUpdater.kt. The adapter names
 * Android types, so no JVM test can run it. Each pin reads the real file and counts one point of the callback
 * path in its code, with comments and string text blanked. Every pin is broken by at least two edited samples
 * and must stay quiet on harmless edits: a comment and a renamed lambda parameter.
 */
internal class UpdateInstallerFinishGateTest {

    private val adapter: String = AppSourceFiles.mainFile("kotlin/dev/breaker/dictation/wiring/UpdateInstallerAndroid.kt")
    private val rule: String = AppSourceFiles.mainFile("kotlin/dev/breaker/dictation/wiring/InstallStatusRule.kt")
    private val updater: String = AppSourceFiles.mainFile("kotlin/dev/breaker/dictation/wiring/AppUpdater.kt")

    /** The text with each pair applied in order; a pair whose old text is missing fails by name. */
    private fun applied(raw: String, edits: List<Pair<String, String>>): String {
        var text: String = raw
        for ((old, new) in edits) {
            check(text.contains(old)) { "app: a finish sample lost its text \"$old\"" }
            text = text.replace(old, new)
        }
        return text
    }

    /** Counts the matches of [pattern] in the code of [raw] with the edits applied, and checks the pin. */
    private fun pin(
        label: String,
        raw: String,
        pattern: String,
        times: Int,
        firing: List<List<Pair<String, String>>>,
        quiet: List<List<Pair<String, String>>>,
    ) {
        val regex = Regex(pattern)
        fun hits(edits: List<Pair<String, String>>): Int =
            regex.findAll(AppSourceFiles.strip(applied(raw, edits)).code).count()
        assertEquals("app: $label does not hold on the real file", times, hits(emptyList()))
        assertTrue("app: $label has fewer than two firing samples", firing.size >= 2)
        for (edits in firing) {
            assertTrue("app: $label still holds after an edit that breaks it", hits(edits) != times)
        }
        for (edits in quiet) {
            assertEquals("app: $label fires on a harmless edit", times, hits(edits))
        }
    }

    @Test
    fun `the adapter takes an optional finish callback with an empty default`() {
        pin(
            "the optional finish callback",
            adapter,
            """private\s+val\s+onSessionFinished\s*:\s*\(\s*Int\s*\)\s*->\s*Unit\s*=\s*\{\s*\}""",
            1,
            firing = listOf(
                listOf("    private val onSessionFinished: (Int) -> Unit = {}," to "    private val onSessionFinished: (Int) -> Unit,"),
                listOf("    private val onSessionFinished: (Int) -> Unit = {}," to "    private val onSessionFinished: (Int) -> Unit = { _ -> Unit },"),
            ),
            quiet = listOf(
                listOf("    private val onSessionFinished: (Int) -> Unit = {}," to "    private val onSessionFinished: (Int) -> Unit = {}, // the status code"),
                listOf("    private val context: Context," to "    private val context: Context, // the app"),
            ),
        )
    }

    @Test
    fun `the finished branch forgets the session and then reports the received status`() {
        pin(
            "the finished branch call",
            adapter,
            """StatusAction\s*\.\s*FINISHED\s*->\s*\{\s*forget\s*\(\s*sessionId\s*\)\s*onSessionFinished\s*\(\s*status\s*\)\s*\}""",
            1,
            firing = listOf(
                listOf("                onSessionFinished(status)" to "                onSessionFinished(0)"),
                listOf("                forget(sessionId)\n                onSessionFinished(status)" to "                onSessionFinished(status)\n                forget(sessionId)"),
            ),
            quiet = listOf(
                listOf("                onSessionFinished(status)" to "                onSessionFinished(status) // the received code"),
                listOf("            StatusAction.FINISHED -> {" to "            StatusAction.FINISHED -> { // done"),
            ),
        )
    }

    @Test
    fun `the abandon path reports a failure status after it forgets the session`() {
        pin(
            "the abandon path callback",
            adapter,
            """finally\s*\{\s*forget\s*\(\s*sessionId\s*\)\s*onSessionFinished\s*\(\s*PackageInstaller\s*\.\s*STATUS_FAILURE\s*\)\s*\}""",
            1,
            firing = listOf(
                listOf("            onSessionFinished(PackageInstaller.STATUS_FAILURE)" to "            onSessionFinished(PackageInstaller.STATUS_SUCCESS)"),
                listOf("            forget(sessionId)\n            onSessionFinished(PackageInstaller.STATUS_FAILURE)" to "            onSessionFinished(PackageInstaller.STATUS_FAILURE)\n            forget(sessionId)"),
            ),
            quiet = listOf(
                listOf("            onSessionFinished(PackageInstaller.STATUS_FAILURE)" to "            onSessionFinished(PackageInstaller.STATUS_FAILURE) // the session is gone"),
                listOf("            forget(sessionId)\n            onSessionFinished(PackageInstaller.STATUS_FAILURE)" to "            forget(sessionId) // first\n            onSessionFinished(PackageInstaller.STATUS_FAILURE)"),
            ),
        )
    }

    @Test
    fun `the finish callback is called at exactly two places`() {
        pin(
            "the count of callback calls",
            adapter,
            """onSessionFinished\s*\(""",
            2,
            firing = listOf(
                listOf("                onSessionFinished(status)" to "                onSessionFinished(status)\n                onSessionFinished(status)"),
                listOf("            onSessionFinished(PackageInstaller.STATUS_FAILURE)" to "            Unit"),
            ),
            quiet = listOf(
                listOf("                onSessionFinished(status)" to "                onSessionFinished(status) // reported"),
                listOf("            onSessionFinished(PackageInstaller.STATUS_FAILURE)" to "            onSessionFinished(PackageInstaller.STATUS_FAILURE) // best effort"),
            ),
        )
    }

    @Test
    fun `the status rule keeps its finished result for every other status`() {
        pin(
            "the rule finished result",
            rule,
            """else\s*->\s*StatusAction\s*\.\s*FINISHED""",
            1,
            firing = listOf(
                listOf("            else -> StatusAction.FINISHED" to "            else -> StatusAction.IGNORE"),
                listOf("            else -> StatusAction.FINISHED" to "            else -> StatusAction.SHOW_CONFIRM"),
            ),
            quiet = listOf(
                listOf("    const val SUCCESS: Int = 0" to "    const val SUCCESS: Int = 0 // success"),
                listOf("object InstallStatusRule {" to "object InstallStatusRule { // the rule"),
            ),
        )
    }

    @Test
    fun `the app updater passes the finish callback to the answer holder`() {
        pin(
            "the updater pass through",
            updater,
            """AndroidApkInstaller\s*\(\s*app\s*,\s*onSessionFinished\s*=\s*\{\s*(\w+)\s*->\s*installAnswers\s*\.\s*reportFinished\s*\(\s*\1\s*\)\s*\}\s*\)""",
            1,
            firing = listOf(
                listOf("{ code -> installAnswers.reportFinished(code) }" to "{ code -> installAnswers.reportFinished(0) }"),
                listOf("AndroidApkInstaller(app, onSessionFinished = { code -> installAnswers.reportFinished(code) })" to "AndroidApkInstaller(app)"),
            ),
            quiet = listOf(
                listOf("{ code -> installAnswers.reportFinished(code) }" to "{ value -> installAnswers.reportFinished(value) }"),
                listOf("    private fun installedInfo(app: BreakerApp): PackageInfo {" to "    // the installed package\n    private fun installedInfo(app: BreakerApp): PackageInfo {"),
            ),
        )
    }
}
