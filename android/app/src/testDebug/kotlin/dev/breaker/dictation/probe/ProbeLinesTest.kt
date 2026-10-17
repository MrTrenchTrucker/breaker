package dev.breaker.dictation.probe

import dev.breaker.dictation.commit.ProbeReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class ProbeLinesTest {

    private val clock: (Long) -> String = { time -> "t$time" }
    private val reading = ProbeReading(fieldFocus = true, imeWindow = true, lastEvent = "typeWindowsChanged", time = 5L)
    private val activity = InsetLine(time = 1L, source = "activity", bottom = 100, visible = true)
    private val overlay = InsetLine(time = 2L, source = "overlay", bottom = 200, visible = false)

    @Test
    fun `an off probe shows one line and no reading`() {
        val lines = latestBlock(KeyboardSignalSetting.OFF, reading, activity, overlay, clock)
        assertEquals("probe: an off probe must show exactly one line", listOf("Probe is off"), lines)
    }

    @Test
    fun `an off probe has no recent lines`() {
        val lines = recentLines(KeyboardSignalSetting.OFF, listOf(reading), listOf(activity), clock)
        assertTrue("probe: an off probe must show no recent lines", lines.isEmpty())
    }

    @Test
    fun `an on probe shows its header and five labelled lines`() {
        val lines = latestBlock(KeyboardSignalSetting.FIELD_FOCUS, reading, activity, overlay, clock)
        assertEquals("probe: an on probe must show six lines", 6, lines.size)
        assertEquals("probe: the header must name the setting", "Probe is on (FIELD_FOCUS)", lines[0])
        assertTrue("probe: line 2 must be the keyboard window", lines[1].startsWith("Keyboard window"))
        assertTrue("probe: line 3 must be the field focus", lines[2].startsWith("Field focus"))
        assertTrue("probe: line 4 must be the activity inset", lines[3].startsWith("Activity inset"))
        assertTrue("probe: line 5 must be the overlay inset", lines[4].startsWith("Overlay inset"))
        assertTrue("probe: line 6 must be the last event", lines[5].startsWith("Last event"))
    }

    @Test
    fun `no reading yet is shown for a missing signal`() {
        val lines = latestBlock(KeyboardSignalSetting.KEYBOARD_VISIBLE, null, null, null, clock)
        assertEquals("probe: an on probe must still show six lines", 6, lines.size)
        for (line in lines.drop(1)) {
            assertTrue("probe: a missing signal must show 'no reading yet', was: $line", line.endsWith("no reading yet"))
        }
    }

    @Test
    fun `recent lines are merged by time with the newest last`() {
        val readings = listOf(
            ProbeReading(fieldFocus = false, imeWindow = false, lastEvent = "typeViewFocused", time = 10L),
            ProbeReading(fieldFocus = true, imeWindow = true, lastEvent = "typeWindowsChanged", time = 30L),
        )
        val insets = listOf(
            InsetLine(time = 20L, source = "activity", bottom = 0, visible = false),
            InsetLine(time = 40L, source = "overlay", bottom = 300, visible = true),
        )
        val lines = recentLines(KeyboardSignalSetting.FIELD_FOCUS, readings, insets, clock)
        assertEquals(
            "probe: recent lines must be merged by time with the newest last",
            listOf("t10", "t20", "t30", "t40"),
            lines.map { it.substringBefore(' ') },
        )
    }

    @Test
    fun `recent lines are capped at eighty`() {
        val readings = (1..50).map { ProbeReading(fieldFocus = false, imeWindow = false, lastEvent = "e", time = it.toLong()) }
        val insets = (51..100).map { InsetLine(time = it.toLong(), source = "overlay", bottom = 0, visible = false) }
        val lines = recentLines(KeyboardSignalSetting.FIELD_FOCUS, readings, insets, clock)
        assertEquals("probe: recent lines must be capped at eighty", 80, lines.size)
        assertEquals("probe: the oldest kept line must be the twenty first", "t21", lines.first().substringBefore(' '))
        assertEquals("probe: the newest line must be the last one", "t100", lines.last().substringBefore(' '))
    }
}
