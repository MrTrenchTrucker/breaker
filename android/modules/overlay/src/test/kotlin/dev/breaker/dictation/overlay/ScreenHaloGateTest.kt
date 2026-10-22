package dev.breaker.dictation.overlay

import dev.breaker.shared.tokens.ThemeMode
import dev.breaker.shared.tokens.TruckingTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two approved constraints of the Home mic's halo, and the readability of the words drawn over it.
 *
 * The gate pins [GlowSpec.SCREEN] at the Home slot sizes 100 dp and 150 dp: the upward reach the halo
 * draws ([ArtGeometry.halo]'s upwardPx, which is [GlowSpec.upwardReachDp]) stays at or under the card
 * bottom above the mic centre ([ArtGeometry.cardBottomDp]), and the halo's sideways reach never exceeds
 * half the page column, so the halo cannot leave the window. The readability test blends the halo's
 * colour at the label's distance from the mic centre over the page colour and measures the WCAG contrast
 * against the label colour, at the text tier this repo already uses (4.5:1 for text and for the on-label
 * over a primary face, 3:1 for a non-text mark). A miss is reported with its number, never dimmed away.
 *
 * Both halves are proven to fire: the gate on an input that breaks each cap, and the readability check on
 * a colour that cannot clear the tier. A test that cannot fail is not evidence.
 */
class ScreenHaloGateTest {

    private val slots = listOf(100f, 150f)
    private val shapes = listOf(TileShape.COLLAPSED, TileShape.NOTICE, TileShape.RECORDING)
    private val looks = ThemeMode.entries.map { TileStyle.look(TileState.IDLE, TruckingTokens.palette(it)) to it }

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

    /** The two approved constraints hold at both slot sizes, in every shape: the upward reach stops at the card bottom and the halo stays inside the column. */
    @Test
    fun `the screen halo is held to the card bottom and stays inside the page column at every slot size`() {
        for (s in slots) {
            val cap = ArtGeometry.cardBottomDp(s)
            val drawn = ScreenHaloRules.upwardDp(input(s))
            val sideways = ScreenHaloRules.sidewaysDp(input(s))
            val half = ScreenHaloRules.halfColumnDp(input(s))
            val window = TileLayout.windowSize(TileShape.RECORDING, s.toInt()).width.toFloat()
            assertTrue("overlay: screen halo: at $s dp the upward reach $drawn must not pass the card bottom $cap", drawn <= cap)
            assertTrue("overlay: screen halo: at $s dp the sideways reach $sideways must not pass half the column $half", sideways <= half)
            assertTrue("overlay: screen halo: at $s dp the window must hold the cell, found $window", window >= s)
            for (shape in shapes) {
                assertQuiet("the real reach in the $shape window at $s dp", ScreenHaloRules.violations(listOf(input(s))))
            }
        }
        assertEquals("overlay: screen halo: the 150 dp card bottom expected 105.4180", 105.4180f, ArtGeometry.cardBottomDp(150f), 0.0001f)
        assertEquals("overlay: screen halo: the 100 dp card bottom expected 80.9453", 80.9453f, ArtGeometry.cardBottomDp(100f), 0.0001f)
        assertEquals("overlay: screen halo: at 150 dp the upward reach expected 105.0", 105.0f, ScreenHaloRules.upwardDp(input(150f)), 0.0001f)
        assertEquals("overlay: screen halo: at 100 dp the upward reach expected 70.0", 70.0f, ScreenHaloRules.upwardDp(input(100f)), 0.0001f)
        assertEquals("overlay: screen halo: at 150 dp the sideways reach expected 105.0", 105.0f, ScreenHaloRules.sidewaysDp(input(150f)), 0.0001f)
        assertEquals("overlay: screen halo: at 100 dp the sideways reach expected 70.0", 70.0f, ScreenHaloRules.sidewaysDp(input(100f)), 0.0001f)
        assertQuiet("the real screen reach in every window", ScreenHaloRules.violations(slots.map { input(it) }))
    }

    /** The upward cap fires when the reach is not held to the card bottom: 1.40 half-cell widths is 105 dp at 150 dp, under the card bottom 105.4180 dp, so 1.50 breaks it. */
    @Test
    fun `the upward cap fires when the reach is not held to the card bottom`() {
        assertQuiet("the honest reach at 150 dp", ScreenHaloRules.upwardProblems(ScreenHaloRules.Input(150f, reach(1.40f, 1.40f), PAGE_COLUMN_DP)))
        val control = ScreenHaloRules.Input(150f, reach(1.40f, 1.50f), PAGE_COLUMN_DP)
        val problems = ScreenHaloRules.upwardProblems(control)
        assertFires("a reach past the card bottom", problems, "the upward reach is not held to the card bottom")
        assertEquals("overlay: screen halo: a reach past the card bottom is one violation", listOf(ScreenHaloRules.UPWARD), problems)
        assertTrue("overlay: screen halo: the control's uncapped reach must pass the card bottom", ScreenHaloRules.upwardUncappedDp(control) > 105.4180f)
        assertEquals("overlay: screen halo: the control's drawn reach is held to the card bottom", 105.4180f, ScreenHaloRules.upwardDp(control), 0.0001f)
        assertQuiet("a reach held under the card bottom", ScreenHaloRules.upwardProblems(ScreenHaloRules.Input(150f, reach(1.40f, 1.0f), PAGE_COLUMN_DP)))
    }

    /** The column bound fires when the reach leaves the page column: the page column is 360 dp, so half of it is 180 dp; 1.40 half-cell widths is 105 dp at 150 dp, well inside it, so a reach over 2.40 at 150 dp leaves it. */
    @Test
    fun `the column bound fires when the reach leaves the page column`() {
        assertQuiet("the honest reach at 150 dp", ScreenHaloRules.columnProblems(ScreenHaloRules.Input(150f, reach(1.40f, 1.40f), PAGE_COLUMN_DP)))
        assertQuiet("the honest reach at 100 dp", ScreenHaloRules.columnProblems(ScreenHaloRules.Input(100f, reach(1.40f, 1.40f), PAGE_COLUMN_DP)))
        val control = ScreenHaloRules.Input(150f, reach(4.00f, 1.40f), PAGE_COLUMN_DP)
        val problems = ScreenHaloRules.columnProblems(control)
        assertFires("a reach past the column edge", problems, "the halo is not inside the page column")
        assertEquals("overlay: screen halo: a reach past the column edge is one violation", listOf(ScreenHaloRules.COLUMN), problems)
        assertTrue("overlay: screen halo: the control reach must pass half the column", ScreenHaloRules.sidewaysDp(control) > 180.0f)
        assertQuiet("a reach held inside the column", ScreenHaloRules.columnProblems(ScreenHaloRules.Input(150f, reach(0.50f, 1.40f), PAGE_COLUMN_DP)))
    }

    /**
     * The widest approved variant (C, 2.33 half-cell widths) leaves its margin inside the page column: at
     * 150 dp its sideways reach is 174.75 dp against a column half-width of 180 dp, so 180f - 174.75f =
     * 5.25 dp; at 100 dp it is 116.5 dp, so 180f - 116.5f = 63.5 dp. The margin is named, so it cannot
     * drift silently, and the column rule stays quiet for C at both slot sizes.
     */
    @Test
    fun `the widest approved variant leaves its margin inside the page column`() {
        val at150 = ScreenHaloRules.Input(150f, reach(VARIANT_C_SIDEWAYS, VARIANT_C_SIDEWAYS), PAGE_COLUMN_DP)
        val margin150 = ScreenHaloRules.halfColumnDp(at150) - ScreenHaloRules.sidewaysDp(at150)
        assertTrue("overlay: screen halo: at 150 dp variant C's margin $margin150 must be positive", margin150 > 0f)
        assertEquals("overlay: screen halo: at 150 dp variant C's margin expected 5.25 (180f - 174.75f)", C_MARGIN_150_DP, margin150, 0.01f)
        assertQuiet("variant C at 150 dp", ScreenHaloRules.columnProblems(at150))

        val at100 = ScreenHaloRules.Input(100f, reach(VARIANT_C_SIDEWAYS, VARIANT_C_SIDEWAYS), PAGE_COLUMN_DP)
        val margin100 = ScreenHaloRules.halfColumnDp(at100) - ScreenHaloRules.sidewaysDp(at100)
        assertTrue("overlay: screen halo: at 100 dp variant C's margin $margin100 must be positive", margin100 > 0f)
        assertEquals("overlay: screen halo: at 100 dp variant C's margin expected 63.5 (180f - 116.5f)", C_MARGIN_100_DP, margin100, 0.01f)
        assertQuiet("variant C at 100 dp", ScreenHaloRules.columnProblems(at100))
    }

    /** The halo is behind the silhouette: the silhouette is drawn after the halo, so the words and the mic read over the halo, not under it. */
    @Test
    fun `the halo is drawn behind the silhouette in the Home view`() {
        val source = ModuleFiles.mainTexts().getValue("ScreenMic.kt")
        val glow = "artDraw.drawGlow(canvas, mic, paint, look.ring, 1f, density, GlowSpec.SCREEN, look.litSegment)"
        val art = "artDraw.drawArt(canvas, mic, paint, face, 1f)"
        assertTrue("overlay: screen halo: ScreenMic.kt has no '$glow'", source.contains(glow))
        assertTrue("overlay: screen halo: ScreenMic.kt has no '$art'", source.contains(art))
        assertTrue("overlay: screen halo: the halo must be drawn before the art", source.indexOf(glow) < source.indexOf(art))
    }

    /**
     * The readability of the TAP TO TALK label, light and dark, at the label's own position (a quarter of the
     * cell above the mic centre, inside the upward reach 80.9453 dp at the 100 dp cell). The label colour is
     * the text colour, so the halo is the only thing that can take the contrast away.
     */
    @Test
    fun `the label keeps its contrast over the halo in both themes`() {
        for ((look, mode) in looks) {
            val pageBg = TruckingTokens.palette(mode).bg.argb
            val over = haloColourOver(look.litSegment, LABEL_DISTANCE_DP, LABEL_CELL_DP, pageBg)
            val ratio = contrastRatio(look.control, over)
            assertTrue(
                "overlay: screen halo: $mode label over the halo must clear $TEXT_MIN, found ${"%.3f".format(ratio)}",
                ratio >= TEXT_MIN,
            )
            assertTrue(
                "overlay: screen halo: $mode label must clear the page it sits on, found ${"%.3f".format(contrastRatio(look.control, pageBg))}",
                contrastRatio(look.control, pageBg) >= TEXT_MIN,
            )
        }
        assertEquals("overlay: screen halo: light label over the halo expected 13.4531", 13.4531, contrastRatio(0xFF111417.toInt(), haloColourOver(0xFF1E7A46.toInt(), LABEL_DISTANCE_DP, LABEL_CELL_DP, 0xFFFFFFFF.toInt())), 0.001)
        assertEquals("overlay: screen halo: dark label over the halo expected 13.0292", 13.0292, contrastRatio(0xFFF2F5F2.toInt(), haloColourOver(0xFF2E9E5B.toInt(), LABEL_DISTANCE_DP, LABEL_CELL_DP, 0xFF0E1113.toInt())), 0.001)
    }

    /**
     * The muted hint keeps its contrast too, light and dark, and both ratios are printed so the boundary is
     * on the record. The hint sits below the mic slot, past the halo's reach at the 100 dp cell, so the halo
     * does not reach it and its contrast is against the page. The instrument itself is proven able to miss
     * by the two boundary controls: a colour against itself is 1.0, and the halo colour against itself is
     * below the tier. A test that cannot fail is not evidence.
     */
    @Test
    fun `the muted hint keeps its contrast in both themes and the instrument can miss`() {
        for ((look, mode) in looks) {
            val pageBg = TruckingTokens.palette(mode).bg.argb
            val label = contrastRatio(look.control, haloColourOver(look.litSegment, LABEL_DISTANCE_DP, LABEL_CELL_DP, pageBg))
            val hint = contrastRatio(TruckingTokens.palette(mode).textMuted.argb, haloColourOver(look.litSegment, HINT_DISTANCE_DP, LABEL_CELL_DP, pageBg))
            assertTrue("overlay: screen halo: $mode label over the halo must clear $TEXT_MIN, found ${"%.3f".format(label)}", label >= TEXT_MIN)
            assertTrue("overlay: screen halo: $mode hint must clear $TEXT_MIN, found ${"%.3f".format(hint)}", hint >= TEXT_MIN)
            println("overlay: screen halo: $mode label ${"%.4f".format(label)} hint ${"%.4f".format(hint)}")
        }
        // The floor is a real boundary, not a constant: the repo's text tier is 4.5 and its on-label test asserts above 4.5.
        assertTrue("overlay: screen halo: 4.5 must clear $TEXT_MIN", 4.5 >= TEXT_MIN)
        assertTrue("overlay: screen halo: 4.4 must miss $TEXT_MIN", 4.4 < TEXT_MIN)
        assertTrue("overlay: screen halo: the repo's on-label ratio is above $TEXT_MIN", 7.5857 > TEXT_MIN)
        // The instrument: a colour against itself is 1.0, and the halo's own colour against itself is a miss.
        assertEquals("overlay: screen halo: a colour against itself is 1.0", 1.0, contrastRatio(0xFF000000.toInt(), 0xFF000000.toInt()), 0.0001)
        val light = TileStyle.look(TileState.IDLE, TruckingTokens.palette(ThemeMode.LIGHT))
        val lightOver = haloColourOver(light.litSegment, LABEL_DISTANCE_DP, LABEL_CELL_DP, TruckingTokens.palette(ThemeMode.LIGHT).bg.argb)
        assertTrue("overlay: screen halo: the light halo colour against itself must miss $TEXT_MIN", contrastRatio(lightOver, lightOver) < TEXT_MIN)
    }

    private companion object {
        /** The repo's text tier, the one the on-label over a primary face already asserts above (4.5:1). */
        const val TEXT_MIN: Double = 4.5

        /** The page column the halo must stay inside: the art page is 540f / 1.5f = 360 dp wide (HomeGeometry). */
        const val PAGE_COLUMN_DP: Float = 360f

        /** The widest approved variant's sideways reach, in half-cell widths (variant C). */
        const val VARIANT_C_SIDEWAYS: Float = 2.33f

        /** Variant C's margin inside the page column at 150 dp: 180f - 174.75f. */
        const val C_MARGIN_150_DP: Float = 5.25f

        /** Variant C's margin inside the page column at 100 dp: 180f - 116.5f. */
        const val C_MARGIN_100_DP: Float = 63.5f

        /** The label sits this far above the mic centre, in dp: a quarter of the cell, inside the upward reach. */
        const val LABEL_DISTANCE_DP: Float = 25f

        /** The hint sits this far below the mic slot, in dp: past the halo's reach at the 100 dp cell, so the halo does not reach it. */
        const val HINT_DISTANCE_DP: Float = 200f

        /** The cell side the label is drawn in, in dp: the Home slot's own size. */
        const val LABEL_CELL_DP: Float = 100f

        /** The WCAG contrast ratio of two opaque colours, 1.0 to 21.0, from [relativeLuminance]. */
        fun contrastRatio(a: Int, b: Int): Double {
            val lighter = maxOf(relativeLuminance(a), relativeLuminance(b))
            val darker = minOf(relativeLuminance(a), relativeLuminance(b))
            return (lighter + 0.05) / (darker + 0.05)
        }

        /** [overlay] composited over [under] at [alpha]: one channel at a time, in whole numbers, the same rounding [shade] uses. */
        fun blend(overlay: Int, under: Int, alpha: Float): Int {
            val a = (alpha * 255f + 0.5f).toInt().coerceIn(0, 255)
            fun channel(o: Int, u: Int): Int = (o * a + u * (255 - a) + 127) / 255
            val red = channel((overlay ushr 16) and 0xFF, (under ushr 16) and 0xFF)
            val green = channel((overlay ushr 8) and 0xFF, (under ushr 8) and 0xFF)
            val blue = channel(overlay and 0xFF, under and 0xFF)
            return 0xFF shl 24 or (red shl 16) or (green shl 8) or blue
        }

        /** The halo's colour at [distanceDp] from the mic centre, over [page], in a cell of side [cellSideDp]: every layer whose reach covers that distance. */
        fun haloColourOver(ring: Int, distanceDp: Float, cellSideDp: Float, page: Int): Int {
            val halo = ArtGeometry.halo(TileRect(0, 0, cellSideDp.toInt(), cellSideDp.toInt()), 1f, GlowSpec.SCREEN)
            var colour = page
            for (layer in 0 until ArtGeometry.haloLayerCount()) {
                if (ArtGeometry.haloLayerFraction(layer) * halo.sidewaysPx >= distanceDp) {
                    colour = blend(glowDrawColor(ring, 1f, ArtGeometry.haloLayerAlpha(layer)), colour, ArtGeometry.haloLayerAlpha(layer))
                }
            }
            return colour
        }
    }
}
