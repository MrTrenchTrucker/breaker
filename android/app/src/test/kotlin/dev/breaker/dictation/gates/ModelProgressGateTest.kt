package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the progress path of the speech model download in four files: the per-download token and the
 * progress posts in `wiring/ModelSupport.kt`, the forwarding of progress to the notification and the
 * in-app listener in `wiring/ForwardingDownloadNotice.kt`, the progress notice in `service/ModelNotifications.kt`
 * and the launcher's progress line in `SettingsLauncherActivity.kt`. Each rule reads the comment-stripped
 * text of its file, holds on the real file, is broken by edited samples, and stays quiet on harmless edits.
 */
internal class ModelProgressGateTest {

    private val dir: String = "kotlin/dev/breaker/dictation/"
    private val files: Map<String, String> = mapOf(
        "model" to dir + "wiring/ModelSupport.kt",
        "forward" to dir + "wiring/ForwardingDownloadNotice.kt",
        "notif" to dir + "service/ModelNotifications.kt",
        "activity" to dir + "SettingsLauncherActivity.kt",
    )

    private class Rule(val name: String, val file: String, val pattern: Regex, val times: Int = 1) {
        fun holds(code: String): Boolean = pattern.findAll(code).count() == times
    }
    private class Sample(val rule: String, val file: String, val old: String, val new: String)
    private class Quiet(val file: String, val old: String, val new: String)

    private val rules: List<Rule> = listOf(
        Rule(
            "TOKEN_IS_CLEARED_FIRST_WHEN_THE_DOWNLOAD_ENDS", "model",
            Regex("""\bprivate\s+fun\s+finish\s*\(.*\)\s*\{\s*active\s*\.\s*set\s*\(\s*null\s*\)\s*running\s*\.\s*set\s*\(\s*false\s*\)"""),
        ),
        Rule(
            "PROGRESS_IS_DELIVERED_ONLY_FOR_THE_ACTIVE_DOWNLOAD", "model",
            Regex("""\bmain\s*\.\s*post\s*\{\s*if\s*\(\s*active\s*\.\s*get\s*\(\s*\)\s*===\s*token\s*\)\s*deliverProgress\s*\(\s*received\s*,\s*total\s*\)\s*\}"""),
        ),
        Rule(
            "CHECKING_NEEDS_A_KNOWN_TOTAL_THAT_IS_REACHED", "model",
            Regex("""\btotal\s*>\s*0\s*&&\s*received\s*>=\s*total\s*->\s*notice\s*\.\s*progress\s*\(\s*ModelSentences\s*\.\s*CHECKING\s*,\s*0\s*,\s*0\s*\)"""),
        ),
        Rule(
            "LAUNCHER_PROGRESS_SHOWS_THE_SENTENCE_LIKE_THE_FIRST_ONE", "activity",
            Regex("""\boverride\s+fun\s+progress\s*\(\s*sentence\s*:\s*String\s*,\s*doneMb\s*:\s*Int\s*,\s*totalMb\s*:\s*Int\s*\)\s*\{\s*downloadSentence\s*=\s*sentence\s*val\s+control\s*=\s*downloadControlOf\s*\(\s*firstRunShown\s*,\s*firstRunView\s*,\s*settingsView\s*\)\s*if\s*\(\s*sentence\s*==\s*ModelSentences\s*\.\s*CHECKING\s*\)\s*\{\s*control\s*\?\s*\.\s*downloadChecking\s*\(\s*\)\s*\}\s*else\s*\{\s*control\s*\?\s*\.\s*downloadProgress\s*\(\s*doneMb\s*,\s*totalMb\s*\)"""),
        ),
        Rule(
            "PROGRESS_FORWARDS_THE_NUMBERS_TO_THE_MODEL_STEP", "activity",
            Regex("""\bif\s*\(\s*sentence\s*==\s*ModelSentences\s*\.\s*CHECKING\s*\)\s*\{\s*control\s*\?\s*\.\s*downloadChecking\s*\(\s*\)\s*\}\s*else\s*\{\s*control\s*\?\s*\.\s*downloadProgress\s*\(\s*doneMb\s*,\s*totalMb\s*\)"""),
        ),
        Rule(
            "FORWARDING_PROGRESS_REACHES_THE_NOTIFICATION_THEN_THE_LISTENER", "forward",
            Regex("""\boverride\s+fun\s+progress\s*\(\s*sentence\s*:\s*String\s*,\s*doneMb\s*:\s*Int\s*,\s*totalMb\s*:\s*Int\s*\)\s*\{\s*notifications\s*\.\s*progress\s*\(\s*sentence\s*,\s*doneMb\s*,\s*totalMb\s*\)\s*listener\s*\?\.\s*progress\s*\(\s*sentence\s*,\s*doneMb\s*,\s*totalMb\s*\)\s*\}"""),
        ),
        Rule(
            "PROGRESS_NOTICE_PASSES_THE_NUMBERS_TO_THE_BAR", "notif",
            Regex("""\boverride\s+fun\s+progress\s*\(\s*sentence\s*:\s*String\s*,\s*doneMb\s*:\s*Int\s*,\s*totalMb\s*:\s*Int\s*\)\s*\{\s*show\s*\([^()]*,\s*true\s*,\s*doneMb\s*,\s*totalMb\s*\)\s*\}"""),
        ),
    )

