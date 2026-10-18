package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.overlay.TileState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the device failure clause of onBegin: a second microphone call while a device failure still runs
 * starts no take, even when the face is armed again after a begin that answered failed.
 * The fake take stands in for a runner that recorded and then answered failed, so the session is set to
 * recording by hand (the fake mirrors recording only for a recording answer).
 * The control drives the same steps once the failure has settled, and then a take starts.
 */
internal class TileCoordinatorDeviceFailureRaceTest {

    @Test
    fun `a second mic call is refused while a device failure still runs after a begin answered failed`() {
        val rig = driveRace(settled = false)
        rig.coordinator.onTap()
        assertTrue("device: the device failure is still running before the second mic call", rig.coordinator.deviceFailure.running())
        assertEquals("device: the face is armed again before the second mic call", TileState.ARMED, rig.tile.states.last())
        assertEquals("device: only the first mic call has reached the take", 1, rig.take.beginCalls)
        assertEquals("device: no begin is queued before the second mic call", 0, rig.background.pending)
        rig.coordinator.onBegin()
        assertEquals("device: no begin is queued by the second mic call", 0, rig.background.pending)
        rig.background.runAll()
        assertEquals("device: no new take starts while the device failure runs", 1, rig.take.beginCalls)
    }

    @Test
    fun `the same drive starts a take once the device failure has settled`() {
        // The two tests differ only in the running flag: here the failure's answer is applied first.
        val rig = driveRace(settled = true)
        rig.coordinator.onTap()
        assertFalse("device: the device failure has settled before the next mic call", rig.coordinator.deviceFailure.running())
        assertEquals("device: the face is armed again before the next mic call", TileState.ARMED, rig.tile.states.last())
        assertEquals("device: only the first mic call has reached the take", 1, rig.take.beginCalls)
        assertEquals("device: no begin is queued before the next mic call", 0, rig.background.pending)
        rig.coordinator.onBegin()
        assertEquals("device: the next mic call queues one begin", 1, rig.background.pending)
        rig.background.runAll()
        assertEquals("device: the next mic call starts a take once the failure has settled", 2, rig.take.beginCalls)
    }

    /**
     * Builds a rig and drives the shared first part in a fixed order: the take records, its begin answers
     * failed, the microphone fails, and the failure's finish runs. With [settled] the failure's answer is
     * applied too, which frees the running flag.
     */
    private fun driveRace(settled: Boolean): TileRig {
        val rig = TileRig()
        rig.take.sessionState = DictationState.RECORDING
        rig.take.beginResult = BeginResult.Failed(FAILED_SENTENCE)
        rig.coordinator.onBegin()
        rig.background.runAll()
        assertTrue("device: the cut is taken while the take records", rig.coordinator.onTakeFailed())
        rig.main.drain()
        rig.background.runAll()
        if (settled) {
            rig.main.drain()
        }
        return rig
    }
}
