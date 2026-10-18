package dev.breaker.dictation.ui.screen.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The geometry of the home screen, one test per named block. Every expected value is the
 * literal dp the art gives, so a wrong factor or a wrong inset fails as a wrong number.
 */
class HomeGeometryTest {

    /** A failure means the bar is not the art's 108 px tall, or the menu lines are misplaced. */
    @Test
    fun `the top bar is 72 dp tall with three menu lines`() {
        val bar = HomeGeometry.topBar(360f)
        assertEquals("home: the bar expected 72 dp", 72f, bar.height, 0.001f)
        assertEquals("home: the clock expected x 14.667 dp", 14.667f, bar.clock.x, 0.001f)
        assertEquals("home: the clock expected y 21.333 dp", 21.333f, bar.clock.y, 0.001f)
        assertEquals("home: the clock expected size 13.333 dp", 13.333f, bar.clock.size, 0.001f)
        assertEquals("home: three menu lines expected", 3, bar.menuLines.size)
        assertEquals("home: the first menu line expected y 38.667 dp", 38.667f, bar.menuLines[0].y, 0.001f)
        assertEquals("home: the second menu line expected y 46.667 dp", 46.667f, bar.menuLines[1].y, 0.001f)
        assertEquals("home: the third menu line expected y 54.667 dp", 54.667f, bar.menuLines[2].y, 0.001f)
        assertEquals("home: the menu line expected width 22.667 dp", 22.667f, bar.menuLines[0].width, 0.001f)
        assertEquals("home: the menu line expected height 3.333 dp", 3.333f, bar.menuLines[0].height, 0.001f)
        assertEquals("home: the wordmark expected x 142.667 dp", 142.667f, bar.wordmark.x, 0.001f)
        assertEquals("home: the wordmark expected top 37.333 dp", 37.333f, bar.wordmark.top, 0.001f)
        assertEquals("home: the wordmark expected cap 20 dp", 20f, bar.wordmark.cap, 0.001f)
        assertEquals("home: the accent rule expected y 70.667 dp", 70.667f, bar.accentRule.y, 0.001f)
        assertEquals("home: the accent rule expected height 1.333 dp", 1.333f, bar.accentRule.height, 0.001f)
    }

    /** A failure means the card is not the art's rect, or the accent bar is misplaced. */
    @Test
    fun `the status card is the art's rect with its accent bar`() {
        val card = HomeGeometry.statusCard()
        assertEquals("home: the card expected x 13.333 dp", 13.333f, card.rect.x, 0.001f)
        assertEquals("home: the card expected y 85.333 dp", 85.333f, card.rect.y, 0.001f)
        assertEquals("home: the card expected width 333.333 dp", 333.333f, card.rect.width, 0.001f)
        assertEquals("home: the card expected height 138.667 dp", 138.667f, card.rect.height, 0.001f)
        assertEquals("home: the card expected radius 2.667 dp", 2.667f, card.radius, 0.001f)
        assertEquals("home: the accent bar expected width 4 dp", 4f, card.accentBar.width, 0.001f)
        assertEquals("home: the accent bar expected the card's full height", card.rect.height, card.accentBar.height, 0.001f)
    }

    /** A failure means the switch is not the art's rect, or the knob is misplaced. */
    @Test
    fun `the switch is the art's rect with its knob`() {
        val card = HomeGeometry.statusCard()
        val track = card.switch.track
        assertEquals("home: the track expected x 288 dp", 288f, track.x, 0.001f)
        assertEquals("home: the track expected y 98.667 dp", 98.667f, track.y, 0.001f)
        assertEquals("home: the track expected width 42.667 dp", 42.667f, track.width, 0.001f)
        assertEquals("home: the track expected height 22.667 dp", 22.667f, track.height, 0.001f)
        assertEquals("home: the track expected radius 2.667 dp", 2.667f, card.switch.trackRadius, 0.001f)
        val knob = card.switch.knob
        assertEquals("home: the knob expected x 309.333 dp", 309.333f, knob.x, 0.001f)
        assertEquals("home: the knob expected y 101.333 dp", 101.333f, knob.y, 0.001f)
        assertEquals("home: the knob expected width 18.667 dp", 18.667f, knob.width, 0.001f)
        assertEquals("home: the knob expected height 17.333 dp", 17.333f, knob.height, 0.001f)
        assertEquals("home: the knob expected radius 2 dp", 2f, card.switch.knobRadius, 0.001f)
    }

