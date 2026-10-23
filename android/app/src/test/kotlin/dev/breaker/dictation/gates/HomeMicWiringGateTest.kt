package dev.breaker.dictation.gates

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the five Home-mic wiring facts no other gate can, because a test can call the helpers but never
 * observe the values they carry: the activity reads the usable height from the barless window metrics
 * (not zero), the helper names its screen mic the HomeControl labels (never an empty string), and the level
 * sink keeps its recording probe wired as-is rather than always-true. The theme pair follows: the mic is
 * made in the neutral [dev.breaker.shared.tokens.ThemeMode.LIGHT], and the mode the app hands it is the one
 * the Home view resolved, not one read from the phone again.
 * Each rule is read against source with comments removed but literals kept, holds on the real activity/helper
 * text, fires on a single targeted edit that breaks it, and stays quiet on a harmless spacing or comment
 * change. String-literal content must survive stripping, so this gate strips
 * comments only - see [stripComments] - instead of the shared scanner, which blanks literal text as well.
 */
internal class HomeMicWiringGateTest {

    private val activity: String = "kotlin/dev/breaker/dictation/SettingsLauncherActivity.kt"
    private val helpers: String = "kotlin/dev/breaker/dictation/host/ActivityHelpers.kt"

    /** A rule holds when its pattern still matches the comment-stripped source. */
    private class Rule(val name: String, val file: String, val holds: (String) -> Boolean)

    /** Per sample: [rule] that must fail after editing [file]'s [old] into [new]. */
    private class Sample(val rule: String, val old: String, val new: String)

    /** A harmless edit ([old] to [new]) applied to the file its rule names. */
    private class Quiet(val rule: String, val file: String, val old: String, val new: String)

    private val rules: List<Rule> = listOf(
        Rule("ACTIVITY_READS_USABLE_HEIGHT_FROM_BARLESS_METRICS", activity) { code ->
            Regex("""\busableHeightDp\s*=\s*windowHeightAfterBarsDp\s*\(\s*this\s*\)""").containsMatchIn(code)
        },
        Rule("HELPERS_SCREEN_MIC_IS_NAMED", helpers) { code ->
            Regex("""\bcreateScreenMic\s*\(\s*context\s*,\s*themeMode\s*,\s*HomeControl\s*\.\s*TAP_TO_TALK\s*,\s*HomeControl\s*\.\s*CANCEL\s*,\s*HomeControl\s*\.\s*DONE\s*\)""").containsMatchIn(code)
        },
        Rule("LEVEL_SINK_KEEP_ITS_RECORDING_PROBE", helpers) { code ->
            Regex("""\bisRecording\s*=\s*isRecording\s*,""").containsMatchIn(code)
        },
        Rule("HOME_MIC_THEME_STARTS_NEUTRAL", helpers) { code ->
            Regex("""\bval\s+themeMode\s*=\s*dev\s*\.\s*breaker\s*\.\s*shared\s*\.\s*tokens\s*\.\s*ThemeMode\s*\.\s*LIGHT\b""").containsMatchIn(code)
        },
        Rule("HOME_MIC_THEME_IS_THE_PAGE_RESOLUTION", activity) { code ->
            Regex("""\bonShownMode\s*=\s*\{\s*mode\s*->\s*mic\s*\.\s*setTheme\s*\(\s*mode\s*\)\s*\}""").containsMatchIn(code)
        },
    )

    private val firing: List<Sample> = listOf(
        Sample("ACTIVITY_READS_USABLE_HEIGHT_FROM_BARLESS_METRICS",
            "usableHeightDp = windowHeightAfterBarsDp(this),", "usableHeightDp = 0f,"),
        Sample("HELPERS_SCREEN_MIC_IS_NAMED", "HomeControl.TAP_TO_TALK", "\"\""),
        Sample("LEVEL_SINK_KEEP_ITS_RECORDING_PROBE",
            "isRecording = isRecording,", "isRecording = { true },"),
        Sample("HOME_MIC_THEME_STARTS_NEUTRAL",
            "val themeMode = dev.breaker.shared.tokens.ThemeMode.LIGHT",
            "val themeMode = themeModeForNight(night)"),
        Sample("HOME_MIC_THEME_IS_THE_PAGE_RESOLUTION",
            "onShownMode = { mode -> mic.setTheme(mode) }",
            "onShownMode = { mode -> mic.setTheme(dev.breaker.shared.tokens.ThemeMode.LIGHT) }"),
    )

