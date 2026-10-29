package dev.breaker.dictation.commit

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The live view of the latest reading, [readings]: it replays the current value when a reader
 * starts collecting and then hands out each distinct change once. This is plain JUnit 4 over the
 * public API only, so no coroutines-test dependency is needed; a tiny harness models what a
 * collector on the main thread would see: its first value is the replay (the current reading at
 * attach) and afterwards it records each distinct change once. The holder is one process-wide
 * object, so every test turns it off and clears it before and after, and no test depends on what
 * another left behind.
 */
internal class KeyboardProbeReadingsFlowTest {

    private class Reader {
        val values = mutableListOf<ProbeReading?>()

        fun attach() {
            push(KeyboardProbeReadings.current())
        }

        fun observe() {
            push(KeyboardProbeReadings.current())
        }

        private fun push(value: ProbeReading?) {
            if (values.isEmpty() || values.last() != value) values.add(value)
        }
    }

    @Before
    fun startOff() {
        KeyboardProbeReadings.setEnabled(false)
        KeyboardProbeReadings.reset()
    }

    @After
    fun endOff() {
        KeyboardProbeReadings.setEnabled(false)
        KeyboardProbeReadings.reset()
    }

    private fun publishLatest(time: Long): ProbeReading =
        KeyboardProbeReadings.publishLatest(
            fieldFocus = true,
            imeWindow = false,
            lastEvent = "typed in",
            time = time,
        )

    @Test
    fun `a fresh holder reads its first value as null`() {
        val reader = Reader()
        reader.attach()

        assertEquals(
            "commit: a fresh holder must read its first value as null",
            null,
            reader.values.first(),
        )
    }

    @Test
    fun `a published reading reaches the reader with its field event and time`() {
        KeyboardProbeReadings.setEnabled(true)
        val reader = Reader()
        reader.attach()

        publishLatest(1L)
        reader.observe()

        assertEquals(
            "commit: a published reading must reach the reader as its latest",
            listOf(null, ProbeReading(true, false, "typed in", 1L)),
            reader.values,
        )
    }

    @Test
    fun `the latest reaches the reader while the probe is off and no recent line is stored`() {
        val reader = Reader()
        reader.attach()

        KeyboardProbeReadings.publishLatest(false, false, "quiet", 5L)
        reader.observe()

        assertEquals(
            "commit: an off probe must store its latest reading even while off",
            5L,
            KeyboardProbeReadings.current()?.time,
        )
        assertTrue("commit: an off probe must not append a recent line", KeyboardProbeReadings.recent().isEmpty())
        assertEquals(
            "commit: the reader must see the stored latest even while the probe is off",
            listOf(null, ProbeReading(false, false, "quiet", 5L)),
            reader.values,
        )
    }

    @Test
    fun `disabling after a live reading re-reads null to the reader`() {
        KeyboardProbeReadings.setEnabled(true)
        publishLatest(1L)

        val reader = Reader()
        reader.attach()
        assertEquals(
            "commit: the reader must see the last published reading at attach",
            1L,
            reader.values.last()?.time,
        )

        KeyboardProbeReadings.setEnabled(false)
        reader.observe()

        assertEquals(
            "commit: disabling after a live reading must re-read null to the reader",
            listOf(ProbeReading(true, false, "typed in", 1L), null),
            reader.values,
        )
    }

    @Test
    fun `each published reading reaches the reader in order`() {
        KeyboardProbeReadings.setEnabled(true)

        val reader = Reader()
        reader.attach()

        publishLatest(1L)
        reader.observe()

        publishLatest(2L)
        reader.observe()

        assertEquals(
            "commit: each published reading must reach the reader, oldest first",
            listOf(null, ProbeReading(true, false, "typed in", 1L), ProbeReading(true, false, "typed in", 2L)),
            reader.values,
        )
    }
}
