package dev.breaker.dictation.ui.screen.home

import dev.breaker.dictation.ui.render.spaceDp
import dev.breaker.dictation.ui.screen.Space
import dev.breaker.shared.tokens.TruckingMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R7: the whole Home screen fits without scrolling on a 360×640 dp usable area.
 *
 * The art is 540×1170 px (360×780 dp at 1.5 px/dp), taller than 640 dp. The
 * non-mic components — top bar, card, mic words, transcript box and stripes —
 * are pinned by HomeGeometry and SpaceScale, and the mic slot takes what is left
 * of the usable height, held between its own floor and its cap (ADR-026: the
 * overlay builds the mic view, and the slot's height is this module's to give).
 * The page fits the usable height while the subtraction is at or above the
 * floor; below that the slot keeps the floor and the page may scroll, so this
 * rule does not apply under 610 dp.
 *
 * All heights are read from HomeGeometry and SpaceScale; no view is run.
 */
class HomeFitTest {

    private val metrics = TruckingMetrics(
        cornerRadiusDp = 4,
        minTouchTargetDp = 48,
        mobileBreakpointDp = 100,
        tabletBreakpointDp = 200,
        gutterDp = 16,
        spaceXsDp = 4,
        spaceSDp = 8,
        spaceMDp = 12,
        spaceLDp = 16,
        spaceXlDp = 32,
    )

    /** The top bar's height. */
    private val topBarDp: Float get() = HomeGeometry.BAR_H_DP

    /** The accent rule under the top bar. */
    private val topRuleDp: Float get() = HomeGeometry.RULE_H_DP

    /** The gap between the top bar and the card. */
    private val gapTopDp: Int get() = spaceDp(metrics, Space.M)

    /** The card's height: its bottom edge minus its top edge. */
    private val cardDp: Float get() = HomeGeometry.CARD_Y1_DP - HomeGeometry.CARD_Y0_DP

    /** The gap between the card and the mic slot. */
    private val gapMicDp: Int get() = spaceDp(metrics, Space.XL)

    /** The gap between the mic slot and its words. */
    private val gapWordsDp: Int get() = spaceDp(metrics, Space.S)

    /** The mic word's height: its cap height. */
    private val micWordDp: Float get() = HomeGeometry.TAP_CAP_DP

    /** The mic hint's height: its text size. */
    private val micHintDp: Float get() = HomeGeometry.HINT_SIZE_DP

    /** The gap between the mic hint and the transcript box. */
    private val gapTranscriptDp: Int get() = spaceDp(metrics, Space.L)

    /** The transcript box's height: its bottom edge minus its top edge. */
    private val transcriptDp: Float get() = HomeGeometry.BOX_Y1_DP - HomeGeometry.BOX_Y0_DP

    /** The bottom stripes' total height: four bars and three steps. */
    private val stripesDp: Float get() = 3f * HomeGeometry.STRIPE_STEP_DP + HomeGeometry.STRIPE_H_DP

    /** The usable screen height on a 360×640 dp phone. */
    private val usableHeightDp: Float = 640f

    /** The usable height at which the subtraction reaches the mic slot's floor. */
    private val floorUsableHeightDp: Float = 610f

    /** The usable height at which the subtraction reaches the mic slot's cap. */
    private val capUsableHeightDp: Float = 660f

    /**
     * The total height of the page's own non-mic parts, summed here from the parts the page draws.
     * The mic slot's height is this module's to give; everything else is pinned by HomeGeometry and
     * SpaceScale.
     */
    private val partsHeightDp: Float =
        topBarDp + topRuleDp + gapTopDp + cardDp + gapMicDp + gapWordsDp + micWordDp + micHintDp + gapTranscriptDp + transcriptDp + stripesDp

    /** A failure means the slot comes out of a sum the page does not draw: a part or a gap has moved. */
    @Test
    fun `the mic slot comes out of the page's own non-mic parts`() {
        assertEquals("the sum the slot comes out of", partsHeightDp, HomeGeometry.nonMicHeightDp(), 0.001f)
    }

