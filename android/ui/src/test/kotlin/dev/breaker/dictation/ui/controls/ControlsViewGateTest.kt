package dev.breaker.dictation.ui.controls

import dev.breaker.dictation.ui.gate.MAIN_SOURCES
import dev.breaker.dictation.ui.gate.withoutComments
import dev.breaker.dictation.ui.gate.withoutCommentsAndStrings
import dev.breaker.dictation.ui.theme.PaletteSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The controls views cannot run on a plain JVM, so the rules for what they draw
 * are checked on their source text, the way the overlay's tile view is checked:
 * no colour literal, every colour from the palette or the theme, the words as
 * a parameter, the touch-target floor, and the framework named only in the
 * view files.
 *
 * Every test runs its rule first on short made-up samples (a wrong one must be
 * reported with the right words, a correct one must not) and only then on the
 * real files. A file that cannot be found is a violation, never a pass.
 * Comments are blanked and string literals are kept.
 */
class ControlsViewGateTest {

    /** The view files, by path relative to this module's package. */
    private val VIEW_SOURCES: Map<String, String> =
        MAIN_SOURCES.filter { (path, _) -> path.startsWith("$VIEW_DIRECTORY/") }.toMap()

    /** The four plain-Kotlin files of the kit, by the same path. */
    private val PLAIN_SOURCES: Map<String, String> =
        MAIN_SOURCES.filter { (path, _) -> path in PLAIN_FILES }.toMap()

