package dev.breaker.dictation.ui.screen.firstrun

import org.junit.Assert.assertEquals
import org.junit.Test

/** The first-run sizes and their two conversions, checked as plain numbers. */
class FirstRunLayoutTest {
    @Test
    fun `a picture 360 dp wide is 240 dp tall, the art's three to two shape`() {
        assertEquals(240f, FirstRunLayout.pictureHeightDp(360f), 0f)
    }

    @Test
    fun `a picture with no width has no height`() {
        assertEquals(0f, FirstRunLayout.pictureHeightDp(0f), 0f)
    }

    @Test
    fun `a pixel count is the nearest whole pixel`() {
        assertEquals(21, FirstRunLayout.dpToPx(10.5f, 2f))
        assertEquals(2, FirstRunLayout.dpToPx(1f, 1.5f))
    }

    @Test
    fun `a pixel count is never below zero`() {
        assertEquals(0, FirstRunLayout.dpToPx(-3f, 2f))
    }

    @Test
    fun `the page sizes are the ones the art gives`() {
        assertEquals(16f, FirstRunLayout.PAGE_PADDING_DP, 0f)
        assertEquals(12f, FirstRunLayout.GAP_DP, 0f)
        assertEquals(33f, FirstRunLayout.MARK_W_DP, 0f)
        assertEquals(8f, FirstRunLayout.MARK_H_DP, 0f)
        assertEquals(960, FirstRunLayout.PICTURE_WIDTH_PX)
        assertEquals(640, FirstRunLayout.PICTURE_HEIGHT_PX)
    }
}
