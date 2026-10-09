package dev.breaker.dictation.wiring

import dev.breaker.dictation.overlay.TileState
import org.junit.Assert.assertEquals
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
}
