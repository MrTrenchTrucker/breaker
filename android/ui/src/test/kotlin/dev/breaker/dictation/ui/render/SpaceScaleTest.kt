package dev.breaker.dictation.ui.render

import dev.breaker.dictation.ui.screen.Space
import dev.breaker.shared.tokens.TruckingMetrics
import dev.breaker.shared.tokens.TruckingTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The spacing scale as a pure function: each name reads its own token and no name reads another's.
 * The metrics here carry five distinct values, so a branch that reads the wrong field gives a wrong answer.
 */
class SpaceScaleTest {
    private val metrics: TruckingMetrics = TruckingTokens.metrics.copy(
        spaceXsDp = 4,
        spaceSDp = 8,
        spaceMDp = 12,
        spaceLDp = 16,
        spaceXlDp = 24,
    )

    @Test
    fun `each name gives the value of its own token`() {
        assertEquals(4, spaceDp(metrics, Space.XS))
        assertEquals(8, spaceDp(metrics, Space.S))
        assertEquals(12, spaceDp(metrics, Space.M))
        assertEquals(16, spaceDp(metrics, Space.L))
        assertEquals(24, spaceDp(metrics, Space.XL))
    }

    @Test
    fun `none is no distance whatever the scale holds`() {
        assertEquals(0, spaceDp(metrics, Space.NONE))
        assertEquals(0, spaceDp(TruckingTokens.metrics, Space.NONE))
    }

    @Test
    fun `the default scale is strictly increasing from xs to xl`() {
        val values = listOf(Space.XS, Space.S, Space.M, Space.L, Space.XL).map { spaceDp(TruckingTokens.metrics, it) }

        assertTrue("the steps must increase: $values", values.zipWithNext().all { (low, high) -> low < high })
    }

    @Test
    fun `the scale reads the metrics it is given and not the defaults`() {
        val other = TruckingTokens.metrics.copy(spaceMDp = 99)

        assertEquals(99, spaceDp(other, Space.M))
        assertEquals(12, spaceDp(metrics, Space.M))
    }
}
