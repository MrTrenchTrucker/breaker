package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the first-run seam of the launcher activity, which no JVM test can run. The rules read the
 * activity source with comments and literal text removed by the shared scanner. Each rule holds on
 * the real text, and each sample edits the text so that the rule must fire.
 */
internal class FirstRunSeamGateTest {

    private val activity: String = "kotlin/dev/breaker/dictation/SettingsLauncherActivity.kt"

    /** A rule with its name; [holds] reads the comment-stripped code of the activity. */
    private class Rule(val name: String, val holds: (String) -> Boolean)

    /** An edit of the real text that must break the rule named [rule]. */
    private class Sample(val rule: String, val old: String, val new: String)

    /** An edit of the real text that must leave every rule holding. */
    private class Quiet(val old: String, val new: String)

    /** The text of the function whose header is [header], from its first brace to the matching brace; empty when absent. */
    private fun bodyOf(code: String, header: String): String {
        val start: Int = code.indexOf(header)
        if (start < 0) return ""
        val open: Int = code.indexOf('{', start)
        var depth = 0
        for (i in open until code.length) {
            if (code[i] == '{') depth += 1
            if (code[i] == '}') {
                depth -= 1
                if (depth == 0) return code.substring(open, i + 1)
            }
        }
        return ""
    }

    private fun count(text: String, pattern: String): Int = Regex(pattern).findAll(text).count()

    /** The word of the switch, written in pieces so that this file does not hold it. */
    private val switchWord: String = "\\barmed" + "Switch\\b"

    private val showHeader: String = "private fun showFirstRun("
    private val createHeader: String = "override fun onCreate("
    private val progressHeader: String = "override fun progress(sentence: String, doneMb: Int, totalMb: Int)"
    private val doneHeader: String = "override fun done()"
    private val failedHeader: String = "override fun failed(sentence: String)"

    private val rules: List<Rule> = listOf(
        Rule("THE_DECISION_IS_IN_ONCREATE_BEFORE_THE_NORMAL_CONTENT") { code ->
            val create: String = bodyOf(code, createHeader)
            val decideAt: Int = create.indexOf("FirstRunGate.decide(")
            val normalAt: Int = create.indexOf("createSettingsView(")
            decideAt >= 0 && normalAt > decideAt
        },
        Rule("THE_THREE_BRANCHES_ARE_ALL_THERE") { code ->
            count(code, """FirstRunGate\s*\.\s*Decision\s*\.\s*(FIRST_RUN|MARK_DONE_QUIETLY|NORMAL)\s*->""") == 3
        },
        Rule("THE_FIRST_RUN_BRANCH_RETURNS") { code ->
            count(code, """FirstRunGate\s*\.\s*Decision\s*\.\s*FIRST_RUN\s*->\s*\{[^}]*\breturn\b[^}]*\}""") == 1
        },
        Rule("THE_MARKED_DONE_BRANCH_WRITES_THE_MARKER") { code ->
            count(code, """MARK_DONE_QUIETLY\s*->\s*firstRunDone\s*\.\s*markDone\s*\(\s*\)""") == 1
        },
        Rule("SHOW_FIRST_RUN_IS_CALLED_WITH_THE_MARKER") { code ->
            count(code, """\bshowFirstRun\s*\(\s*app\s*,\s*modelReady\s*,\s*firstRunDone\s*\)""") == 1
        },
        Rule("ONFINISHED_MARKS_DONE_THEN_RECREATES") { code ->
            count(bodyOf(code, showHeader), """onFinished\s*=\s*\{\s*done\s*\.\s*markDone\s*\(\s*\)\s*;?\s*recreate\s*\(\s*\)\s*\}""") == 1
        },
        Rule("CREATE_FIRST_RUN_VIEW_IS_CALLED_ONCE_INSIDE_SHOW") { code ->
            count(code, """\bcreateFirstRunView\s*\(""") == 1 &&
                count(bodyOf(code, showHeader), """\bcreateFirstRunView\s*\(""") == 1
        },
        Rule("PROVIDERS_ARE_PASSED_BY_REFERENCE_IN_SHOW") { code ->
            val body: String = bodyOf(code, showHeader)
            count(body, """modelReady\s*=\s*modelReady::isReady\s*,""") == 1 &&
                count(body, """downloadLine\s*=\s*this\s*::\s*pendingDownloadLine\s*,""") == 1 &&
                count(body, """onDownloadModel\s*=\s*app\s*\.\s*tileHost::requestDownload\s*,""") == 1 &&
                count(body, """modelDownloadMb\s*=\s*\{\s*selectedModelMb\s*\(\s*app\s*\)\s*\}""") == 1 &&
                count(code, """private\s+fun\s+pendingDownloadLine\(\)\s*:\s*String\?\s*=\s*downloadSentence""") == 1
        },
        Rule("PROGRESS_KEEPS_THE_SENTENCE") { code ->
            count(bodyOf(code, progressHeader), """\bdownloadSentence\s*=\s*sentence\b""") == 1
        },
        Rule("PROGRESS_FORWARDS_THE_NUMBERS_UNCHANGED") { code ->
            count(bodyOf(code, progressHeader), """\bdownloadProgress\s*\(\s*doneMb\s*,\s*totalMb\s*\)""") == 1
        },
        Rule("PROGRESS_TESTS_THE_CHECKING_CONSTANT") { code ->
            val body: String = bodyOf(code, progressHeader)
            count(body, """sentence\s*==\s*ModelSentences\s*\.\s*CHECKING\b""") == 1 &&
                count(body, """\bdownloadChecking\s*\(\s*\)""") == 1
        },
        // Each of done() and failed() stops the download exactly once, and onResume refreshes the screen.
        Rule("DONE_STOPS_THE_DOWNLOAD_ONCE") { code ->
            val doneBody: String = bodyOf(code, doneHeader)
            count(doneBody, """downloadStopped\s*\(\s*\)""") == 1 &&
                count(bodyOf(code, "override fun onResume()"), """refresh\s*\(\s*\)""") >= 1
        },
        Rule("FAILED_STOPS_THE_DOWNLOAD_ONCE") { code ->
            val failedBody: String = bodyOf(code, failedHeader)
            count(failedBody, """downloadStopped\s*\(\s*\)""") == 1 &&
                count(bodyOf(code, "override fun onResume()"), """refresh\s*\(\s*\)""") >= 1
        },
        // The whole activity has exactly two stops: the one in done() and the one in failed().
        Rule("THE_DOWNLOAD_IS_STOPPED_ONLY_BY_DONE_AND_FAILED") { code ->
            count(code, """\bdownloadStopped\s*\(\s*\)""") == 2
        },
        Rule("THE_ONBOARDING_VIEW_IS_RETIRED") { code ->
            count(code, """\bcreateOnboardingView\s*\(""") == 0
        },
        // Four switch uses: the Home take wiring, Home's adapter and the two calls in onStart.
        // The first-run block adds none of its own.
        Rule("THE_SWITCH_WORD_COUNT_IS_UNCHANGED") { code ->
            count(code, switchWord) == 4
        },
    )

