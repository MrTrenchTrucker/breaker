package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the return activity of the phone sign-in, because no JVM test can run an activity.
 * It takes the return link, starts the launcher to the front and finishes on every outcome,
 * and it keeps no task of its own. Each rule reads the real file with comments and literal
 * text removed by the shared scanner; each rule holds on the real file, is broken by at least
 * two edited samples, and stays quiet on harmless edits.
 */
internal class HandoffReturnActivityGateTest {

    private val file: String = "kotlin/dev/breaker/dictation/host/HandoffReturnActivity.kt"

    private class Rule(val name: String, val pattern: String, val times: Int) {
        fun holds(code: String): Boolean = Regex(pattern).findAll(code).count() == times
    }

    private class Sample(val rule: String, val old: String, val new: String)

    private class Quiet(val old: String, val new: String)

    private val rules: List<Rule> = listOf(
        Rule("REORDER_TO_FRONT_IS_THE_ONLY_FLAG", """\bFLAG_ACTIVITY_REORDER_TO_FRONT\b""", 1),
        Rule("NO_NEW_TASK_FLAG", """\bFLAG_ACTIVITY_NEW_TASK\b""", 0),
        Rule("NO_CLEAR_TASK_FLAG", """\bFLAG_ACTIVITY_CLEAR_TASK\b""", 0),
        Rule("NO_PUT_EXTRA", """\bputExtra\s*\(""", 0),
        Rule("NO_SET_DATA", """\bsetData\w*\s*\(""", 0),
        Rule("NO_LOG_CALL", """\bLog\s*\.""", 0),
        Rule("NO_TASK_AFFINITY", """\btaskAffinity\b""", 0),
        Rule("NO_ON_RESUME", """\bonResume\b""", 0),
        Rule("NO_ON_NEW_INTENT", """\bonNewIntent\b""", 0),
        Rule("ON_CREATE_IS_THE_ONLY_OVERRIDE", """\boverride\s+fun\s+(?!onCreate\b)\w+""", 0),
        Rule("ON_CREATE_IS_OVERRIDDEN_ONCE", """\boverride\s+fun\s+onCreate\s*\(""", 1),
        Rule("FINISH_IS_IN_THE_FINALLY_PATH", """\bfinally\s*\{[^}]*\bfinish\s*\(\s*\)[^}]*\}""", 1),
        Rule("ONE_START_OF_THE_LAUNCHER", """\bstartActivity\s*\(""", 1),
        Rule("HANDLE_GETS_ACTION_AND_DATA_UNCHANGED", """\bhandle\s*\(\s*intent\s*\?\.\s*action\s*,\s*intent\s*\?\.\s*dataString\s*\)""", 1),
    )

