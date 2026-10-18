package dev.breaker.dictation.wiring

import dev.breaker.dictation.overlay.TileState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The coordinator's entries while a device failure runs, over a fresh tile rig each time.
 *
 * The state "a failure runs and no send is pushed" cannot be reached through the public calls, because
 * onTakeFailed sets sendPushed before it starts the failure. Each test therefore starts the failure through
 * the internal yield, deviceFailure.start(), and leaves the background block of that failure unrun, so the
 * running flag stays true until the rig settles. Each test also runs the same call once with no failure,
 * in the same body, so that a test cannot pass only because the call never reaches its code.
 */
internal class TileCoordinatorBarrierTest {

    /**
     * onSend is barred: with a failure running and no send pushed, it pushes no face and queues no finish.
     * Turned red by a barrier that is missing from onSend: the sending face is pushed and the take is
     * finished a second time.
     */
    @Test
    fun `a send does not start while a device failure runs and no send is pushed`() {
        val rig = TileRig()
        rig.startRecording()
        rig.take.finishResult = FinishResult.ReadyToSend(tileTranscription("captured text"))
        assertTrue("device: the failure starts through the yield", rig.coordinator.deviceFailure.start())
        assertTrue("device: the failure is running before the send", rig.coordinator.deviceFailure.running())
        rig.coordinator.onSend()
        assertEquals("device: a send while the failure runs leaves the recording face", TileState.RECORDING, rig.tile.states.last())
        rig.settle()
        assertEquals("device: only the failure's own finish reaches the take", 1, rig.take.finishCalls)

        val control = TileRig()
        control.startRecording()
        control.coordinator.onSend()
        assertEquals("device: the control send pushes the sending face", TileState.SENDING, control.tile.states.last())
    }

    /**
     * onTakeMicTaken is barred: with a failure running and no send pushed, it shows no busy face, enters no
     * taken branch and queues no finish. Turned red by a barrier that is missing from onTakeMicTaken: the busy
     * face is pushed, the taken branch is entered and the take is finished a second time.
     */
    @Test
    fun `a microphone taken cut does not start while a device failure runs and no send is pushed`() {
        val rig = TileRig()
        rig.startRecording()
        assertTrue("device: the failure starts through the yield", rig.coordinator.deviceFailure.start())
        rig.coordinator.onTakeMicTaken()
        assertFalse("device: the busy face is not pushed while the failure runs", rig.tile.states.contains(TileState.MIC_BUSY))
        assertFalse("device: the taken branch is not entered while the failure runs", rig.coordinator.yield.yieldRunning())
        rig.settle()
        assertEquals("device: only the failure's own finish reaches the take", 1, rig.take.finishCalls)

        val control = TileRig()
        control.startRecording()
        control.coordinator.onTakeMicTaken()
        assertEquals("device: the control cut shows the busy face", TileState.MIC_BUSY, control.tile.states.last())
        assertTrue("device: the control cut enters the taken branch", control.coordinator.yield.yieldRunning())
    }

    /**
     * onTakeEnded is barred: with a failure running and no send pushed, it pushes no face. Turned red by a
     * barrier that is missing from onTakeEnded: the recording face is pushed again at the take end.
     */
    @Test
    fun `a take end does not push a face while a device failure runs and no send is pushed`() {
        val rig = TileRig()
        rig.startRecording()
        assertTrue("device: the failure starts through the yield", rig.coordinator.deviceFailure.start())
        val before: Int = rig.tile.states.size
        rig.coordinator.onTakeEnded()
        assertEquals("device: a take end while the failure runs pushes no face", before, rig.tile.states.size)
        assertTrue("device: the failure still runs after the take end", rig.coordinator.deviceFailure.running())

        val control = TileRig()
        control.startRecording()
        val controlBefore: Int = control.tile.states.size
        control.coordinator.onTakeEnded()
        assertTrue("device: the control take end pushes a face", control.tile.states.size > controlBefore)
    }

    /**
     * onBegin is barred: with a failure running, an armed idle tile starts no begin. The tile is taken armed
     * and idle, not recording: a recording tile is also refused by the face check of onBegin, so without the
     * barrier it would still start nothing. Only the barrier stops the begin here. Turned red by a barrier that
     * is missing from onBegin: the begin reaches the take.
     */
    @Test
    fun `a begin does not start while a device failure runs on an armed idle tile`() {
        val rig = TileRig()
        assertEquals("device: the tile is armed before the begin", TileState.ARMED, rig.tile.states.last())
        assertTrue("device: the failure starts through the yield", rig.coordinator.deviceFailure.start())
        rig.coordinator.onBegin()
        rig.settle()
        assertEquals("device: no begin reaches the take while the failure runs", 0, rig.take.beginCalls)

        val control = TileRig()
        control.coordinator.onBegin()
        control.settle()
        assertEquals("device: the control begin reaches the take", 1, control.take.beginCalls)
    }