    private val firing: List<Sample> = listOf(
        Sample("THE_DECISION_IS_IN_ONCREATE_BEFORE_THE_NORMAL_CONTENT", "FirstRunGate.decide(", "FirstRunGate.judge("),
        Sample("THE_DECISION_IS_IN_ONCREATE_BEFORE_THE_NORMAL_CONTENT", "dev.breaker.dictation.ui.createSettingsView(", "dev.breaker.dictation.ui.createSettingsScreen("),
        Sample("THE_THREE_BRANCHES_ARE_ALL_THERE", "FirstRunGate.Decision.NORMAL -> Unit", "FirstRunGate.Decision.NORMAL = Unit"),
        Sample("THE_FIRST_RUN_BRANCH_RETURNS", "showFirstRun(app, modelReady, firstRunDone); return }", "showFirstRun(app, modelReady, firstRunDone) }"),
        Sample("THE_MARKED_DONE_BRANCH_WRITES_THE_MARKER", "MARK_DONE_QUIETLY -> firstRunDone.markDone()", "MARK_DONE_QUIETLY -> Unit"),
        Sample("SHOW_FIRST_RUN_IS_CALLED_WITH_THE_MARKER", "showFirstRun(app, modelReady, firstRunDone); return }", "showFirstRunPage(app, modelReady, firstRunDone); return }"),
        Sample("ONFINISHED_MARKS_DONE_THEN_RECREATES", "onFinished = { done.markDone(); recreate() }", "onFinished = { recreate(); done.markDone() }"),
        Sample("ONFINISHED_MARKS_DONE_THEN_RECREATES", "onFinished = { done.markDone(); recreate() }", "onFinished = { done.markDone() }"),
        Sample("CREATE_FIRST_RUN_VIEW_IS_CALLED_ONCE_INSIDE_SHOW", "        super.onCreate(savedInstanceState)", "        super.onCreate(savedInstanceState)\n        dev.breaker.dictation.ui.createFirstRunView(this)"),
        Sample("CREATE_FIRST_RUN_VIEW_IS_CALLED_ONCE_INSIDE_SHOW", "    override fun onStart() {", "    override fun onStart() {\n        dev.breaker.dictation.ui.createFirstRunView(this)"),
        Sample("PROVIDERS_ARE_PASSED_BY_REFERENCE_IN_SHOW", "downloadLine = this::pendingDownloadLine,", "downloadLine = { downloadSentence },"),
        Sample("PROVIDERS_ARE_PASSED_BY_REFERENCE_IN_SHOW", "onDownloadModel = app.tileHost::requestDownload,", "onDownloadModel = { app.tileHost.requestDownload() },"),
        Sample("PROGRESS_KEEPS_THE_SENTENCE", "downloadSentence = sentence\n        val control", "val control"),
        Sample("PROGRESS_KEEPS_THE_SENTENCE", "downloadSentence = sentence\n        val control", "downloadSentence = null\n        val control"),
        Sample("PROGRESS_FORWARDS_THE_NUMBERS_UNCHANGED", "control?.downloadProgress(doneMb, totalMb)", "control?.downloadProgress(doneMb, 0)"),
        Sample("PROGRESS_FORWARDS_THE_NUMBERS_UNCHANGED", "control?.downloadProgress(doneMb, totalMb)", "control?.downloadProgress(totalMb, doneMb)"),
        Sample("PROGRESS_TESTS_THE_CHECKING_CONSTANT", "sentence == ModelSentences.CHECKING", "sentence == \"Checking the speech model.\""),
        Sample("PROGRESS_TESTS_THE_CHECKING_CONSTANT", "sentence == ModelSentences.CHECKING", "sentence.isEmpty()"),
        Sample("DONE_STOPS_THE_DOWNLOAD_ONCE", "downloadSentence = null\n        downloadControl()?.downloadStopped()", "downloadSentence = null\n        downloadControl()?.refresh()"),
        Sample("DONE_STOPS_THE_DOWNLOAD_ONCE", "override fun done() {", "override fun doneLater() {"),
        Sample("FAILED_STOPS_THE_DOWNLOAD_ONCE", "override fun failed(sentence: String) {", "override fun failedLater(sentence: String) {"),
        Sample("FAILED_STOPS_THE_DOWNLOAD_ONCE", "downloadSentence = sentence\n        downloadControl()?.downloadStopped()", "downloadSentence = sentence\n        downloadControl()?.refresh()"),
        Sample("THE_DOWNLOAD_IS_STOPPED_ONLY_BY_DONE_AND_FAILED", "override fun done() {", "override fun done() {\n        downloadControl()?.downloadStopped()"),
        Sample("THE_DOWNLOAD_IS_STOPPED_ONLY_BY_DONE_AND_FAILED", "downloadSentence = null\n        downloadControl()?.downloadStopped()", "downloadSentence = null\n        downloadControl()?.refresh()"),
        Sample("THE_SWITCH_WORD_COUNT_IS_UNCHANGED", "private var pendingRefusal: String? = null", "private var pendingRefusal: String? = null\n    private var ${"armed" + "Switch"}: Int = 0"),
        Sample("THE_ONBOARDING_VIEW_IS_RETIRED", "override fun onStart() {", "override fun onStart() {\n        createOnboardingView(this)"),
        Sample("THE_ONBOARDING_VIEW_IS_RETIRED", "private fun selectedModelMb(app: BreakerApp): Int =", "private fun selectedModelMb(app: BreakerApp): Int =\n        createOnboardingView(app)"),
    )