    /** A failure means the rows are not 24 dp apart, or the dash is missing. */
    @Test
    fun `the three status rows are 24 dp apart with dashes on the first two`() {
        val card = HomeGeometry.statusCard()
        assertEquals("home: three rows expected", 3, card.rows.size)
        assertEquals("home: the first row expected y 152 dp", 152f, card.rows[0].y, 0.001f)
        assertEquals("home: the second row expected y 176 dp", 176f, card.rows[1].y, 0.001f)
        assertEquals("home: the third row expected y 200 dp", 200f, card.rows[2].y, 0.001f)
        assertEquals("home: the row label expected x 29.333 dp", 29.333f, card.rows[0].labelX, 0.001f)
        assertEquals("home: the row value expected x 213.333 dp", 213.333f, card.rows[0].valueX, 0.001f)
        assertEquals("home: the first row expected a dash", true, card.rows[0].dash != null)
        assertEquals("home: the second row expected a dash", true, card.rows[1].dash != null)
        assertEquals("home: the third row expected no dash", null, card.rows[2].dash)
    }

    /** A failure means the mic is not the art's scale or origin. */
    @Test
    fun `the big mic is the art's scale and origin`() {
        val mic = HomeGeometry.bigMic()
        assertEquals("home: the mic expected scale 0.33", 0.33f, mic.scale, 0.001f)
        assertEquals("home: the mic expected x 75.28 dp", 75.28f, mic.x, 0.001f)
        assertEquals("home: the mic expected y 246.733 dp", 246.733f, mic.y, 0.001f)
    }

    /** A failure means the lettering is not the art's top. */
    @Test
    fun `the TAP TO TALK lettering is at the art's top`() {
        val lettering = HomeGeometry.lettering()
        assertEquals("home: the tap expected centre x 180 dp", 180f, lettering.tap.x, 0.001f)
        assertEquals("home: the tap expected top 484 dp", 484f, lettering.tap.top, 0.001f)
        assertEquals("home: the tap expected cap 17.333 dp", 17.333f, lettering.tap.cap, 0.001f)
        assertEquals("home: the hint expected x 41.333 dp", 41.333f, lettering.hint.x, 0.001f)
        assertEquals("home: the hint expected y 528 dp", 528f, lettering.hint.y, 0.001f)
        assertEquals("home: the hint expected size 14 dp", 14f, lettering.hint.size, 0.001f)
    }

    /** A failure means the box is not the art's rect, or the outline is the wrong width. */
    @Test
    fun `the transcript box is the art's rect with its outline`() {
        val box = HomeGeometry.transcriptBox()
        assertEquals("home: the box expected x 13.333 dp", 13.333f, box.rect.x, 0.001f)
        assertEquals("home: the box expected y 548 dp", 548f, box.rect.y, 0.001f)
        assertEquals("home: the box expected width 333.333 dp", 333.333f, box.rect.width, 0.001f)
        assertEquals("home: the box expected height 156 dp", 156f, box.rect.height, 0.001f)
        assertEquals("home: the box expected radius 2.667 dp", 2.667f, box.radius, 0.001f)
        assertEquals("home: the box expected outline 1.333 dp", 1.333f, box.outline, 0.001f)
    }

    /** A failure means the Copy button is not exactly 100x48 dp, the touch floor. */
    @Test
    fun `the Copy button is exactly 100x48 dp, the touch floor`() {
        val button = HomeGeometry.copyButton()
        assertEquals("home: the button expected width 100 dp", 100f, button.rect.width, 0.001f)
        assertEquals("home: the button expected height 48 dp", 48f, button.rect.height, 0.001f)
        assertTrue("home: the button expected at least 48 dp tall", button.rect.height >= 48f)
        assertEquals("home: the button expected x 29.333 dp", 29.333f, button.rect.x, 0.001f)
        assertEquals("home: the button expected y 642.667 dp", 642.667f, button.rect.y, 0.001f)
        assertEquals("home: the button expected radius 2.667 dp", 2.667f, button.radius, 0.001f)
    }

