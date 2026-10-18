package dev.breaker.dictation.ui.render

import dev.breaker.dictation.ui.gate.mainSourceOf
import dev.breaker.dictation.ui.gate.withoutComments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Source-text rules for the Home art: the top-bar logo shares the bar with the menu icon and takes the
 * logo's height; the mic art is centred in its box and the box takes the art's height; the Home host
 * opens its padding for the full-width layers when it is built; and the page stripes come last on the
 * page. Each rule reads comment-stripped text, passes on the real file, and fires on an edited copy.
 */
class HomeArtRulesTest {
    private val art = mainSourceOf("render/BrandArtViews.kt")
    private val host = mainSourceOf("render/HomeHostView.kt")
    private val homeScreen = mainSourceOf("screen/home/HomeScreen.kt")

    private val whitespace = Regex("""\s+""")

    /** [text] with its comments taken out, then every run of whitespace made into one space. */
    private fun flat(text: String): String = withoutComments(text).replace(whitespace, " ")

    /** The parts of [wanted] that [text] does not contain, after comments are taken out and spacing collapsed. */
    private fun missing(text: String, wanted: List<String>): List<String> =
        wanted.filterNot { it in flat(text) }

    /** [text] with [old] replaced by [new]; fails by name when [old] is not exactly once in [text]. */
    private fun replaceOnce(text: String, old: String, new: String): String {
        assertTrue("ui: home art: the text to change is not in the source exactly once: $old", Regex(Regex.escape(old)).findAll(text).count() == 1)
        return text.replace(old, new)
    }

    private val TOP_BAR_BRANCH = "findTagged(root, HomeNodeIds.TOP_BAR_WORD)"
    private val DRAWER_BRANCH = "findTagged(root, HomeNodeIds.DRAWER_WORD)"
    private val logoParts = listOf("LayoutParams( 0, (HomeGeometry.LOGO_H_DP", "toInt(), 1f, )")
    private val micCentredParts = listOf("canvas.translate(", "((area.width() - width) shr 1)", "((area.height() - height) shr 1)")
    private val micHeightParts = listOf("mic.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, art.intrinsicHeight)")
    private val bleedOpenParts = listOf("scroll.openForBleed() page = scroll")
    private val stripesLastParts = listOf("add(BottomStripes(HomeNodeIds.STRIPES)) if (state.menuOpen) addAll(drawer())")
    private val ruleParts = listOf("add(topBar()) add(Rule(HomeNodeIds.TOP_BAR_TRIM, PaletteSlot.ACCENT))")
    private val gapParts = listOf("add(Spacer(HomeNodeIds.GAP_MIC, Space.XL))")

    @Test
    fun `the logo takes a share of the bar's width and the logo's own height`() {
        assertEquals("the real logo", emptyList<String>(), missing(art, logoParts))
        val edited = replaceOnce(art, "            0,\n            (HomeGeometry.LOGO_H_DP", "            48,\n            (HomeGeometry.LOGO_H_DP")
        assertEquals("the logo width not a share", listOf(logoParts[0]), missing(edited, logoParts))
    }

    /** The top-bar branch of applyBrandArt, comments taken out and flattened: where the logo takes the wordmark's place. */
    private fun topBarBranch(text: String): String =
        flat(text).substringAfter(TOP_BAR_BRANCH, "").substringBefore(DRAWER_BRANCH, "")

    @Test
    fun `the logo does not copy the wordmark's layout over its own`() {
        val branch = topBarBranch(art)
        assertTrue("the top bar branch was not found", branch.contains("parent.addView(logo, index)"))
        assertTrue("the real logo keeps its own size", !branch.contains("layoutParams"))
        val insert = "            val logo = topBarLogo(context, theme)\n"
        val copiedName = replaceOnce(art, insert, insert + "            logo.layoutParams = word.layoutParams\n")
        assertTrue("the copied layout is caught", topBarBranch(copiedName).contains("layoutParams"))
        val otherName = replaceOnce(art, insert, insert + "            logo.layoutParams = parent.layoutParams\n")
        assertTrue("a copy under another name is caught", topBarBranch(otherName).contains("layoutParams"))
        val commented = replaceOnce(art, insert, insert + "            // logo.layoutParams = parent.layoutParams\n")
        assertTrue("a comment naming a copy is not a copy", !topBarBranch(commented).contains("layoutParams"))
    }

    @Test
    fun `the mic art is centred in its box both ways`() {
        assertEquals("the real mic art", emptyList<String>(), missing(art, micCentredParts))
        val edited = replaceOnce(art, "((area.width() - width) shr 1)", "0")
        assertEquals("the mic art not centred across", listOf(micCentredParts[1]), missing(edited, micCentredParts))
        val edited2 = replaceOnce(art, "((area.height() - height) shr 1)", "0")
        assertEquals("the mic art not centred down", listOf(micCentredParts[2]), missing(edited2, micCentredParts))
    }

    @Test
    fun `the mic box takes the art's height`() {
        assertEquals("the real mic height", emptyList<String>(), missing(art, micHeightParts))
        val edited = replaceOnce(art, "art.intrinsicHeight)", "0)")
        assertEquals("the mic box height not the art's", micHeightParts, missing(edited, micHeightParts))
    }

    @Test
    fun `the Home host opens its padding for the full-width layers when it is built`() {
        assertEquals("the real host", emptyList<String>(), missing(host, bleedOpenParts))
        val edited = replaceOnce(host, "        scroll.openForBleed()\n", "        // scroll.openForBleed()\n")
        assertEquals("the bleed opening commented out", bleedOpenParts, missing(edited, bleedOpenParts))
    }

    @Test
    fun `the page stripes are the last node of the page, before the drawer`() {
        assertEquals("the real page order", emptyList<String>(), missing(homeScreen, stripesLastParts))
        val edited = replaceOnce(homeScreen, "            add(BottomStripes(HomeNodeIds.STRIPES))\n", "")
        assertEquals("the stripes removed from the page", stripesLastParts, missing(edited, stripesLastParts))
        val moved = replaceOnce(homeScreen, "            add(BottomStripes(HomeNodeIds.STRIPES))\n", "            add(BottomStripes(HomeNodeIds.STRIPES))\n            add(topBar())\n")
        assertEquals("the stripes followed by another node", stripesLastParts, missing(moved, stripesLastParts))
    }

    @Test
    fun `the accent rule is added right after the top bar`() {
        assertEquals("the real rule order", emptyList<String>(), missing(homeScreen, ruleParts))
        val edited = replaceOnce(homeScreen, "            add(Rule(HomeNodeIds.TOP_BAR_TRIM, PaletteSlot.ACCENT))\n", "")
        assertEquals("the rule removed", ruleParts, missing(edited, ruleParts))
    }

    @Test
    fun `the gap above the mic is the XL scale name`() {
        assertEquals("the real gap", emptyList<String>(), missing(homeScreen, gapParts))
        val edited = replaceOnce(homeScreen, "Spacer(HomeNodeIds.GAP_MIC, Space.XL)", "Spacer(HomeNodeIds.GAP_MIC, Space.M)")
        assertEquals("the gap not XL", gapParts, missing(edited, gapParts))
    }
}
