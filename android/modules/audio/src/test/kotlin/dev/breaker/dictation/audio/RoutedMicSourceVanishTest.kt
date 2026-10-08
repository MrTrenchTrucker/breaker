package dev.breaker.dictation.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A device that is unplugged can make the read fail before the platform says
 * the device went away. The source looks again and switches once, silently.
 *
 * A failure means such an unplug ended the take, a real failure was hidden by
 * a switch, or the source switched more than once in one call.
 */
class RoutedMicSourceVanishTest {

    private val buffer = ShortArray(64)

    private class Rig(initial: List<MicDevice>) {
        val port = FakeMicInputPort()
        val devices = FixedDevices(initial)
        val source = RoutedMicSource(devices, port)
    }

    /** Opens the rig and takes one good read of 10 shorts. */
    private fun started(rig: Rig, vararg then: Int) {
        rig.port.reads += 10
        for (r in then) rig.port.reads += r
        rig.source.open()
        rig.source.read(buffer, 0, buffer.size)
    }

    @Test
    fun `a failed read with the chosen device gone switches to the best remaining and returns its count`() {
        val rig = Rig(listOf(bt(1), wired(2), builtIn(3)))
        started(rig, -6, 7)
        rig.devices.list = listOf(wired(2), builtIn(3))
        assertEquals("audio: the read after the switch must return the new count", 7, rig.source.read(buffer, 0, buffer.size))
        assertTrue("audio: the data must be the new device's", filledWith(buffer, 7, 2))
        assertEquals(
            listOf(
                openEvent(bt(1)), READ_EVENT,
                READ_EVENT, CLOSE_EVENT, openEvent(wired(2)), READ_EVENT,
            ),
            rig.port.events,
        )
        rig.port.reads += 4
        assertEquals("audio: the take must go on after the switch", 4, rig.source.read(buffer, 0, buffer.size))
    }

    @Test
    fun `a failed read with every listed device gone switches to the system default`() {
        val rig = Rig(listOf(bt(1)))
        started(rig, -6, 5)
        rig.devices.list = emptyList()
        assertEquals(5, rig.source.read(buffer, 0, buffer.size))
        assertTrue(filledWith(buffer, 5, FakeMicInputPort.DEFAULT_MARKER.toShort()))
        assertEquals(CLOSE_EVENT, rig.port.events[3])
        assertEquals(openEvent(null), rig.port.events[4])
    }

    @Test
    fun `a failed read with the device still listed and no loss throws with the code`() {
        val rig = Rig(listOf(bt(1), builtIn(3)))
        started(rig, -6)
        val error = expectMicFailure("device still present") { rig.source.read(buffer, 0, buffer.size) }
        assertTrue(
            "audio: the message must carry the code but was: ${error.message}",
            error.message.orEmpty().contains("-6"),
        )
        assertEquals(
            "audio: no switch may happen when the device is still there",
            listOf(openEvent(bt(1)), READ_EVENT, READ_EVENT),
            rig.port.events,
        )
    }

    @Test
    fun `a failed read with the route reported lost switches even when the list is unchanged`() {
        val rig = Rig(listOf(bt(1), builtIn(3)))
        started(rig, -6, 7)
        rig.port.lostDuringRead = true
        assertEquals(7, rig.source.read(buffer, 0, buffer.size))
        assertEquals(
            listOf(
                openEvent(bt(1)), READ_EVENT,
                READ_EVENT, CLOSE_EVENT, openEvent(bt(1)), READ_EVENT,
            ),
            rig.port.events,
        )
    }

    @Test
    fun `a second failed read after the switch throws and does not switch again`() {
        val rig = Rig(listOf(bt(1), wired(2), builtIn(3)))
        started(rig, -6, -9, 8)
        rig.devices.list = listOf(wired(2), builtIn(3))
        val error = expectMicFailure("second failure") { rig.source.read(buffer, 0, buffer.size) }
        assertTrue(
            "audio: the message must carry the second code but was: ${error.message}",
            error.message.orEmpty().contains("-9"),
        )
        assertEquals(
            listOf(
                openEvent(bt(1)), READ_EVENT,
                READ_EVENT, CLOSE_EVENT, openEvent(wired(2)), READ_EVENT,
            ),
            rig.port.events,
        )
    }

    @Test
    fun `a failed read right after a loss switch throws and does not switch twice`() {
        val rig = Rig(listOf(bt(1), wired(2), builtIn(3)))
        started(rig, -6, 8)
        rig.devices.list = listOf(wired(2), builtIn(3))
        rig.port.lostPending = true
        rig.port.lostDuringRead = true
        expectMicFailure("failure after a switch") { rig.source.read(buffer, 0, buffer.size) }
        assertEquals(
            listOf(
                openEvent(bt(1)), READ_EVENT,
                CLOSE_EVENT, openEvent(wired(2)), READ_EVENT,
            ),
            rig.port.events,
        )
    }

    @Test
    fun `a failed read on the system default input always throws and lists nothing again`() {
        val rig = Rig(emptyList())
        started(rig, -6, 5)
        rig.devices.list = listOf(builtIn(3))
        rig.port.lostDuringRead = true
        expectMicFailure("default input") { rig.source.read(buffer, 0, buffer.size) }
        assertEquals(listOf(openEvent(null), READ_EVENT, READ_EVENT), rig.port.events)
        assertEquals("audio: the default input must not be listed again", 1, rig.devices.listCalls)
    }
}