    /** A failure means a stripe is not the art's span or y. */
    @Test
    fun `the four bottom stripes are the art's spans and y`() {
        val stripes = HomeGeometry.stripes()
        assertEquals("home: four stripes expected", 4, stripes.size)
        assertEquals("home: the first stripe expected x 0 dp", 0f, stripes[0].x, 0.001f)
        assertEquals("home: the first stripe expected width 200 dp", 200f, stripes[0].width, 0.001f)
        assertEquals("home: the second stripe expected x 26.667 dp", 26.667f, stripes[1].x, 0.001f)
        assertEquals("home: the second stripe expected width 260 dp", 260f, stripes[1].width, 0.001f)
        assertEquals("home: the third stripe expected x 0 dp", 0f, stripes[2].x, 0.001f)
        assertEquals("home: the third stripe expected width 146.667 dp", 146.667f, stripes[2].width, 0.001f)
        assertEquals("home: the fourth stripe expected x 166.667 dp", 166.667f, stripes[3].x, 0.001f)
        assertEquals("home: the fourth stripe expected width 180 dp", 180f, stripes[3].width, 0.001f)
        assertEquals("home: the first stripe expected y 724 dp", 724f, stripes[0].y, 0.001f)
        assertEquals("home: the second stripe expected y 736 dp", 736f, stripes[1].y, 0.001f)
        assertEquals("home: the third stripe expected y 748 dp", 748f, stripes[2].y, 0.001f)
        assertEquals("home: the fourth stripe expected y 760 dp", 760f, stripes[3].y, 0.001f)
        assertEquals("home: the stripe expected height 6.667 dp", 6.667f, stripes[0].height, 0.001f)
    }

    /** A failure means the drawer panel is not the art's width. */
    @Test
    fun `the drawer panel is the art's width`() {
        val drawer = HomeGeometry.drawer()
        assertEquals("home: the panel expected 266.667 dp", 266.667f, drawer.panelWidth, 0.001f)
        assertEquals("home: the wordmark expected x 56 dp", 56f, drawer.wordmark.x, 0.001f)
        assertEquals("home: the wordmark expected top 45.333 dp", 45.333f, drawer.wordmark.top, 0.001f)
        assertEquals("home: the wordmark expected cap 26.667 dp", 26.667f, drawer.wordmark.cap, 0.001f)
        assertEquals("home: the accent rule expected y 88 dp", 88f, drawer.accentRule.y, 0.001f)
    }

    /** A failure means the menu items are not 48 dp apart, or the dividers are misplaced. */
    @Test
    fun `the four menu items are 48 dp apart with dividers`() {
        val items = HomeGeometry.menuItems()
        assertEquals("home: four items expected", 4, items.size)
        assertEquals("home: the first item expected y 128 dp", 128f, items[0].label.top, 0.001f)
        assertEquals("home: the second item expected y 176 dp", 176f, items[1].label.top, 0.001f)
        assertEquals("home: the third item expected y 224 dp", 224f, items[2].label.top, 0.001f)
        assertEquals("home: the fourth item expected y 272 dp", 272f, items[3].label.top, 0.001f)
        assertEquals("home: the item label expected x 20 dp", 20f, items[0].label.x, 0.001f)
        assertEquals("home: the item label expected cap 14.667 dp", 14.667f, items[0].label.cap, 0.001f)
        assertEquals("home: the divider expected x 20 dp", 20f, items[0].divider.x, 0.001f)
        assertEquals("home: the divider expected width 226.667 dp", 226.667f, items[0].divider.width, 0.001f)
        assertEquals("home: the divider expected height 0.667 dp", 0.667f, items[0].divider.height, 0.001f)
    }

    /** A failure means a drawer stripe is not the art's span or y. */
    @Test
    fun `the three drawer stripes are the art's spans and y`() {
        val drawer = HomeGeometry.drawer()
        assertEquals("home: three stripes expected", 3, drawer.stripes.size)
        assertEquals("home: the first stripe expected x 0 dp", 0f, drawer.stripes[0].x, 0.001f)
        assertEquals("home: the first stripe expected width 173.333 dp", 173.333f, drawer.stripes[0].width, 0.001f)
        assertEquals("home: the second stripe expected x 20 dp", 20f, drawer.stripes[1].x, 0.001f)
        assertEquals("home: the second stripe expected width 200 dp", 200f, drawer.stripes[1].width, 0.001f)
        assertEquals("home: the third stripe expected x 0 dp", 0f, drawer.stripes[2].x, 0.001f)
        assertEquals("home: the third stripe expected width 126.667 dp", 126.667f, drawer.stripes[2].width, 0.001f)
        assertEquals("home: the first stripe expected y 733.333 dp", 733.333f, drawer.stripes[0].y, 0.001f)
        assertEquals("home: the second stripe expected y 745.333 dp", 745.333f, drawer.stripes[1].y, 0.001f)
        assertEquals("home: the third stripe expected y 757.333 dp", 757.333f, drawer.stripes[2].y, 0.001f)
    }

    /** A failure means the drawer dim is not the art's 55%. */
    @Test
    fun `the drawer dim is 55 percent`() {
        assertEquals("home: the drawer dim expected 0.55", 0.55f, HomeGeometry.DRAWER_DIM, 0.001f)
    }
}