    /** The whole kit: the views and the plain files, nothing else. */
    private val KIT_SOURCES: Map<String, String> = VIEW_SOURCES + PLAIN_SOURCES

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("controls: view: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("controls: view: $what must not be reported", emptyList<String>(), problems)

    private fun viewText(name: String): String = VIEW_SOURCES[name] ?: ""

    private fun plainText(name: String): String = PLAIN_SOURCES[name] ?: ""

    // ---- No colour literal in a view ----

    /** A packed colour written as a hex string, with or without its alpha. */
    private val HEX_STRING = Regex("""(?i)["']#(?:[0-9a-f]{8}|[0-9a-f]{6})["']""")

    /** A packed colour written as a hex number, with or without its alpha. */
    private val HEX_NUMBER = Regex("""\b0[xX](?:[0-9a-fA-F]{8}|[0-9a-fA-F]{6})\b""")

    /** A packed colour written as the decimal number it is worth. */
    private val LARGE_DECIMAL = Regex("""(?<![\w.$])-?[1-9][0-9]{6,}(?![\w.$])""")

    /** The framework's colour type, which paints and blends rather than naming a token. */
    private val FRAMEWORK_COLOUR = Regex("""\bandroid\.graphics\.(?:Color|color)\b""")

    /** The named shape a piece of [text] breaks, or null when it breaks none. One shape per call, as the no-colour gate spells it. */
    private fun colourIn(text: String): String? {
        val code = withoutComments(text)
        return when {
            FRAMEWORK_COLOUR.containsMatchIn(code) -> "the framework colour type"
            HEX_STRING.containsMatchIn(code) -> "a hex colour string"
            HEX_NUMBER.containsMatchIn(code) -> "a hex colour number"
            LARGE_DECIMAL.containsMatchIn(code) -> "a packed colour as a decimal number"
            else -> null
        }
    }

    /** Every line of [text] that carries a colour literal, each as `line: shape`. */
    private fun colourLinesIn(text: String): List<String> =
        text.lines().mapIndexedNotNull { number, line ->
            colourIn(line)?.let { shape -> "${number + 1}: $shape" }
        }

    /** A failure here means a view paints with a colour the tokens never gave it, so the kit holds a colour of its own. */
    @Test
    fun `no view file holds a colour literal`() {
        assertFires("a hex colour string", colourLinesIn("val c = \"#1E7A46\"\n"), "a hex colour string")
        assertFires("a hex colour number", colourLinesIn("val c = 0xFF1E7A46.toInt()\n"), "a hex colour number")
        assertFires("a packed colour as a decimal", colourLinesIn("val c = -14451130\n"), "a packed colour as a decimal number")
        assertFires("the framework colour type", colourLinesIn("val c = android.graphics.Color.RED\n"), "the framework colour type")
        assertFires("a literal inside a paint call", colourLinesIn("paint.color = 0xFF1E7A46.toInt()\n"), "a hex colour number")
        assertQuiet(
            "a label parameter and a palette call",
            colourLinesIn("val label: String\npaint.color = ControlsPalette.partColor(part, theme)\n"),
        )
        val found = VIEW_SOURCES.flatMap { (path, text) -> colourLinesIn(text).map { line -> "$path:$line" } }
        assertEquals("colour literals in a view: $found", emptyList<String>(), found)
    }

    // ---- Every colour comes from the palette or the theme ----

    /** A paint's colour being assigned. */
    private val COLOUR_ASSIGNMENT = Regex("""\.color\s*=""")

    /** The value assigned on a line that assigns a paint's colour, or null on a line that assigns none. */
    private fun colourValueOf(line: String): String? =
        if (COLOUR_ASSIGNMENT.containsMatchIn(line)) line.substringAfter('=').trim() else null

    private fun colourSourceProblems(text: String): List<String> =
        withoutCommentsAndStrings(text).lines().mapIndexedNotNull { number, line ->
            val value = colourValueOf(line) ?: return@mapIndexedNotNull null
            val fromPalette = value.startsWith("ControlsPalette.partColor(")
            val fromTheme = value.startsWith("theme.color(PaletteSlot.") || value.startsWith("Theme.color(PaletteSlot.")
            if (!fromPalette && !fromTheme) "${number + 1}: the colour is set from '$value'" else null
        }

    /** A failure here means a colour reaches the canvas from outside the palette and the theme, so the theme stops deciding what a part looks like. */
    @Test
    fun `every colour a view paints comes from the palette or the theme`() {
        assertFires("a colour constant", colourSourceProblems("paint.color = Color.RED\n"), "the colour is set from 'Color.RED'")
        assertFires("a hex colour", colourSourceProblems("paint.color = 0xFF1E7A46.toInt()\n"), "the colour is set from '0xFF1E7A46.toInt()'")
        assertFires("a colour variable", colourSourceProblems("textPaint.color = tint\n"), "the colour is set from 'tint'")
        assertFires("a made number", colourSourceProblems("paint.color = shade(face, 7)\n"), "the colour is set from 'shade(face, 7)'")
        assertQuiet("the palette's part colour", colourSourceProblems("paint.color = ControlsPalette.partColor(ControlPart.LABEL, theme)\n"))
        assertQuiet("the theme's slot colour", colourSourceProblems("paint.color = theme.color(PaletteSlot.TEXT)\n"))
        assertQuiet("the theme's slot colour in full", colourSourceProblems("paint.color = Theme.color(PaletteSlot.TEXT)\n"))
        val found = VIEW_SOURCES.flatMap { (path, text) -> colourSourceProblems(text).map { line -> "$path:$line" } }
        assertEquals("colours set outside the palette and the theme: $found", emptyList<String>(), found)
    }

    // ---- No view capitalises its own label ----

    /** Every line of [text] that calls `.uppercase(` itself, comments and string literals removed. */
    private fun ownUppercaseProblems(text: String): List<String> =
        withoutCommentsAndStrings(text).lines().mapIndexedNotNull { number, line ->
            if (line.contains(".uppercase(")) "${number + 1}: the view capitalises its own label" else null
        }

    /** A failure here means a view draws its label in a case of its own, so the drawn form lives in every view instead of in [drawnLabel]. */
    @Test
    fun `no view capitalises its own label`() {
        assertFires("a view that uppercases", ownUppercaseProblems("canvas.drawText(label.uppercase(), half(w), 0f, textPaint)\n"), "capitalises its own label")
        assertQuiet("the drawn-label helper", ownUppercaseProblems("canvas.drawText(drawnLabel(label), half(w), 0f, textPaint)\n"))
        val found = VIEW_FILES.flatMap { name -> ownUppercaseProblems(viewText(name)).map { line -> "$name:$line" } }
        assertEquals("labels capitalised in a view: $found", emptyList<String>(), found)
    }

    // ---- The words are a parameter; the view holds none of its own ----

    /** A label taken as a constructor parameter. */
    private val LABEL_PARAM = Regex("""label: String""")

    /** A count taken as a constructor parameter, for the view that carries no words. */
    private val COUNT_PARAM = Regex("""val count: Int""")

    private fun labelParamProblems(text: String): List<String> =
        if (LABEL_PARAM.containsMatchIn(withoutComments(text))) emptyList()
        else listOf("the view takes no label as a parameter")

    /** Every line of [text] that holds a string literal of the view's own, comments removed. */
    private fun ownStringProblems(text: String): List<String> =
        withoutComments(text).lines().mapIndexedNotNull { number, line ->
            if (line.contains('"')) "${number + 1}: the view holds a string of its own" else null
        }

    /** A failure here means a screen reads a sentence the kit invented, or a view ignores the words it was given. */
    @Test
    fun `each view takes its words as a parameter and holds none of its own`() {
        LABELLED_VIEWS.forEach { name ->
            val text = viewText("$VIEW_DIRECTORY/$name.kt")
            assertTrue("$name takes no label as a parameter", LABEL_PARAM.containsMatchIn(withoutComments(text)))
        }
        val steps = viewText("$VIEW_DIRECTORY/StepMarksView.kt")
        assertTrue("StepMarksView takes no count as a parameter", COUNT_PARAM.containsMatchIn(withoutComments(steps)))
        assertFires("a view with no parameter", labelParamProblems("internal class V(context: Context) : View(context)"), "takes no label")
        assertFires("a string of its own", ownStringProblems("val label: String\nval own = \"READY\"\n"), "holds a string")
        assertQuiet("a label parameter", ownStringProblems("val label: String\n"))
        val found = VIEW_FILES.flatMap { name -> ownStringProblems(viewText(name)).map { line -> "$name:$line" } }
        assertEquals("strings held by a view: $found", emptyList<String>(), found)
    }

    // ---- The touch-target floor ----

    /** The touch floor as the plain factors spell it. */
    private val TOUCH_FACTOR = Regex("""TOUCH_MIN_DP:\s*Float\s*=\s*([0-9.]+)f""")

    /** What the touch floor is worth, or below any floor when the line is not there. */
    private fun touchFactorOf(text: String): Float =
        TOUCH_FACTOR.find(text)?.groupValues?.get(1)?.toFloatOrNull() ?: Float.NEGATIVE_INFINITY

    private fun floorProblems(text: String): List<String> =
        listOfNotNull(
            "the view never reads ControlsFactors.TOUCH_MIN_DP".takeUnless { text.contains("ControlsFactors.TOUCH_MIN_DP") },
            "the view never overrides onMeasure".takeUnless { text.contains("override fun onMeasure") },
        )

    /** A failure here means a control can be tapped below the floor, or the floor itself has moved. */
    @Test
    fun `each view's touch target stands at the touch floor`() {
        val factor = touchFactorOf(plainText("controls/ControlsFactors.kt"))
        assertTrue("controls: the touch floor expected at least 48f, found $factor", factor >= 48f)
        VIEW_FILES.forEach { name ->
            val text = viewText(name)
            if (name in DISPLAY_ONLY) return@forEach
            // Delete and full width build on the main button, so the floor and the
            // geometry reach them through the class they extend, not their own text.
            if (name in INHERITING) {
                assertTrue("$name never builds on the main button", text.contains("Button3dView("))
                return@forEach
            }
            assertTrue("$name never reads ControlsFactors.TOUCH_MIN_DP", text.contains("ControlsFactors.TOUCH_MIN_DP"))
            assertTrue("$name never overrides onMeasure", text.contains("override fun onMeasure"))
        }
        assertFires("a view with the floor missing", floorProblems("override fun onMeasure"), "never reads")
        assertFires("a view that measures nothing", floorProblems(""), "never overrides onMeasure")
        assertQuiet("a view at the floor", floorProblems("override fun onMeasure { touchTargetPx(ControlsFactors.TOUCH_MIN_DP, density) }"))
        assertEquals(
            "the kit's file set is not the one the gate read",
            (VIEW_FILES + PLAIN_FILES).toSet(),
            KIT_SOURCES.keys,
        )
    }

    // ---- The framework is named only in the view files ----

    /** A framework package named as a whole word. */
    private val FRAMEWORK_PACKAGE = Regex("""\bandroidx?\.""")

    private fun frameworkNamesIn(text: String): List<String> =
        FRAMEWORK_PACKAGE.findAll(withoutCommentsAndStrings(text)).map { it.value }.distinct().toList()

    /** A failure here means the plain-Kotlin core names the framework, so its tests can no longer run on a plain JVM, or the views are not where the framework lives. */
    @Test
    fun `the framework is named only in the view files, never in the plain ones`() {
        assertFires("an import", frameworkNamesIn("import android.view.View\n"), "android.")
        assertFires("a full name", frameworkNamesIn("val v = android.graphics.Paint()\n"), "android.")
        assertFires("a support library import", frameworkNamesIn("import androidx.core.view.ViewCompat\n"), "androidx.")
        assertQuiet("a plain-Kotlin import", frameworkNamesIn("import dev.breaker.dictation.ui.controls.ControlsGeometry\n"))
        PLAIN_FILES.forEach { name ->
            assertEquals(
                "the plain file $name names the framework",
                emptyList<String>(),
                frameworkNamesIn(plainText(name)),
            )
        }
        val named = KIT_SOURCES.filter { (_, text) -> frameworkNamesIn(text).isNotEmpty() }.keys
        assertEquals("the kit's files that name the framework", VIEW_FILES.toSet(), named)
        VIEW_FILES.forEach { name ->
            val text = viewText(name)
            if (name in INHERITING) {
                assertTrue("$name never builds on the main button", text.contains("Button3dView("))
            } else if (name in MATH_DRAWN) {
                assertTrue("$name never draws what DownloadBarMath returns", text.contains("DownloadBarMath."))
            } else {
                assertTrue("$name never draws what ControlsGeometry returns", text.contains("ControlsGeometry."))
            }
        }
    }

    private companion object {
        /** The directory the views live in, relative to this module's package. */
        const val VIEW_DIRECTORY = "render/controls"

        /** The views that carry words, as their file names. */
        val LABELLED_VIEWS = listOf(
            "Button3dView",
            "NeutralButtonView",
            "OutlinedButtonView",
            "DeleteButtonView",
            "FullWidthButtonView",
            "SwitchView",
            "BadgeView",
            "TextFieldView",
        )

        /**
         * The two views that build on [Button3dView] rather than standing alone, by their
         * path. Their touch floor and their geometry reach them through the class they
         * extend, so a rule that reads their own text would flag them for inheriting.
         */
        val INHERITING = listOf(
            "render/controls/DeleteButtonView.kt",
            "render/controls/FullWidthButtonView.kt",
        )
    }
}
