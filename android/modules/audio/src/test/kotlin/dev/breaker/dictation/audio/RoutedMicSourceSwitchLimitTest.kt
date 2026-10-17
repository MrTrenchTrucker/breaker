package dev.breaker.dictation.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A supplier that answers each listing with the next scripted list, and with the
 * last one once the script runs out, so a test can change what is present
 * between two looks that the source takes inside one call.
 */
internal class ScriptedDevices(private val lists: List<List<MicDevice>>) : MicDeviceSupplier {
    /** How many times the devices were listed. */
    var listCalls = 0
        private set

    override fun inputs(): List<MicDevice> {
        val answer = lists[minOf(listCalls, lists.size - 1)]
        listCalls++
        return answer
    }
}

/**
 * After one switch in a failed read, the source reads once more and stops
 * looking: even when the new device is also gone and the read fails again.
 *
 * A failure means a failed read switched more than once in one call, listed
 * the devices again after its one switch, or hid the second failure.
 */
class RoutedMicSourceSwitchLimitTest {

    private val buffer = ShortArray(64)

    @Test
    fun `a failed read switches once even when the new device is also gone and the read fails again`() {
        val port = FakeMicInputPort()
        // Listings in order: open, the vanish check, the pick of the switch, then a
        // second vanish check that would say the new device is gone as well.
        val devices = ScriptedDevices(
            listOf(
                listOf(bt(1), wired(2), builtIn(3)),
                listOf(wired(2), builtIn(3)),
                listOf(wired(2), builtIn(3)),
                listOf(builtIn(3)),
            ),
        )
        val source = RoutedMicSource(devices, port)
        port.reads += listOf(10, -6, -9, 8)
        source.open()
        assertEquals(10, source.read(buffer, 0, buffer.size))
        val error = expectMicFailure("second failure after the one switch") { source.read(buffer, 0, buffer.size) }
        assertTrue(
            "audio: the message must carry the second code but was: ${error.message}",
            error.message.orEmpty().contains("-9"),
        )
        assertEquals(
            "audio: exactly one close and open pair is allowed in one read call",
            listOf(
                openEvent(bt(1)), READ_EVENT,
                READ_EVENT, CLOSE_EVENT, openEvent(wired(2)), READ_EVENT,
            ),
            port.events,
        )
        assertEquals("audio: the devices were listed after the one switch", 3, devices.listCalls)
        assertEquals("audio: the last scripted read must be left unread", listOf(8), port.reads.toList())
    }
}
