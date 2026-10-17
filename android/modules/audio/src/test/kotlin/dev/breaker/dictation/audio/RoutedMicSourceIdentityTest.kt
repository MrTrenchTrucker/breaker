package dev.breaker.dictation.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The source must remember exactly which device the port is recording from,
 * and must tell devices apart by their id as well as their kind. These tests
 * cover two ways that memory can go wrong after a failed read.
 *
 * A failure means the source watches a device it is not recording from (after
 * a silent fall back to the phone's own microphone), or it treats a new device
 * of the same kind as the one it was using, so a failed read ends the take
 * instead of switching.
 */
class RoutedMicSourceIdentityTest {

    private val buffer = ShortArray(64)

    private class Rig(initial: List<MicDevice>) {
        val port = FakeMicInputPort()
        val devices = FixedDevices(initial)
        val source = RoutedMicSource(devices, port)
    }

    /** Reads once and fails by name, with the cause attached, when the source throws. */
    private fun readOnce(rig: Rig, what: String): Int {
        try {
            return rig.source.read(buffer, 0, buffer.size)
        } catch (e: MicSourceException) {
            throw AssertionError("audio: $what: the read threw instead of switching: ${e.message}", e)
        }
    }

    /** A rig whose Bluetooth device refuses to open, so the take starts on the built-in one. */
    private fun fellBackRig(): Rig {
        val rig = Rig(listOf(bt(1), builtIn(3)))
        rig.port.failingIds += 1
        rig.port.reads += 10
        rig.source.open()
        rig.source.read(buffer, 0, buffer.size)
        return rig
    }

    /** A rig recording from Bluetooth id 1, after one good read of 10 shorts. */
    private fun bluetoothRig(listing: List<MicDevice>): Rig {
        val rig = Rig(listing)
        rig.port.reads += 10
        rig.source.open()
        rig.source.read(buffer, 0, buffer.size)
        return rig
    }

    @Test
    fun `after a fall back to the built in microphone a failed read follows the built in one leaving the list`() {
        val rig = fellBackRig()
        // The built-in microphone leaves the list; the Bluetooth device that failed to open is listed.
        // It can open now.
        rig.devices.list = listOf(bt(1))
        rig.port.failingIds.clear()
        rig.port.reads += -6
        rig.port.reads += 7
        assertEquals("audio: the read after the switch must return the new count", 7, readOnce(rig, "built-in gone"))
        assertTrue("audio: the data must be the Bluetooth device's", filledWith(buffer, 7, 1))
        assertEquals(
            listOf(
                openEvent(bt(1)), CLOSE_EVENT, openEvent(builtIn(3)), READ_EVENT,
                READ_EVENT, CLOSE_EVENT, openEvent(bt(1)), READ_EVENT,
            ),
            rig.port.events,
        )
    }

    @Test
    fun `after a fall back a failed read with the built in microphone still listed throws and does not switch`() {
        val rig = fellBackRig()
        rig.port.reads += -6
        val error = expectMicFailure("built-in still listed") { rig.source.read(buffer, 0, buffer.size) }
        assertTrue(
            "audio: the message must carry the code but was: ${error.message}",
            error.message.orEmpty().contains("-6"),
        )
        assertEquals(
            "audio: no switch may happen while the device in use is still listed",
            listOf(
                openEvent(bt(1)), CLOSE_EVENT, openEvent(builtIn(3)), READ_EVENT,
                READ_EVENT,
            ),
            rig.port.events,
        )
    }

    @Test
    fun `a failed read after the device was replaced by one of the same kind with a new id switches to it`() {
        val rig = bluetoothRig(listOf(bt(1), builtIn(3)))
        rig.devices.list = listOf(bt(2), builtIn(3))
        rig.port.reads += -6
        rig.port.reads += 7
        assertEquals("audio: the read after the switch must return the new count", 7, readOnce(rig, "new id"))
        assertTrue("audio: the data must be the new Bluetooth device's", filledWith(buffer, 7, 2))
        assertEquals(
            listOf(
                openEvent(bt(1)), READ_EVENT,
                READ_EVENT, CLOSE_EVENT, openEvent(bt(2)), READ_EVENT,
            ),
            rig.port.events,
        )
    }

    @Test
    fun `a failed read with the device in use still listed next to one of the same kind throws and does not switch`() {
        val rig = bluetoothRig(listOf(bt(1), bt(2), builtIn(3)))
        rig.port.reads += -6
        val error = expectMicFailure("same id still listed") { rig.source.read(buffer, 0, buffer.size) }
        assertTrue(
            "audio: the message must carry the code but was: ${error.message}",
            error.message.orEmpty().contains("-6"),
        )
        assertEquals(
            "audio: no switch may happen while the device in use is still listed",
            listOf(openEvent(bt(1)), READ_EVENT, READ_EVENT),
            rig.port.events,
        )
    }
}
