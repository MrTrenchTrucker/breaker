package dev.breaker.dictation.ui.gate

import dev.breaker.dictation.ui.gate.mainSourceOf
import dev.breaker.dictation.ui.gate.withoutCommentsAndStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/*
 * The no-stock-widget rule: no stock Android widget on any screen Breaker
 * draws, ui and app; system dialogs, permission prompts and notifications
 * are out of scope.
 *
 * Stock widgets (TextView, Button, LinearLayout, ScrollView, etc.) are the
 * framework's own view classes. A screen Breaker draws is described as nodes
 * and rendered into views; the render layer may use stock widgets, but the
 * screen descriptions may not. The rule scans both modules for stock widget
 * usage and ensures it is only in the render layer or in the named allowed
 * containers.
 *
 * The only allowed stock classes are the ones that are NOT drawn content,
 * each named one by one with a reason. The rule is read twice: once on the
 * ui module's sources, and once on the app module's sources. Each rule is
 * shown to fire on a sample before it is held on the real files.
 */
class NoStockWidgetGateTest {

    private val WHITESPACE_RUNS = Regex("""\s+""")

    /** A stock Android widget class name. */
    private val STOCK_WIDGET = Regex("""android\.widget\.\w+""")

    /**
     * The stock classes that are NOT drawn content, each named with its true reason.
     * LinearLayout is the column a rendered screen is put in, in the settings activity.
     * ScrollView is the page scroller a host wraps a screen in, in the same activity.
     * FrameLayout is not listed: the Home host draws its drawer overlay with one, and the
     * first-run host is rooted in one, both inside HostView files, which the file rule
     * above exempts. No file outside those may use a FrameLayout.
     */
    private val ALLOWED_STOCK_WIDGETS = mapOf(
        "android.widget.LinearLayout" to "the column a rendered screen is put in, in the settings activity",
        "android.widget.ScrollView" to "the page scroller a host wraps a screen in, in the settings activity",
    )

    /**
     * Stock classes that a screen Breaker draws may never name outside the render layer and
     * the HostView files, even if the allowlist above were ever to name one of them.
     */
    private val ALWAYS_REFUSED_STOCK_WIDGETS = setOf(
        "android.widget.TextView",
        "android.widget.Button",
        "android.widget.ImageView",
    )

    /** The flat code of [text], with comments and strings removed and whitespace collapsed. */
    private fun flatCode(text: String): String = withoutCommentsAndStrings(text).replace(WHITESPACE_RUNS, " ")

    /** The stock widget references in the code of [text]. */
    private fun stockWidgetsIn(text: String): List<String> =
        STOCK_WIDGET.findAll(flatCode(text)).map { it.value }.distinct().toList()

    /**
     * Whether [relative] is a file that may name a stock widget.
     *
     * The render layer (the render package and the HostView classes) may use
     * stock widgets; the screen descriptions may not.
     */
    private fun mayUseStockWidget(relative: String): Boolean {
        val path = File(relative).path.replace(File.separatorChar, '/')
        // The render package may use stock widgets
        if (path.startsWith("render/")) return true
        // HostView classes are the render layer for their screens
        if (path.endsWith("HostView.kt")) return true
        return false
    }

    /** The named problems with the no-stock-widget rule in [text]; an empty list means the rule holds. */
    private fun stockWidgetProblemsIn(relative: String, text: String): List<String> {
        if (mayUseStockWidget(relative)) return emptyList()
        val found = stockWidgetsIn(text).filter { it !in ALLOWED_STOCK_WIDGETS || it in ALWAYS_REFUSED_STOCK_WIDGETS }
        return if (found.isNotEmpty()) listOf("$relative: stock widget used: $found") else emptyList()
    }

    /** [text] with [old] replaced by [new]; fails by name when [old] is not exactly once in [text]. */
    private fun replaceOnce(text: String, old: String, new: String): String {
        assertTrue(
            "ui: no-stock-widget: the text to change is not in the source exactly once: $old",
            Regex(Regex.escape(old)).findAll(text).count() == 1,
        )
        return text.replace(old, new)
    }

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("ui: no-stock-widget: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })

    @Test
    fun `the ui module uses stock widgets only in the render layer`() {
        val found = MAIN_SOURCES.flatMap { (path, text) -> stockWidgetProblemsIn(path, text) }
        assertEquals("stock widget references outside the render layer: $found", emptyList<String>(), found)
    }

    @Test
    fun `a screen description that uses a stock widget is reported`() {
        val control = "package dev.breaker.dictation.ui.screen.home\n\nimport android.widget.TextView\n"
        assertFires("a stock widget in a screen description", stockWidgetProblemsIn("screen/home/HomeScreen.kt", control), "stock widget")
    }

    @Test
    fun `a render file that uses a stock widget is allowed`() {
        val control = "package dev.breaker.dictation.ui.render\n\nimport android.widget.TextView\n"
        assertEquals(emptyList<String>(), stockWidgetProblemsIn("render/ScreenRenderer.kt", control))
    }

    @Test
    fun `a HostView that uses a stock widget is allowed`() {
        val control = "package dev.breaker.dictation.ui.screen.about\n\nimport android.widget.ScrollView\n"
        assertEquals(emptyList<String>(), stockWidgetProblemsIn("screen/about/AboutHostView.kt", control))
    }

    @Test
    fun `the app module uses only allowed stock widgets`() {
        val appModuleDir = File(MODULE_DIR, "../app")
        val appPackagePath = "src/main/kotlin/dev/breaker/dictation"
        val appRoot = File(appModuleDir, appPackagePath)
        assertTrue("the app module was not found at ${appRoot.absolutePath}", appRoot.isDirectory)
        val appSources = appRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { file -> file.relativeTo(appRoot).path.replace(File.separatorChar, '/') to file.readText() }
        assertTrue("the app module holds no Kotlin source", appSources.count() > 0)
        val found = appSources.flatMap { (path, text) -> stockWidgetProblemsIn(path, text) }.toList()
        assertEquals("stock widget references outside the allowed containers: $found", emptyList<String>(), found)
    }

    @Test
    fun `a stock widget in the activity is reported`() {
        val control = "package dev.breaker.dictation\n\nimport android.widget.Button\n"
        assertFires("a stock widget in the activity", stockWidgetProblemsIn("SettingsLauncherActivity.kt", control), "stock widget")
    }

    @Test
    fun `the allowed containers pass`() {
        val control = "package dev.breaker.dictation\n\nimport android.widget.LinearLayout\nimport android.widget.ScrollView\n"
        assertEquals(emptyList<String>(), stockWidgetProblemsIn("SettingsLauncherActivity.kt", control))
    }

    @Test
    fun `a stock button, text view or image view is refused outside the render layer`() {
        assertFires("a stock button in a screen description", stockWidgetProblemsIn("screen/home/HomeScreen.kt", "import android.widget.Button\n"), "stock widget")
        assertFires("a stock text view in the activity", stockWidgetProblemsIn("SettingsLauncherActivity.kt", "import android.widget.TextView\n"), "stock widget")
        assertEquals(emptyList<String>(), stockWidgetProblemsIn("render/ScreenRenderer.kt", "import android.widget.ImageView\n"))
        assertEquals(emptyList<String>(), stockWidgetProblemsIn("screen/about/AboutHostView.kt", "import android.widget.Button\n"))
    }
}