    /** A failure means the slot is not the usable height less the non-mic parts, held between the floor and the cap. */
    @Test
    fun `the mic slot is the usable height less the non-mic parts`() {
        assertEquals(
            "the slot at the 640 dp phone",
            usableHeightDp - HomeGeometry.nonMicHeightDp(),
            HomeGeometry.slotHeightDp(usableHeightDp),
            0.001f,
        )
        assertEquals(
            "the slot at a taller phone",
            capUsableHeightDp - HomeGeometry.nonMicHeightDp(),
            HomeGeometry.slotHeightDp(capUsableHeightDp),
            0.001f,
        )
    }

    /** A failure means the non-mic parts leave the slot less than its floor on the 360×640 dp phone. */
    @Test
    fun `the non-mic parts leave the mic slot its floor within 640 dp`() {
        val remainingDp = usableHeightDp - partsHeightDp
        assertTrue(
            "non-mic height $partsHeightDp dp leaves only $remainingDp dp for the mic slot (need at least ${HomeGeometry.MIC_SLOT_MIN_DP} dp)",
            remainingDp >= HomeGeometry.MIC_SLOT_MIN_DP,
        )
    }

    /** A failure means the 360×640 dp phone no longer gives the mic slot 130 dp. */
    @Test
    fun `a 360 by 640 dp phone gives the mic slot 130 dp`() {
        assertEquals("the slot on the 640 dp phone", 130f, HomeGeometry.slotHeightDp(usableHeightDp), 0.001f)
    }

    /** A failure means a tall phone no longer stops the mic slot at the cap. */
    @Test
    fun `a tall phone stops the mic slot at the cap`() {
        assertEquals("the slot on an 800 dp phone", 150f, HomeGeometry.slotHeightDp(800f), 0.001f)
    }

    /** A failure means the usable height at the slot's floor no longer gives the mic slot 100 dp. */
    @Test
    fun `the usable height at the slot's floor gives the mic slot 100 dp`() {
        assertEquals("the slot at 610 dp", 100f, HomeGeometry.slotHeightDp(floorUsableHeightDp), 0.001f)
    }

    /** A failure means the page does not fit the usable height at or above 610 dp. */
    @Test
    fun `the page fits the usable height at or above 610 dp`() {
        for (usableDp in listOf(floorUsableHeightDp, usableHeightDp, 700f, 800f, 1000f)) {
            val totalDp = HomeGeometry.nonMicHeightDp() + HomeGeometry.slotHeightDp(usableDp)
            assertTrue(
                "the page is $totalDp dp tall on a usable $usableDp dp, and must fit it",
                totalDp <= usableDp + 0.001f,
            )
        }
        assertEquals(
            "the page on the 360x640 dp phone",
            usableHeightDp,
            HomeGeometry.nonMicHeightDp() + HomeGeometry.slotHeightDp(usableHeightDp),
            0.001f,
        )
    }

    /** A failure means the slot keeps growing on a phone taller than 660 dp instead of holding the cap. */
    @Test
    fun `the slot holds the cap on every phone taller than 660 dp`() {
        for (usableDp in listOf(capUsableHeightDp, 700f, 800f, 1000f)) {
            assertEquals(
                "the slot on a usable $usableDp dp",
                HomeGeometry.MIC_SLOT_MAX_DP,
                HomeGeometry.slotHeightDp(usableDp),
                0.001f,
            )
        }
    }

    /** A failure means the slot falls below its floor on a phone shorter than 610 dp. */
    @Test
    fun `the slot holds its floor on every phone shorter than 610 dp`() {
        for (usableDp in listOf(500f, 550f, 600f, floorUsableHeightDp)) {
            assertEquals(
                "the slot on a usable $usableDp dp",
                HomeGeometry.MIC_SLOT_MIN_DP,
                HomeGeometry.slotHeightDp(usableDp),
                0.001f,
            )
        }
    }

    /** A failure means the slot no longer grows with the usable height: a fixed slot sits at one height on every phone. */
    @Test
    fun `the slot grows one dp for each dp of usable height up to the cap`() {
        val heights = listOf(620f, 630f, usableHeightDp, 650f, capUsableHeightDp)
        heights.zipWithNext().forEach { (lower, higher) ->
            assertEquals(
                "the slot's growth from a usable $lower dp to $higher dp",
                higher - lower,
                HomeGeometry.slotHeightDp(higher) - HomeGeometry.slotHeightDp(lower),
                0.001f,
            )
        }
    }
}
