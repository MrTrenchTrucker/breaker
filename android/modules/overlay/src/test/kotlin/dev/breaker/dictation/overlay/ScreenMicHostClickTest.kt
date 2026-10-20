package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The touch path and the host path must disagree where the bug says they did: a click over the cancel zone is a
 * SEND for the host (state-based) but a CANCEL for a stored-down tap. Because [ScreenMicView] is an Android
 * [android.view.View], it cannot be constructed on a plain JVM, so this test exercises the pure decision
 * [MicTapZone.resolve] and [MicTapZone.hostClick] that the view delegates to — exactly the math both paths use.
 */
class ScreenMicHostClickTest {

    // The recording window at side s = 200 has three zones: cancel [0,150), mic [150,300), send [300,450).
    private val side = 200
    private val downCancelX = 75f // midpoint of the cancel zone

    /** The decision a touch whose down point is in [x] makes through the view's own path (the same math as route()). */
    private fun touchDecision(state: TileState, shape: TileShape, x: Float) =
        MicTapZone.resolve(state, shape, x, 32f, side)

    /** The decision a host click makes from state alone — the stored point is irrelevant to it. */
    private fun hostDecision(state: TileState) = MicTapZone.hostClick(state)

    @Test
    fun `a stored-down tap in the cancel zone resolves to CANCEL`() {
        val shape = TileShape.RECORDING
        // (75, 32) is inside the cancel zone [0,150); a touch there cancels.
        assertEquals(
            "the touch path must cancel a tap whose down point lands in the cancel zone",
            TileAction.CANCEL,
            touchDecision(TileState.RECORDING, shape, downCancelX),
        )
    }

    @Test
    fun `a stored-down tap in the send zone resolves to SEND`() {
        val shape = TileShape.RECORDING
        // (375, 32) is inside the send zone [300,450); a touch there sends.
        assertEquals(
            "the touch path must send a tap whose down point lands in the send zone",
            TileAction.SEND,
            touchDecision(TileState.RECORDING, shape, 375f),
        )
    }

    @Test
    fun `a stored-down tap in the mic zone resolves to SEND`() {
        val shape = TileShape.RECORDING
        // (224, 32) is inside the mic zone [150,300); a touch there sends.
        assertEquals(
            "the touch path must send a tap whose down point lands in the mic zone",
            TileAction.SEND,
            touchDecision(TileState.RECORDING, shape, 224f),
        )
    }

    @Test
    fun `host click does not read the stored down point`() {
        val shape = TileShape.RECORDING
        // With that same stored down point in the cancel zone:
        assertEquals(
            "the host decision for RECORDING is SEND, ignoring where it was clicked",
            TileAction.SEND,
            hostDecision(TileState.RECORDING),
        )
        // Same state, no matter what point "stored" — still SEND.
        assertEquals(
            "hostClick ignores the stored point entirely: RECORDING always sends",
            TileAction.SEND,
            MicTapZone.hostClick(state = TileState.RECORDING),
        )
    }

    @Test
    fun `the old behaviour would have returned CANCEL for the same tap`() {
        // The bug read the stored down point: the host decision was resolve(RECORDING, RECORDING, downCancelX, 32f, side) == CANCEL.
        val shape = TileShape.RECORDING
        val oldHostDecision = MicTapZone.resolve(TileState.RECORDING, shape, downCancelX, 32f, side)
        assertEquals(
            "the stored-gesture bug would have cancelled",
            TileAction.CANCEL,
            oldHostDecision,
        )
        // The new host decision differs from that buggy one exactly in this case:
        assertTrue(
            "the new host decision must not match the stored-gesture decision",
            oldHostDecision != MicTapZone.hostClick(TileState.RECORDING),
        )
    }

    @Test
    fun `host click never cancels for RECORDING while a touch can`() {
        // The two paths disagree precisely where the bug lived: the cancel zone.
        val shape = TileShape.RECORDING
        assertEquals(TileAction.CANCEL, touchDecision(TileState.RECORDING, shape, 75f))
        assertTrue(
            "the host path must send rather than cancel for a click in the cancel zone",
            MicTapZone.hostClick(TileState.RECORDING) == TileAction.SEND,
        )
    }

      // --- hostAction dispatch: the pure when(...) behind the host click's callbacks ---

      private fun runDispatch(
          action: TileAction,
          onSend: (() -> Unit)? = null,
          onCancel: (() -> Unit)? = null,
          onBegin: (() -> Unit)? = null,
          onTap: (() -> Unit)? = null,
      ): List<String> {
          val ran = mutableListOf<String>()
          ScreenMicProbeKit.dispatch(
              action,
              onSend?.let { { ran += "send" } } ?: onSend,
              onCancel?.let { { ran += "cancel" } } ?: onCancel,
              onBegin?.let { { ran += "begin" } }?: onBegin,
              onTap?.let { { ran += "tap" } } ?: {},
          )
          return ran
      }

      @Test
      fun `dispatch SEND runs only onSend`() {
          assertEquals(listOf("send"), runDispatch(TileAction.SEND, onSend = {}, onCancel = {}, onBegin = {}))
      }

      @Test
      fun `dispatch CANCEL runs only onCancel`() {
          assertEquals(listOf("cancel"), runDispatch(TileAction.CANCEL, onSend = {}, onCancel = {}, onBegin = {}))
      }

      @Test
      fun `dispatch BEGIN runs only onBegin`() {
          assertEquals(listOf("begin"), runDispatch(TileAction.BEGIN, onSend = {}, onCancel = {}, onBegin = {}))
      }

      @Test
      fun `dispatch TAP runs only onTap`() {
          assertEquals(listOf("tap"), runDispatch(TileAction.TAP, onTap = {}))
      }

      @Test
      fun `dispatch NONE and DISMISS run nothing`() {
          assertEquals(emptyList<String>(), runDispatch(TileAction.NONE))
          assertEquals(emptyList<String>(), runDispatch(TileAction.DISMISS))
      }
}