    private val firing: List<Sample> = listOf(
        Sample("TOKEN_IS_CLEARED_FIRST_WHEN_THE_DOWNLOAD_ENDS", "model",
            "private fun finish(outcome: DownloadOutcome, onFinished: (DownloadOutcome) -> Unit) {\n        active.set(null)\n",
            "private fun finish(outcome: DownloadOutcome, onFinished: (DownloadOutcome) -> Unit) {\n"),
        Sample("TOKEN_IS_CLEARED_FIRST_WHEN_THE_DOWNLOAD_ENDS", "model",
            "private fun finish(outcome: DownloadOutcome, onFinished: (DownloadOutcome) -> Unit) {\n        active.set(null)\n        running.set(false)",
            "private fun finish(outcome: DownloadOutcome, onFinished: (DownloadOutcome) -> Unit) {\n        running.set(false)\n        active.set(null)"),
        Sample("PROGRESS_IS_DELIVERED_ONLY_FOR_THE_ACTIVE_DOWNLOAD", "model",
            "active.get() === token", "active.get() !== token"),
        Sample("PROGRESS_IS_DELIVERED_ONLY_FOR_THE_ACTIVE_DOWNLOAD", "model",
            "if (active.get() === token) deliverProgress(received, total)", "deliverProgress(received, total)"),
        Sample("CHECKING_NEEDS_A_KNOWN_TOTAL_THAT_IS_REACHED", "model",
            "total > 0 && received >= total", "received >= total"),
        Sample("CHECKING_NEEDS_A_KNOWN_TOTAL_THAT_IS_REACHED", "model",
            "received >= total ->", "received > total ->"),
        Sample("CHECKING_NEEDS_A_KNOWN_TOTAL_THAT_IS_REACHED", "model",
            "notice.progress(ModelSentences.CHECKING, 0, 0)", "notice.progress(ModelSentences.progressFor(0, 0), 0, 0)"),
        Sample("LAUNCHER_PROGRESS_SHOWS_THE_SENTENCE_LIKE_THE_FIRST_ONE", "activity",
            "override fun progress(sentence: String, doneMb: Int, totalMb: Int) {",
            "override fun progressLater(sentence: String, doneMb: Int, totalMb: Int) {"),
        Sample("LAUNCHER_PROGRESS_SHOWS_THE_SENTENCE_LIKE_THE_FIRST_ONE", "activity",
            "control?.downloadChecking()",
            "settingsView.showDownloadStopped()"),
        Sample("LAUNCHER_PROGRESS_SHOWS_THE_SENTENCE_LIKE_THE_FIRST_ONE", "activity",
            "control?.downloadProgress(doneMb, totalMb)",
            "control?.downloadProgress(totalMb, doneMb)"),
        Sample("FORWARDING_PROGRESS_REACHES_THE_NOTIFICATION_THEN_THE_LISTENER", "forward",
            "\n        listener?.progress(sentence, doneMb, totalMb)", ""),
        Sample("FORWARDING_PROGRESS_REACHES_THE_NOTIFICATION_THEN_THE_LISTENER", "forward",
            "notifications.progress(sentence, doneMb, totalMb)\n        listener?.progress(sentence, doneMb, totalMb)",
            "listener?.progress(sentence, doneMb, totalMb)\n        notifications.progress(sentence, doneMb, totalMb)"),
        Sample("PROGRESS_FORWARDS_THE_NUMBERS_TO_THE_MODEL_STEP", "activity",
            "control?.downloadChecking()",
            "settingsView.showDownloadStopped()"),
        Sample("PROGRESS_FORWARDS_THE_NUMBERS_TO_THE_MODEL_STEP", "activity",
            "control?.downloadProgress(doneMb, totalMb)", "control?.downloadProgress(totalMb, doneMb)"),
        Sample("PROGRESS_NOTICE_PASSES_THE_NUMBERS_TO_THE_BAR", "notif",
            "null, true, doneMb, totalMb)", "null, true, 0, 0)"),
        Sample("PROGRESS_NOTICE_PASSES_THE_NUMBERS_TO_THE_BAR", "notif",
            "null, true, doneMb, totalMb)", "null, false, doneMb, totalMb)"),
    )

