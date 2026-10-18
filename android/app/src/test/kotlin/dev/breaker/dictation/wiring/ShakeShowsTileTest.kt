package dev.breaker.dictation.wiring

import dev.breaker.dictation.overlay.TileState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A shake shows the tile when the tile is closed or hidden, and never begins a take. The rig and the fakes
 * are the ones in TileFakes.kt. The shake is handed straight to the coordinator, as the tile host posts it.
 */
internal class ShakeShowsTileTest {

    @Test
    fun `a shake with the tile closed shows it and never begins a take`() {
        val rig = TileRig()
        rig.coordinator.onCloseRequested()
        rig.tile.calls.clear()
        rig.coordinator.onShake()
        rig.settle()
        assertEquals("app: a shake with the tile closed must show the tile exactly once", 1, rig.tile.calls.count { it == "show" })
        assertFalse("app: a shake that shows the tile must clear the user-closed flag", rig.coordinator.tileShowGuard.isClosed)
        assertEquals("app: a shake must never begin a take", 0, rig.take.beginCalls)
        val showAt: Int = rig.tile.calls.indexOf("show")
        assertTrue("app: a shake that shows the tile must push the tile face after the show", rig.tile.calls.indexOfFirst { it.startsWith("state:") } > showAt)
    }

    @Test
    fun `a shake with the tile on screen does nothing at all`() {
        val rig = TileRig()
        rig.tile.calls.clear()
        rig.coordinator.onShake()
        rig.settle()
        assertEquals("app: a shake with the tile on screen must make no tile call", emptyList<String>(), rig.tile.calls)
        assertEquals("app: a shake with the tile on screen must begin no take", 0, rig.take.beginCalls)
    }

    @Test
    fun `a shake while a take is recording does nothing`() {
        val rig = TileRig()
        rig.startRecording()
        rig.coordinator.onCloseRequested()
        rig.tile.calls.clear()
        rig.coordinator.onShake()
        rig.settle()
        assertEquals("app: a shake while recording must make no tile call", emptyList<String>(), rig.tile.calls)
        assertEquals("app: a shake while recording must leave the one take begun", 1, rig.take.beginCalls)
    }

    @Test
    fun `a shake while a begin is pending does nothing`() {
        val rig = TileRig()
        rig.coordinator.onBegin()
        rig.coordinator.onCloseRequested()
        rig.tile.calls.clear()
        rig.coordinator.onShake()
        assertEquals("app: a shake while a begin is pending must make no show call", 0, rig.tile.calls.count { it == "show" })
        assertFalse("app: a shake while a begin is pending must not call show at all", rig.tile.calls.contains("show"))
        rig.settle()
        assertEquals("app: a shake while a begin is pending must leave only the tap's one take begun", 1, rig.take.beginCalls)
    }

    @Test
    fun `a shake while the finish is sending does nothing`() {
        val rig = TileRig()
        rig.startRecording()
        rig.coordinator.onSend()
        assertEquals("app: the take should be on SENDING before the shake", TileState.SENDING, rig.tile.states.last())
        rig.coordinator.onCloseRequested()
        rig.tile.calls.clear()
        rig.coordinator.onShake()
        assertEquals("app: a shake while the finish is sending must make no tile show call", 0, rig.tile.calls.count { it == "show" })
    }

    @Test
    fun `a shake when the switch is off does nothing`() {
        val rig = TileRig(arm = false)
        rig.coordinator.onShake()
        rig.settle()
        assertEquals("app: a shake while the switch is off must make no tile call", emptyList<String>(), rig.tile.calls)
        assertEquals("app: a shake while the switch is off must begin no take", 0, rig.take.beginCalls)
    }

    @Test
    fun `a shake after a refused show tries the show again and says the tile is unavailable again and then leaves the shown tile alone`() {
        val rig = TileRig(arm = false)
        rig.tile.showResult = TileShow.FAILED
        rig.coordinator.onArmedChanged(true)
        rig.tile.calls.clear()
        rig.notice.calls.clear()
        rig.coordinator.onShake()
        rig.settle()
        assertEquals("app: a shake after a refused show must try the show once more", 1, rig.tile.calls.count { it == "show" })
        assertEquals("app: a refused shake show must say the tile is unavailable once more", 1, rig.notice.calls.count { it == "tileUnavailable" })
        assertEquals("app: a refused shake show must begin no take", 0, rig.take.beginCalls)
        rig.tile.showResult = TileShow.SHOWN
        rig.tile.calls.clear()
        rig.coordinator.onShake()
        rig.settle()
        assertTrue("app: the next shake must show the tile and push its face", rig.tile.calls.any { it.startsWith("state:") })
        assertEquals("app: a shake that shows the tile must begin no take", 0, rig.take.beginCalls)
        rig.tile.calls.clear()
        rig.coordinator.onShake()
        rig.settle()
        assertFalse("app: a shake after the tile was shown again must not show it a second time", rig.tile.calls.contains("show"))
        assertEquals("app: a shake after the tile was shown again must begin no take", 0, rig.take.beginCalls)
    }

    @Test
    fun `a shake that shows the tile after a refused show takes the unavailable notice away`() {
        val rig = TileRig(arm = false)
        rig.tile.showResult = TileShow.FAILED
        rig.coordinator.onArmedChanged(true)
        rig.tile.showResult = TileShow.SHOWN
        rig.notice.calls.clear()
        rig.coordinator.onShake()
        rig.settle()
        assertTrue("app: a shake that shows the tile must clear the unavailable notice", rig.notice.calls.contains("clear"))
    }

    @Test
    fun `a refused show on a tile the user closed keeps it closed so the next shake tries again`() {
        val rig = TileRig()
        rig.tile.calls.clear()
        rig.coordinator.onCloseRequested()
        rig.tile.showResult = TileShow.FAILED
        rig.coordinator.onShake()
        rig.settle()
        assertTrue("app: a refused shake show on a closed tile must keep the tile closed", rig.coordinator.tileShowGuard.isClosed)
        rig.tile.showResult = TileShow.SHOWN
        rig.coordinator.onShake()
        rig.settle()
        assertFalse("app: the next shake that shows the tile must clear the closed flag", rig.coordinator.tileShowGuard.isClosed)
        assertEquals("app: the two shakes must call show twice in total", 2, rig.tile.calls.count { it == "show" })
    }

    @Test
    fun `two shakes in a row show the tile once`() {
        val rig = TileRig()
        rig.coordinator.onCloseRequested()
        rig.tile.calls.clear()
        rig.coordinator.onShake()
        rig.coordinator.onShake()
        rig.settle()
        assertEquals("app: two shakes in a row must show the tile exactly once", 1, rig.tile.calls.count { it == "show" })
    }

    @Test
    fun `a tap on the tile is still what begins a take after a shake showed it`() {
        val rig = TileRig()
        rig.coordinator.onCloseRequested()
        rig.coordinator.onShake()
        rig.settle()
        assertEquals("app: a shake that showed the tile must begin no take", 0, rig.take.beginCalls)
        rig.coordinator.onBegin()
        rig.settle()
        assertEquals("app: a tap after a shake that showed the tile must begin one take", 1, rig.take.beginCalls)
    }
}
