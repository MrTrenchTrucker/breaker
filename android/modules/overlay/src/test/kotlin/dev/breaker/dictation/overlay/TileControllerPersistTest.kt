package dev.breaker.dictation.overlay

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.SttMode
import dev.breaker.dictation.core.model.TilePosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.breaker.dictation.core.model.ThemeMode as CoreThemeMode

/**
 * Saving the tile position when a drag ends, and restoring it on the next show.
 *
 * The default screen is 16,80 to 1096,2300 with a 100 px tile: x 16..996 and y 80..2200, a movable range of
 * 980 by 2120. A saved fraction of 0.5 on both axes puts the tile at 506,1140. Every expected value below is
 * worked out by hand.
 */
class TileControllerPersistTest {

    /** A saved fraction and the pixel position it must give on the default screen. */
    private class Extreme(val fx: Float, val fy: Float, val x: Int, val y: Int)

    /** One controller over fresh fakes, with counters for the tap and failure callbacks. */
    private class Rig(seed: AppSettings = AppSettings(), withCallback: Boolean = true) {
        var taps = 0
        var saveFailures = 0
        val window = FakeTileWindow()
        val settings = FakeSettingsStore(seed)
        private val callback: () -> Unit = { saveFailures += 1 }
        val controller = TestData.controller(
            window,
            settings,
            onTap = { taps += 1 },
            onSaveFailed = if (withCallback) callback else null,
        )

        /** Shows the tile and checks it starts at the centre, 506,1140. */
        fun show() {
            assertEquals("android_overlay: show should report SHOWN", ShowResult.SHOWN, controller.show())
            assertEquals("android_overlay: one window should be added by show", 1, window.adds.size)
            assertEquals("android_overlay: the tile should start at x 506", 506, window.adds[0].x)
            assertEquals("android_overlay: the tile should start at y 1140", 1140, window.adds[0].y)
        }

        /** A whole drag: down at 300,300, one move by the delta, release where the move ended. */
        fun dragBy(dx: Float, dy: Float) {
            window.down(300f, 300f)
            window.move(300f + dx, 300f + dy)
            window.up(300f + dx, 300f + dy)
        }
    }

    /** If this fails, the saved position is not the fraction of the final clamped pixels, or it is saved more or less than once. */
    @Test
    fun `a drag end saves once with the fraction of the final clamped position`() {
        // Two moves end at 586,1200: (586-16)/980 and (1200-80)/2120.
        val inside = Rig()
        inside.show()
        inside.window.down(300f, 300f)
        inside.window.move(400f, 350f)
        inside.window.move(380f, 360f)
        inside.window.up(380f, 360f)
        assertEquals("android_overlay: a drag end should save exactly once", 1, inside.settings.saveCount)
        assertEquals("android_overlay: one settings value should be recorded", 1, inside.settings.saved.size)
        assertEquals("android_overlay: saved x should be 570/980", 0.5816327f, inside.settings.saved[0].tilePosition.x, 0.0001f)
        assertEquals("android_overlay: saved y should be 1120/2120", 0.5283019f, inside.settings.saved[0].tilePosition.y, 0.0001f)
        assertEquals("android_overlay: onSaveFailed should not be called on success", 0, inside.saveFailures)

        // Raw x 2506 clamps to 996 (fraction 1.0) and raw y 1300 stays: (1300-80)/2120.
        val clamped = Rig()
        clamped.show()
        clamped.dragBy(2000f, 160f)
        assertEquals("android_overlay: a clamped drag end should save exactly once", 1, clamped.settings.saveCount)
        assertEquals("android_overlay: saved x should be the right edge, 1.0", 1.0f, clamped.settings.saved[0].tilePosition.x, 0.0001f)
        assertEquals("android_overlay: saved y should be 1220/2120", 0.5754717f, clamped.settings.saved[0].tilePosition.y, 0.0001f)
        assertEquals("android_overlay: onSaveFailed should not be called after a clamped save", 0, clamped.saveFailures)
    }

