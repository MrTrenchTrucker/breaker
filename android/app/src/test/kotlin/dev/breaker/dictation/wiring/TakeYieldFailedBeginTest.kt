package dev.breaker.dictation.wiring

import dev.breaker.dictation.overlay.TileState
import org.junit.Assert.assertEquals
import org.junit.Test

/** The sentence a begin answers with when the device refuses to start the take. */
private const val TAKE_YIELD_FAILED_BEGIN_SENTENCE: String = "The device would not start the take."

/**
 * A begin that fails on the device, made from the busy face. The busy face must not survive the failure:
 * the tile shows the failure and the failure sentence, in that order.
 */
class TakeYieldFailedBeginTest {

    @Test
    fun `a begin that fails on the device after a take refused as taken shows FAILED and not the busy face`() {
        val rig = TileRig()
        // The first begin is refused as taken: the tile shows the busy face and posts no failure notice.
        rig.take.beginResult = BeginResult.Taken
        rig.coordinator.onBegin()
        rig.settle()
        assertEquals("app: a begin refused as taken should leave the busy face in place", TileState.MIC_BUSY, rig.tile.states.last())

        // The next begin, made from the busy face, fails on the device.
        rig.take.beginResult = BeginResult.Failed(TAKE_YIELD_FAILED_BEGIN_SENTENCE)
        rig.coordinator.onBegin()
        rig.settle()

        assertEquals("app: a begin that fails on the device should show FAILED, not the busy face", TileState.FAILED, rig.tile.states.last())
        assertEquals("app: the failure sentence should reach the tile once", listOf(TAKE_YIELD_FAILED_BEGIN_SENTENCE), rig.tile.notices)
        assertEquals("app: the notice should come after the FAILED state, which would clear it", "state:FAILED", rig.tile.calls[rig.tile.calls.size - 2])
    }
}
