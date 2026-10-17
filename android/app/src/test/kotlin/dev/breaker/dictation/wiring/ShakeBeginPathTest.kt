package dev.breaker.dictation.wiring

import dev.breaker.dictation.overlay.TileState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A shake never begins a take. It shows the tile when the tile is closed or hidden, and does nothing else;
 * a tap on the tile is the only way to begin. [wired] gives the gesture the same trigger shape the tile host
 * gives it: a trigger that posts the coordinator's onShake to the main queue. The coordinator, the rig and the
 * gesture are the real classes over the test fakes; only the sensor port is a fake.
 */
internal class ShakeBeginPathTest {

    private fun wired(rig: TileRig, port: FakeShakePort, onFire: () -> Unit = { }): ShakeGesture {
        val gesture = ShakeGesture(port)
        gesture.start {
            onFire()
            rig.main.post { rig.coordinator.onShake() }
        }
        return gesture
    }

    @Test
    fun `a shake leaves a shown tile alone and a tap is what begins`() {
        val rig = TileRig()
        val port = FakeShakePort()
        wired(rig, port)
        rig.tile.calls.clear()
        for (reading in shakeReadings()) port.feed(reading)
        rig.settle()
        assertEquals("app: a shake with the tile on screen must add no tile call", emptyList<String>(), rig.tile.calls)
        assertEquals("app: a shake must begin no take", 0, rig.take.beginCalls)
        rig.startRecording()
        assertEquals("app: a tap after a shake must begin one take", 1, rig.take.beginCalls)
        assertEquals("app: a tap after a shake must put the tile on RECORDING", TileState.RECORDING, rig.tile.states.last())
    }

    @Test
    fun `a shake while a take is recording does nothing`() {
        val rig = TileRig()
        val port = FakeShakePort()
        var shakes = 0
        wired(rig, port) { shakes += 1 }
        rig.startRecording()
        assertEquals("app: the tap must start one take", 1, rig.take.beginCalls)
        rig.tile.calls.clear()
        for (reading in shakeReadings()) port.feed(reading)
        rig.settle()
        assertEquals("app: the shake must reach the coordinator", 1, shakes)
        assertEquals("app: a shake while recording must not start a second take", 1, rig.take.beginCalls)
        assertEquals("app: a shake while recording must leave the tile on RECORDING", TileState.RECORDING, rig.tile.states.last())
        assertEquals("app: a shake while recording must add no tile call", emptyList<String>(), rig.tile.calls)
    }

    @Test
    fun `a shake when the coordinator is not armed starts nothing`() {
        val rig = TileRig(arm = false)
        val port = FakeShakePort()
        var shakes = 0
        wired(rig, port) { shakes += 1 }
        for (reading in shakeReadings()) port.feed(reading)
        rig.settle()
        assertEquals("app: the shake must reach the coordinator, which then refuses it", 1, shakes)
        assertEquals("app: a shake while the switch is off must start no take", 0, rig.take.beginCalls)
        assertEquals("app: a shake while the switch is off must not touch the tile", emptyList<String>(), rig.tile.calls)
    }

    @Test
    fun `after the switch goes off a shake starts nothing`() {
        val rig = TileRig()
        val port = FakeShakePort()
        var shakes = 0
        val gesture = wired(rig, port) { shakes += 1 }
        gesture.stop()
        rig.coordinator.onArmedChanged(false)
        rig.settle()
        for (reading in shakeReadings()) port.feed(reading)
        rig.settle()
        assertEquals("app: a stopped gesture must not reach the coordinator", 0, shakes)
        assertEquals("app: a shake after switch-off must start no take", 0, rig.take.beginCalls)
    }
}