    /** If this fails, the saved position follows the finger past an edge instead of the tile, so a drag that goes out and back saves the wrong place. */
    @Test
    fun `a drag past an edge and back saves the tile position and not the finger position`() {
        val rig = Rig()
        rig.show()

        // The first move is the whole delta from the down point: 1190-600 = 590, which asks for x 506+590 = 1096.
        // The largest x is 996, so the tile stops there.
        rig.window.down(600f, 1200f)
        rig.window.move(1190f, 1200f)
        assertEquals(
            "android_overlay: the first move should stop at the right edge, 996,1140",
            listOf(PixelPoint(996, 1140)),
            rig.window.moves,
        )

        // The finger comes back 1190-1090 = 100 px, so the tile goes from 996 to 896 (a finger-based position would give 996).
        rig.window.move(1090f, 1200f)
        assertEquals(
            "android_overlay: the second move should be 896,1140, 100 px back from the edge",
            listOf(PixelPoint(996, 1140), PixelPoint(896, 1140)),
            rig.window.moves,
        )
        rig.window.up(1090f, 1200f)

        // x is (896-16)/980 = 880/980 = 0.8979592 and y is (1140-80)/2120 = 0.5. An unclamped tracker would save x 1.0.
        assertEquals("android_overlay: the drag end should save exactly once", 1, rig.settings.saveCount)
        assertEquals("android_overlay: one settings value should be recorded", 1, rig.settings.saved.size)
        assertEquals("android_overlay: saved x should be 880/980", 0.8979592f, rig.settings.saved[0].tilePosition.x, 0.0001f)
        assertEquals("android_overlay: saved y should stay at 0.5", 0.5f, rig.settings.saved[0].tilePosition.y, 0.0001f)
    }

    /** If this fails, the settings are written on every move, not only when the finger lifts. */
    @Test
    fun `nothing is saved while the finger is still moving`() {
        val rig = Rig()
        rig.show()

        rig.window.down(300f, 300f)
        rig.window.move(400f, 350f)
        assertEquals("android_overlay: no save after the first move", 0, rig.settings.saveCount)
        rig.window.move(420f, 360f)
        assertEquals("android_overlay: no save after the second move", 0, rig.settings.saveCount)
        rig.window.move(450f, 380f)
        assertEquals("android_overlay: no save after the third move", 0, rig.settings.saveCount)
        assertTrue("android_overlay: nothing should be recorded before the release", rig.settings.saved.isEmpty())

        rig.window.up(450f, 380f)
        assertEquals("android_overlay: the release should save exactly once", 1, rig.settings.saveCount)
        // 656,1220 is (656-16)/980 and (1220-80)/2120.
        assertEquals("android_overlay: saved x should be 640/980", 0.6530612f, rig.settings.saved[0].tilePosition.x, 0.0001f)
        assertEquals("android_overlay: saved y should be 1140/2120", 0.5377358f, rig.settings.saved[0].tilePosition.y, 0.0001f)
    }

