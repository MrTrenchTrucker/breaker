package dev.breaker.dictation.ui.render

import dev.breaker.dictation.ui.gate.mainSourceOf
import dev.breaker.dictation.ui.gate.withoutComments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-text rules for the spread row and the dot. Each rule reads one function of a file, with the
 * comments taken out and the spacing collapsed, and reports what the function lacks. Each rule is quiet
 * on the real text and fires on two samples made from the real text.
 */
class SpreadRenderRulesTest {
    private val renderer = mainSourceOf("render/ScreenRenderer.kt")
    private val nodeViews = mainSourceOf("render/NodeViews.kt")

    private val whitespace = Regex("""\s+""")

    /** The text from [start] up to [end], or to the end of [text] when [end] is null; empty when [start] is missing. */
    private fun between(text: String, start: String, end: String?): String {
        val after = text.substringAfter(start, "")
        return if (end == null) after else after.substringBefore(end, after)
    }

    /** [text] with its comments taken out, then every run of whitespace, line breaks included, made into one space. */
    private fun flat(text: String): String = withoutComments(text).replace(whitespace, " ")

    private fun placedProblems(text: String): List<String> {
        val body = flat(between(text, "private fun placedChild(", "private fun pixelsOf("))
        return listOfNotNull(
            "the child is laid out by childOf before a spread row gives it its own width".takeUnless {
                val laidOut = body.indexOf("val view = childOf(child, box.horizontal, theme, onIntent)")
                val wrapped = body.indexOf("} else if (box.horizontal && box.spread) { view.layoutParams = wrapParams() }")
                laidOut >= 0 && wrapped > laidOut
            },
            "every child of a spread row keeps its own width".takeUnless {
                "} else if (box.horizontal && box.spread) { view.layoutParams = wrapParams() }" in body
            },
            "a spread row sets no gap, and a vertical box still sets one".takeUnless {
                "if (params != null && index > 0 && !(box.horizontal && box.spread))" in body
            },
        )
    }

    private fun boxProblems(text: String): List<String> {
        val body = flat(between(text, "private fun boxOf(", "private fun placedChild("))
        return listOfNotNull(
            "a separator follows each child of a spread row but the last, and only in a spread row".takeUnless {
                "if (index < spreadSeparatorCount(node.children.size) && node.horizontal && node.spread) addView(separatorOf())" in body
            },
        )
    }

    private fun separatorProblems(text: String): List<String> {
        val body = flat(between(text, "private fun separatorOf(", "private fun dotOf("))
        return listOfNotNull(
            "the separator takes the free width with weight one".takeUnless {
                "layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)" in body
            },
            "the separator is announced to no one".takeUnless {
                "importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO" in body
            },
        )
    }

    private fun viewOfProblems(text: String): List<String> {
        val body = flat(between(text, "private fun viewOf(", "private fun boxOf("))
        return listOfNotNull(
            "the dot is drawn by its own view".takeUnless { "is Dot -> dotOf(node, theme)" in body },
        )
    }

    private fun dotProblems(text: String): List<String> {
        val body = flat(between(text, "private fun dotOf(", "private fun labelOf("))
        return listOfNotNull(
            "the dot is one step S of the scale, read through pixelsOf".takeUnless {
                "val side = pixelsOf(theme, Space.S)" in body
            },
            "the dot takes its colour from its slot through the theme".takeUnless {
                "DotView(context, theme.argbOf(node.color), side)" in body
            },
        )
    }

    private fun dotViewProblems(text: String): List<String> {
        val body = flat(between(text, "internal class DotView(", "internal class BackArrowView("))
        return listOfNotNull(
            "the dot is not focusable and sets no other focusable".takeUnless {
                "isFocusable = false" in body && body.split("ocusable").size == 2
            },
            "the dot carries no content description".takeUnless {
                !body.contains("contentdescription", ignoreCase = true)
            },
        )
    }

    private fun wrapProblems(text: String): List<String> {
        val body = flat(between(text, "private fun wrapsInRow(", "/**"))
        return listOfNotNull(
            "a dot keeps its own width in a horizontal row".takeUnless { "node is Rule || node is Dot ||" in body },
        )
    }

    private fun assertFires(what: String, problems: List<String>) =
        assertTrue("render: spread: $what must be reported, got $problems", problems.isNotEmpty())

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("render: spread: $what must not be reported", emptyList<String>(), problems)