    private val quiet: List<Quiet> = listOf(
        Quiet("model", "private val active = AtomicReference<Any?>(null)", "private val active = AtomicReference<Any?>(null) // the one token"),
        Quiet("model", "if (active.get() === token)", "if ( active.get()  ===  token )"),
        Quiet("model", "total > 0 && received >= total", "total > 0  &&  received >= total"),
        Quiet("activity", "override fun progress(sentence: String, doneMb: Int, totalMb: Int) {", "override fun progress(sentence: String, doneMb: Int, totalMb: Int) { // the line"),
        Quiet("forward", "listener?.progress(sentence, doneMb, totalMb)", "listener?.progress( sentence, doneMb, totalMb )"),
        Quiet("notif", "null, true, doneMb, totalMb)", "null, true, doneMb, totalMb) // the bar"),
        Quiet("activity", "if (sentence == ModelSentences.CHECKING)", "if ( sentence == ModelSentences.CHECKING )"),
        Quiet("activity", "control?.downloadChecking()", "control?.downloadChecking() // the check"),
    )

    private fun edit(text: String, old: String, new: String): String {
        val at: Int = text.indexOf(old)
        check(at >= 0) { "app: a progress sample lost its text '$old'" }
        return text.substring(0, at) + new + text.substring(at + old.length)
    }
    private fun raw(key: String): String = AppSourceFiles.mainFile(files.getValue(key))
    private fun codeOf(key: String, old: String? = null, new: String = ""): String =
        AppSourceFiles.strip(if (old == null) raw(key) else edit(raw(key), old, new)).code

    @Test
    fun `every file is read and every rule holds on the real files`() {
        val codes: Map<String, String> = files.keys.associateWith { codeOf(it) }
        for ((key, code) in codes) assertTrue("app: ${files.getValue(key)} was read as empty", code.isNotBlank())
        for (rule in rules) assertTrue("app: ${files.getValue(rule.file)} breaks rule ${rule.name}", rule.holds(codes.getValue(rule.file)))
    }

    @Test
    fun `the firing samples cover each rule at least twice`() {
        val names: List<String> = rules.map { it.name }
        assertEquals("app: a progress rule name is used twice", names.toSet().size, names.size)
        assertEquals("app: a firing sample belongs to an unknown rule or a rule has none", names.toSet(), firing.map { it.rule }.toSet())
        for (rule in rules) {
            val count: Int = firing.count { it.rule == rule.name }
            assertTrue("app: progress rule ${rule.name} needs at least two firing samples, has $count", count >= 2)
        }
    }

    @Test
    fun `every rule fires on each of its edited samples`() {
        val byName: Map<String, Rule> = rules.associateBy { it.name }
        for (sample in firing) {
            val rule: Rule = byName.getValue(sample.rule)
            assertEquals("app: sample for ${sample.rule} names the wrong file", rule.file, sample.file)
            assertFalse("app: progress rule ${sample.rule} must fire on the edit '${sample.old}' => '${sample.new}'", rule.holds(codeOf(sample.file, sample.old, sample.new)))
        }
    }

    @Test
    fun `no rule fires on a harmless edit`() {
        for (sample in quiet) {
            val code: String = codeOf(sample.file, sample.old, sample.new)
            for (rule in rules.filter { it.file == sample.file }) {
                assertTrue("app: progress rule ${rule.name} must stay quiet on the harmless edit '${sample.old}' => '${sample.new}'", rule.holds(code))
            }
        }
    }

    @Test
    fun `a sample whose target text is missing fails by name`() {
        val thrown = assertThrows("app: a missing target must fail", IllegalStateException::class.java) { edit("val a = 1", "no such text", "x") }
        assertTrue("app: the failure must name the lost text, got ${thrown.message}", thrown.message!!.contains("no such text"))
    }
}