    private val quiet: List<Quiet> = listOf(
        Quiet("ACTIVITY_READS_USABLE_HEIGHT_FROM_BARLESS_METRICS", activity,
            "windowHeightAfterBarsDp(this),", "windowHeightAfterBarsDp(this), // the home content height"),
        Quiet("HELPERS_SCREEN_MIC_IS_NAMED", helpers,
            "createScreenMic(context, themeMode, HomeControl.TAP_TO_TALK, HomeControl.CANCEL, HomeControl.DONE)",
            "createScreenMic( context , themeMode , HomeControl.TAP_TO_TALK, HomeControl.CANCEL, HomeControl.DONE )"),
        Quiet("LEVEL_SINK_KEEP_ITS_RECORDING_PROBE", helpers,
            "isRecording = isRecording,", "isRecording   =   isRecording,"),
        Quiet("HOME_MIC_THEME_STARTS_NEUTRAL", helpers,
            "val themeMode = dev.breaker.shared.tokens.ThemeMode.LIGHT",
            "val themeMode = dev.breaker.shared.tokens.ThemeMode . LIGHT"),
        Quiet("HOME_MIC_THEME_IS_THE_PAGE_RESOLUTION", activity,
            "onShownMode = { mode -> mic.setTheme(mode) }",
            "onShownMode = { mode -> mic.setTheme( mode ) }"),
    )

    private fun edit(text: String, old: String, new: String): String {
        val at = text.indexOf(old)
        check(at >= 0) { "app: a gate sample lost its text '$old'" }
        return text.substring(0, at) + new + text.substring(at + old.length)
    }

    private fun activityCode(): String = stripComments(AppSourceFiles.mainFile(activity))

    private fun helpersCode(): String = stripComments(AppSourceFiles.mainFile(helpers))

    @Test
    fun `every rule holds on the real activity and helper`() {
        val broken = rules.filterNot { it.holds(activityCode() + "\n\n" + helpersCode()) }.map { it.name }
        assertTrue("the Home mic wiring breaks: $broken", broken.isEmpty())
    }

    @Test
    fun `every firing sample makes its rule fail`() {
        val byName = rules.associateBy { it.name }
        val stillTrue = mutableListOf<String>()
        for (s in firing) {
            val rule = byName.getValue(s.rule)
            val fileText = edit(if (rule.file == helpers) helpersCode() else activityCode(), s.old, s.new)
            // Combine: the edited file's text plus the untouched partner file.
            val combined = if (rule.file == helpers) {
                activityCode() + "\n\n" + fileText
            } else {
                fileText + "\n\n" + helpersCode()
            }
            if (rule.holds(combined)) stillTrue += "${s.rule}: '${s.old}' => '${s.new}'"
        }
        assertTrue("the Home mic wiring keeps these rules true: $stillTrue", stillTrue.isEmpty())
    }

    @Test
    fun `every quiet edit keeps every rule true`() {
        for (q in quiet) {
            val edited = edit(if (q.file == helpers) helpersCode() else activityCode(), q.old, q.new)
            val combined = if (q.file == helpers) activityCode() + "\n\n" + edited else edited + "\n\n" + helpersCode()
            for (rule in rules) {
                assertTrue("app: rule ${rule.name} must stay quiet on '${q.old}' => '${q.new}'", rule.holds(combined))
            }
        }
    }

}

/** Strips // line comments and /* */ block comments from [source], leaving string literals untouched. */
private fun stripComments(source: String): String {
    val out = StringBuilder()
    var i = 0
    while (i < source.length) {
        when {
            source.startsWith("//", i) -> {
                val end = source.indexOf('\n', i)
                i = if (end < 0) source.length else end
            }
            source.startsWith("/*", i) -> {
                val end = source.indexOf("*/", i)
                i = if (end < 0) source.length else end + 2
            }
            else -> out.append(source[i]).let { i++ }
        }
    }
    return out.toString()
}