    /** If this fails, saving the tile position overwrites another setting with a default or a stale copy. */
    @Test
    fun `every other setting is kept when the position is saved`() {
        val seed = TestData.settings(TilePosition(0.5f, 0.5f))
        val rig = Rig(seed)
        rig.show()

        rig.dragBy(100f, 50f)

        assertEquals("android_overlay: one save expected", 1, rig.settings.saved.size)
        val saved = rig.settings.saved[0]
        assertEquals("android_overlay: mode should stay LOCAL", SttMode.LOCAL, saved.mode)
        assertEquals("android_overlay: modelSize should stay medium", "medium", saved.modelSize)
        assertEquals("android_overlay: serverUrl should stay as seeded", "http://example.invalid:8080", saved.serverUrl)
        assertEquals("android_overlay: apiKeyRef should stay ref-1", "ref-1", saved.apiKeyRef)
        assertEquals("android_overlay: wakeGestureEnabled should stay false", false, saved.wakeGestureEnabled)
        assertEquals("android_overlay: language should stay de", "de", saved.language)
        assertEquals("android_overlay: preloadModel should stay false", false, saved.preloadModel)
        assertEquals("android_overlay: formattingEnabled should stay false", false, saved.formattingEnabled)
        assertEquals("android_overlay: themeMode should stay DARK", CoreThemeMode.DARK, saved.themeMode)
        assertEquals("android_overlay: the tile position should have changed in x", 0.6020408f, saved.tilePosition.x, 0.0001f)
        assertEquals("android_overlay: the tile position should have changed in y", 0.5235849f, saved.tilePosition.y, 0.0001f)

        // The settings are read when the drag ends, not copied when the tile was shown.
        val later = Rig(seed)
        later.show()
        later.window.down(300f, 300f)
        later.window.move(400f, 350f)
        later.settings.current = later.settings.current.copy(language = "fr")
        later.window.up(400f, 350f)
        assertEquals("android_overlay: one save expected after a settings change", 1, later.settings.saved.size)
        assertEquals("android_overlay: a language changed during the drag should be kept", "fr", later.settings.saved[0].language)
        assertEquals("android_overlay: the other seeded fields should be kept", "medium", later.settings.saved[0].modelSize)
    }

    /** If this fails, a tap writes the settings. */
    @Test
    fun `a tap saves nothing`() {
        val rig = Rig()
        rig.show()

        rig.window.down(300f, 300f)
        rig.window.up(302f, 301f)

        assertEquals("android_overlay: the gesture should have tapped once", 1, rig.taps)
        assertEquals("android_overlay: a tap should not save", 0, rig.settings.saveCount)
        assertTrue("android_overlay: a tap should record no settings", rig.settings.saved.isEmpty())
    }

    /** If this fails, a dragged position is not restored on the next show, or is not fitted to the new screen. */
    @Test
    fun `a saved position is restored on the next show and fitted to a different screen`() {
        val rig = Rig()
        rig.show()
        rig.dragBy(100f, 50f)
        assertEquals("android_overlay: the drag should save once", 1, rig.settings.saveCount)

        rig.controller.hide()
        // A screen 10,20 to 610,1020 has a movable range of 500 by 900: 10+301 and 20+471.
        rig.window.bounds = PixelBounds(10, 20, 610, 1020)
        assertEquals("android_overlay: show after hide should report SHOWN", ShowResult.SHOWN, rig.controller.show())

        assertEquals("android_overlay: two windows should have been added in all", 2, rig.window.adds.size)
        assertEquals("android_overlay: restored x should be 311 on the new screen", 311, rig.window.adds[1].x)
        assertEquals("android_overlay: restored y should be 491 on the new screen", 491, rig.window.adds[1].y)
    }

    /** If this fails, a failing save escapes, is not reported exactly once, moves the tile back, or stops later saves. */
    @Test
    fun `a save that fails is swallowed, reported once, and the tile stays`() {
        val rig = Rig()
        rig.show()
        rig.settings.failSave = IllegalStateException("android_overlay: disk full")

        rig.dragBy(100f, 50f)

        assertEquals("android_overlay: the save should have been attempted once", 1, rig.settings.saveCount)
        assertTrue("android_overlay: a failed save should record nothing", rig.settings.saved.isEmpty())
        assertEquals("android_overlay: onSaveFailed should be called exactly once", 1, rig.saveFailures)
        assertEquals("android_overlay: the tile should not move back", listOf(PixelPoint(606, 1190)), rig.window.moves)

        // The next drag starts from 606,1190 and saves again: 706,1240 is 690/980 and 1160/2120.
        rig.settings.failSave = null
        rig.dragBy(100f, 50f)
        assertEquals("android_overlay: the next drag should move on from where the tile stayed", PixelPoint(706, 1240), rig.window.moves.last())
        assertEquals("android_overlay: the next drag end should save", 1, rig.settings.saved.size)
        assertEquals("android_overlay: saved x should be 690/980", 0.7040816f, rig.settings.saved[0].tilePosition.x, 0.0001f)
        assertEquals("android_overlay: saved y should be 1160/2120", 0.5471698f, rig.settings.saved[0].tilePosition.y, 0.0001f)
        assertEquals("android_overlay: onSaveFailed should not be called again on success", 1, rig.saveFailures)
    }

