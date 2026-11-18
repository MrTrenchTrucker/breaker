package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two approved constraints of the Home mic's halo, checked at the slot sizes the Home page can
 * really produce after the owner-ordered 25% shrink: the new floor 75 dp, a size between the two
 * bounds (95 dp) and the new cap 112.5 dp. [ScreenHaloGateTest] pins the same rules at 100 dp and
 * 150 dp, the old range; this test closes the gap at the sizes that range now reaches.
 *
 * The finding, read off the formulas and not off a hand-computed number: [GlowSpec.SCREEN] is 1.40
 * half-cell widths on every side, so the uncapped upward reach is 0.70 of the cell side, 52.5 dp at
 * 75 dp and 78.75 dp at 112.5 dp, and the sideways reach is the same 0.70 of the cell side against
 * half the 360 dp page column, 180 dp. The card bottom above the mic centre is half the cell, less
 * the silhouette's own offset above the cell centre, plus the card's extra-large space, so it grows
 * with the cell and stays above that reach at both ends. No violation is expected at any of the
 * three sizes, and the two controls below prove the check can still fail.
 *
 * One nuance the smooth formula hides: [ArtGeometry.cardBottomDp] reads its art scale through
 * [ArtGeometry.scaleDp], which truncates the cell side with `cellSideDp.toInt()`, so at 112.5 dp
 * the scale is the one for 112 dp. Every comparison here is against the computed value, never a
 * hand-typed dp number.
 *
 * Mutant: lower the floor or raise the cap until 0.70 of the cell side passes the card bottom, or
 * widen [GlowSpec.SCREEN]'s upward reach past the card bottom at 112.5 dp, and the quiet assertion
 * above fails; drop the cap in [GlowSpec.upwardReachDp] and the upward control stops firing.
 */
class ScreenHaloRangeTest {

    /** The real Home slot range, in dp: the floor, a size between the two bounds, and the cap. */
    private val slots = listOf(75f, 95f, 112.5f)

    /** The rule input for one slot size: the reach of [GlowSpec.SCREEN] and the page column that holds it. */
    private fun input(cellSideDp: Float): ScreenHaloRules.Input =
        ScreenHaloRules.Input(cellSideDp, GlowSpec.SCREEN, PAGE_COLUMN_DP)

    /** A reach in half-cell widths, for a control that breaks a cap on purpose. */
    private fun reach(sideways: Float, upward: Float): HaloReach =
        HaloReach(sideways = sideways, downward = GlowSpec.SCREEN.downward, upward = upward)

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("overlay: screen halo: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("overlay: screen halo: $what must not be reported", emptyList<String>(), problems)

    /** The two approved constraints hold at the real Home slot range: the upward reach stops at the card bottom and the halo stays inside the column. */
    @Test
    fun `the real screen reach is quiet at the floor the cap and a size between them`() {
        for (s in slots) {
            val cap = ArtGeometry.cardBottomDp(s)
            val drawn = ScreenHaloRules.upwardDp(input(s))
            val sideways = ScreenHaloRules.sidewaysDp(input(s))
            val half = ScreenHaloRules.halfColumnDp(input(s))
            assertTrue("overlay: screen halo: at $s dp the upward reach $drawn must not pass the card bottom $cap", drawn <= cap)
            assertTrue("overlay: screen halo: at $s dp the sideways reach $sideways must not pass half the column $half", sideways <= half)
            assertQuiet("the real screen reach at $s dp", ScreenHaloRules.violations(listOf(input(s))))
        }
        assertEquals("overlay: screen halo: the real screen reach must be quiet at 75, 95 and 112.5 dp", emptyList<String>(), ScreenHaloRules.violations(slots.map { input(it) }))
    }

    /** The upward cap fires at the cap when the reach is not held: 2.00 half-cell widths is 112.5 dp at 112.5 dp, past the card bottom. */
    @Test
    fun `the upward control fires at the cap when the reach passes the card bottom`() {
        assertQuiet("the honest reach at the cap", ScreenHaloRules.upwardProblems(ScreenHaloRules.Input(112.5f, reach(1.40f, 1.40f), PAGE_COLUMN_DP)))
        val control = ScreenHaloRules.Input(112.5f, reach(1.40f, 2.00f), PAGE_COLUMN_DP)
        val problems = ScreenHaloRules.upwardProblems(control)
        assertFires("a reach past the card bottom at the cap", problems, "the upward reach is not held to the card bottom")
        assertEquals("overlay: screen halo: a reach past the card bottom is one violation", listOf(ScreenHaloRules.UPWARD), problems)
        assertTrue("overlay: screen halo: the control's uncapped reach must pass the card bottom", ScreenHaloRules.upwardUncappedDp(control) > ArtGeometry.cardBottomDp(112.5f))
        assertEquals("overlay: screen halo: the control's drawn reach is held to the card bottom", ArtGeometry.cardBottomDp(112.5f), ScreenHaloRules.upwardDp(control), 0.0001f)
        assertQuiet("a reach held under the card bottom", ScreenHaloRules.upwardProblems(ScreenHaloRules.Input(112.5f, reach(1.40f, 1.0f), PAGE_COLUMN_DP)))
    }

    /** The column bound fires at the cap when the reach leaves the page column: 4.00 half-cell widths is 225 dp at 112.5 dp, past half the 360 dp column. */
    @Test
    fun `the column control fires at the cap when the reach leaves the page column`() {
        assertQuiet("the honest reach at the cap", ScreenHaloRules.columnProblems(ScreenHaloRules.Input(112.5f, reach(1.40f, 1.40f), PAGE_COLUMN_DP)))
        val control = ScreenHaloRules.Input(112.5f, reach(4.00f, 1.40f), PAGE_COLUMN_DP)
        val problems = ScreenHaloRules.columnProblems(control)
        assertFires("a reach past the column edge", problems, "the halo is not inside the page column")
        assertEquals("overlay: screen halo: a reach past the column edge is one violation", listOf(ScreenHaloRules.COLUMN), problems)
        assertTrue("overlay: screen halo: the control reach must pass half the column", ScreenHaloRules.sidewaysDp(control) > ScreenHaloRules.halfColumnDp(control))
        assertQuiet("a reach held inside the column", ScreenHaloRules.columnProblems(ScreenHaloRules.Input(112.5f, reach(0.50f, 1.40f), PAGE_COLUMN_DP)))
    }

    private companion object {
        /** The page column the halo must stay inside: the art page is 540f / 1.5f = 360 dp wide (HomeGeometry). */
        const val PAGE_COLUMN_DP: Float = 360f
    }
}
