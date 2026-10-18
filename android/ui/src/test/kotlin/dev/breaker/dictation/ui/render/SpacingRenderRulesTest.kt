package dev.breaker.dictation.ui.render

import dev.breaker.dictation.ui.gate.mainSourceOf
import dev.breaker.dictation.ui.gate.withoutComments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-text rules for the spacing the renderer applies. Each rule reads one function of a file,
 * with the comments taken out and the spacing of the text collapsed, and reports what the function
 * lacks. Each rule is quiet on the real text and fires on samples made from the real text.
 */
class SpacingRenderRulesTest {
    private val renderer = mainSourceOf("render/ScreenRenderer.kt")
    private val scale = mainSourceOf("render/SpaceScale.kt")

    private val whitespace = Regex("""\s+""")

    /** The text from [start] up to [end], or to the end of [text] when [end] is null; empty when [start] is missing. */
    private fun between(text: String, start: String, end: String?): String {
        val after = text.substringAfter(start, "")
        return if (end == null) after else after.substringBefore(end, after)
    }

    /** [text] with its comments taken out, then every run of whitespace, line breaks included, made into one space. */
    private fun flat(text: String): String = withoutComments(text).replace(whitespace, " ")

    private fun viewOfProblems(text: String): List<String> {
        val body = flat(between(text, "private fun viewOf(", "private fun boxOf("))
        return listOfNotNull(
            "the spacer node is drawn by its own view".takeUnless { "is Spacer -> spacerOf(node, theme)" in body },
        )
    }

    private fun boxProblems(text: String): List<String> {
        val body = flat(between(text, "private fun boxOf(", "private fun placedChild("))
        return listOfNotNull(
            "boxOf pads every side by the scale".takeUnless {
                "val side = pixelsOf(theme, node.pad) setPadding(side, side, side, side)" in body
            },
            "boxOf places every child through placedChild with its index".takeUnless {
                "node.children.forEachIndexed { index, child -> addView(placedChild(child, index, node, theme, onIntent)) if (index < spreadSeparatorCount(node.children.size) && node.horizontal && node.spread) addView(separatorOf()) }" in body
            },
        )
    }

    private fun placedChildProblems(text: String): List<String> {
        val body = flat(between(text, "private fun placedChild(", "private fun pixelsOf("))
        return listOfNotNull(
            "the child is laid out by childOf before its gap is set".takeUnless {
                val laidOut = body.indexOf("val view = childOf(child, box.horizontal, theme, onIntent)")
                val gapped = body.indexOf("params.setMarginStart(step)")
                laidOut >= 0 && gapped > laidOut
            },
            "a spacer in a row takes its size as a width".takeUnless {
                "if (child is Spacer && box.horizontal) { view.layoutParams = LinearLayout.LayoutParams(pixelsOf(theme, child.size), ViewGroup.LayoutParams.WRAP_CONTENT) }" in body
            },
            "every child after the first is pushed by the gap in its own direction".takeUnless {
                "if (params != null && index > 0 && !(box.horizontal && box.spread)) { val step = pixelsOf(theme, box.gap) if (box.horizontal) { params.setMarginStart(step) } else { params.topMargin = step } }" in body
            },
        )
    }

    private fun pixelsProblems(text: String): List<String> {
        val body = flat(between(text, "private fun pixelsOf(", "private fun spacerOf("))
        return listOfNotNull(
            "a size is the scale in dp times the density".takeUnless {
                "(spaceDp(theme.metrics, space) * context.resources.displayMetrics.density).toInt()" in body
            },
        )
    }

    private fun spacerProblems(text: String): List<String> {
        val body = flat(between(text, "private fun spacerOf(", "private fun labelOf("))
        return listOfNotNull(
            "the spacer is as tall as its size and announced to no one".takeUnless {
                "importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, pixelsOf(theme, node.size))" in body
            },
        )
    }

