package dev.breaker.dictation.ui.screen.home

import dev.breaker.dictation.ui.gate.mainSourceOf
import dev.breaker.dictation.ui.gate.withoutComments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The gap above the Home mic slot, pinned in the three places it is written.
 *
 * The overlay draws the Home mic's halo and caps its upward reach at the card's
 * bottom edge, and that cap is measured with the same token this page uses for
 * the gap: the extra-large space. So the gap above the mic slot is not only a
 * page distance, it is the halo's upward cap. A change to the gap in one of the
 * three places without the others moves the page and the halo apart, and a
 * change to the gap alone moves the halo's cap with it. Each rule reads
 * comment-stripped text, passes on the real file, and fires on an edited copy,
 * and each failure says which overlay spec follows the gap.
 */
class HomeMicGapGlowSpecTest {
    private val screen = mainSourceOf("screen/home/HomeScreen.kt")
    private val geometry = mainSourceOf("screen/home/HomeGeometry.kt")
    private val scale = mainSourceOf("render/SpaceScale.kt")

    private val whitespace = Regex("""\s+""")

    /** [text] with its comments taken out, then every run of whitespace made into one space. */
    private fun flat(text: String): String = withoutComments(text).replace(whitespace, " ")

    /** The parts of [wanted] that [text] does not contain, after comments are taken out and spacing collapsed. */
    private fun missing(text: String, wanted: List<String>): List<String> =
        wanted.filterNot { it in flat(text) }

    /** [text] with [old] replaced by [new]; fails by name when [old] is not exactly once in [text]. */
    private fun replaceOnce(text: String, old: String, new: String): String {
        assertTrue("ui: home: the text to change is not in the source exactly once: $old", Regex(Regex.escape(old)).findAll(text).count() == 1)
        return text.replace(old, new)
    }

    /** The gap above the mic slot in the page tree: the spacer node the page adds before the mic. */
    private val TREE_GAP = "add(Spacer(HomeNodeIds.GAP_MIC, Space.XL))"

    /** The same gap in the non-mic budget: the token the overlay reads, by its Space name. */
    private val BUDGET_GAP = "spaceDp(TruckingTokens.metrics, Space.XL)"

    /** The scale arm that turns the name into the token the overlay reads. */
    private val SCALE_ARM = "Space.XL -> metrics.spaceXlDp"

    /** What a change to the gap moves in the overlay, named so a failure says which spec follows it. */
    private val OVERLAY_SPEC = "the overlay's GlowSpec.SCREEN caps the screen halo's upward reach at this gap, so a change here must move that cap"

    @Test
    fun `the gap above the mic slot is XL in the page tree`() {
        assertEquals(
            "home: the gap above the mic slot in the page tree must stay Space.XL; $OVERLAY_SPEC",
            emptyList<String>(),
            missing(screen, listOf(TREE_GAP)),
        )
        val edited = replaceOnce(screen, "Spacer(HomeNodeIds.GAP_MIC, Space.XL)", "Spacer(HomeNodeIds.GAP_MIC, Space.M)")
        assertEquals(
            "home: the gap above the mic slot in the page tree must stay Space.XL; $OVERLAY_SPEC",
            listOf(TREE_GAP),
            missing(edited, listOf(TREE_GAP)),
        )
    }

    @Test
    fun `the gap above the mic slot is XL in the non-mic budget`() {
        assertEquals(
            "home: the gap above the mic slot in the non-mic budget must stay Space.XL; $OVERLAY_SPEC",
            emptyList<String>(),
            missing(geometry, listOf(BUDGET_GAP)),
        )
        val edited = replaceOnce(geometry, "spaceDp(TruckingTokens.metrics, Space.XL)", "spaceDp(TruckingTokens.metrics, Space.L)")
        assertEquals(
            "home: the gap above the mic slot in the non-mic budget must stay Space.XL; $OVERLAY_SPEC",
            listOf(BUDGET_GAP),
            missing(edited, listOf(BUDGET_GAP)),
        )
    }

    @Test
    fun `the scale still turns the gap's name into the token the overlay reads`() {
        assertEquals(
            "home: the gap above the mic slot is Space.XL, and the scale must keep Space.XL -> metrics.spaceXlDp; $OVERLAY_SPEC",
            emptyList<String>(),
            missing(scale, listOf(SCALE_ARM)),
        )
        val edited = replaceOnce(scale, "Space.XL -> metrics.spaceXlDp", "")
        assertEquals(
            "home: the gap above the mic slot is Space.XL, and the scale must keep Space.XL -> metrics.spaceXlDp; $OVERLAY_SPEC",
            listOf(SCALE_ARM),
            missing(edited, listOf(SCALE_ARM)),
        )
    }
}
