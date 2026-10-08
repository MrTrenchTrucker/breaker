package dev.breaker.dictation.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which device a routed source opens, and that choosing is quiet.
 *
 * A failure means the source opened a worse microphone than the best one
 * present, opened more than one, or did something besides opening.
 */
class RoutedMicSourceSelectionTest {

    private fun openedOn(list: List<MicDevice>): List<String> {
        val port = FakeMicInputPort()
        RoutedMicSource(FixedDevices(list), port).open()
        return port.events
    }

    @Test
    fun `bluetooth is opened when present beside wired and built-in`() {
        assertEquals(listOf(openEvent(bt(1))), openedOn(listOf(builtIn(3), wired(2), bt(1))))
    }

    @Test
    fun `bluetooth alone is opened`() {
        assertEquals(listOf(openEvent(bt(1))), openedOn(listOf(bt(1))))
    }

    @Test
    fun `the first of two bluetooth devices is opened`() {
        assertEquals(listOf(openEvent(bt(6))), openedOn(listOf(bt(6), bt(4))))
    }

    @Test
    fun `wired is opened when there is no bluetooth`() {
        assertEquals(listOf(openEvent(wired(2))), openedOn(listOf(builtIn(3), wired(2))))
    }

    @Test
    fun `built-in is opened when it is all there is`() {
        assertEquals(listOf(openEvent(builtIn(3))), openedOn(listOf(builtIn(3))))
    }

    @Test
    fun `the system default is opened when no device is listed`() {
        assertEquals(listOf("open:default"), openedOn(emptyList()))
    }

    @Test
    fun `choosing is one quiet open with no read close or retry`() {
        // open() returning normally is the "no throw"; the event list is the
        // "nothing else happened": no read, no close, no second open.
        val port = FakeMicInputPort()
        val source = RoutedMicSource(FixedDevices(listOf(bt(1), builtIn(3))), port)
        source.open()
        assertEquals(listOf(openEvent(bt(1))), port.events)
    }

    @Test
    fun `a reopen after close lists the devices again and follows the new list`() {
        val port = FakeMicInputPort()
        val devices = FixedDevices(listOf(bt(1), builtIn(3)))
        val source = RoutedMicSource(devices, port)
        source.open()
        source.close()
        devices.list = listOf(builtIn(3))
        source.open()
        assertEquals(listOf(openEvent(bt(1)), CLOSE_EVENT, openEvent(builtIn(3))), port.events)
    }
}
