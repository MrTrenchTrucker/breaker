package dev.breaker.dictation.ui.controls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The switch track, as the kit's own pair of numbers. The art draws its track 72 by 40 px, which is
 * 48 by 26.67 dp at 1.5 px per dp. The pair holds the numbers the switch borrowed before, so the
 * track draws the same pixels; these tests pin the values and the order of the pair.
 */
class ControlsSwitchTrackTest {

    /** A failure means the track pair is not the art track size in dp, or its width and height are swapped. */
    @Test
    fun `the switch track pair is the art track size in dp`() {
        assertEquals("controls: the track width expected 48 dp, the art's 72 px", 48f, ControlsGeometry.TRACK_W_DP, 0f)
        assertEquals("controls: the track height expected 26.67 dp, the art's 40 px", 80f / 3f, ControlsGeometry.TRACK_H_DP, 0.0001f)
    }

    /** A failure means the switch does not draw the pair as its track, on or off, or the off outline is not that track. */
    @Test
    fun `the switch draws the pair as its track on and off`() {
        val box = Rect(0f, 0f, 48f, 80f / 3f)
        val onParts = ControlsGeometry.switch(on = true, w = ControlsGeometry.TRACK_W_DP, h = ControlsGeometry.TRACK_H_DP)
        val offParts = ControlsGeometry.switch(on = false, w = ControlsGeometry.TRACK_W_DP, h = ControlsGeometry.TRACK_H_DP)
        assertEquals("controls: the on track expected the pair's box", box, onParts.track)
        assertEquals("controls: the off track expected the pair's box", box, offParts.track)
        assertEquals("controls: the off outline expected the track", box, offParts.outline)
        assertNull("controls: the on switch expected no outline", onParts.outline)
    }
}
