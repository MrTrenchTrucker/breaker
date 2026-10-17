package dev.breaker.dictation.overlay

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.TilePosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The inset probe through the public tile: the facade hands the listener to the window, and a probe
 * that is on or off makes the same window calls. Built like the facade test, over a controller on fakes.
 */
class FloatingTileImeProbeTest {

    /** A failure here means the facade does not hand the listener it is given to the window. */
    @Test
    fun `the facade hands the listener to the window`() {
        val window = FakeTileWindow()
        val tile = FloatingTile(TestData.controller(window, FakeSettingsStore(AppSettings())))
        val listener: (Int, Boolean) -> Unit = { _, _ -> }

        tile.setImeProbe(listener)

        assertEquals("overlay: expected exactly one listener handed to the window", 1, window.imeProbes.size)
        assertSame("overlay: the window must receive the same listener", listener, window.imeProbes[0])
    }

    /** A failure here means a null listener is not passed on as null, so the probe could not be turned off. */
    @Test
    fun `a null listener reaches the window as null`() {
        val window = FakeTileWindow()
        val tile = FloatingTile(TestData.controller(window, FakeSettingsStore(AppSettings())))

        tile.setImeProbe(null)

        assertEquals("overlay: expected exactly one call handed to the window", 1, window.imeProbes.size)
        assertNull("overlay: a null listener must reach the window as null", window.imeProbes[0])
    }

    /** A failure here means the probe changes what a show and a hide do to the window. */
    @Test
    fun `show and hide make the same window calls with the probe on and off`() {
        val listener: (Int, Boolean) -> Unit = { _, _ -> }
        val on = FakeTileWindow()
        val onTile = FloatingTile(TestData.controller(on, FakeSettingsStore(TestData.settings(TilePosition(0.25f, 0.75f)))))
        onTile.setImeProbe(listener)
        onTile.show()
        onTile.hide()

        val off = FakeTileWindow()
        val offTile = FloatingTile(TestData.controller(off, FakeSettingsStore(TestData.settings(TilePosition(0.25f, 0.75f)))))
        offTile.show()
        offTile.hide()

        assertEquals("overlay: the probe on must make the same window calls as the probe off", off.calls, on.calls)
        assertEquals("overlay: the probe on must make the same window order as the probe off", off.order, on.order)
        assertEquals("overlay: the probe on must add the window at the same places", placesOf(off), placesOf(on))
        assertFalse("overlay: the probe on must still hide the tile", onTile.isShown)
    }

    /** A failure here means setting the probe makes a window call of its own, so the tile would change when the probe is set. */
    @Test
    fun `setting the probe changes no window call`() {
        val window = FakeTileWindow()
        val tile = FloatingTile(TestData.controller(window, FakeSettingsStore(AppSettings())))
        tile.show()
        val before = window.calls.toList()
        val listener: (Int, Boolean) -> Unit = { _, _ -> }

        tile.setImeProbe(listener)
        tile.setImeProbe(null)

        assertEquals("overlay: setting the probe must make no window call", before, window.calls)
        assertEquals("overlay: setting the probe must not remove the window", 0, window.removeCount)
        assertTrue("overlay: setting the probe must leave the tile shown", tile.isShown)
    }

    /** A failure here means setting or taking away the probe shows a tile that was hidden, which adds a window the app never asked for. */
    @Test
    fun `a hidden tile stays hidden when the probe is set or taken away`() {
        val window = FakeTileWindow()
        val tile = FloatingTile(TestData.controller(window, FakeSettingsStore(AppSettings())))
        val first: (Int, Boolean) -> Unit = { _, _ -> }
        val second: (Int, Boolean) -> Unit = { _, _ -> }

        tile.setImeProbe(first)
        tile.setImeProbe(null)
        tile.setImeProbe(second)
        tile.setImeProbe(null)

        assertEquals("overlay: a hidden tile must not be added by the probe", 0, window.adds.size)
        assertEquals("overlay: a hidden tile must make no window call when the probe is set or taken away", emptyList<String>(), window.calls)
        assertFalse("overlay: a hidden tile must stay hidden when the probe is set or taken away", tile.isShown)
        assertEquals("overlay: expected four listeners given in order", 4, window.imeProbes.size)
        assertSame("overlay: the first listener must reach the window", first, window.imeProbes[0])
        assertNull("overlay: the null after it must reach the window as null", window.imeProbes[1])
        assertSame("overlay: the second listener must reach the window", second, window.imeProbes[2])
        assertNull("overlay: the last null must reach the window as null", window.imeProbes[3])
    }

    private fun placesOf(window: FakeTileWindow): List<PixelPoint> = window.adds.map { PixelPoint(it.x, it.y) }
}
