package dev.breaker.dictation.ui.screen.settings

import dev.breaker.dictation.ui.gate.mainSourceOf
import dev.breaker.dictation.ui.gate.withoutCommentsAndStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every part of the settings page takes its theme from the one the host redraws with.
 *
 * The host keeps no theme of its own, builds its download bar with the controller's theme
 * once, and hands the redrawn theme to every part it draws. The bar keeps its theme in a var
 * that its setter assigns and then repaints from. Each check holds on the real file and is
 * broken by edited samples of its own; the harmless edits below leave every check true.
 * Checks that count code see the text with comments and literal text blanked; the redraw
 * and setter bodies are read flat, so a line break inside a call does not hide a part.
 */
class SettingsThemeWatchRulesTest {
    private val host = mainSourceOf("render/SettingsHostView.kt")
    private val bar = mainSourceOf("render/controls/DownloadBarView.kt")

    private val whitespace = Regex("""\s+""")

    /** A named check on the text of one file: it holds when [holds] is true for that text. */
    private class Check(val name: String, val holds: (String) -> Boolean)

    /** An edit to a source; [check] names the check it must break, or is blank for a harmless edit. */
    private class Sample(val check: String, val old: String, val new: String)

    /** The parts the redraw hands the redrawn theme to, each with the words that name it in a failure. */
    private val redrawParts: List<Pair<String, String>> = listOf(
        "renderer.render(rendered.screen, rendered.theme, ::onIntent)" to "the column",
        "downloadBar.setTheme(rendered.theme)" to "the download bar",
        "setBackgroundColor(rendered.theme.color(PaletteSlot.BACKGROUND).argb)" to "the background",
    )

    /** How many matches of [pattern] the code of [text] holds. */
    private fun countOf(text: String, pattern: String): Int =
        Regex(pattern).findAll(withoutCommentsAndStrings(text)).count()

    /** The flat code of the function whose header is [header], from the header to its closing brace; empty when the header is missing. */
    private fun bodyOf(text: String, header: String): String {
        val code = withoutCommentsAndStrings(text)
        val start = code.indexOf(header)
        if (start < 0) return ""
        val end = code.indexOf("\n    }\n", start)
        return code.substring(start, if (end < 0) code.length else end).replace(whitespace, " ")
    }

    /** The redraw problems in the host: a part not drawn with the redrawn theme, or a read of the controller inside the redraw. */
    private fun redrawProblems(text: String): List<String> {
        val body = bodyOf(text, "fun show(rendered: Rendered) {")
        return redrawParts.filter { !body.contains(it.first) }.map { "the redraw does not hand the redrawn theme to ${it.second}" } +
            listOfNotNull("the redraw reads the controller instead of the redrawn theme".takeIf { body.contains("themes.state") })
    }

    /** The setter problems in the bar: a setter that does not assign the theme it is given, or does not repaint. */
    private fun setThemeProblems(text: String): List<String> {
        val body = bodyOf(text, "fun setTheme(theme: Theme) {")
        return listOfNotNull(
            "the bar does not assign the theme it is given".takeUnless { body.contains("this.theme = theme") },
            "the bar does not repaint after a new theme".takeUnless { body.contains("invalidate()") },
        )
    }

    private val hostOwnershipChecks: List<Check> = listOf(
        Check("HOST_KEEPS_NO_THEME_FIELD") { countOf(it, """\bval\s+\w+\s*:\s*Theme\b|\bvar\s+\w+\s*:\s*Theme\b|\bval\s+\w+\s*=\s*Themes\.of\(""") == 0 },
        Check("THEMES_OF_IS_CALLED_ONCE") { countOf(it, """Themes\.of\(""") == 1 },
        Check("THE_BAR_IS_BUILT_WITH_THE_CONTROLLER_THEME") { countOf(it, """DownloadBarView\(context,\s*Themes\.of\(themes\.state\.shown\)\)""") == 1 },
    )

    private val redrawCheck = Check("REDRAW_HANDS_THE_REDRAWN_THEME_TO_EVERY_PART") { redrawProblems(it).isEmpty() }

    private val hostChecks: List<Check> = hostOwnershipChecks + redrawCheck

    private val barChecks: List<Check> = listOf(
        Check("BAR_HOLDS_A_VAR_THEME") { countOf(it, """\bprivate\s+var\s+theme\s*:\s*Theme\b""") == 1 },
        Check("BAR_HOLDS_NO_VAL_THEME") { countOf(it, """\bprivate\s+val\s+theme\b""") == 0 },
        Check("BAR_SETTHEME_ASSIGNS_AND_REPAINTS") { setThemeProblems(it).isEmpty() },
    )

    private val hostAnchor = "private var running: Boolean = false"

