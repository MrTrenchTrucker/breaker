package dev.breaker.dictation.ui.render

import dev.breaker.dictation.ui.gate.mainSourceOf
import dev.breaker.dictation.ui.gate.withoutComments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-text rules for the touch gap that balances the top bar. Each rule reads one part of the
 * renderer or of the views, with the comments taken out and the spacing collapsed. Each rule is
 * quiet on the real text and fires on a sample made from the real text.
 */
class TouchGapRulesTest {
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

    private fun dispatchProblems(text: String): List<String> {
        val code = flat(text)
        return listOfNotNull(
            "the touch gap is drawn by its own view, in the dispatch".takeUnless { "is TouchGap -> touchGapOf(theme)" in code },
        )
    }

    private fun gapBuildProblems(text: String): List<String> {
        val body = flat(between(text, "private fun touchGapOf(", "private fun "))
        return listOfNotNull(
            "the gap is a TouchGapView at the touch-target size".takeUnless {
                "TouchGapView(context, touchTargetPx(theme.metrics.minTouchTargetDp.toFloat(), context.resources.displayMetrics.density)) .apply { layoutParams = wrapParams() }" in body
            },
        )
    }

    private fun wrapProblems(text: String): List<String> {
        val body = flat(between(text, "private fun wrapsInRow(", "/**"))
        return listOfNotNull(
            "the touch gap keeps its own width in a horizontal row".takeUnless { "node is Dot || node is TouchGap ||" in body },
        )
    }

    private fun viewProblems(text: String): List<String> {
        val body = flat(between(text, "internal class TouchGapView(", null))
        return listOfNotNull(
            "the gap view takes no focus".takeUnless { "isFocusable = false" in body },
            "the gap view is announced to no one".takeUnless { "importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO" in body },
            "the gap view holds its side as its minimum width".takeUnless { "minimumWidth = side" in body },
            "the gap view holds its side as its minimum height".takeUnless { "minimumHeight = side" in body },
        )
    }

    private fun assertFires(what: String, problems: List<String>) =
        assertTrue("render: touch gap: $what must be reported, got $problems", problems.isNotEmpty())

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("render: touch gap: $what must not be reported", emptyList<String>(), problems)

    @Test
    fun `the touch gap is drawn by its own view, dispatched in the view tree`() {
        assertQuiet("the real renderer", dispatchProblems(renderer))
        assertFires("the dispatch line left out", dispatchProblems(renderer.replace("is TouchGap -> touchGapOf(theme)", "")))
        assertFires("the dispatch line commented out", dispatchProblems(renderer.replace("is TouchGap -> touchGapOf(theme)", "// is TouchGap -> touchGapOf(theme)")))
    }

    @Test
    fun `the gap view is sized to the touch target, as the menu icon is`() {
        assertQuiet("the real renderer", gapBuildProblems(renderer))
        assertFires(
            "the gap given a fixed size instead of the touch target",
            gapBuildProblems(
                renderer.replace(
                    "TouchGapView(context, touchTargetPx(theme.metrics.minTouchTargetDp.toFloat(), context.resources.displayMetrics.density))",
                    "TouchGapView(context, 48)",
                ),
            ),
        )
    }

    @Test
    fun `the gap keeps its own width in a horizontal row`() {
        assertQuiet("the real renderer", wrapProblems(renderer))
        assertFires("the gap dropped from the wrap set", wrapProblems(renderer.replace("node is TouchGap || ", "")))
    }

    @Test
    fun `the gap view takes no focus, is announced to no one, and holds its side`() {
        assertQuiet("the real view", viewProblems(nodeViews))
        assertFires(
            "the gap view takes focus",
            viewProblems(nodeViews.replace("isFocusable = false\n        importantForAccessibility", "isFocusable = true\n        importantForAccessibility")),
        )
        assertFires(
            "the gap view is announced",
            viewProblems(nodeViews.replace("importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO\n        minimumWidth", "importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO\n        minimumWidth")),
        )
        assertFires("the gap view loses its width", viewProblems(nodeViews.replace("minimumWidth = side", "minimumWidth = 0")))
        assertFires("the gap view loses its height", viewProblems(nodeViews.replace("minimumHeight = side", "minimumHeight = 0")))
    }
}