    private val firing: List<Sample> = listOf(
        Sample("REORDER_TO_FRONT_IS_THE_ONLY_FLAG", "FLAG_ACTIVITY_REORDER_TO_FRONT", "FLAG_ACTIVITY_SINGLE_TOP"),
        Sample("REORDER_TO_FRONT_IS_THE_ONLY_FLAG", "addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)", "addFlags(0)"),
        Sample("NO_NEW_TASK_FLAG", "addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)", "addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)"),
        Sample("NO_NEW_TASK_FLAG", "super.onCreate(savedInstanceState)", "super.onCreate(savedInstanceState)\n        val spare = Intent.FLAG_ACTIVITY_NEW_TASK"),
        Sample("NO_CLEAR_TASK_FLAG", "addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)", "addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_CLEAR_TASK)"),
        Sample("NO_CLEAR_TASK_FLAG", "super.onCreate(savedInstanceState)", "super.onCreate(savedInstanceState)\n        val spare = Intent.FLAG_ACTIVITY_CLEAR_TASK"),
        Sample("NO_PUT_EXTRA", "super.onCreate(savedInstanceState)", "super.onCreate(savedInstanceState)\n        intent.putExtra(\"n\", 1)"),
        Sample("NO_PUT_EXTRA", "super.onCreate(savedInstanceState)", "super.onCreate(savedInstanceState)\n        val copy = Intent().putExtra(\"n\", 1)"),
        Sample("NO_SET_DATA", "super.onCreate(savedInstanceState)", "super.onCreate(savedInstanceState)\n        intent.setData(null)"),
        Sample("NO_SET_DATA", "super.onCreate(savedInstanceState)", "super.onCreate(savedInstanceState)\n        val spare = Intent().setDataAndType(null, null)"),
        Sample("NO_LOG_CALL", "super.onCreate(savedInstanceState)", "super.onCreate(savedInstanceState)\n        android.util.Log.w(\"x\", \"y\")"),
        Sample("NO_LOG_CALL", "super.onCreate(savedInstanceState)", "super.onCreate(savedInstanceState)\n        android.util.Log.d(\"x\", \"y\")"),
        Sample("NO_TASK_AFFINITY", "super.onCreate(savedInstanceState)", "super.onCreate(savedInstanceState)\n        val taskAffinity = 1"),
        Sample("NO_TASK_AFFINITY", "            finish()", "            finish()\n            taskAffinity = null"),
        Sample("NO_ON_RESUME", "super.onCreate(savedInstanceState)", "super.onCreate(savedInstanceState)\n        onResume()"),
        Sample("NO_ON_RESUME", "    override fun onCreate(savedInstanceState: Bundle?) {", "    override fun onResume() {}\n\n    override fun onCreate(savedInstanceState: Bundle?) {"),
        Sample("NO_ON_NEW_INTENT", "super.onCreate(savedInstanceState)", "super.onCreate(savedInstanceState)\n        onNewIntent(intent)"),
        Sample("NO_ON_NEW_INTENT", "    override fun onCreate(savedInstanceState: Bundle?) {", "    override fun onNewIntent(i: Intent?) {}\n\n    override fun onCreate(savedInstanceState: Bundle?) {"),
        Sample("ON_CREATE_IS_THE_ONLY_OVERRIDE", "    override fun onCreate(savedInstanceState: Bundle?) {", "    override fun onStart() {}\n\n    override fun onCreate(savedInstanceState: Bundle?) {"),
        Sample("ON_CREATE_IS_THE_ONLY_OVERRIDE", "    override fun onCreate(savedInstanceState: Bundle?) {", "    override fun onDestroy() {}\n\n    override fun onCreate(savedInstanceState: Bundle?) {"),
        Sample("ON_CREATE_IS_OVERRIDDEN_ONCE", "    override fun onCreate(savedInstanceState: Bundle?) {", "    override fun onCreate(savedInstanceState: Bundle?) {}\n\n    override fun onCreate(savedInstanceState: Bundle?) {"),
        Sample("ON_CREATE_IS_OVERRIDDEN_ONCE", "    override fun onCreate(savedInstanceState: Bundle?) {", "    fun onCreate(savedInstanceState: Bundle?) {"),
        Sample("FINISH_IS_IN_THE_FINALLY_PATH", "            finish()\n        }", "        }\n        finish()"),
        Sample("FINISH_IS_IN_THE_FINALLY_PATH", "            finish()\n", ""),
        Sample("ONE_START_OF_THE_LAUNCHER", "            startActivity(", "            startActivity(Intent())\n            startActivity("),
        Sample("ONE_START_OF_THE_LAUNCHER", "super.onCreate(savedInstanceState)", "super.onCreate(savedInstanceState)\n        startActivity(Intent())"),
        Sample("HANDLE_GETS_ACTION_AND_DATA_UNCHANGED", "handle(intent?.action, intent?.dataString)", "handle(intent?.action, null)"),
        Sample("HANDLE_GETS_ACTION_AND_DATA_UNCHANGED", "handle(intent?.action, intent?.dataString)", "handle(intent?.dataString, intent?.action)"),
    )

    private val quiet: List<Quiet> = listOf(
        Quiet("super.onCreate(savedInstanceState)", "super.onCreate(savedInstanceState) // no Log. putExtra( or onResume here"),
        Quiet("FLAG_ACTIVITY_REORDER_TO_FRONT", "FLAG_ACTIVITY_REORDER_TO_FRONT /* taskAffinity and setData( */"),
        Quiet("    override fun onCreate(savedInstanceState: Bundle?) {", "    // override fun onStart() would be a second override\n    override fun onCreate(savedInstanceState: Bundle?) {"),
    )

    private fun edit(text: String, old: String, new: String): String {
        val at = text.indexOf(old)
        check(at >= 0) { "app: a return activity sample lost its text '$old'" }
        return text.substring(0, at) + new + text.substring(at + old.length)
    }

    private fun raw(): String = AppSourceFiles.mainFile(file)

    private fun codeOf(old: String? = null, new: String = ""): String =
        AppSourceFiles.strip(if (old == null) raw() else edit(raw(), old, new)).code

    @Test
    fun `every return activity rule holds on the real file`() {
        assertTrue("app: the return activity file was read as empty", codeOf().isNotBlank())
        val broken: List<String> = rules.filter { !it.holds(codeOf()) }.map { it.name }
        assertTrue("app: the return activity breaks rules $broken", broken.isEmpty())
    }

    @Test
    fun `the firing samples cover each return activity rule at least twice`() {
        val names: List<String> = rules.map { it.name }
        assertEquals("app: a return activity rule name is used twice", names.toSet().size, names.size)
        assertEquals("app: a firing sample belongs to an unknown rule", names.toSet(), firing.map { it.rule }.toSet())
        for (rule in rules) {
            val count: Int = firing.count { it.rule == rule.name }
            assertTrue("app: return activity rule ${rule.name} needs at least two firing samples, has $count", count >= 2)
        }
    }

    @Test
    fun `every return activity rule fires on each of its edited samples`() {
        val byName: Map<String, Rule> = rules.associateBy { it.name }
        for (sample in firing) {
            val rule: Rule = byName.getValue(sample.rule)
            assertFalse(
                "app: return activity rule ${sample.rule} must fire on the edit '${sample.old}' => '${sample.new}'",
                rule.holds(codeOf(sample.old, sample.new)),
            )
        }
    }

