package dev.breaker.dictation.ui.render

import dev.breaker.dictation.ui.screen.home.HomeGeometry
import dev.breaker.dictation.ui.theme.PaletteSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plain stripe variant of the Settings and History screens, as plain functions and geometry: three
 * bars, the primary colour on the first and third and the hover colour on the middle one; the bars sit
 * at the drawer frame's spacing and size, which is the three-bar motif this lane has measured.
 */
class BleedVariantTest {

    @Test
    fun `the plain look has three bars and the Home look has four`() {
        assertEquals("the plain look: three bars", 3, HomeGeometry.plainStripes().size)
        assertEquals("the Home look: four bars", 4, HomeGeometry.stripes().size)
    }

    @Test
    fun `the middle plain stripe takes the hover colour and the others the primary colour`() {
        assertEquals("the first plain stripe is primary", PaletteSlot.PRIMARY, plainStripeSlot(0))
        assertEquals("the middle plain stripe is the hover colour", PaletteSlot.PRIMARY_HOVER, plainStripeSlot(1))
        assertEquals("the third plain stripe is primary", PaletteSlot.PRIMARY, plainStripeSlot(2))
    }

    @Test
    fun `only the middle stripe is the hover stripe`() {
        assertFalse("the first is not the hover stripe", isHoverStripe(0))
        assertTrue("the middle is the hover stripe", isHoverStripe(1))
        assertFalse("the third is not the hover stripe", isHoverStripe(2))
    }

    @Test
    fun `the plain bars run top to bottom at the drawer frame's spacing and size`() {
        val bars = HomeGeometry.plainStripes()
        assertEquals("the first plain bar's y", 733.333f, bars[0].y, 0.001f)
        assertEquals("the second plain bar's y", 745.333f, bars[1].y, 0.001f)
        assertEquals("the third plain bar's y", 757.333f, bars[2].y, 0.001f)
        assertEquals("the plain bar's height", 6.667f, bars[0].height, 0.001f)
        assertEquals("the first plain bar's width", 173.333f, bars[0].width, 0.001f)
        assertEquals("the second plain bar's x", 20f, bars[1].x, 0.001f)
        assertEquals("the second plain bar's width", 200f, bars[1].width, 0.001f)
        assertEquals("the third plain bar's width", 126.667f, bars[2].width, 0.001f)
    }

    @Test
    fun `the plain bars are not the Home bars, their first top differs from the Home first top`() {
        assertFalse(
            "the plain look reuses the Home table by mistake",
            HomeGeometry.plainStripes().first().y == HomeGeometry.stripes().first().y,
        )
    }
}
