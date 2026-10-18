package dev.breaker.dictation.probe

import dev.breaker.dictation.commit.ProbeReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val readings = (1..50).map {
            ProbeReading(fieldFocus = it % 2L == 0L, imeWindow = false, lastEvent = "e", time = it.toLong())
        }
        val insets = (51..100).map { InsetLine(time = it.toLong(), source = "overlay", bottom = 0, visible = false) }
        val lines = recentLines(KeyboardSignalSetting.FIELD_FOCUS, readings, insets, clock)
        assertEquals("probe: recent lines must be capped at eighty", 80, lines.size)
        assertEquals("probe: the oldest kept line must be the twenty first", "t21", lines.first().substringBefore(' '))
        assertEquals("probe: the newest line must be the last one", "t100", lines.last().substringBefore(' '))
    }

    // --- Point 3: collapse consecutive identical readings ---

    @Test
    fun `consecutive identical readings collapse into one line with a count`() {
        val readings = listOf(
            ProbeReading(fieldFocus = true, imeWindow = false, lastEvent = "typeViewFocused", time = 1L),
            ProbeReading(fieldFocus = true, imeWindow = false, lastEvent = "typeViewFocused", time = 2L),
            ProbeReading(fieldFocus = true, imeWindow = false, lastEvent = "typeViewFocused", time = 3L),
        )
        val lines = recentLines(KeyboardSignalSetting.FIELD_FOCUS, readings, emptyList(), clock)
        assertEquals("probe: identical readings must collapse into one line", 1, lines.size)
        assertTrue("probe: the collapsed line must show the run count", lines[0].contains("3"))
    }

    @Test
    fun `a different reading breaks a collapsed run`() {
        val readings = listOf(
            ProbeReading(fieldFocus = true, imeWindow = false, lastEvent = "typeViewFocused", time = 1L),
            ProbeReading(fieldFocus = true, imeWindow = false, lastEvent = "typeViewFocused", time = 2L),
            ProbeReading(fieldFocus = false, imeWindow = true, lastEvent = "typeWindowsChanged", time = 3L),
            ProbeReading(fieldFocus = false, imeWindow = true, lastEvent = "typeWindowsChanged", time = 4L),
        )
        val lines = recentLines(KeyboardSignalSetting.FIELD_FOCUS, readings, emptyList(), clock)
        assertEquals("probe: two runs must show two lines", 2, lines.size)
        assertTrue("probe: the first run must show count 2", lines[0].contains("2"))
        assertTrue("probe: the second run must show count 2", lines[1].contains("2"))
    }

    @Test
    fun `a different event name breaks a run with the same signals`() {
        val readings = listOf(
            ProbeReading(fieldFocus = true, imeWindow = false, lastEvent = "typeViewFocused", time = 1L),
            ProbeReading(fieldFocus = true, imeWindow = false, lastEvent = "typeWindowShown", time = 2L),
        )
        val lines = recentLines(KeyboardSignalSetting.FIELD_FOCUS, readings, emptyList(), clock)
        assertEquals("probe: readings that differ only by event name must stay two lines", 2, lines.size)
    }

    // --- Points 5 / 5a / 5b: the share text ---

    @Test
    fun `the share text holds only the readings the keyboard tile needs`() {
        val readings = listOf(
            ProbeReading(fieldFocus = true, imeWindow = false, lastEvent = "typeViewFocused", time = 1L),
            ProbeReading(fieldFocus = true, imeWindow = false, lastEvent = "typeViewFocused", time = 2L),
        )
        val insets = listOf(
            InsetLine(time = 3L, source = "activity", bottom = 100, visible = true),
            InsetLine(time = 4L, source = "overlay", bottom = 200, visible = false),
        )
        val text = buildShareText(KeyboardSignalSetting.FIELD_FOCUS, readings, insets, "Pixel", "14")
        assertTrue("probe: the share text must hold the setting summary", text.contains("FIELD_FOCUS"))
        assertTrue("probe: the share text must hold a field focus change", text.contains("Field focus"))
        assertTrue("probe: the share text must hold an inset change", text.contains("Activity inset"))
        assertFalse("probe: the share text must never hold a raw event dump", text.contains("typeViewFocused"))
    }

    @Test
    fun `the share text is capped at two hundred reading lines`() {
        val readings = (1..250).map { ProbeReading(fieldFocus = false, imeWindow = false, lastEvent = "e", time = it.toLong()) }
        val text = buildShareText(KeyboardSignalSetting.FIELD_FOCUS, readings, emptyList(), "Pixel", "14")
        val readingLines = text.lineSequence().filter { it.contains("ime window") }.count()
        assertTrue("probe: the share text must hold at most 200 reading lines, was $readingLines", readingLines <= 200)
    }

    @Test
    fun `the share text is capped at sixteen kilobytes`() {
        val readings = (1..500).map { ProbeReading(fieldFocus = false, imeWindow = false, lastEvent = "e", time = it.toLong()) }
        val text = buildShareText(KeyboardSignalSetting.FIELD_FOCUS, readings, emptyList(), "Pixel", "14")
        val bytes = text.toByteArray(Charsets.UTF_8).size
        assertTrue("probe: the share text must be at most 16384 UTF-8 bytes, was $bytes", bytes <= 16384)
    }

    @Test
    fun `the share text holds exactly two hundred reading lines and names the drop`() {
        val readings = (1..250).map { i ->
            ProbeReading(fieldFocus = i % 2 == 0, imeWindow = false, lastEvent = "event$i", time = i.toLong())
        }
        val text = buildShareText(KeyboardSignalSetting.FIELD_FOCUS, readings, emptyList(), "Pixel", "14")
        val readingLines = text.lineSequence().filter { it.contains("ime window") }.count()
        assertEquals("probe: the share text must hold exactly 200 reading lines, was $readingLines", 200, readingLines)
        assertTrue(
            "probe: the share text must name the 50 dropped lines in its header",
            text.lineSequence().any { it == "Dropped 50 older lines" },
        )
    }

    @Test
    fun `the byte cap drops the oldest inset lines and names the drop`() {
        val insets = (1..200).map { i ->
            InsetLine(time = i.toLong(), source = "overlay" + "x".repeat(180), bottom = i, visible = false)
        }
        val text = buildShareText(KeyboardSignalSetting.FIELD_FOCUS, emptyList(), insets, "Pixel", "14", clock)
        val bytes = text.toByteArray(Charsets.UTF_8).size
        assertTrue("probe: the share text must be at most 16384 UTF-8 bytes, was $bytes", bytes <= 16384)
        assertFalse("probe: the byte cap must drop the oldest inset line", text.contains("bottom 1,"))
        assertTrue("probe: the byte cap must keep the newest inset line", text.contains("bottom 200,"))
        val keptInsets = text.lineSequence().filter { it.contains(Regex("^t\\d+ \\S+ bottom ")) }.count()
        assertTrue(
            "probe: the share text must name the ${200 - keptInsets} dropped lines in its header",
            text.lineSequence().any { it == "Dropped ${200 - keptInsets} older lines" },
        )
    }

    // The shared text is built from the kept 200-line store, never from a 40-line list.

    @Test
    fun `the shared text is built from the kept two hundred line store`() {
        val readings = (1..10).map {
            ProbeReading(fieldFocus = false, imeWindow = false, lastEvent = "e", time = it.toLong())
        }
        val insets = (1..41).map {
            InsetLine(time = 100L + it, source = "overlay", bottom = it, visible = false)
        }
        val text = buildShareText(KeyboardSignalSetting.FIELD_FOCUS, readings, insets, "Pixel", "14")
        for (n in 1..41) {
            assertTrue(
                "probe: inset change $n is missing from the shared text",
                text.contains("bottom $n,"),
            )
        }
    }


    @Test
    fun `the share summary shows the newest activity inset`() {
        val insets = listOf(
            InsetLine(time = 1L, source = "activity", bottom = 100, visible = true),
            InsetLine(time = 2L, source = "activity", bottom = 300, visible = false),
        )
        val text = buildShareText(KeyboardSignalSetting.FIELD_FOCUS, emptyList(), insets, "Pixel", "14", clock)
        assertTrue(
            "probe: the summary must show the newest activity inset",
            text.contains("Activity inset: bottom 300,"),
        )
        assertFalse(
            "probe: the summary must not show the activity inset it has replaced",
            text.contains("Activity inset: bottom 100,"),
        )
    }

    @Test
    fun `the share summary shows the newest overlay inset`() {
        val insets = listOf(
            InsetLine(time = 1L, source = "overlay", bottom = 100, visible = true),
            InsetLine(time = 2L, source = "overlay", bottom = 300, visible = false),
        )
        val text = buildShareText(KeyboardSignalSetting.FIELD_FOCUS, emptyList(), insets, "Pixel", "14", clock)
        assertTrue(
            "probe: the summary must show the newest overlay inset",
            text.contains("Overlay inset: bottom 300,"),
        )
        assertFalse(
            "probe: the summary must not show the overlay inset it has replaced",
            text.contains("Overlay inset: bottom 100,"),
        )
    }

    @Test
    fun `the share list merges readings and inset lines in time order`() {
        val readings = listOf(
            ProbeReading(fieldFocus = true, imeWindow = false, lastEvent = "a", time = 1L),
            ProbeReading(fieldFocus = false, imeWindow = false, lastEvent = "b", time = 3L),
            ProbeReading(fieldFocus = true, imeWindow = false, lastEvent = "c", time = 5L),
        )
        val insets = listOf(
            InsetLine(time = 2L, source = "activity", bottom = 10, visible = true),
            InsetLine(time = 4L, source = "overlay", bottom = 20, visible = false),
        )
        val text = buildShareText(KeyboardSignalSetting.FIELD_FOCUS, readings, insets, "Pixel", "14", clock)
        assertEquals(
            "probe: the share list must merge readings and insets by time, oldest first",
            listOf("t1", "t2", "t3", "t4", "t5"),
            text.lineSequence()
                .map { it.substringBefore(' ') }
                .filter { it.startsWith("t") && it.length > 1 && it[1].isDigit() }
                .toList(),
        )
    }

    @Test
    fun `the line cap drops the oldest line by time and not the oldest reading`() {
        val readings = (51..250).map { i ->
            ProbeReading(fieldFocus = i % 2 == 0, imeWindow = false, lastEvent = "e$i", time = i.toLong())
        }
        val insets = (1..51).map { i ->
            InsetLine(time = i.toLong(), source = "overlay", bottom = i, visible = false)
        }
        val text = buildShareText(KeyboardSignalSetting.FIELD_FOCUS, readings, insets, "Pixel", "14", clock)
        val stamps = text.lineSequence()
            .map { it.substringBefore(' ') }
            .filter { it.startsWith("t") && it.length > 1 && it[1].isDigit() }
            .toList()
        assertEquals("probe: the line cap must keep exactly 200 lines", 200, stamps.size)
        assertTrue("probe: the oldest reading must survive, it is newer than every inset", stamps.contains("t51"))
        assertFalse("probe: the oldest inset must be dropped, it is older than every reading", stamps.contains("t1"))
    }

    @Test
    fun `the share list collapses two readings that differ only by event name`() {
        val readings = listOf(
            ProbeReading(fieldFocus = true, imeWindow = false, lastEvent = "typeViewFocused", time = 1L),
            ProbeReading(fieldFocus = true, imeWindow = false, lastEvent = "typeWindowShown", time = 2L),
        )
        val text = buildShareText(KeyboardSignalSetting.FIELD_FOCUS, readings, emptyList(), "Pixel", "14", clock)
        assertEquals(
            "probe: the share list must collapse readings that differ only by event name",
            1,
            text.lineSequence().filter { it.contains("ime window") }.count(),
        )
        assertTrue("probe: the collapsed line must show the run count", text.contains("ime window: no (x2)"))
    }
}
