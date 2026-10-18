package dev.breaker.dictation.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Following the microphone when it changes, and falling back silently when a
 * device cannot be opened.
 *
 * A failure means a lost microphone ended or broke the take, the source
 * switched to the wrong device or in the wrong order, or a fallback made
 * noise or was retried the wrong number of times.
 */
class RoutedMicSourceFallbackTest {

    private val buffer = ShortArray(64)

    private class Rig(initial: List<MicDevice>) {
        val port = FakeMicInputPort()
        val devices = FixedDevices(initial)
        val source = RoutedMicSource(devices, port)
    }

    /** Opens the rig, reads once, then loses the route with [after] as the new list. */
    private fun switchTo(rig: Rig, after: List<MicDevice>, firstRead: Int, switchRead: Int): Int {
        rig.port.reads += firstRead
        rig.port.reads += switchRead
        rig.source.open()
        rig.source.read(buffer, 0, buffer.size)
        rig.devices.list = after
        rig.port.lostPending = true
        return rig.source.read(buffer, 0, buffer.size)
    }

    @Test
    fun `losing bluetooth switches to wired inside the read and returns the new data`() {
        val rig = Rig(listOf(bt(1), wired(2), builtIn(3)))
        val count = switchTo(rig, listOf(wired(2), builtIn(3)), firstRead = 10, switchRead = 7)
        assertEquals("audio: the switch read must return the new device's count", 7, count)
        assertTrue("audio: the data after a switch must be the new device's", filledWith(buffer, 7, 2))
        assertEquals(
            listOf(
                openEvent(bt(1)), READ_EVENT,
                CLOSE_EVENT, openEvent(wired(2)), READ_EVENT,
            ),
            rig.port.events,
        )
    }

    @Test
    fun `losing bluetooth and wired switches to built-in`() {
        val rig = Rig(listOf(bt(1), wired(2), builtIn(3)))
        val count = switchTo(rig, listOf(builtIn(3)), firstRead = 10, switchRead = 5)
        assertEquals(5, count)
        assertTrue(filledWith(buffer, 5, 3))
        assertEquals(
            listOf(openEvent(bt(1)), READ_EVENT, CLOSE_EVENT, openEvent(builtIn(3)), READ_EVENT),
            rig.port.events,
        )
    }

    @Test
    fun `losing every device switches to the system default`() {
        val rig = Rig(listOf(bt(1)))
        val count = switchTo(rig, emptyList(), firstRead = 10, switchRead = 4)
        assertEquals(4, count)
        assertTrue(filledWith(buffer, 4, FakeMicInputPort.DEFAULT_MARKER.toShort()))
        assertEquals(
            listOf(openEvent(bt(1)), READ_EVENT, CLOSE_EVENT, "open:default", READ_EVENT),
            rig.port.events,
        )
    }

    @Test
    fun `the switch lists the devices again and picks from the current list`() {
        val rig = Rig(listOf(bt(1), builtIn(3)))
        switchTo(rig, listOf(bt(8), builtIn(3)), firstRead = 10, switchRead = 6)
        assertEquals("audio: a lost route must list the devices again", 2, rig.devices.listCalls)
        assertEquals(openEvent(bt(8)), rig.port.events[3])
        assertTrue(filledWith(buffer, 6, 8))
    }

    @Test
    fun `a second loss switches again`() {
        val rig = Rig(listOf(bt(1), wired(2), builtIn(3)))
        rig.port.reads += listOf(10, 7, 5)
        rig.source.open()
        rig.source.read(buffer, 0, buffer.size)
        rig.devices.list = listOf(wired(2), builtIn(3))
        rig.port.lostPending = true
        assertEquals(7, rig.source.read(buffer, 0, buffer.size))
        rig.devices.list = listOf(builtIn(3))
        rig.port.lostPending = true
        assertEquals(5, rig.source.read(buffer, 0, buffer.size))
        assertTrue(filledWith(buffer, 5, 3))
        assertEquals(
            listOf(
                openEvent(bt(1)), READ_EVENT,
                CLOSE_EVENT, openEvent(wired(2)), READ_EVENT,
                CLOSE_EVENT, openEvent(builtIn(3)), READ_EVENT,
            ),
            rig.port.events,
        )
    }