    private val hostSamples: List<Sample> = listOf(
        Sample("HOST_KEEPS_NO_THEME_FIELD", hostAnchor, "$hostAnchor\n    private val frozen: Theme? = null"),
        Sample("HOST_KEEPS_NO_THEME_FIELD", hostAnchor, "$hostAnchor\n    private val first = Themes.of(themes.state.shown)"),
        Sample("THEMES_OF_IS_CALLED_ONCE", hostAnchor, "$hostAnchor\n    private val label: String = Themes.of(themes.state.shown).name"),
        Sample("THEMES_OF_IS_CALLED_ONCE", hostAnchor, "$hostAnchor\n    private val size: Int = Themes.of(themes.state.shown).hashCode()"),
        Sample("THE_BAR_IS_BUILT_WITH_THE_CONTROLLER_THEME", "DownloadBarView(context, Themes.of(themes.state.shown))", "DownloadBarView(context, Themes.of(themes.state.shown.name))"),
        Sample("THE_BAR_IS_BUILT_WITH_THE_CONTROLLER_THEME", "DownloadBarView(context, Themes.of(themes.state.shown))", "DownloadBarView(themes.state.shown.name, Themes.of(themes.state.shown))"),
        Sample("REDRAW_HANDS_THE_REDRAWN_THEME_TO_EVERY_PART", "downloadBar.setTheme(rendered.theme)", "downloadBar.setTheme(Themes.of(themes.state.shown))"),
        Sample("REDRAW_HANDS_THE_REDRAWN_THEME_TO_EVERY_PART", "setBackgroundColor(rendered.theme.color(PaletteSlot.BACKGROUND).argb)", "setBackgroundColor(themes.state.shown.hashCode())"),
        Sample("REDRAW_HANDS_THE_REDRAWN_THEME_TO_EVERY_PART", "downloadBar.setTheme(rendered.theme)", "// downloadBar.setTheme(rendered.theme)"),
    )

    /** Harmless edits to the host: a comment that names a theme field, a plain read of the controller, and a comment that names the bar hand-off. */
    private val hostQuiet: List<Sample> = listOf(
        Sample("", hostAnchor, "$hostAnchor\n    // private val frozen: Theme? = null"),
        Sample("", hostAnchor, "$hostAnchor\n    private val label = themes.state.shown.name"),
        Sample("", "val held = scrollY", "// the bar takes downloadBar.setTheme(rendered.theme) below\n        val held = scrollY"),
    )

    private val barAnchor = "private var animator: ValueAnimator? = null"

    private val barSamples: List<Sample> = listOf(
        Sample("BAR_HOLDS_A_VAR_THEME", "private var theme: Theme,", "private val theme: Theme,"),
        Sample("BAR_HOLDS_A_VAR_THEME", "private var theme: Theme,", "private var colour: Theme,"),
        Sample("BAR_HOLDS_NO_VAL_THEME", "private var theme: Theme,", "private val theme: Theme,"),
        Sample("BAR_HOLDS_NO_VAL_THEME", barAnchor, "$barAnchor\n    private val theme = 0"),
        Sample("BAR_SETTHEME_ASSIGNS_AND_REPAINTS", "this.theme = theme", "Unit"),
        Sample("BAR_SETTHEME_ASSIGNS_AND_REPAINTS", "this.theme = theme\n        invalidate()", "this.theme = theme"),
    )

    /** Harmless edit to the bar: a comment that names the old field. */
    private val barQuiet: List<Sample> = listOf(
        Sample("", barAnchor, "$barAnchor\n    // private val theme: Theme was the old shape"),
    )

    /** [text] with [old] replaced by [new]; fails by name when [old] is not exactly once in [text]. */
    private fun editOnce(text: String, old: String, new: String): String {
        val count = Regex(Regex.escape(old)).findAll(text).count()
        check(count == 1) { "screen: settings theme watch: the text to change is not in the source exactly once ($count times): $old" }
        return text.replace(old, new)
    }

    /** Whether the edit of [sample] to [source] breaks the check it names among [checks]. */
    private fun firedBy(checks: List<Check>, source: String, sample: Sample): Boolean =
        !checks.single { it.name == sample.check }.holds(editOnce(source, sample.old, sample.new))

    @Test
    fun `the host keeps no theme field and builds its bar with the controller theme once`() {
        assertEquals("the host breaks an ownership check", emptyList<String>(), hostOwnershipChecks.filter { !it.holds(host) }.map { it.name })
    }

    @Test
    fun `the redraw hands the redrawn theme to every themed part the host keeps`() {
        assertEquals("the redraw breaks a check", emptyList<String>(), redrawProblems(host))
    }

    @Test
    fun `the bar keeps a var theme and its setter assigns it and repaints`() {
        assertEquals("the bar breaks a check", emptyList<String>(), barChecks.filter { !it.holds(bar) }.map { it.name })
    }

    @Test
    fun `each theme watch check fires on its edited samples`() {
        val unfired = hostSamples.filter { !firedBy(hostChecks, host, it) }.map { "${it.check}: '${it.old}'" } +
            barSamples.filter { !firedBy(barChecks, bar, it) }.map { "${it.check}: '${it.old}'" }
        assertTrue("samples that leave their check true: $unfired", unfired.isEmpty())
    }

    @Test
    fun `each theme watch check has at least two firing samples`() {
        val allSamples = hostSamples + barSamples
        val names = hostChecks.map { it.name } + barChecks.map { it.name }
        val thin = names.filter { name -> allSamples.count { it.check == name } < 2 }
        assertTrue("checks with fewer than two firing samples: $thin", thin.isEmpty())
    }

    @Test
    fun `a harmless edit leaves every theme watch check true`() {
        val broken = hostQuiet.flatMap { q ->
            hostChecks.filter { !it.holds(editOnce(host, q.old, q.new)) }.map { "${it.name} on '${q.old}'" }
        } + barQuiet.flatMap { q ->
            barChecks.filter { !it.holds(editOnce(bar, q.old, q.new)) }.map { "${it.name} on '${q.old}'" }
        }
        assertTrue("a harmless edit broke a check: $broken", broken.isEmpty())
    }

    @Test
    fun `a sample whose target text is missing fails by name`() {
        val thrown = runCatching { editOnce("val a = 1", "no such text", "x") }.exceptionOrNull()
        assertTrue("the failure must name the lost text, got $thrown", thrown?.message?.contains("no such text") == true)
    }
}
