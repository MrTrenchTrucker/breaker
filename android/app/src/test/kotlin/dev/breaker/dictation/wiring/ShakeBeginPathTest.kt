package dev.breaker.dictation.wiring

import dev.breaker.dictation.overlay.TileState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A shake ends in the tile coordinator's begin, through the main queue. [wired] gives the gesture the same
 * trigger shape the tile host gives it (a trigger that posts the coordinator's begin to the main queue). The
 * coordinator, the rig and the gesture are the real classes over the test fakes; only the sensor port is a fake.
 */
internal class ShakeBeginPathTest {

    private fun wired(rig: TileRig, port: FakeShakePort, onShake: () -> Unit = { }): ShakeGesture {
        val gesture = ShakeGesture(port)
        gesture.start {
            onShake()
            rig.main.post { rig.coordinator.onBegin() }
        }
        return gesture
    }

    @Test
    fun `a shake and a tap on the tile end in the same take and the same tile state`() {
        val byTap = TileRig()
        byTap.startRecording()
        val byShake = TileRig()
        val port = FakeShakePort()
        wired(byShake, port)
        for (reading in shakeReadings()) port.feed(reading)
        byShake.settle()
        assertEquals("app: a shake must begin one take, as a tap does", byTap.take.beginCalls, byShake.take.beginCalls)
        assertEquals("app: a shake must leave the tile in the state a tap leaves it", byTap.tile.states.last(), byShake.tile.states.last())
        assertEquals("app: a shake must put the tile on RECORDING", TileState.RECORDING, byShake.tile.states.last())
    }

    @Test
    fun `a shake while a take is recording starts no second take`() {
        val rig = TileRig()
        val port = FakeShakePort()
        var shakes = 0
        wired(rig, port) { shakes += 1 }
        for (reading in shakeReadings()) port.feed(reading)
        rig.settle()
        assertEquals("app: the first shake must start one take", 1, rig.take.beginCalls)
        assertEquals("app: the first shake must bring the tile to RECORDING before the second shake", TileState.RECORDING, rig.tile.states.last())
        for (reading in shakeReadings(offsetMs = 3000L, restMs = 600L)) port.feed(reading)
        rig.settle()
        assertEquals("app: the second shake must reach the coordinator", 2, shakes)
        assertEquals("app: a shake while recording must not start a second take", 1, rig.take.beginCalls)
        assertEquals("app: a shake while recording must leave the tile on RECORDING", TileState.RECORDING, rig.tile.states.last())
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