    @Test
    fun `a lost flag read once does not switch again`() {
        val rig = Rig(listOf(bt(1), builtIn(3)))
        rig.port.reads += listOf(10, 7, 9, 3)
        rig.source.open()
        rig.source.read(buffer, 0, buffer.size)
        rig.devices.list = listOf(builtIn(3))
        rig.port.lostPending = true
        rig.source.read(buffer, 0, buffer.size)
        assertEquals(9, rig.source.read(buffer, 0, buffer.size))
        assertEquals(3, rig.source.read(buffer, 0, buffer.size))
        assertEquals(
            listOf(
                openEvent(bt(1)), READ_EVENT,
                CLOSE_EVENT, openEvent(builtIn(3)), READ_EVENT,
                READ_EVENT, READ_EVENT,
            ),
            rig.port.events,
        )
        assertEquals("audio: reads with no loss must not list the devices", 2, rig.devices.listCalls)
    }

    @Test
    fun `a failed open of the pick falls back silently to the built-in`() {
        val rig = Rig(listOf(bt(1), wired(2), builtIn(3)))
        rig.port.failingIds += 1
        rig.port.reads += 6
        rig.source.open()
        assertEquals(6, rig.source.read(buffer, 0, buffer.size))
        assertTrue(filledWith(buffer, 6, 3))
        assertEquals(
            listOf(openEvent(bt(1)), CLOSE_EVENT, openEvent(builtIn(3)), READ_EVENT),
            rig.port.events,
        )
    }

    @Test
    fun `a failed wired pick with no built-in listed falls back to the default`() {
        val rig = Rig(listOf(wired(2)))
        rig.port.failingIds += 2
        rig.source.open()
        assertEquals(listOf(openEvent(wired(2)), CLOSE_EVENT, "open:default"), rig.port.events)
    }

    @Test
    fun `when the pick and the fallback both fail open throws once with the cause`() {
        val rig = Rig(listOf(bt(1), builtIn(3)))
        rig.port.failingIds += setOf(1, 3)
        val error = expectMicFailure("both opens refused") { rig.source.open() }
        assertNotNull("audio: the cause must be attached", error.cause)
        assertTrue(error.message.orEmpty().contains("microphone"))
        assertEquals(
            "audio: exactly one retry, then give up",
            listOf(openEvent(bt(1)), CLOSE_EVENT, openEvent(builtIn(3)), CLOSE_EVENT),
            rig.port.events,
        )
    }

    @Test
    fun `a failed built-in pick is not retried`() {
        val rig = Rig(listOf(builtIn(3)))
        rig.port.failingIds += 3
        val error = expectMicFailure("built-in refused") { rig.source.open() }
        assertNotNull(error.cause)
        assertEquals(listOf(openEvent(builtIn(3)), CLOSE_EVENT), rig.port.events)
    }

    @Test
    fun `a failed default open is not retried`() {
        val rig = Rig(emptyList())
        rig.port.failDefault = true
        expectMicFailure("default refused") { rig.source.open() }
        assertEquals(listOf("open:default", CLOSE_EVENT), rig.port.events)
    }

    @Test
    fun `a switch whose first choice fails falls back to the built-in inside the read`() {
        val rig = Rig(listOf(bt(1), wired(2), builtIn(3)))
        rig.port.failingIds += 2
        val count = switchTo(rig, listOf(wired(2), builtIn(3)), firstRead = 10, switchRead = 8)
        assertEquals(8, count)
        assertTrue(filledWith(buffer, 8, 3))
        assertEquals(
            listOf(
                openEvent(bt(1)), READ_EVENT,
                CLOSE_EVENT, openEvent(wired(2)), CLOSE_EVENT, openEvent(builtIn(3)), READ_EVENT,
            ),
            rig.port.events,
        )
    }

    @Test
    fun `a switch whose re-open fails entirely throws and leaves the source closed`() {
        val rig = Rig(listOf(bt(1), builtIn(3)))
        rig.port.failingIds += setOf(3)
        rig.port.reads += 10
        rig.source.open()
        rig.source.read(buffer, 0, buffer.size)
        rig.devices.list = listOf(builtIn(3))
        rig.port.lostPending = true
        expectMicFailure("re-open refused") { rig.source.read(buffer, 0, buffer.size) }
        expectMicFailure("read after a failed switch") { rig.source.read(buffer, 0, buffer.size) }
        assertEquals(
            listOf(openEvent(bt(1)), READ_EVENT, CLOSE_EVENT, openEvent(builtIn(3)), CLOSE_EVENT),
            rig.port.events,
        )
    }
}
