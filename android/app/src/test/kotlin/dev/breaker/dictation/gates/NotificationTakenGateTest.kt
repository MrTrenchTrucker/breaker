package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the two files a plain JVM cannot run: the clipboard maker in the wiring swaps and the notification
 * builder in the service. It holds them to their shape by reading their text through the shared scanner, so
 * comments and literals are removed first. Each rule must hold on the real file, be broken by at least two
 * edited samples, and stay quiet on a harmless edit; an edit whose target text is missing fails by name, so a
 * sample cannot go quiet by a typo. The single-builder rule also guards against a copy-pasted second builder:
 * a second setContentText call must fail it.
 */
internal class NotificationTakenGateTest {

    private val dir: String = "kotlin/dev/breaker/dictation/"
    private val files: Map<String, String> = mapOf(
        "swaps" to dir + "wiring/AndroidSwaps.kt",
        "notification" to dir + "service/DictationNotification.kt",
    )

    private class Rule(val name: String, val file: String, val holds: (String) -> Boolean)
    private class Sample(val rule: String, val old: String, val new: String)
    private class Quiet(val file: String, val old: String, val new: String)

    /** The pattern must match the code exactly [times] times. */
    private fun rx(name: String, file: String, pattern: String, times: Int = 1) =
        Rule(name, file) { Regex(pattern).findAll(it).count() == times }

    private val rules: List<Rule> = listOf(
        rx("TAKEN_CLIPBOARD_MAKER", "swaps", """\bfun\s+appTakenClipboard\s*\(\s*context\s*:\s*Context\s*\)\s*:\s*TakenClipboard\s*="""),
        rx("WITHTEXT_EXISTS", "notification", """\bfun\s+withText\s*\(\s*context\s*:\s*Context\s*,\s*text\s*:\s*String\s*\)\s*:\s*Notification\s*="""),
        rx("ONE_PARAMETERIZED_BUILDER", "notification", """.setContentText\("""),
        rx("BUILD_SIGNATURE_UNCHANGED", "notification", """\bfun\s+build\s*\(\s*context\s*:\s*Context\s*\)\s*:\s*Notification\s*="""),
    )

    private val firing: List<Sample> = listOf(
        Sample("TAKEN_CLIPBOARD_MAKER", "fun appTakenClipboard", "fun takenClipboard"),
        Sample("TAKEN_CLIPBOARD_MAKER",
            "fun appTakenClipboard(context: Context): TakenClipboard =",
            "fun appTakenClipboard(context: Context): Any ="),
        Sample("WITHTEXT_EXISTS",
            "fun withText(context: Context, text: String): Notification =",
            "fun withText(context: Context, text: String): Any ="),
        Sample("WITHTEXT_EXISTS",
            "fun withText(context: Context, text: String): Notification =",
            "fun withTextLine(context: Context, text: String): Notification ="),
        Sample("ONE_PARAMETERIZED_BUILDER", ".setContentText(textLine())", ".setContentText(textLine())\n                .setContentText(other)"),
        Sample("ONE_PARAMETERIZED_BUILDER", ".setContentText(textLine())", ".setContentTitle(context.getString(R.string.dictation_notification_title))"),
        Sample("BUILD_SIGNATURE_UNCHANGED", "fun build(context: Context): Notification =", "removedBuild(context: Context): Notification ="),
        Sample("BUILD_SIGNATURE_UNCHANGED", "fun build(context: Context): Notification =", "withText(context, context.getString(R.string.dictation_notification_text))"),
    )

    private val quiet: List<Quiet> = listOf(
        Quiet("swaps",
            "fun appTakenClipboard(context: Context): TakenClipboard =",
            "fun appTakenClipboard(context: Context): TakenClipboard = // the one maker"),
        Quiet("notification",
            "fun withText(context: Context, text: String): Notification =",
            "fun withText(context: Context, text: String): Notification = // a doc comment on the builder line"),
        Quiet("notification",
            "private fun builder(context: Context, textLine: () -> String): Notification {",
            "/** A shared builder for build and withText. */\n    private fun builder(context: Context, textLine: () -> String): Notification {"),
        Quiet("notification",
            "fun build(context: Context): Notification =",
            "fun build(context: Context): Notification = // a doc comment on the signature"),
    )

    private fun edit(text: String, old: String, new: String): String {
        val at = text.indexOf(old)
        check(at >= 0) { "app: a gate sample lost its text '$old'" }
        return text.substring(0, at) + new + text.substring(at + old.length)
    }

    private fun raw(key: String): String = AppSourceFiles.mainFile(files.getValue(key))
    private fun codeOf(key: String, old: String? = null, new: String = ""): String =
        AppSourceFiles.strip(if (old == null) raw(key) else edit(raw(key), old, new)).code

    @Test
    fun `every rule holds on the real files`() {
        for (rule in rules) assertTrue("app: ${files.getValue(rule.file)} breaks rule ${rule.name}", rule.holds(codeOf(rule.file)))
    }

    @Test
    fun `every firing sample fires the rule on its edit`() {
        val byName = rules.associateBy { it.name }
        for (sample in firing) {
            val rule = byName.getValue(sample.rule)
            assertFalse("app: gate rule ${rule.name} must fire on the edit '${sample.old}' => '${sample.new}'", rule.holds(codeOf(rule.file, sample.old, sample.new)))
        }
    }

    @Test
    fun `every rule has at least two firing samples`() {
        val names = rules.map { it.name }
        assertEquals("app: a gate rule name is used twice", names.toSet().size, names.size)
        for (rule in rules) {
            val count = firing.count { it.rule == rule.name }
            assertTrue("app: gate rule ${rule.name} needs at least two firing samples, has $count", count >= 2)
        }
    }

    @Test
    fun `no rule fires on a harmless edit`() {
        for (q in quiet) {
            for (rule in rules.filter { it.file == q.file }) {
                assertTrue("app: gate rule ${rule.name} must stay quiet on the harmless edit '${q.old}' => '${q.new}'", rule.holds(codeOf(rule.file, q.old, q.new)))
            }
        }
    }

    @Test
    fun `a sample whose target text is missing fails by name`() {
        val thrown = runCatching { edit("val a = 1", "no such text", "x") }.exceptionOrNull()
        assertTrue("app: a missing target must fail, got ${thrown?.message}", thrown != null)
    }
}