    /** If this fails, a failing settings read at drag end escapes, writes defaults over the settings, or is not reported once. */
    @Test
    fun `a read that fails while saving is handled like a failed save`() {
        val rig = Rig()
        rig.show()
        rig.window.down(300f, 300f)
        rig.window.move(400f, 350f)
        rig.settings.failLoad = IllegalStateException("android_overlay: read failed")

        rig.window.up(400f, 350f)

        assertEquals("android_overlay: onSaveFailed should be called exactly once", 1, rig.saveFailures)
        assertEquals("android_overlay: nothing should be written when the read failed", 0, rig.settings.saveCount)
        assertTrue("android_overlay: nothing should be recorded when the read failed", rig.settings.saved.isEmpty())
        assertEquals("android_overlay: the tile should not move back", listOf(PixelPoint(606, 1190)), rig.window.moves)

        // The next drag saves again: 706,1240.
        rig.settings.failLoad = null
        rig.dragBy(100f, 50f)
        assertEquals("android_overlay: the next drag should move on from where the tile stayed", PixelPoint(706, 1240), rig.window.moves.last())
        assertEquals("android_overlay: the next drag end should save", 1, rig.settings.saved.size)
        assertEquals("android_overlay: onSaveFailed should not be called again on success", 1, rig.saveFailures)
    }

    /** If this fails, a failed save with no callback given throws or loses the drag. */
    @Test
    fun `a failed save without a callback does not fail`() {
        val save = Rig(withCallback = false)
        save.show()
        save.settings.failSave = IllegalStateException("android_overlay: disk full")
        save.dragBy(100f, 50f)
        assertEquals("android_overlay: the save should have been attempted once", 1, save.settings.saveCount)
        assertEquals("android_overlay: the tile should stay at 606,1190", listOf(PixelPoint(606, 1190)), save.window.moves)

        val load = Rig(withCallback = false)
        load.show()
        load.window.down(300f, 300f)
        load.window.move(400f, 350f)
        load.settings.failLoad = IllegalStateException("android_overlay: read failed")
        load.window.up(400f, 350f)
        assertEquals("android_overlay: no write should happen after a failed read", 0, load.settings.saveCount)
        assertEquals("android_overlay: the tile should stay at 606,1190 after a failed read", listOf(PixelPoint(606, 1190)), load.window.moves)
    }

    /** If this fails, a saved fraction of 0 or 1 does not reach the matching edge of the usable area on both axes. */
    @Test
    fun `the extreme fractions put the tile at the extreme positions`() {
        val cases = listOf(
            Extreme(0f, 0f, 16, 80),
            Extreme(1f, 1f, 996, 2200),
            Extreme(0f, 1f, 16, 2200),
            Extreme(1f, 0f, 996, 80),
        )
        for (case in cases) {
            val window = FakeTileWindow()
            val settings = FakeSettingsStore(TestData.settings(TilePosition(case.fx, case.fy)))
            val controller = TestData.controller(window, settings)

            assertEquals("android_overlay: show should report SHOWN for ${case.fx},${case.fy}", ShowResult.SHOWN, controller.show())

            assertEquals("android_overlay: one add for ${case.fx},${case.fy}", 1, window.adds.size)
            assertEquals("android_overlay: x for fraction ${case.fx}", case.x, window.adds[0].x)
            assertEquals("android_overlay: y for fraction ${case.fy}", case.y, window.adds[0].y)
        }
    }
}
