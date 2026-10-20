package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [MicTapZone]: the two pure helpers resolve a touch's raw point into an action and decide a host click from
 * state alone. No platform names, no clock, no stored gesture in the decision itself. Every test is self-
 * contained: it sets its own state/shape rather than sharing mutable fields with other tests.
 */
class MicTapZoneTest {

    // Recording window at side s = 200 has three zones of width floor(48*200/64+0.5) = 150:
    // cancel [0,150), mic [150,300), send [300,450).
    private val zoneWidth = 150

    /** A touch whose down point lands at the centre of one of the three recording-window zones. */
    private fun zonePoint(zone: Int): Pair<Int, Float> {
        val x = when (zone) {
            0 -> zoneWidth / 2
            1 -> 3 * zoneWidth / 2
            else -> 5 * zoneWidth / 2
        }
        return x to (zoneWidth.toFloat() + 32f)
    }

    private fun resolve(state: TileState, shape: TileShape, x: Int): TileAction =
        MicTapZone.resolve(state, shape, x + 0.5f, zonePoint(1).second, 200)

    @Test
    fun `RECORDING mic zone is SEND`() {
        assertEquals(TileAction.SEND, resolve(TileState.RECORDING, TileShape.RECORDING, zonePoint(1).first))
    }

    @Test
    fun `RECORDING cancel zone is CANCEL`() {
        assertEquals(TileAction.CANCEL, resolve(TileState.RECORDING, TileShape.RECORDING, zonePoint(0).first))
    }

    @Test
    fun `RECORDING send zone is SEND`() {
        assertEquals(TileAction.SEND, resolve(TileState.RECORDING, TileShape.RECORDING, zonePoint(2).first))
    }

    @Test
    fun `RECORDING a point outside the window is NONE`() {
        assertEquals(TileAction.NONE, resolve(TileState.RECORDING, TileShape.RECORDING, 40_000))
    }

    @Test
    fun `COLLAPSED resolves to MIC action for any point`() {
        assertEquals(TileAction.TAP, MicTapZone.resolve(TileState.IDLE, TileShape.COLLAPSED, 19f, 5f, 200))
        assertEquals(TileAction.TAP, MicTapZone.resolve(TileState.FAILED, TileShape.COLLAPSED, 999f, 999f, 200))
    }

    @Test
    fun `ARMED mic zone is BEGIN`() {
        assertEquals(TileAction.BEGIN, resolve(TileState.ARMED, TileShape.RECORDING, zonePoint(1).first))
    }

    @Test
    fun `IDLE MIC zone is TAP and STRIP and else map correctly`() {
        assertEquals(TileAction.TAP, resolve(TileState.IDLE, TileShape.RECORDING, zonePoint(1).first)) // mic -> tap
        assertEquals(TileAction.NONE, resolve(TileState.IDLE, TileShape.RECORDING, zonePoint(0).first)) // cancel -> none
        assertEquals(TileAction.DISMISS, MicTapZone.resolve(TileState.IDLE, TileShape.NOTICE, 5f, 5f, 200)) // strip
    }

    @Test
    fun `SENDING is NONE at the mic while the finished outcomes map to TAP`() {
        assertEquals(TileAction.NONE, resolve(TileState.SENDING, TileShape.RECORDING, zonePoint(1).first))
        // The two finished outcomes answer like IDLE: a plain tap on the microphone.
        assertEquals("SENT maps to TAP", TileAction.TAP, resolve(TileState.SENT, TileShape.RECORDING, zonePoint(1).first))
        assertEquals("SENT_LOCAL maps to TAP", TileAction.TAP, resolve(TileState.SENT_LOCAL, TileShape.RECORDING, zonePoint(1).first))
    }

    @Test
    fun `MIC_BUSY maps to TAP like IDLE`() {
        assertEquals(TileAction.TAP, resolve(TileState.MIC_BUSY, TileShape.RECORDING, zonePoint(1).first))
    }

      // A fractional negative down point: floor(-0.5) is -1, outside every zone; a truncate
      // (toInt) would give 0, the cancel zone, and the mutant would survive.
      @Test
      fun `RECORDING a fractional negative x floors out of the window and resolves NONE`() {
          assertEquals(TileAction.NONE, MicTapZone.resolve(TileState.RECORDING, TileShape.RECORDING, -0.5f, 100f, 200))
      }

    // --- hostClick: the decision a click makes from state alone ---

    @Test
    fun `hostClick RECORDING is SEND regardless of stored point`() {
        assertEquals(TileAction.SEND, MicTapZone.hostClick(TileState.RECORDING))
    }

    @Test
    fun `hostClick ARMED is BEGIN`() {
        assertEquals(TileAction.BEGIN, MicTapZone.hostClick(TileState.ARMED))
    }

    @Test
    fun `hostClick idle-like states is TAP`() {
        for (st in listOf(TileState.IDLE, TileState.FAILED, TileState.SENT, TileState.SENT_LOCAL, TileState.MIC_BUSY)) {
            assertEquals("$st maps to TAP", TileAction.TAP, MicTapZone.hostClick(st))
        }
    }

    @Test
    fun `hostClick SENDING is NONE`() {
        assertEquals(TileAction.NONE, MicTapZone.hostClick(TileState.SENDING))
    }
}