    private fun scaleProblems(text: String): List<String> {
        val body = flat(text)
        return listOfNotNull(
            "each name reads its own token".takeUnless {
                listOf(
                    "Space.XS -> metrics.spaceXsDp",
                    "Space.S -> metrics.spaceSDp",
                    "Space.M -> metrics.spaceMDp",
                    "Space.L -> metrics.spaceLDp",
                    "Space.XL -> metrics.spaceXlDp",
                ).all { it in body }
            },
            "none is no distance".takeUnless { "Space.NONE -> 0" in body },
        )
    }

    private fun assertFires(what: String, problems: List<String>) =
        assertTrue("render: spacing: $what must be reported, got $problems", problems.isNotEmpty())

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("render: spacing: $what must not be reported", emptyList<String>(), problems)

    @Test
    fun `the spacer node is drawn by its own view`() {
        assertQuiet("the real renderer", viewOfProblems(renderer))
        assertFires("the spacer left out of the dispatch", viewOfProblems(renderer.replace("is Spacer -> spacerOf(node, theme)", "")))
    }

    @Test
    fun `boxOf pads every side by the scale and places every child through the gap`() {
        assertQuiet("the real renderer", boxProblems(renderer))
        assertFires("the pad replaced by no distance", boxProblems(renderer.replace("val side = pixelsOf(theme, node.pad)", "val side = 0")))
        assertFires(
            "the children placed without the index",
            boxProblems(renderer.replace("node.children.forEachIndexed { index, child ->", "node.children.forEach { child ->")),
        )
        assertFires("the padding line commented out", boxProblems(renderer.replace("setPadding(side, side, side, side)", "// setPadding(side, side, side, side)")))
    }

    @Test
    fun `the placement loop of boxOf is pinned to its closing brace`() {
        assertQuiet("the real renderer", boxProblems(renderer))
        assertFires("a statement after the placement on its line", boxProblems(renderer.replace("addView(placedChild(child, index, node, theme, onIntent))", "addView(placedChild(child, index, node, theme, onIntent)); Unit")))
        assertFires("a statement after the separator line", boxProblems(renderer.replace("addView(separatorOf())", "addView(separatorOf()); Unit")))
        assertQuiet("a comment after the separator line", boxProblems(renderer.replace("addView(separatorOf())", "addView(separatorOf()) // the loop closes below")))
    }

    @Test
    fun `a gap is set after the child is laid out, in the box's direction, and a spacer takes a width in a row`() {
        assertQuiet("the real renderer", placedChildProblems(renderer))
        assertFires("the horizontal gap dropped", placedChildProblems(renderer.replace("params.setMarginStart(step)", "Unit")))
        assertFires("the child not laid out by childOf", placedChildProblems(renderer.replace("val view = childOf(child, box.horizontal, theme, onIntent)", "val view = View(context)")))
        assertFires("the spacer width not set in a row", placedChildProblems(renderer.replace("if (child is Spacer && box.horizontal)", "if (child is Spacer)")))
    }

    @Test
    fun `a size is the scale in dp times the density and the spacer is sized by it`() {
        assertQuiet("the real renderer", pixelsProblems(renderer) + spacerProblems(renderer))
        assertFires("the density left out", pixelsProblems(renderer.replace("spaceDp(theme.metrics, space) * context", "spaceDp(theme.metrics, space) + context")))
        assertFires(
            "the spacer sized by no distance",
            spacerProblems(renderer.replace("layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, pixelsOf(theme, node.size))", "layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0)")),
        )
    }

    @Test
    fun `the scale reads each name from its own token and gives none no distance`() {
        assertQuiet("the real scale", scaleProblems(scale))
        assertFires("the xs name reads the s token", scaleProblems(scale.replace("Space.XS -> metrics.spaceXsDp", "Space.XS -> metrics.spaceSDp")))
        assertFires("none gives a distance", scaleProblems(scale.replace("Space.NONE -> 0", "Space.NONE -> 12")))
    }
}
