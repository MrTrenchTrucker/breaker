package dev.breaker.dictation.ui.render

import dev.breaker.dictation.ui.gate.mainSourceOf
import dev.breaker.dictation.ui.gate.withoutComments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-text rules for the full-width layers. The rule and the stripes measure to the bleed width
 * and move by the bleed shift; the stripes move down by the host's bottom padding; each layer
 * unclips the boxes between it and the scroll view; the scroll host opens its padding for them; the
 * stripes alternate their colours by index. Each rule reads comment-stripped code, is quiet on the
 * real text and fires on samples. A call written in two views is sampled at each site alone as well
 * as at both sites together.
 */
class FoundationBleedRulesTest {
    private val bleed = mainSourceOf("render/BleedViews.kt")

    /** The text from [start] up to [end], or to the end of [text] when [end] is null; empty when [start] is missing. */
    private fun between(text: String, start: String, end: String?): String {
        val after = text.substringAfter(start, "")
        return if (end == null) after else after.substringBefore(end, after)
    }

    private val whitespace = Regex("""\s+""")

    /** [text] with its comments taken out, then every run of whitespace made into one space. */
    private fun flat(text: String): String = withoutComments(text).replace(whitespace, " ")

    /** [text] with the second occurrence of [part] replaced by [with]. */
    private fun replaceSecond(text: String, part: String, with: String): String {
        val first = text.indexOf(part)
        val second = text.indexOf(part, first + part.length)
        return text.substring(0, second) + with + text.substring(second + part.length)
    }

    private fun bleedProblems(text: String): List<String> {
        val rule = flat(between(text, "internal class RuleView(", "internal class BottomStripesView("))
        val stripes = flat(between(text, "internal class BottomStripesView(", "internal fun ScrollView.openForBleed()"))
        return listOf(
            "bleedWidth(parentWidth, host.paddingLeft, host.paddingRight)",
            "bleedShift(host.paddingLeft).toFloat()",
        ).flatMap { call ->
            listOfNotNull(
                "the rule lacks $call".takeUnless { call in rule },
                "the stripes lack $call".takeUnless { call in stripes },
            )
        }
    }

    private fun openProblems(text: String): List<String> {
        val body = flat(between(text, "internal fun ScrollView.openForBleed()", "private fun View.enclosingScroll("))
        return listOf("clipToPadding = false", "isFillViewport = true", "column?.clipChildren = false")
            .filterNot { it in body }
            .map { "openForBleed lacks $it" }
    }

    private fun stripeProblems(text: String): List<String> {
        val stripes = flat(between(text, "internal class BottomStripesView(", "internal fun ScrollView.openForBleed()"))
        return listOf(
            "paint.color = if (plain) theme.color(plainStripeSlot(index)).argb else if (isPrimaryStripe(index)) primary else soft",
            "val soft = softStripeColor(primary, theme.mode == ThemeMode.DARK)",
        ).filterNot { it in stripes }.map { "the bottom stripes lack $it" }
    }

    private fun bottomProblems(text: String): List<String> {
        val stripes = flat(between(text, "internal class BottomStripesView(", "internal fun ScrollView.openForBleed()"))
        return listOfNotNull(
            "the stripes do not move down by the host's bottom padding"
                .takeUnless { "translationY = if (host == null) 0f else host.paddingBottom.toFloat()" in stripes },
            "the stripes do not measure the art height alone"
                .takeUnless { "setMeasuredDimension(width, resolveSize(artHeightPx(), heightMeasureSpec))" in stripes },
        )
    }

    private fun unclipProblems(text: String): List<String> {
        val rule = flat(between(text, "internal class RuleView(", "internal class BottomStripesView("))
        val stripes = flat(between(text, "internal class BottomStripesView(", "internal fun ScrollView.openForBleed()"))
        val body = flat(between(text, "private fun View.unclipAncestors()", null))
        return listOfNotNull(
            "the rule does not unclip its ancestors on attach"
                .takeUnless { "override fun onAttachedToWindow() { super.onAttachedToWindow() unclipAncestors() }" in rule },
            "the stripes do not unclip their ancestors on attach"
                .takeUnless { "override fun onAttachedToWindow() { super.onAttachedToWindow() unclipAncestors() }" in stripes },
            "the ancestors are not walked up to the scroll child"
                .takeUnless { "if (current === target) chain.forEach { it.clipChildren = false }" in body },
        )
    }

    private fun assertFires(what: String, problems: List<String>) =
        assertTrue("render: bleed: $what must be reported, got $problems", problems.isNotEmpty())

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("render: bleed: $what must not be reported", emptyList<String>(), problems)

    @Test
    fun `the rule and the stripes measure to the bleed width and move by the bleed shift`() {
        assertQuiet("the real views", bleedProblems(bleed))
        assertFires(
            "a layer measured to the column only",
            bleedProblems(bleed.replace("bleedWidth(parentWidth, host.paddingLeft, host.paddingRight)", "parentWidth")),
        )
        assertFires(
            "a layer not moved left",
            bleedProblems(bleed.replace("bleedShift(host.paddingLeft).toFloat()", "0f")),
        )
    }

