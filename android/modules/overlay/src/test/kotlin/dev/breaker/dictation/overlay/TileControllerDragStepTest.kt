package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a drag step reads fresh, and where a drag starts after the screen changed.
 *
 * The default screen is 16,80 to 1096,2300 with a 100 px tile, so the tile may sit at x 16..996 and y 80..2200.
 * A saved fraction of 0.5 on both axes puts it at 506,1140. Every expected pixel below is worked out by hand.
 */
class TileControllerDragStepTest {

    /** One controller over fresh fakes. */
    private class Rig {
        val window = FakeTileWindow()
        val settings = FakeSettingsStore()
        val controller = TestData.controller(window, settings)

        /** Shows the tile and checks it starts at the centre, 506,1140. */
        fun show() {
            assertEquals("android_overlay: show should report SHOWN", ShowResult.SHOWN, controller.show())
            assertEquals("android_overlay: one window should be added by show", 1, window.adds.size)
            assertEquals("android_overlay: the tile should start at x 506", 506, window.adds[0].x)
            assertEquals("android_overlay: the tile should start at y 1140", 1140, window.adds[0].y)
        }
    }

    /** If this fails, the tile size is read once and kept, so a drag is clamped with a stale size after the size changed. */
    @Test
    fun `the tile size is read again on every drag step`() {
        val rig = Rig()
        rig.show()

        // The first move is the whole delta from the down point: 600 to 620 is 20, so 506+20 = 526.
        rig.window.down(600f, 1200f)
        rig.window.move(620f, 1200f)
        assertEquals(
            "android_overlay: the first move should follow the finger to 526,1140",
            listOf(PixelPoint(526, 1140)),
            rig.window.moves,
        )

        // The tile grows to 200 px: the largest x is 1096-200 = 896. The finger moved 1090-620 = 470,
        // which asks for 526+470 = 996. A size read at show (100 px) would allow 996.
        rig.window.sizePx = 200
        rig.window.move(1090f, 1200f)

        assertEquals(
            "android_overlay: the second move should clamp to x 896 with the 200 px tile, y unchanged at 1140",
            listOf(PixelPoint(526, 1140), PixelPoint(896, 1140)),
            rig.window.moves,
        )
    }

    /** If this fails, a drag after a display change starts from the old pixels, so the tile jumps away from the finger. */
    @Test
    fun `a drag after a display change moves from the repositioned tile`() {
        val rig = Rig()
        rig.show()

        // A screen 10,20 to 610,1020 is 600 by 1000, so the movable range is 500 by 900 with the 100 px tile.
        // The centre fraction gives x 10+250 = 260 and y 20+450 = 470.
        rig.window.bounds = PixelBounds(10, 20, 610, 1020)
        rig.controller.onDisplayChanged()
        assertEquals(
            "android_overlay: the display change should place the tile at 260,470",
            listOf(PixelPoint(260, 470)),
            rig.window.moves,
        )

        // The first step is the whole delta from the down point: 300 to 320 is 20, 500 to 500 is 0.
        // From 260,470 that is 280,470. Starting from the old 506,1140 it would be clamped to 510,920.
        rig.window.down(300f, 500f)
        rig.window.move(320f, 500f)

        assertEquals(
            "android_overlay: the drag should start from 260,470 and move to 280,470",
            listOf(PixelPoint(260, 470), PixelPoint(280, 470)),
            rig.window.moves,
        )
    }
}