    @Test
    fun `a harmless edit leaves every return activity rule true`() {
        for (sample in quiet) {
            for (rule in rules) {
                assertTrue(
                    "app: return activity rule ${rule.name} must stay quiet on the harmless edit '${sample.old}' => '${sample.new}'",
                    rule.holds(codeOf(sample.old, sample.new)),
                )
            }
        }
    }

    @Test
    fun `a sample whose target text is missing fails by name`() {
        val thrown = assertThrows("app: a missing target must fail", IllegalStateException::class.java) { edit("val a = 1", "no such text", "x") }
        assertTrue("app: the failure must name the lost text, got ${thrown.message}", thrown.message!!.contains("no such text"))
    }

    private val launcher: String = "kotlin/dev/breaker/dictation/SettingsLauncherActivity.kt"
    private val root: String = "kotlin/dev/breaker/dictation/BreakerCompositionRoot.kt"
    private val browser: String = "kotlin/dev/breaker/dictation/host/SignInBrowser.kt"

    private class FileRule(val name: String, val path: String, val pattern: String) {
        fun holds(code: String): Boolean = Regex(pattern).findAll(code).count() == 1
    }

    private class FileSample(val rule: String, val old: String, val new: String)

    private val fileRules: List<FileRule> = listOf(
        FileRule("LAUNCHER_RESUME_TAKES_THE_RETURN_STATE", launcher, """\bsignInStart\.screen\.onResume\(\s*app\.compositionRoot\.signInReturnStore\.take\(\)\s*\)"""),
        FileRule("SERVER_LAMBDA_READS_THE_STORED_ADDRESS", browser, """createSignInStartView\(activity, app\.settingsStore, \{\s*app\.settingsStore\.load\(\)\.serverUrl\s*\}"""),
        FileRule("ROOT_BUILDS_THE_STARTER_WITH_NO_ARGUMENT", root, """SignInHolder\(\s*SignInStarterImpl\(\s*\)\s*\)"""),
    )

    private val fileFiring: List<FileSample> = listOf(
        FileSample("LAUNCHER_RESUME_TAKES_THE_RETURN_STATE", "signInStart.screen.onResume(app.compositionRoot.signInReturnStore.take())", "signInStart.screen.onResume(null)"),
        FileSample("LAUNCHER_RESUME_TAKES_THE_RETURN_STATE", "signInStart.screen.onResume(app.compositionRoot.signInReturnStore.take())", "signInStart.screen.onResume(app.compositionRoot.signInReturnStore.peek())"),
        FileSample("SERVER_LAMBDA_READS_THE_STORED_ADDRESS", "{ app.settingsStore.load().serverUrl }, { screen", "{ \"\" }, { screen"),
        FileSample("SERVER_LAMBDA_READS_THE_STORED_ADDRESS", "{ app.settingsStore.load().serverUrl }, { screen", "{ app.settingsStore.load().serverUrl.trim() }, { screen"),
        FileSample("ROOT_BUILDS_THE_STARTER_WITH_NO_ARGUMENT", "SignInHolder(SignInStarterImpl())", "SignInHolder(SignInStarterImpl(entropy = { ByteArray(32) }))"),
        FileSample("ROOT_BUILDS_THE_STARTER_WITH_NO_ARGUMENT", "SignInHolder(SignInStarterImpl())", "SignInHolder(SignInStarterImpl(null))"),
    )

    private fun fileCode(path: String, old: String? = null, new: String = ""): String {
        val text: String = AppSourceFiles.mainFile(path)
        return AppSourceFiles.strip(if (old == null) text else edit(text, old, new)).code
    }

    @Test
    fun `the launcher and the root hold the three sign-in killers on the real files`() {
        val broken: List<String> = fileRules.filter { !it.holds(fileCode(it.path)) }.map { it.name }
        assertTrue("app: the sign-in killers break on the real files: $broken", broken.isEmpty())
    }

    @Test
    fun `each sign-in killer has at least two firing samples and each one fires`() {
        for (rule in fileRules) {
            val count: Int = fileFiring.count { it.rule == rule.name }
            assertTrue("app: sign-in killer ${rule.name} needs at least two firing samples, has $count", count >= 2)
        }
        for (sample in fileFiring) {
            val rule: FileRule = fileRules.single { it.name == sample.rule }
            assertFalse(
                "app: sign-in killer ${rule.name} must fire on '${sample.old}' => '${sample.new}'",
                rule.holds(fileCode(rule.path, sample.old, sample.new)),
            )
        }
    }

    @Test
    fun `a sign-in killer stays quiet when only a comment is added`() {
        assertTrue("app: the launcher killers stay quiet on a comment", fileRules.filter { it.path == launcher }.all {
            it.holds(fileCode(it.path, "        if (this::signInStart.isInitialized)", "        // signInStart.screen.onResume(app.compositionRoot.signInReturnStore.take())\n        if (this::signInStart.isInitialized)"))
        })
        assertTrue("app: the root killer stays quiet on a comment", fileRules.single { it.path == root }.holds(fileCode(root, "    val signInHolder", "    // SignInHolder(SignInStarterImpl())\n    val signInHolder")))
    }
}
