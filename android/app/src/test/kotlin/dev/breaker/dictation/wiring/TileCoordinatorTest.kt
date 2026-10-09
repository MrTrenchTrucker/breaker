package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.overlay.TileState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TileCoordinatorTest {
    @Test
    fun `switching on shows the tile, pushes armed and clears the unavailable notice`() {
        val rig = TileRig(arm = false)
        rig.coordinator.onArmedChanged(true)
        assertEquals("app: arming should show the tile and then push the state", listOf("show", "state:ARMED"), rig.tile.calls)
        assertEquals("app: a shown tile should clear the notifications", listOf("clear"), rig.notice.calls)
    }

    @Test
    fun `a tile that cannot be shown tells the user and is not pushed`() {
        for (result in listOf(TileShow.NO_PERMISSION, TileShow.FAILED)) {
            val rig = TileRig(arm = false)
            rig.tile.showResult = result
            rig.coordinator.onArmedChanged(true)
            assertEquals("app: $result should only try to show the tile", listOf("show"), rig.tile.calls)
            assertEquals("app: $result should raise the tile-unavailable notice once", listOf("tileUnavailable"), rig.notice.calls)
        }
    }

    @Test
    fun `a tile whose show throws is treated like a failed show`() {
        val rig = TileRig(arm = false)
        rig.tile.throwing = true
        rig.coordinator.onArmedChanged(true)
        assertEquals("app: a throwing show should raise the tile-unavailable notice", listOf("tileUnavailable"), rig.notice.calls)
        assertEquals("app: a throwing show should be the only tile call", listOf("show"), rig.tile.calls)
    }

    @Test
    fun `switching off pushes idle, hides the tile and cancels the take on the background`() {
        val rig = TileRig()
        rig.tile.calls.clear()
        rig.coordinator.onArmedChanged(false)
        assertEquals("app: disarming should push IDLE and then hide", listOf("state:IDLE", "hide"), rig.tile.calls)
        assertEquals("app: the cancel must not run on the calling thread", 0, rig.take.cancelCalls)
        assertEquals("app: the cancel should be queued on the background once", 1, rig.background.pending)
        rig.background.runAll()
        assertEquals("app: the queued cancel should drop the take once", 1, rig.take.cancelCalls)
    }

    @Test
    fun `a tap on a tile that is switched off opens the launcher`() {
        val rig = TileRig(arm = false)
        rig.coordinator.onTap()
        assertEquals("app: a tap while off should open the launcher once", listOf<String?>(null), rig.opener.routes)
        assertEquals("app: a tap while off pushes no state", emptyList<TileState>(), rig.tile.states)
        assertEquals("app: a tap while off clears the notice", listOf("clearNotice"), rig.tile.calls)
    }

    @Test
    fun `a tap with no speech model opens the launcher on the model route and one with a model on the plain launcher`() {
        val missing = TileRig(ready = false)
        missing.coordinator.onTap()
        assertEquals("app: no model should open the model route", listOf<String?>("model"), missing.opener.routes)
        val present = TileRig(ready = true)
        present.coordinator.onTap()
        assertEquals("app: a model should open the plain launcher", listOf<String?>(null), present.opener.routes)
    }

    @Test
    fun `a tap on a failed tile clears the failure and the notice and shows armed`() {
        val rig = TileRig()
        rig.take.beginResult = BeginResult.Failed(FAILED_SENTENCE)
        rig.coordinator.onBegin()
        rig.settle()
        assertEquals("app: the failed begin should leave the tile on FAILED", TileState.FAILED, rig.tile.states.last())
        rig.tile.calls.clear()
        rig.coordinator.onTap()
        assertEquals("app: a tap on FAILED should clear the notice and push ARMED", listOf("clearNotice", "state:ARMED"), rig.tile.calls)
        rig.take.beginResult = BeginResult.Recording
        rig.startRecording()
        assertEquals("app: after the tap the tile should accept a new take", TileState.RECORDING, rig.tile.states.last())
    }

    @Test
    fun `cancelling a take shows armed at once and drops the take on the background`() {
        val rig = TileRig()
        rig.startRecording()
        rig.tile.calls.clear()
        rig.coordinator.onCancel()
        assertEquals("app: cancel should clear the notice and push ARMED", listOf("clearNotice", "state:ARMED"), rig.tile.calls)
        assertEquals("app: the cancel must wait for the background", 0, rig.take.cancelCalls)
        rig.settle()
        assertEquals("app: cancel should drop the take exactly once", 1, rig.take.cancelCalls)
    }

    @Test
    fun `cancelling forgets an earlier failure`() {
        val rig = TileRig()
        rig.take.beginResult = BeginResult.Refused(REFUSED_SENTENCE)
        rig.coordinator.onBegin()
        rig.settle()
        rig.coordinator.onCancel()
        assertEquals("app: cancel after a failure should show ARMED", TileState.ARMED, rig.tile.states.last())
    }

    @Test
    fun `a take that ends by itself puts the tile back to armed`() {
        val rig = TileRig()
        rig.startRecording()
        assertEquals("app: the take should be recording first", TileState.RECORDING, rig.tile.states.last())
        rig.take.sessionState = DictationState.IDLE
        rig.coordinator.onTakeEnded()
        assertEquals("app: a take that ended by itself should show ARMED", TileState.ARMED, rig.tile.states.last())
    }

    @Test
    fun `a take that ends by itself after the switch went off does nothing`() {
        val rig = TileRig()
        rig.coordinator.onArmedChanged(false)
        rig.tile.calls.clear()
        rig.coordinator.onTakeEnded()
        assertEquals("app: onTakeEnded while off should not touch the tile", emptyList<String>(), rig.tile.calls)
    }

    @Test
    fun `a take that ends by itself while the text is on its way leaves the tile on sending`() {
        val rig = TileRig()
        rig.startRecording()
        rig.coordinator.onSend()
        rig.tile.calls.clear()
        rig.coordinator.onTakeEnded()
        assertEquals("app: onTakeEnded while sending should not touch the tile", emptyList<String>(), rig.tile.calls)
    }

    @Test
    fun `every callback after the switch went off is ignored except the tap`() {
        val rig = TileRig()
        rig.coordinator.onArmedChanged(false)
        rig.background.runAll()
        val cancelsBefore = rig.take.cancelCalls
        rig.tile.calls.clear()
        rig.coordinator.onBegin()
        rig.coordinator.onSend()
        rig.coordinator.onCancel()
        rig.settle()
        assertEquals("app: a begin, send or cancel after switch-off must not touch the tile", emptyList<String>(), rig.tile.calls)
        assertEquals("app: a begin after switch-off must not start a take", 0, rig.take.beginCalls)
        assertEquals("app: a send after switch-off must not finish a take", 0, rig.take.finishCalls)
        assertEquals("app: a cancel after switch-off must not cancel again", cancelsBefore, rig.take.cancelCalls)
    }

    @Test
    fun `an answer that arrives after the switch went off and on again is dropped`() {
        val rig = TileRig()
        rig.coordinator.onBegin()
        rig.coordinator.onArmedChanged(false)
        rig.coordinator.onArmedChanged(true)
        rig.settle()
        assertTrue("app: the begin should have run before the cancel", rig.take.beginCalls == 1 && rig.take.cancelCalls == 1)
        assertEquals(
            "app: the stale begin answer must not push RECORDING",
            listOf(TileState.ARMED, TileState.IDLE, TileState.ARMED),
            rig.tile.states,
        )
        rig.startRecording()
        assertEquals("app: a begin after the stale answer should be accepted", TileState.RECORDING, rig.tile.states.last())
        assertEquals("app: the begin after the stale answer should reach the take", 2, rig.take.beginCalls)
    }

    @Test
    fun `an answer that arrives after the switch went off is dropped`() {
        val rig = TileRig()
        rig.take.beginResult = BeginResult.Failed(FAILED_SENTENCE)
        rig.coordinator.onBegin()
        rig.coordinator.onArmedChanged(false)
        rig.settle()
        assertEquals("app: a stale failed begin must not push a state", listOf(TileState.ARMED, TileState.IDLE), rig.tile.states)
        assertEquals("app: a stale failed begin must not show its sentence", emptyList<String>(), rig.tile.notices)
    }

    @Test
    fun `a send answer that arrives after the switch went off and on again is dropped`() {
        val rig = TileRig()
        rig.take.finishResult = FinishResult.Failed("Nothing was heard.")
        rig.startRecording()
        rig.coordinator.onSend()
        rig.coordinator.onArmedChanged(false)
        rig.coordinator.onArmedChanged(true)
        rig.settle()
        assertEquals("app: a tile switched on again starts armed, not on the old take", TileState.ARMED, rig.tile.states.last())
        assertEquals("app: a stale finish must not show its sentence", emptyList<String>(), rig.tile.notices)
        assertEquals("app: the old take should still be finished once", 1, rig.take.finishCalls)
        assertEquals("app: the old take should be dropped once", 1, rig.take.cancelCalls)
    }

    @Test
    fun `a tile, notice or launcher that throws never escapes any callback`() {
        val rig = TileRig()
        rig.tile.throwing = true
        rig.notice.throwing = true
        rig.opener.throwing = true
        rig.coordinator.onArmedChanged(true)
        rig.coordinator.onBegin()
        rig.settle()
        rig.coordinator.onSend()
        rig.settle()
        rig.coordinator.onCancel()
        rig.settle()
        rig.coordinator.onTap()
        rig.coordinator.onTakeEnded()
        rig.coordinator.onArmedChanged(false)
        rig.settle()
        assertTrue("app: the throwing tile should have been reached", rig.tile.calls.size > 5)
        assertEquals("app: the throwing launcher should have been reached once", 1, rig.opener.routes.size)
    }

    @Test
    fun `cancelling after a send frees the next send`() {
        val rig = TileRig()
        rig.startRecording()
        rig.coordinator.onSend()
        rig.coordinator.onCancel()
        assertEquals("app: cancel after a send should show ARMED at once", TileState.ARMED, rig.tile.states.last())
        val queued = rig.background.pending
        rig.coordinator.onSend()
        assertEquals("app: a send after a cancel should show SENDING again", TileState.SENDING, rig.tile.states.last())
        assertEquals("app: a send after a cancel should queue its own finish", queued + 1, rig.background.pending)
        rig.settle()
        assertEquals("app: the tile should end on ARMED", TileState.ARMED, rig.tile.states.last())
    }

    @Test
    fun `a tile or notification that throws on the missing model notice does not stop the other`() {
        val tileFails = TileRig(ready = false)
        tileFails.tile.throwing = true
        tileFails.coordinator.onBegin()
        assertEquals("app: a tile notice that throws must not stop the notification", listOf("clear", "missing"), tileFails.notice.calls)
        assertEquals("app: the throwing tile notice was tried once", listOf(ModelSentences.NO_MODEL), tileFails.tile.notices)
        val noticeFails = TileRig(ready = false)
        noticeFails.notice.throwing = true
        noticeFails.coordinator.onBegin()
        assertEquals("app: a notification that throws must not stop the tile notice", listOf(ModelSentences.NO_MODEL), noticeFails.tile.notices)
    }

    @Test
    fun `a model check that throws counts as no model`() {
        val rig = TileRig()
        rig.model.error = IllegalStateException("the model check failed")
        rig.coordinator.onBegin()
        rig.coordinator.onTap()
        assertEquals("app: a throwing model check should show the missing notice", listOf("clear", "missing"), rig.notice.calls)
        assertEquals("app: a throwing model check must not start a take", 0, rig.take.beginCalls)
        assertEquals("app: a throwing model check should open the model route", listOf<String?>("model"), rig.opener.routes)
    }

    @Test
    fun `a session read that throws counts as idle`() {
        val rig = TileRig(arm = false)
        val coordinator = TileCoordinator(
            rig.tile,
            rig.main,
            rig.background,
            object : TakePort by rig.take {
                override val sessionState: DictationState
                    get() = throw IllegalStateException("the session failed")
            },
            rig.model,
            rig.notice,
            rig.opener,
        )
        coordinator.onArmedChanged(true)
        coordinator.onTap()
        assertEquals("app: an unreadable session should still show ARMED", listOf(TileState.ARMED, TileState.ARMED), rig.tile.states)
    }
}
