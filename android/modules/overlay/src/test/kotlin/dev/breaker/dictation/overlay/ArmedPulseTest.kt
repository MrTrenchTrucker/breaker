package dev.breaker.dictation.overlay

import dev.breaker.shared.tokens.ThemeMode
import dev.breaker.shared.tokens.TruckingTokens
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The armed ring's pulse rule and the alpha its ring is drawn with. The pulse stays between its bounds,
 * peaks at half a cycle, wraps every whole cycle and is mirrored about half a cycle. The alpha changes the
 * armed ring and no other state's ring. Pure arithmetic: no clock, no sleep, no thread.
 */
class ArmedPulseTest {

    private val modes = listOf(ThemeMode.LIGHT, ThemeMode.DARK)

    /** Phases from -2.5 to 2.5 in steps of 0.125, which are exact in binary floating point. */
    private val phases: List<Float> = (0..40).map { -2.5f + it * 0.125f }

    /** A failure means the pulse leaves its bounds for some phase, so the armed ring would be drawn fainter or stronger than the rule allows. */
    @Test
    fun `the pulse stays within its minimum and maximum for phases from minus 2 point 5 to 2 point 5`() {
        for (phase in phases) {
            val alpha = armedPulseAlpha(phase)
            assertTrue(
                "overlay: pulse at $phase expected between $PULSE_ALPHA_MIN and $PULSE_ALPHA_MAX, found $alpha",
                alpha >= PULSE_ALPHA_MIN && alpha <= PULSE_ALPHA_MAX,
            )
        }
    }

    /** A failure means a phase that is not a finite number gives an alpha other than the maximum. */
    @Test
    fun `a phase that is not a finite number gives the maximum alpha`() {
        for (phase in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertTrue("overlay: pulse at $phase expected the maximum, found ${armedPulseAlpha(phase)}", armedPulseAlpha(phase) == PULSE_ALPHA_MAX)
        }
    }

    /** A failure means phase zero or phase one does not give the minimum, or phase one half does not give the maximum. */
    @Test
    fun `phase zero and phase one give the minimum and phase one half gives the maximum`() {
        assertTrue("overlay: pulse at 0 expected the minimum, found ${armedPulseAlpha(0f)}", armedPulseAlpha(0f) == PULSE_ALPHA_MIN)
        assertTrue("overlay: pulse at 1 expected the minimum, found ${armedPulseAlpha(1f)}", armedPulseAlpha(1f) == PULSE_ALPHA_MIN)
        assertTrue("overlay: pulse at 0.5 expected the maximum, found ${armedPulseAlpha(0.5f)}", armedPulseAlpha(0.5f) == PULSE_ALPHA_MAX)
    }

    /** A failure means the pulse is not mirrored about half a cycle: a phase and its complement must give the same alpha. */
    @Test
    fun `the pulse is symmetric about phase one half`() {
        for (step in 0..8) {
            val phase = step * 0.125f
            val mirror = 1f - phase
            assertTrue(
                "overlay: pulse at $phase and $mirror expected the same alpha, found ${armedPulseAlpha(phase)} and ${armedPulseAlpha(mirror)}",
                abs(armedPulseAlpha(phase) - armedPulseAlpha(mirror)) <= 1e-6f,
            )
        }
    }

    /** A failure means the phase does not wrap: a phase and the same phase one whole cycle later must give the same alpha. */
    @Test
    fun `the pulse repeats every whole cycle`() {
        for (phase in phases) {
            assertTrue(
                "overlay: pulse at $phase expected the same alpha one cycle later, found ${armedPulseAlpha(phase)} and ${armedPulseAlpha(phase + 1f)}",
                armedPulseAlpha(phase) == armedPulseAlpha(phase + 1f),
            )
        }
    }

    /** A failure means the armed ring is not drawn at the alpha it is given, or the colour of the ring changes with the alpha. */
    @Test
    fun `the alpha is applied to the armed ring and keeps its colour`() {
        for (mode in modes) {
            val p = TruckingTokens.palette(mode)
            assertEquals("overlay: $mode armed ring at alpha 1 expected the primary colour", p.primary.argb, ringColor(TileState.ARMED, p, 1f))
            assertEquals("overlay: $mode armed ring at alpha 0 expected a zero alpha byte", 0, ringColor(TileState.ARMED, p, 0f) ushr 24)
            assertEquals("overlay: $mode armed ring at alpha 0.5 expected an alpha byte of 128", 128, ringColor(TileState.ARMED, p, 0.5f) ushr 24)
            assertEquals(
                "overlay: $mode armed ring at alpha 0.5 expected the primary colour under the alpha",
                p.primary.argb and 0xFFFFFF,
                ringColor(TileState.ARMED, p, 0.5f) and 0xFFFFFF,
            )
        }
    }

    /** A failure means a state other than armed is drawn with a different ring for some alpha: the alpha must apply to armed only. */
    @Test
    fun `the alpha changes no ring other than the armed ring`() {
        for (mode in modes) {
            val p = TruckingTokens.palette(mode)
            for (state in TileState.values()) {
                if (state == TileState.ARMED) continue
                for (alpha in listOf(0f, 0.5f, 1f)) {
                    assertEquals(
                        "overlay: $mode $state ring at alpha $alpha expected the palette ring whatever the alpha",
                        TileStyle.look(state, p).ring,
                        ringColor(state, p, alpha),
                    )
                }
            }
        }
    }
}
