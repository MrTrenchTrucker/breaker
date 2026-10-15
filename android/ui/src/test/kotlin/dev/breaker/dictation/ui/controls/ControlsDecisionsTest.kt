package dev.breaker.dictation.ui.controls

import dev.breaker.dictation.ui.theme.Theme
import dev.breaker.dictation.ui.theme.Themes
import dev.breaker.shared.tokens.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The decisions the views take from plain code: how wide a button is, how a full width button fills its
 * row, which step marks are lit, where a badge pill sits, which fill a badge takes, where a switch outline
 * is, and the corner of a thumb. Each expected number is a literal the art gives.
 */
class ControlsDecisionsTest {

    private val light: Theme = Themes.of(ThemeMode.LIGHT)
    private val dark: Theme = Themes.of(ThemeMode.DARK)

    /** A failure means a button is narrower than the art's 170 px, or does not grow past its label plus 16 dp each side. */
    @Test
    fun `a button is never narrower than the art width and grows past it with its label`() {
        assertEquals("controls: an empty label expected 170 px", 340f / 3f, ControlsGeometry.buttonWidth(0f), 0.0001f)
        assertEquals("controls: a short label expected 170 px", 340f / 3f, ControlsGeometry.buttonWidth(50f), 0.0001f)
        assertEquals("controls: a label of 81 dp expected still 170 px", 340f / 3f, ControlsGeometry.buttonWidth(81f), 0.0001f)
        assertEquals("controls: a label of 82 dp expected 114 dp", 114f, ControlsGeometry.buttonWidth(82f), 0.0001f)
        assertEquals("controls: a label of 100 dp expected 132 dp", 132f, ControlsGeometry.buttonWidth(100f), 0.0001f)
        assertEquals("controls: a label of 200 dp expected 232 dp", 232f, ControlsGeometry.buttonWidth(200f), 0.0001f)
        assertEquals("controls: the boundary expected 170 px on both sides", 340f / 3f, ControlsGeometry.buttonWidth(340f / 3f - 32f), 0.0001f)
    }

    /** A failure means a full width button ignores its measured row, takes its label where a row was measured, or drops below its floor. */
    @Test
    fun `a full width button fills the measured row and never drops below its floor`() {
        assertEquals("controls: a row of 1080 px expected 1080 px", 1080, ControlsGeometry.fullWidthPx(1080, 200, 72))
        assertEquals("controls: a row under the floor expected the floor", 72, ControlsGeometry.fullWidthPx(40, 200, 72))
        assertEquals("controls: no row expected the label width", 200, ControlsGeometry.fullWidthPx(null, 200, 72))
        assertEquals("controls: no row and a short label expected the floor", 72, ControlsGeometry.fullWidthPx(null, 30, 72))
        assertEquals("controls: a row at the floor expected the floor", 72, ControlsGeometry.fullWidthPx(72, 200, 72))
    }

    /** A failure means a step mark is lit past the current step, or the first mark is lit with no step at all. */
    @Test
    fun `a step mark is lit up to the current step and no further`() {
        fun lit(current: Int): List<Boolean> = (0..3).map { ControlsGeometry.isStepLit(it, current) }
        assertEquals("controls: step 1 expected the first mark lit", listOf(true, false, false, false), lit(1))
        assertEquals("controls: step 2 expected two marks lit", listOf(true, true, false, false), lit(2))
        assertEquals("controls: step 3 expected three marks lit", listOf(true, true, true, false), lit(3))
        assertEquals("controls: step 4 expected all marks lit", listOf(true, true, true, true), lit(4))
        assertEquals("controls: step 0 expected no mark lit", listOf(false, false, false, false), lit(0))
        assertEquals("controls: step 9 expected all marks lit", listOf(true, true, true, true), lit(9))
        assertEquals("controls: step -1 expected no mark lit", listOf(false, false, false, false), lit(-1))
    }