    /**
     * onCancel is barred: with a failure running, a cancel pushes no face and reaches no cancel of the take
     * before the settle, and none after it. Turned red by a barrier that is missing from onCancel: the
     * recording face is replaced by the armed face at once, and the cancel reaches the take at the settle.
     */
    @Test
    fun `a cancel does not reach the take while a device failure runs`() {
        val rig = TileRig()
        rig.startRecording()
        assertTrue("device: the failure starts through the yield", rig.coordinator.deviceFailure.start())
        rig.coordinator.onCancel()
        assertEquals("device: a cancel while the failure runs leaves the recording face", TileState.RECORDING, rig.tile.states.last())
        assertEquals("device: no cancel reaches the take before the settle", 0, rig.take.cancelCalls)
        rig.settle()
        assertEquals("device: no cancel reaches the take while the failure runs", 0, rig.take.cancelCalls)

        val control = TileRig()
        control.startRecording()
        control.coordinator.onCancel()
        control.settle()
        assertEquals("device: the control cancel reaches the take", 1, control.take.cancelCalls)
    }

    /**
     * onTakeFailed is barred: with a failure running, a second cut returns false, pushes no face and starts
     * no finish. Turned red by a barrier that is missing from onTakeFailed: the sending face is pushed and
     * then taken back to the recording face, although the second start is refused.
     */
    @Test
    fun `a cut is not taken again while a device failure runs and no send is pushed`() {
        val rig = TileRig()
        rig.startRecording()
        assertTrue("device: the failure starts through the yield", rig.coordinator.deviceFailure.start())
        val before: Int = rig.tile.states.size
        assertFalse("device: a cut while the failure runs is not taken", rig.coordinator.onTakeFailed())
        assertEquals("device: a refused cut pushes no face", before, rig.tile.states.size)
        rig.settle()
        assertEquals("device: the take is finished once, by the failure", 1, rig.take.finishCalls)

        val control = TileRig()
        control.startRecording()
        assertTrue("device: the control cut is taken", control.coordinator.onTakeFailed())
    }

    /**
     * disarm is NOT barred: a switch off clears a failure that runs, and a later switch on takes the next cut.
     * Turned red by a barrier added to disarm: the switch off returns early, the failure keeps running, the
     * tile keeps the recording face and the running check fails first.
     */
    @Test
    fun `a switch off clears a running device failure and a later cut is taken`() {
        val rig = TileRig()
        rig.startRecording()
        rig.take.finishResult = FinishResult.ReadyToSend(tileTranscription("captured text"))
        assertTrue("device: the first cut is taken", rig.coordinator.onTakeFailed())
        assertTrue("device: the failure runs before the switch off", rig.coordinator.deviceFailure.running())
        rig.coordinator.onArmedChanged(false)
        assertFalse("device: the switch off clears the running failure", rig.coordinator.deviceFailure.running())
        assertEquals("device: the switch off shows the idle face", TileState.IDLE, rig.tile.states.last())
        rig.coordinator.onArmedChanged(true)
        assertTrue("device: a later cut is taken after the switch on", rig.coordinator.onTakeFailed())
        rig.settle()
        assertEquals("device: the later cut ends failed", TileState.FAILED, rig.tile.states.last())
    }

    /**
     * fail is NOT barred: a begin answer that arrives while a failure runs is still shown. The begin is queued
     * first and the failure second, so the begin answer is applied on the main queue while the failure still
     * holds the running flag. Turned red by a barrier added to fail: the refused sentence is not shown.
     */
    @Test
    fun `a refused begin answer is still shown while a device failure runs`() {
        val rig = TileRig()
        rig.take.beginResult = BeginResult.Refused(REFUSED_SENTENCE)
        rig.coordinator.onBegin()
        assertEquals("device: the begin is queued and not yet answered", 1, rig.background.pending)
        assertTrue("device: the failure starts behind the begin", rig.coordinator.deviceFailure.start())
        assertEquals("device: the begin and the failure are both queued", 2, rig.background.pending)
        assertEquals("device: no begin has run before the settle", 0, rig.take.beginCalls)
        rig.settle()
        assertTrue("device: the refused sentence is shown", rig.tile.notices.contains(REFUSED_SENTENCE))
        assertEquals("device: the tile ends in the failed face", TileState.FAILED, rig.tile.states.last())

        val control = TileRig()
        control.take.beginResult = BeginResult.Refused(REFUSED_SENTENCE)
        control.coordinator.onBegin()
        control.settle()
        assertTrue("device: the control refused sentence is shown", control.tile.notices.contains(REFUSED_SENTENCE))
    }
}
