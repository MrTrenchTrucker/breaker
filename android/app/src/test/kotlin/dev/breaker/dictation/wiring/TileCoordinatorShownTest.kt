package dev.breaker.dictation.wiring

import dev.breaker.dictation.overlay.TileState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A take may begin only while the tile is on screen. A re-arm whose show did not answer SHOWN (no
 * permission, a refused window, or a show that throws) leaves the tile off, so a begin is refused even
 * though the tile still shows armed or sent. The rig and the fakes are the ones in TileFakes.kt.
 */
internal class TileCoordinatorShownTest {
    @Test
    fun `a begin after a re-arm whose show failed does not reach the take`() {
        val rig = TileRig()
        rig.tile.showResult = TileShow.FAILED
        rig.coordinator.onArmedChanged(true)
        rig.coordinator.onBegin()
        rig.settle()
        assertEquals("app: a begin after a re-arm whose show failed must not reach the take", 0, rig.take.beginCalls)
    }

    @Test
    fun `a begin after a re-arm without permission does not reach the take`() {
        val rig = TileRig()
        rig.tile.showResult = TileShow.NO_PERMISSION
        rig.coordinator.onArmedChanged(true)
        rig.coordinator.onBegin()
        rig.settle()
        assertEquals("app: a begin after a re-arm without permission must not reach the take", 0, rig.take.beginCalls)
    }

    @Test
    fun `a begin after a re-arm that shows again reaches the take once`() {
        val rig = TileRig()
        rig.coordinator.onArmedChanged(true)
        rig.coordinator.onBegin()
        rig.settle()
        assertEquals("app: a begin after a re-arm that shows must reach the take once", 1, rig.take.beginCalls)
    }

    @Test
    fun `a begin from sent after a re-arm whose show failed does not reach the take`() {
        val rig = TileRig()
        rig.startRecording()
        rig.coordinator.onSend()
        rig.settle()
        assertEquals("app: the take should be on SENT before the failed re-arm", TileState.SENT, rig.tile.states.last())
        rig.tile.showResult = TileShow.FAILED
        rig.coordinator.onArmedChanged(true)
        rig.coordinator.onBegin()
        rig.settle()
        assertEquals("app: a begin from SENT after a failed re-arm must not reach the take", 1, rig.take.beginCalls)
    }

    @Test
    fun `a begin is accepted again once a re-arm shows the tile`() {
        val rig = TileRig()
        rig.tile.showResult = TileShow.FAILED
        rig.coordinator.onArmedChanged(true)
        rig.tile.showResult = TileShow.SHOWN
        rig.coordinator.onArmedChanged(true)
        rig.coordinator.onBegin()
        rig.settle()
        assertEquals("app: a begin after the tile shows again must reach the take once", 1, rig.take.beginCalls)
        assertEquals("app: the take should be recording after that begin", TileState.RECORDING, rig.tile.states.last())
    }

    @Test
    fun `a failed re-arm pushes no state and only tries to show the tile`() {
        val rig = TileRig()
        val statesBefore: Int = rig.tile.states.size
        val callsBefore: Int = rig.tile.calls.size
        rig.tile.showResult = TileShow.FAILED
        rig.coordinator.onArmedChanged(true)
        assertEquals("app: a failed re-arm must not push a state", statesBefore, rig.tile.states.size)
        assertEquals("app: a failed re-arm should only try to show the tile", listOf("show"), rig.tile.calls.subList(callsBefore, rig.tile.calls.size))
    }

    @Test
    fun `a begin after a re-arm whose show throws does not reach the take`() {
        val rig = TileRig()
        rig.tile.throwing = true
        rig.coordinator.onArmedChanged(true)
        assertEquals("app: the throwing show should be the last tile call", "show", rig.tile.calls.last())
        rig.coordinator.onBegin()
        rig.settle()
        assertEquals("app: a begin after a re-arm whose show throws must not reach the take", 0, rig.take.beginCalls)
    }

    @Test
    fun `onCloseRequested hides the tile and remembers the close`() {
        val rig = TileRig()
        rig.coordinator.onCloseRequested()
        assertEquals("app: a close request must hide the tile", "hide", rig.tile.calls.last())
        // The close is remembered: the next begin shows the tile again instead of using the one on screen.
        rig.tile.calls.clear()
        rig.coordinator.onBegin()
        assertEquals("app: a begin after a close request must show the tile again", "show", rig.tile.calls.first())
    }

    @Test
    fun `a begin after a close request shows the tile and reaches the take`() {
        val rig = TileRig()
        rig.tile.calls.clear()
        rig.coordinator.onCloseRequested()
        rig.coordinator.onBegin()
        rig.settle()
        assertEquals("app: a close request must hide the tile", "hide", rig.tile.calls.first())
        assertEquals("app: a begin after a close request must show the tile again", 1, rig.tile.calls.count { it == "show" })
        assertEquals("app: a begin after a close request must reach the take once", 1, rig.take.beginCalls)
    }

    @Test
    fun `a begin whose re-show is refused after a close request does not reach the take`() {
        val rig = TileRig()
        rig.tile.showResult = TileShow.FAILED
        rig.coordinator.onCloseRequested()
        rig.coordinator.onBegin()
        rig.settle()
        // The refused re-show begins nothing and throws nothing; the close stays remembered for the next arm.
        assertEquals("app: a begin whose re-show is refused must not reach the take", 0, rig.take.beginCalls)
        assertEquals("app: a refused re-show must say the tile is unavailable", "tileUnavailable", rig.notice.calls.last())
    }

