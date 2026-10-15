package dev.breaker.dictation.ui.controls

import dev.breaker.dictation.ui.gate.MAIN_SOURCES
import dev.breaker.dictation.ui.gate.withoutCommentsAndStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The switch's shape, checked on its source text the way ControlsViewGateTest checks the kit:
 * the accessibility node, the tap and the order of its steps, the value set from code, the
 * clickable flag, the thumb's corners and the outline. Each rule runs first on short made-up
 * samples and only then on the real SwitchView read from the main tree. A file that cannot be
 * found is a violation, never a pass. Comments and string literals are blanked before a rule
 * reads the code.
 */
class ControlsSwitchViewRulesTest {

    private val SWITCH = "render/controls/SwitchView.kt"

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("controls: switch: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("controls: switch: $what must not be reported", emptyList<String>(), problems)

    /** The real switch's text, read from the main tree. */
    private fun realSwitch(): String {
        val text = MAIN_SOURCES.toMap()[SWITCH]
        assertTrue("controls: switch: $SWITCH was not read from the main tree", text != null)
        return text ?: ""
    }

    /** The code of [text], with its comments and string literals blanked. */
    private fun codeOf(text: String): String = withoutCommentsAndStrings(text)

    /** One problem per shape in [shapes] that the code of [text] does not hold. */
    private fun missingProblems(text: String, shapes: List<Pair<Regex, String>>): List<String> {
        val code = codeOf(text)
        return shapes.mapNotNull { (shape, words) -> if (shape.containsMatchIn(code)) null else "missing: $words" }
    }

    /** A `performClick` whose body is [steps], closed the way the real file closes it. */
    private fun tap(vararg steps: String): String =
        (listOf("    override fun performClick(): Boolean {") + steps.map { "        $it" } + listOf("    }")).joinToString("\n")

    // ---- The node the accessibility service reads ----

    private val CLASS_NAME = Regex("""info\.className\s*=\s*Switch::class\.java\.name\b""")
    private val CHECKABLE = Regex("""info\.isCheckable\s*=\s*true\b""")
    private val CHECKED = Regex("""info\.isChecked\s*=\s*on\b""")

    private fun nodeProblems(text: String): List<String> =
        missingProblems(
            text,
            listOf(
                CLASS_NAME to "the node names the Switch class",
                CHECKABLE to "the node is checkable",
                CHECKED to "the node carries the on value",
            ),
        )

    /** A failure here means a screen reader is told the switch is something other than a checkable Switch. */
    @Test
    fun `the switch announces itself as a checkable Switch`() {
        val onSwitch = "info.className = Switch::class.java.name\ninfo.isCheckable = true\ninfo.isChecked = on\n"
        assertFires("the class of a View", nodeProblems(onSwitch.replace("Switch::class", "View::class")), "names the Switch class")
        assertFires("the class as a string", nodeProblems(onSwitch.replace("Switch::class.java.name", "\"android.widget.Switch\"")), "names the Switch class")
        assertFires("the node not checkable", nodeProblems(onSwitch.replace("isCheckable = true", "isCheckable = false")), "is checkable")
        assertFires("the node not checked", nodeProblems(onSwitch.replace("isChecked = on", "isChecked = false")), "carries the on value")
        assertQuiet("the three lines", nodeProblems(onSwitch))
        assertQuiet("the real switch", nodeProblems(realSwitch()))
    }

    // ---- The tap: flip, then super, then the listener, then the handled value ----

    private fun tapProblems(text: String): List<String> {
        val body = codeOf(text).lines()
            .dropWhile { !it.contains("override fun performClick()") }
            .drop(1)
            .takeWhile { it != "    }" }
            .map { it.trim() }
        val flip = body.indexOf("on = !on")
        val handled = body.indexOf("val handled = super.performClick()")
        val invoke = body.indexOf("onToggled?.invoke(on)")
        val returns = body.indexOf("return handled")
        val invokes = body.count { it.contains("onToggled?.invoke(") }
        return when {
            body.isEmpty() -> listOf("the tap is not overridden")
            flip < 0 -> listOf("the tap never flips the switch")
            handled < 0 -> listOf("the tap never calls super.performClick")
            invoke < 0 -> listOf("the tap never calls onToggled")
            returns < 0 -> listOf("the tap never returns the handled value")
            invokes > 1 -> listOf("the tap calls onToggled more than once")
            listOf(flip, handled, invoke, returns).zipWithNext().any { (a, b) -> a >= b } ->
                listOf("the tap runs its steps out of order")
            else -> emptyList()
        }
    }

    /** A failure here means a tap reports a value before the switch holds it, or reports one it did not make. */
    @Test
    fun `a tap flips the switch, then calls super, then reports the new value`() {
        assertFires(
            "the listener before the flip",
            tapProblems(tap("onToggled?.invoke(on)", "on = !on", "val handled = super.performClick()", "return handled")),
            "out of order",
        )
        assertFires(
            "the listener dropped",
            tapProblems(tap("on = !on", "val handled = super.performClick()", "return handled")),
            "never calls onToggled",
        )
        assertFires(
            "the flip turned into a no-op",
            tapProblems(tap("on = on", "val handled = super.performClick()", "onToggled?.invoke(on)", "return handled")),
            "never flips",
        )
        assertFires(
            "the flip dropped",
            tapProblems(tap("val handled = super.performClick()", "onToggled?.invoke(on)", "return handled")),
            "never flips",
        )
        assertFires(
            "the handled value replaced",
            tapProblems(tap("on = !on", "val handled = super.performClick()", "onToggled?.invoke(on)", "return true")),
            "never returns the handled value",
        )
        assertFires(
            "the listener twice",
            tapProblems(tap("on = !on", "val handled = super.performClick()", "onToggled?.invoke(on)", "onToggled?.invoke(on)", "return handled")),
            "more than once",
        )
        assertQuiet(
            "the steps in order",
            tapProblems(tap("on = !on", "val handled = super.performClick()", "onToggled?.invoke(on)", "return handled")),
        )
        assertQuiet("the real switch", tapProblems(realSwitch()))
    }

    // ---- The value set from code does not call the listener ----

    private fun setterProblems(text: String): List<String> {
        val lines = codeOf(text).lines()
        val start = lines.indexOfFirst { it.contains("set(value) {") }
        if (start < 0) return listOf("the on setter is missing")
        val body = lines.drop(start + 1).takeWhile { it != "        }" }
        return if (body.any { it.contains("onToggled") }) listOf("the on setter mentions onToggled") else emptyList()
    }

    /** A failure here means setting the switch from code calls the listener, so a screen hears a tap that never happened. */
    @Test
    fun `setting the switch from code does not report a tap`() {
        val setter = "    var on: Boolean = false\n" +
            "        set(value) {\n" +
            "            if (field == value) return\n" +
            "            field = value\n" +
            "            invalidate()\n" +
            "        }\n"
        assertFires(
            "the listener called in the setter",
            setterProblems(setter.replace("            invalidate()", "            onToggled?.invoke(value)\n            invalidate()")),
            "mentions onToggled",
        )
        assertFires(
            "the listener read in the setter",
            setterProblems(setter.replace("            invalidate()", "            val call = onToggled\n            invalidate()")),
            "mentions onToggled",
        )
        assertFires("the setter removed", setterProblems("    var on: Boolean = false\n"), "setter is missing")
        assertQuiet("the setter as written", setterProblems(setter))
        assertQuiet("the real switch", setterProblems(realSwitch()))
    }

    // ---- Clickable from the init block ----

    private val CLICKABLE_IN_INIT = Regex("""init\s*\{[^{}]*isClickable\s*=\s*true\b[^{}]*\}""")

    private fun clickableProblems(text: String): List<String> =
        if (CLICKABLE_IN_INIT.containsMatchIn(codeOf(text))) emptyList()
        else listOf("the switch is not clickable from its init")

    /** A failure here means a tap on the switch cannot reach its click handler, so the switch never flips. */
    @Test
    fun `the switch is clickable from its init`() {
        val init = "    init {\n        contentDescription = label\n        isClickable = true\n    }\n"
        assertFires("no clickable line", clickableProblems(init.replace("        isClickable = true\n", "")), "not clickable")
        assertFires("clickable turned off", clickableProblems(init.replace("isClickable = true", "isClickable = false")), "not clickable")
        assertFires("clickable set outside the init", clickableProblems("    fun setup() {\n        isClickable = true\n    }\n"), "not clickable")
        assertQuiet("clickable in the init", clickableProblems(init))
        assertQuiet("the real switch", clickableProblems(realSwitch()))
    }

    // ---- The thumb's corners come from the thumb constant ----

    private val THUMB_CORNERS = Regex(
        """parts\.thumb\.y \+ parts\.thumb\.height,\s*ControlsGeometry\.THUMB_CORNER_DP,\s*ControlsGeometry\.THUMB_CORNER_DP,""",
    )

    private fun thumbProblems(text: String): List<String> =
        if (THUMB_CORNERS.containsMatchIn(codeOf(text))) emptyList()
        else listOf("the thumb is not drawn with THUMB_CORNER_DP for both radii")

    /** A failure here means the thumb's corners follow the body's radius, not the art's own thumb radius. */
    @Test
    fun `the thumb is drawn with the thumb corner for both radii`() {
        val thumb = "                parts.thumb.y + parts.thumb.height,\n" +
            "                ControlsGeometry.THUMB_CORNER_DP,\n" +
            "                ControlsGeometry.THUMB_CORNER_DP,\n"
        assertFires("both radii from the body", thumbProblems(thumb.replace("ControlsGeometry.THUMB_CORNER_DP,", "corner,")), "THUMB_CORNER_DP")
        assertFires("one radius a number", thumbProblems(thumb.replaceFirst("ControlsGeometry.THUMB_CORNER_DP,", "4f,")), "THUMB_CORNER_DP")
        assertQuiet("both radii from the thumb constant", thumbProblems(thumb))
        assertQuiet("the real switch", thumbProblems(realSwitch()))
    }

    // ---- The outline comes from the parts, never from the state ----

    private val OUTLINE_FROM_PARTS = Regex("""val outline = parts\.outline[ \t]*$""", RegexOption.MULTILINE)
    private val OUTLINE_ON_STATE = Regex("""if\s*\(!?on\)\s*\{""")
    /** Reads the `if` that directly follows the outline line: a lambda in its condition is read on one line, a condition split over lines up to the first brace. */
    private val OUTLINE_IF = Regex("""val outline = parts\.outline[ \t]*\n\s*if\s*\(([^{]*)\)\s*\{""")
    /** The same `if`, read to the end of its line, so a lambda in its condition is read too. */
    private val OUTLINE_IF_LINE = Regex("""val outline = parts\.outline[ \t]*\n\s*if\s*\(([^\n]*)\)\s*\{""")
    private val ON_WORD = Regex("""\bon\b""")

    private fun outlineProblems(text: String): List<String> {
        val code = codeOf(text)
        val conditions = listOf(OUTLINE_IF, OUTLINE_IF_LINE).mapNotNull { rule -> rule.find(code)?.groupValues?.get(1) }
        return listOfNotNull(
            "the outline is not taken from parts.outline".takeUnless { OUTLINE_FROM_PARTS.containsMatchIn(code) },
            "the outline is drawn under a test of the on state".takeIf {
                OUTLINE_ON_STATE.containsMatchIn(code) || conditions.any { ON_WORD.containsMatchIn(it) }
            },
        )
    }

    /** A failure here means the outline shows in a state the geometry did not give it, so the art's own on and off shapes stop deciding it. */
    @Test
    fun `the outline is drawn from the parts and not from the state`() {
        val outline = "            val outline = parts.outline\n            if (outline != null) {\n"
        assertFires("the state tests the outline", outlineProblems(outline.replace("if (outline != null)", "if (!on)")), "on state")
        assertFires("the state tests the outline when on", outlineProblems(outline.replace("if (outline != null)", "if (on)")), "on state")
        assertFires("the on state added to the outline test", outlineProblems(outline.replace("if (outline != null)", "if (outline != null && on)")), "on state")
        assertFires("the off state added to the outline test", outlineProblems(outline.replace("if (outline != null)", "if (outline != null && !on)")), "on state")
        assertFires("the on state in a nested condition", outlineProblems(outline.replace("if (outline != null)", "if ((outline != null) && on)")), "on state")
        assertFires("the on state on a second line of the condition", outlineProblems(outline.replace("if (outline != null)", "if (outline != null\n                && on)")), "on state")
        assertFires("the on state in a lambda condition", outlineProblems(outline.replace("if (outline != null)", "if (outline != null && outline.let { on })")), "on state")
        assertFires("the outline not from the parts", outlineProblems(outline.replace("val outline = parts.outline", "val outline = null")), "parts.outline")
        assertFires("the outline taken through takeIf", outlineProblems(outline.replace("val outline = parts.outline", "val outline = parts.outline.takeIf { false }")), "parts.outline")
        assertFires("the outline taken through a default", outlineProblems(outline.replace("val outline = parts.outline", "val outline = parts.outline ?: null")), "parts.outline")
        assertQuiet("the outline from the parts", outlineProblems(outline))
        assertQuiet("the real switch", outlineProblems(realSwitch()))
    }
}