    /** A failure means a badge pill is not centred in its box, or starts left of or above the box. */
    @Test
    fun `a badge pill is centred in its box and never starts before the box`() {
        val ready = ControlsGeometry.badgePill("READY", 100f, 48f)
        assertEquals("controls: READY in a wide box expected x 15 dp", 15f, ready.x, 0.0001f)
        assertEquals("controls: READY in a wide box expected y 32 thirds", 32f / 3f, ready.y, 0.0001f)
        assertEquals("controls: READY expected 70 dp wide", 70f, ready.width, 0.0001f)
        assertEquals("controls: READY expected 80 thirds high", 80f / 3f, ready.height, 0.0001f)
        val exact = ControlsGeometry.badgePill("READY", 70f, 80f / 3f)
        assertEquals("controls: a box of the pill's own size expected x 0", 0f, exact.x, 0.0001f)
        assertEquals("controls: a box of the pill's own size expected y 0", 0f, exact.y, 0.0001f)
        val small = ControlsGeometry.badgePill("READY", 50f, 20f)
        assertEquals("controls: a box too small expected x never negative", 0f, small.x, 0f)
        assertEquals("controls: a box too small expected y never negative", 0f, small.y, 0f)
        val phone = ControlsGeometry.badgePill("PHONE ONLY", 400f, 48f)
        assertEquals("controls: PHONE ONLY centred expected x 430 thirds", 430f / 3f, phone.x, 0.0001f)
        assertEquals("controls: PHONE ONLY expected 340 thirds wide", 340f / 3f, phone.width, 0.0001f)
        val failed = ControlsGeometry.badgePill("FAILED", 400f, 48f)
        assertEquals("controls: FAILED centred expected x 482 thirds", 482f / 3f, failed.x, 0.0001f)
        assertEquals("controls: FAILED expected 236 thirds wide", 236f / 3f, failed.width, 0.0001f)
    }

    /** A failure means a badge takes the wrong fill part, or the wrong colour for that part in one of the two themes. */
    @Test
    fun `each badge takes the fill of its state in both themes`() {
        assertEquals("controls: READY expected the face part", ControlPart.FACE, ControlsPalette.badgeFacePart(BadgeKind.READY))
        assertEquals("controls: light READY expected 0xFF1E7A46", 0xFF1E7A46.toInt(), ControlsPalette.partColor(ControlsPalette.badgeFacePart(BadgeKind.READY), light))
        assertEquals("controls: dark READY expected 0xFF2E9E5B", 0xFF2E9E5B.toInt(), ControlsPalette.partColor(ControlsPalette.badgeFacePart(BadgeKind.READY), dark))
        assertEquals("controls: PHONE ONLY expected the warning face part", ControlPart.FACE_WARNING, ControlsPalette.badgeFacePart(BadgeKind.PHONE_ONLY))
        assertEquals("controls: light PHONE ONLY expected 0xFFA55713", 0xFFA55713.toInt(), ControlsPalette.partColor(ControlsPalette.badgeFacePart(BadgeKind.PHONE_ONLY), light))
        assertEquals("controls: dark PHONE ONLY expected 0xFFF39C12", 0xFFF39C12.toInt(), ControlsPalette.partColor(ControlsPalette.badgeFacePart(BadgeKind.PHONE_ONLY), dark))
        assertEquals("controls: FAILED expected the danger face part", ControlPart.FACE_DANGER, ControlsPalette.badgeFacePart(BadgeKind.FAILED))
        assertEquals("controls: light FAILED expected 0xFFC0392B", 0xFFC0392B.toInt(), ControlsPalette.partColor(ControlsPalette.badgeFacePart(BadgeKind.FAILED), light))
        assertEquals("controls: dark FAILED expected 0xFFE74C3C", 0xFFE74C3C.toInt(), ControlsPalette.partColor(ControlsPalette.badgeFacePart(BadgeKind.FAILED), dark))
    }

    /** A failure means a switch draws its outline while on, or leaves the outline off while off. */
    @Test
    fun `the switch outline is the track while off and absent while on`() {
        assertEquals("controls: off expected the outline at the track", Rect(0f, 0f, 48f, 80f / 3f), ControlsGeometry.switch(on = false, w = 48f, h = 80f / 3f).outline)
        assertNull("controls: on expected no outline", ControlsGeometry.switch(on = true, w = 48f, h = 80f / 3f).outline)
    }

    /** A failure means a thumb corner is not 3 px, or the button side or the button minimum has moved from the kit values. */
    @Test
    fun `the thumb corner is 3 px and the button sides hold the kit values`() {
        assertEquals("controls: the thumb corner expected 2 dp", 2f, ControlsGeometry.THUMB_CORNER_DP, 0f)
        assertEquals("controls: the button side expected 16 dp", 16f, ControlsGeometry.BUTTON_SIDE_DP, 0f)
        assertEquals("controls: the button minimum expected 170 px", 340f / 3f, ControlsGeometry.BUTTON_MIN_WIDTH_DP, 0.0001f)
    }
}