    @Test
    fun `a refused show leaves the flag set so the next begin works`() {
        val rig = TileRig()
        rig.tile.showResult = TileShow.FAILED
        rig.coordinator.onCloseRequested()
        rig.coordinator.onBegin()
        rig.settle()
        assertEquals("app: a begin whose re-show is refused must not reach the take", 0, rig.take.beginCalls)
        rig.tile.showResult = TileShow.SHOWN
        rig.tile.calls.clear()
        rig.coordinator.onBegin()
        rig.settle()
        assertEquals("app: the next begin must show the tile again", "show", rig.tile.calls.first())
        assertEquals("app: the next begin after a refused re-show must reach the take once", 1, rig.take.beginCalls)
    }

    @Test
    fun `launcher visible through onArmedChanged clears the close`() {
        val rig = TileRig()
        rig.coordinator.onCloseRequested()
        rig.coordinator.onArmedChanged(true)
        rig.tile.calls.clear()
        rig.coordinator.onBegin()
        rig.settle()
        assertEquals("app: a begin after the launcher is visible must not show the tile again", 0, rig.tile.calls.count { it == "show" })
        assertEquals("app: a begin after the launcher is visible must reach the take once", 1, rig.take.beginCalls)
    }

    @Test
    fun `switching the tile on again clears the close so a begin does not show it again`() {
        val rig = TileRig()
        rig.coordinator.onCloseRequested()
        // A re-arm clears the close, so the next begin does not show the tile again.
        rig.coordinator.onArmedChanged(true)
        rig.tile.calls.clear()
        rig.coordinator.onBegin()
        rig.settle()
        assertEquals("app: a begin after the tile is switched on again must not show it again", 0, rig.tile.calls.count { it == "show" })
        assertEquals("app: a begin after the tile is switched on again must reach the take once", 1, rig.take.beginCalls)
    }

    @Test
    fun `a fresh coordinator has no close to clear`() {
        val rig = TileRig(arm = false)
        val fresh = TileCoordinator(rig.tile, rig.main, rig.background, rig.take, rig.model, rig.notice, rig.opener)
        fresh.onArmedChanged(true)
        fresh.onBegin()
        rig.settle()
        assertEquals("app: a fresh coordinator must show the tile when it is switched on", 1, rig.tile.calls.count { it == "show" })
        assertEquals("app: a fresh coordinator must reach the take on its first begin", 1, rig.take.beginCalls)
    }

    @Test
    fun `a begin while the tile is on screen does not show it again`() {
        val rig = TileRig()
        rig.tile.calls.clear()
        rig.coordinator.onBegin()
        rig.settle()
        assertEquals("app: a begin while the tile is on screen must not show it again", 0, rig.tile.calls.count { it == "show" })
        assertEquals("app: a begin while the tile is on screen must reach the take once", 1, rig.take.beginCalls)
    }

    @Test
    fun `a begin after a close request whose show throws does not reach the take`() {
        val rig = TileRig()
        rig.tile.showThrows = true
        rig.coordinator.onCloseRequested()
        rig.coordinator.onBegin()  // must not throw
        rig.settle()
        assertEquals("app: a begin whose show throws must not reach the take", 0, rig.take.beginCalls)
        assertEquals("app: a begin whose show throws must say the tile is unavailable", "tileUnavailable", rig.notice.calls.last())
    }

    @Test
    fun `disarm clears the close flag`() {
        val rig = TileRig()
        rig.coordinator.onCloseRequested()
        rig.coordinator.onArmedChanged(false)  // disarm
        assertEquals("app: a disarm must clear the close flag", false, rig.coordinator.userClosedHandler.isClosed)
    }

    @Test
    fun `a refused re-show is not reported as shown`() {
        val rig = TileRig()
        rig.tile.showResult = TileShow.FAILED
        val statesBefore = rig.tile.states.size
        rig.coordinator.onCloseRequested()
        rig.coordinator.onBegin()
        rig.settle()
        // After a refused re-show, the tile must NOT have been pushed to a shown state
        assertEquals("app: a refused re-show must not push a state", statesBefore, rig.tile.states.size)
        assertEquals("app: a refused re-show must say the tile is unavailable", "tileUnavailable", rig.notice.calls.last())
    }

    @Test
    fun `a successful show clears the flag`() {
        val rig = TileRig()
        rig.tile.calls.clear()
        rig.coordinator.onCloseRequested()
        rig.coordinator.onBegin()
        rig.settle()
        assertEquals("app: a begin after a close must show the tile once", 1, rig.tile.calls.count { it == "show" })
        assertEquals("app: a begin after a close must reach the take once", 1, rig.take.beginCalls)
        // Flag is cleared: isClosed is false after a successful show
        assertEquals("app: a successful show must clear the close flag", false, rig.coordinator.userClosedHandler.isClosed)
    }

    @Test
    fun `a close request whose hide throws does not reach the caller`() {
        val rig = TileRig()
        rig.tile.hideThrows = true
        rig.coordinator.onCloseRequested()  // must not throw
        assertEquals("app: the close flag must stay set even if hide throws", true, rig.coordinator.userClosedHandler.isClosed)
    }

    @Test
    fun `a begin after a close with no model pushes armed and shows missing`() {
        val rig = TileRig(ready = false)
        rig.tile.calls.clear()
        rig.coordinator.onCloseRequested()
        rig.coordinator.onBegin()
        rig.settle()
        val showIdx = rig.tile.calls.indexOf("show")
        val armedIdx = rig.tile.calls.indexOf("state:ARMED")
        assertTrue("app: a begin after a close must push ARMED after showing", showIdx >= 0 && armedIdx > showIdx)
        assertEquals("app: a begin after a close with no model must show missing", "missing", rig.notice.calls.last())
    }
}
