package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Home mic's halo shape selector. [GlowShape] names the three shapes, [GlowShapes] selects
 * one and hands the drawing path the reach it draws. V1 is the shipped shape, so the release build
 * draws exactly what it drew before this slice: [GlowShapes.reachFor] returns the approved
 * [GlowSpec.SCREEN] instance itself for V1. V2 and V3 are debug-only visual experiments, and all
 * three stay inside the page column at every cell size the layout allows (60, 100 and 150 dp).
 *
 * This test only reads [GlowShapes.screen]; GlowShapeReleaseGateTest owns the mutation and the release gate, so no
 * `@Before`/`@After` touches the selector and no assertion reads [GlowShapes.screen].
 */
class GlowShapeTest {

    /** The cell sides the layout allows, in dp. */
    private val cellSides = listOf(60f, 100f, 150f)

    /** The page column, in dp: a halo's sideways reach must stay inside half of it. */
    private val columnWidth = 360f

    @Test
    fun `the shape enum holds exactly V1 V2 V3 in that order`() {
        assertEquals(
            "overlay: the halo shapes expected exactly V1, V2, V3 in that order",
            listOf(GlowShape.V1, GlowShape.V2, GlowShape.V3),
            GlowShape.entries.toList(),
        )
    }

    @Test
    fun `V1 is the shipped shape - the approved reaches of GlowSpec SCREEN`() {
        assertEquals(
            "overlay: V1 must equal the shipped GlowSpec.SCREEN",
            GlowSpec.SCREEN,
            GlowShapes.reachFor(GlowShape.V1),
        )
        assertSame(
            "overlay: V1 must be the very GlowSpec.SCREEN instance",
            GlowSpec.SCREEN,
            GlowShapes.reachFor(GlowShape.V1),
        )
        assertEquals("overlay: V1 sideways expected 1.40", 1.40f, GlowShapes.reachFor(GlowShape.V1).sideways, 0f)
        assertEquals("overlay: V1 downward expected 1.40", 1.40f, GlowShapes.reachFor(GlowShape.V1).downward, 0f)
        assertEquals("overlay: V1 upward expected 1.40", 1.40f, GlowShapes.reachFor(GlowShape.V1).upward, 0f)
        assertEquals("overlay: V1 kind expected SCREEN", GlowKind.SCREEN, GlowShapes.reachFor(GlowShape.V1).kind)
    }

    @Test
    fun `V2 is the widest sideways reach the column approves`() {
        val reach = GlowShapes.reachFor(GlowShape.V2)
        assertEquals("overlay: V2 kind expected SCREEN", GlowKind.SCREEN, reach.kind)
        assertEquals("overlay: V2 sideways expected 2.33", 2.33f, reach.sideways, 0f)
        assertEquals("overlay: V2 downward expected 1.40", 1.40f, reach.downward, 0f)
        assertEquals("overlay: V2 upward expected 1.40", 1.40f, reach.upward, 0f)
    }

    @Test
    fun `V3 is a tight halo`() {
        val reach = GlowShapes.reachFor(GlowShape.V3)
        assertEquals("overlay: V3 kind expected SCREEN", GlowKind.SCREEN, reach.kind)
        assertEquals("overlay: V3 sideways expected 1.00", 1.00f, reach.sideways, 0f)
        assertEquals("overlay: V3 downward expected 1.00", 1.00f, reach.downward, 0f)
        assertEquals("overlay: V3 upward expected 1.00", 1.00f, reach.upward, 0f)
    }

    @Test
    fun `all three shapes stay inside the page column at every allowed cell size`() {
        for (shape in GlowShape.entries) {
            for (cellSide in cellSides) {
                val problems = ScreenHaloRules.columnProblems(
                    ScreenHaloRules.Input(cellSide, GlowShapes.reachFor(shape), columnWidth),
                )
                assertTrue(
                    "overlay: $shape at $cellSide dp left the page column: $problems",
                    problems.isEmpty(),
                )
            }
        }
    }

    @Test
    fun `the column rule fires on a planted reach - the check is not vacuous`() {
        val planted = HaloReach(sideways = 3.0f, downward = 1.40f, upward = 1.40f, kind = GlowKind.SCREEN)
        val problems = ScreenHaloRules.columnProblems(ScreenHaloRules.Input(150f, planted, columnWidth))
        assertTrue(
            "overlay: the column rule must fire on a planted 3.0f reach at 150 dp, got $problems",
            problems.isNotEmpty(),
        )
        assertTrue(
            "overlay: the planted reach must be reported as ${ScreenHaloRules.COLUMN}, got $problems",
            problems.contains(ScreenHaloRules.COLUMN),
        )
    }

    @Test
    fun `control - the V1 assertion fails on a planted selector whose reach changed`() {
        val planted = PlantedSelector.reachFor(GlowShape.V1)
        assertNotEquals(
            "overlay: the V1 sideways assertion must fail on a planted 1.41f reach",
            1.40f, planted.sideways, 0f,
        )
        assertNotEquals(
            "overlay: the V1 identity assertion must fail on a planted reach",
            GlowSpec.SCREEN, planted,
        )
    }

    @Test
    fun `control - the V2 sideways assertion fails on a planted selector`() {
        val planted = PlantedSelector.reachFor(GlowShape.V2)
        assertNotEquals(
            "overlay: the V2 sideways assertion must fail on a planted 1.40f reach",
            2.33f, planted.sideways, 0f,
        )
    }

    @Test
    fun `control - the V3 reaches assertion fails on a planted selector`() {
        val planted = PlantedSelector.reachFor(GlowShape.V3)
        assertNotEquals(
            "overlay: the V3 sideways assertion must fail on a planted 3.00f reach",
            1.00f, planted.sideways, 0f,
        )
    }

    @Test
    fun `control - the column assertion fails on a planted selector whose reach leaves the column`() {
        val planted = PlantedSelector.reachFor(GlowShape.V3)
        val problems = ScreenHaloRules.columnProblems(ScreenHaloRules.Input(150f, planted, columnWidth))
        assertTrue(
            "overlay: the column assertion must fail on a planted 3.00f sideways reach at 150 dp, got $problems",
            problems.isNotEmpty(),
        )
    }

    /**
     * A copy of [GlowShapes] with one reach changed, so a control can show the matching assertion
     * fails. It is never assigned to [GlowShapes.screen]; this slice only reads the selector.
     */
    private object PlantedSelector {
        fun reachFor(shape: GlowShape): HaloReach = when (shape) {
            GlowShape.V1 -> HaloReach(sideways = 1.41f, downward = 1.40f, upward = 1.40f, kind = GlowKind.SCREEN)
            GlowShape.V2 -> HaloReach(sideways = 1.40f, downward = 1.40f, upward = 1.40f, kind = GlowKind.SCREEN)
            GlowShape.V3 -> HaloReach(sideways = 3.00f, downward = 1.00f, upward = 1.00f, kind = GlowKind.SCREEN)
        }
    }
}
