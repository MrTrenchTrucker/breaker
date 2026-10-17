package dev.breaker.dictation.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real failures, pass-through of the port's counts, and the open and close
 * rules of a routed source.
 *
 * A failure means a driver error was swallowed or altered, a read worked on a
 * source that is not open, or open and close left the port in a wrong state.
 */
class RoutedMicSourceFailureTest {

    private val buffer = ShortArray(64)

    private fun opened(
        list: List<MicDevice> = listOf(builtIn(3)),
        readResults: List<Int> = emptyList(),
    ): Pair<RoutedMicSource, FakeMicInputPort> {
        val port = FakeMicInputPort()
        port.reads += readResults
        val source = RoutedMicSource(FixedDevices(list), port)
        source.open()
        return source to port
    }

    @Test
    fun `a negative port read throws with the code in the message`() {
        for (code in listOf(-3, -38)) {
            val (source, _) = opened(readResults = listOf(code))
            val error = expectMicFailure("driver code $code") { source.read(buffer, 0, buffer.size) }
            assertTrue(
                "audio: the message must carry the code $code but was: ${error.message}",
                error.message.orEmpty().contains(code.toString()),
            )
        }
    }

    @Test
    fun `a zero read is passed through unchanged`() {
        val (source, port) = opened(readResults = listOf(0, 12))
        assertEquals("audio: zero means nothing yet and must stay zero", 0, source.read(buffer, 0, buffer.size))
        assertEquals(12, source.read(buffer, 0, buffer.size))
        assertEquals(listOf(openEvent(builtIn(3)), READ_EVENT, READ_EVENT), port.events)
    }

    @Test
    fun `a positive read returns the count of the port exactly`() {
        val (source, _) = opened(readResults = listOf(37))
        assertEquals(37, source.read(buffer, 0, buffer.size))
        assertTrue(filledWith(buffer, 37, 3))
    }

    @Test
    fun `a read before open throws not open and never touches the port`() {
        val port = FakeMicInputPort()
        val source = RoutedMicSource(FixedDevices(listOf(builtIn(3))), port)
        val error = expectMicFailure("read before open") { source.read(buffer, 0, buffer.size) }
        assertTrue(error.message.orEmpty().contains("not open"))
        assertEquals(emptyList<String>(), port.events)
    }

    @Test
    fun `a read after close throws not open`() {
        val (source, port) = opened(readResults = listOf(5))
        source.close()
        val error = expectMicFailure("read after close") { source.read(buffer, 0, buffer.size) }
        assertTrue(error.message.orEmpty().contains("not open"))
        assertEquals(listOf(openEvent(builtIn(3)), CLOSE_EVENT), port.events)
    }

    @Test
    fun `a read after a failed open throws not open`() {
        val port = FakeMicInputPort()
        port.failDefault = true
        val source = RoutedMicSource(FixedDevices(emptyList()), port)
        expectMicFailure("open refused") { source.open() }
        expectMicFailure("read after failed open") { source.read(buffer, 0, buffer.size) }
        assertEquals(listOf("open:default", CLOSE_EVENT), port.events)
    }

    @Test
    fun `close before open is safe and closes nothing`() {
        val port = FakeMicInputPort()
        RoutedMicSource(FixedDevices(listOf(builtIn(3))), port).close()
        assertEquals(emptyList<String>(), port.events)
    }

    @Test
    fun `close twice closes the port once per open`() {
        val (source, port) = opened()
        source.close()
        source.close()
        assertEquals(listOf(openEvent(builtIn(3)), CLOSE_EVENT), port.events)
    }

    @Test
    fun `open after close works and lists the devices again`() {
        val port = FakeMicInputPort()
        port.reads += 4
        val devices = FixedDevices(listOf(builtIn(3)))
        val source = RoutedMicSource(devices, port)
        source.open()
        source.close()
        devices.list = listOf(wired(2), builtIn(3))
        source.open()
        assertEquals(4, source.read(buffer, 0, buffer.size))
        assertTrue(filledWith(buffer, 4, 2))
        source.close()
        assertEquals(
            listOf(
                openEvent(builtIn(3)), CLOSE_EVENT,
                openEvent(wired(2)), READ_EVENT, CLOSE_EVENT,
            ),
            port.events,
        )
    }

    @Test
    fun `a second open without a close releases the port first`() {
        val (source, port) = opened()
        source.open()
        assertEquals(listOf(openEvent(builtIn(3)), CLOSE_EVENT, openEvent(builtIn(3))), port.events)
    }

    @Test
    fun `the source reports the rate of the port and one channel`() {
        val port = FakeMicInputPort()
        val source = RoutedMicSource(FixedDevices(emptyList()), port)
        assertEquals(16_000, source.sampleRateHz)
        assertEquals(1, source.channelCount)
        val other = RoutedMicSource(FixedDevices(emptyList()), FakeMicInputPort(sampleRateHz = 48_000))
        assertEquals("audio: the rate comes from the port", 48_000, other.sampleRateHz)
    }
}
