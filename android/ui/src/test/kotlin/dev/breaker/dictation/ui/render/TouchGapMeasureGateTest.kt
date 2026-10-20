package dev.breaker.dictation.ui.render

import dev.breaker.dictation.ui.gate.mainSourceOf
import dev.breaker.dictation.ui.gate.withoutCommentsAndStrings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Gate for the touch gap's measure. A plain View answers "at most N" with N, so a gap with no measure
 * of its own grows to the whole offered height when a scroll view that fills its window measures the
 * page a second time: the top bar becomes as tall as the window and every row after it is pushed off
 * screen, which is a blank Home on a tall phone. The gap must measure itself from its own side, the
 * way the dot does: resolveSize(side, spec) on both axes, so an "at most" offer gives the side and
 * only an exact offer gives more. The rule reads the code of the real view, comments and strings
 * blanked, and is shown to fail when the measure is removed or falls back to the default size.
 */
internal class TouchGapMeasureGateTest {
    private val views: String = mainSourceOf("render/NodeViews.kt")

    /** [text] with comments and string literals removed and whitespace folded away. */
    private fun flat(text: String): String = withoutCommentsAndStrings(text).replace(Regex("""\s+"""), "")

    /** The body of the TouchGapView class, from its header to the next top-level declaration. */
    private fun gapBody(source: String): String {
        val start = source.indexOf("internal class TouchGapView")
        assertTrue("ui: the TouchGapView class is not in NodeViews.kt", start >= 0)
        val next = source.indexOf("\n}\n", start)
        assertTrue("ui: the end of TouchGapView was not found", next > start)
        return flat(source.substring(start, next))
    }

    private val measure = flat("override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) { setMeasuredDimension(resolveSize(side, widthMeasureSpec), resolveSize(side, heightMeasureSpec)) }")

    /** Replace the first text with [new]; fail by name unless it occurs exactly once in [text]. */
    private fun replaceOnce(text: String, old: String, new: String): String {
        assertTrue(
            "ui: the text to change is not in the source exactly once: $old",
            Regex(Regex.escape(old)).findAll(text).count() == 1,
        )
        return text.replace(old, new)
    }

    @Test fun `the touch gap measures itself from its own side on both axes`() {
        assertTrue("ui: TouchGapView must measure itself with resolveSize(side, spec) on both axes", gapBody(views).contains(measure))
    }

    /** The measure call as it stands in the gap, with the gap's own comment line before it: the dot has the same call, so the text alone is not unique. */
    private val anchor: String =
        "it would grow to the window and push the page off screen.\n" +
            "    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {\n" +
            "        setMeasuredDimension(resolveSize(side, widthMeasureSpec), resolveSize(side, heightMeasureSpec))"
    private val head: String = anchor.substringBefore("setMeasuredDimension")

    @Test fun `a touch gap with no measure of its own is reported`() {
        val gone = replaceOnce(views, anchor, head)
        assertFalse("ui: removing the gap's measure must be seen", gapBody(gone).contains(measure))
    }

    @Test fun `a touch gap that falls back to the default size is reported`() {
        val fallback = replaceOnce(
            views,
            anchor,
            head + "setMeasuredDimension(getDefaultSize(side, widthMeasureSpec), getDefaultSize(side, heightMeasureSpec))",
        )
        assertFalse("ui: a default-size measure must be seen", gapBody(fallback).contains(measure))
    }
}