    @Test
    fun `the child is laid out by childOf before a spread row gives it its own width`() {
        assertQuiet("the real renderer", placedProblems(renderer))
        assertFires("the child width set without childOf", placedProblems(renderer.replace("val view = childOf(child, box.horizontal, theme, onIntent)", "val view = View(context)")))
        assertFires("the spread width line removed", placedProblems(renderer.replace("view.layoutParams = wrapParams()", "Unit")))
    }

    @Test
    fun `every child of a spread row keeps its own width`() {
        assertQuiet("the real renderer", placedProblems(renderer))
        assertFires("the spread test removed from the width branch", placedProblems(renderer.replace("} else if (box.horizontal && box.spread) {", "} else if (box.horizontal && box.spread && false) {")))
        assertFires("the spread width line removed", placedProblems(renderer.replace("view.layoutParams = wrapParams()", "Unit")))
    }

    @Test
    fun `a spread row sets no gap, and a vertical box still sets one`() {
        assertQuiet("the real renderer", placedProblems(renderer))
        assertFires("the gap set in a spread row too", placedProblems(renderer.replace("if (params != null && index > 0 && !(box.horizontal && box.spread))", "if (params != null && index > 0)")))
        assertFires("the spread test dropped from the gap", placedProblems(renderer.replace("!(box.horizontal && box.spread)) {", "!(box.spread)) {")))
    }

    @Test
    fun `a separator follows each child of a spread row but the last, and only in a spread row`() {
        assertQuiet("the real renderer", boxProblems(renderer))
        assertFires("the separator in every row", boxProblems(renderer.replace("&& node.horizontal && node.spread) addView(separatorOf())", ") addView(separatorOf())")))
        assertFires("the separator replaced by a plain view", boxProblems(renderer.replace("addView(separatorOf())", "addView(View(context))")))
    }

    @Test
    fun `the separator takes the free width with weight one and is announced to no one`() {
        assertQuiet("the real renderer", separatorProblems(renderer))
        assertFires("the separator without weight", separatorProblems(renderer.replace("layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)", "layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT)")))
        assertFires("the separator announced", separatorProblems(renderer.replace("importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO", "importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO")))
    }

    @Test
    fun `the dot is drawn by its own view`() {
        assertQuiet("the real renderer", viewOfProblems(renderer))
        assertFires("the dot left out of the dispatch", viewOfProblems(renderer.replace("is Dot -> dotOf(node, theme)", "")))
        assertFires("the dot drawn by a plain view", viewOfProblems(renderer.replace("is Dot -> dotOf(node, theme)", "is Dot -> View(context)")))
    }

    @Test
    fun `the dot is one step S of the scale through pixelsOf and takes its colour from the theme`() {
        assertQuiet("the real renderer", dotProblems(renderer))
        assertFires("the dot one step M", dotProblems(renderer.replace("val side = pixelsOf(theme, Space.S)", "val side = pixelsOf(theme, Space.M)")))
        assertFires("the dot sized from the token directly", dotProblems(renderer.replace("val side = pixelsOf(theme, Space.S)", "val side = theme.metrics.spaceSDp")))
    }

    @Test
    fun `the dot view is not focusable and carries no content description`() {
        assertQuiet("the real dot view", dotViewProblems(nodeViews))
        assertFires("the dot focusable", dotViewProblems(nodeViews.replace("isFocusable = false", "isFocusable = true")))
        assertFires("the dot with a description", dotViewProblems(nodeViews.replace("setBackgroundColor(colour)", "setBackgroundColor(colour)\n        contentDescription = \"dot\"")))
        assertFires("the dot focusable as well as not", dotViewProblems(nodeViews.replace("isFocusable = false", "isFocusable = false\n        setFocusable(true)")))
        assertFires("the dot described through the setter", dotViewProblems(nodeViews.replace("setBackgroundColor(colour)", "setBackgroundColor(colour)\n        setContentDescription(\"dot\")")))
        assertQuiet("a comment naming the setting and the setter", dotViewProblems(nodeViews.replace("setBackgroundColor(colour)", "setBackgroundColor(colour) // setFocusable(true) and setContentDescription(\"dot\")")))
    }

    @Test
    fun `a dot keeps its own width in a horizontal row`() {
        assertQuiet("the real renderer", wrapProblems(renderer))
        assertFires("the dot removed from the wrap set", wrapProblems(renderer.replace("node is Dot ||", "")))
        assertFires("the dot replaced by a box in the wrap set", wrapProblems(renderer.replace("node is Dot", "node is Box")))
    }
}