    @Test
    fun `the bleed width and shift are each checked at the rule and at the stripes, one site at a time`() {
        val width = "bleedWidth(parentWidth, host.paddingLeft, host.paddingRight)"
        val shift = "bleedShift(host.paddingLeft).toFloat()"
        assertFires("the rule measured to the column only", bleedProblems(bleed.replaceFirst(width, "parentWidth")))
        assertFires("the stripes measured to the column only", bleedProblems(replaceSecond(bleed, width, "parentWidth")))
        assertFires("the rule not moved left", bleedProblems(bleed.replaceFirst(shift, "0f")))
        assertFires("the stripes not moved left", bleedProblems(replaceSecond(bleed, shift, "0f")))
    }

    @Test
    fun `openForBleed opens the padding, fills the viewport and lets the column draw past its edge`() {
        assertQuiet("the real function", openProblems(bleed))
        assertFires("the padding clipping", openProblems(bleed.replace("clipToPadding = false", "clipToPadding = true")))
        assertFires("the column clipping", openProblems(bleed.replace("column?.clipChildren = false", "column?.clipChildren = true")))
        assertFires("the viewport not filled", openProblems(bleed.replace("isFillViewport = true", "isFillViewport = false")))
    }

    @Test
    fun `the bottom stripes take the primary colour on the even stripes and the soft colour on the odd`() {
        assertQuiet("the real stripes", stripeProblems(bleed))
        assertFires(
            "the colours swapped",
            stripeProblems(bleed.replace("paint.color = if (plain) theme.color(plainStripeSlot(index)).argb else if (isPrimaryStripe(index)) primary else soft", "paint.color = if (plain) theme.color(plainStripeSlot(index)).argb else if (isPrimaryStripe(index)) soft else primary")),
        )
        assertFires(
            "the soft colour from a fixed value",
            stripeProblems(bleed.replace("softStripeColor(primary, theme.mode == ThemeMode.DARK)", "softStripeColor(primary, false)")),
        )
    }

    @Test
    fun `the stripes move down by the host's bottom padding and measure the art height alone`() {
        assertQuiet("the real stripes", bottomProblems(bleed))
        assertFires(
            "the stripes not moved down",
            bottomProblems(bleed.replace("translationY = if (host == null) 0f else host.paddingBottom.toFloat()", "translationY = 0f")),
        )
        assertFires(
            "the stripes moved down by a comment only",
            bottomProblems(bleed.replace("translationY = if (host == null) 0f else host.paddingBottom.toFloat()", "// translationY = if (host == null) 0f else host.paddingBottom.toFloat()")),
        )
        assertFires(
            "the bottom padding added to the measured height",
            bottomProblems(bleed.replace("resolveSize(artHeightPx(), heightMeasureSpec)", "resolveSize(artHeightPx() + below, heightMeasureSpec)")),
        )
    }

    @Test
    fun `each layer unclips the boxes between it and the scroll child when it is attached`() {
        assertQuiet("the real views and helper", unclipProblems(bleed))
        assertFires(
            "the rule not unclipped on attach",
            unclipProblems(bleed.replaceFirst("        unclipAncestors()\n", "")),
        )
        assertFires(
            "the rule unclipped by a comment only",
            unclipProblems(bleed.replaceFirst("        unclipAncestors()\n", "        // unclipAncestors()\n")),
        )
        assertFires(
            "only the first box unclipped",
            unclipProblems(bleed.replace("if (current === target) chain.forEach { it.clipChildren = false }", "if (current === target) chain.take(1).forEach { it.clipChildren = false }")),
        )
    }

    /** The line of unclipAncestors that opens the padded scroll view when the walk passed an unpadded one. */
    private fun clipPaddingProblems(text: String): List<String> {
        val body = flat(between(text, "private fun View.unclipAncestors()", null))
        return listOfNotNull(
            "the padded scroll view keeps its padding clipped when the walk passes another"
                .takeUnless { "if (index > 0) target.clipToPadding = false" in body },
        )
    }

    @Test
    fun `a layer that walks past an unpadded scroll view opens the padded one's clipping`() {
        assertQuiet("the real helper", clipPaddingProblems(bleed))
        assertFires(
            "the padding clipping left on",
            clipPaddingProblems(bleed.replace("if (index > 0) target.clipToPadding = false", "if (index > 0) target.clipToPadding = true")),
        )
        assertFires(
            "the padding opened by a comment only",
            clipPaddingProblems(bleed.replace("if (index > 0) target.clipToPadding = false", "// if (index > 0) target.clipToPadding = false")),
        )
        assertFires(
            "the line removed",
            clipPaddingProblems(bleed.replace("    if (index > 0) target.clipToPadding = false\n", "")),
        )
    }
}