    private val quiet: List<Quiet> = listOf(
        Quiet("val firstRunDone = FirstRunDoneStore(", "val firstRunDone = /* the marker */ FirstRunDoneStore("),
        Quiet("override fun progress(sentence: String, doneMb: Int, totalMb: Int) {", "override fun progress(sentence: String, doneMb: Int, totalMb: Int) { // the line"),
        Quiet("onFinished = { done.markDone(); recreate() }", "onFinished = { done.markDone();   recreate() }"),
        Quiet("private var pendingRefusal: String? = null", "private var pendingRefusal: String? = null // the ${"armed" + "Switch"} word"),
    )

    /** [old] replaced by [new] at its first place; fails by name when it is not there. */
    private fun edit(text: String, old: String, new: String): String {
        val at: Int = text.indexOf(old)
        check(at >= 0) { "app: a first-run seam sample lost its text '$old'" }
        return text.substring(0, at) + new + text.substring(at + old.length)
    }

    private fun codeOf(old: String? = null, new: String = ""): String {
        val text: String = AppSourceFiles.mainFile(activity)
        return AppSourceFiles.strip(if (old == null) text else edit(text, old, new)).code
    }

    @Test
    fun `every first-run rule holds on the real activity file`() {
        assertTrue("app: the activity was read as empty", codeOf().isNotBlank())
        for (rule in rules) {
            assertTrue("app: the activity breaks first-run rule ${rule.name}", rule.holds(codeOf()))
        }
    }

    @Test
    fun `the samples name only known rules and cover each rule`() {
        val names: List<String> = rules.map { it.name }
        assertEquals("app: a first-run rule name is used twice", names.toSet().size, names.size)
        assertEquals("app: a sample belongs to an unknown rule or a rule has none", names.toSet(), firing.map { it.rule }.toSet())
    }

    @Test
    fun `every first-run rule fires on each of its edited samples`() {
        val byName: Map<String, Rule> = rules.associateBy { it.name }
        for (sample in firing) {
            assertFalse(
                "app: first-run rule ${sample.rule} must fire on the edit '${sample.old}' => '${sample.new}'",
                byName.getValue(sample.rule).holds(codeOf(sample.old, sample.new)),
            )
        }
    }

    @Test
    fun `no first-run rule fires on a harmless edit`() {
        for (sample in quiet) {
            for (rule in rules) {
                assertTrue(
                    "app: first-run rule ${rule.name} must stay quiet on the harmless edit '${sample.old}' => '${sample.new}'",
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
}
