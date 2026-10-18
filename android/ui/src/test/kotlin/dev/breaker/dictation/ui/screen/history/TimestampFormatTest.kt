package dev.breaker.dictation.ui.screen.history

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The row time: the phone's short date and time for its locale, in its zone. The expected
 * text is given for a fixed instant in a fixed zone. The JDK data differs between versions in
 * the no-break spaces it puts before the clock and after the date, so both are turned into a
 * plain space before the comparison.
 */
class TimestampFormatTest {
    private val instant: Long = Instant.parse("2024-01-02T15:04:00Z").toEpochMilli()
    private val utc: ZoneId = ZoneId.of("UTC")
    private val chicago: ZoneId = ZoneId.of("America/Chicago")

    private fun plain(text: String): String = text.replace('\u202F', ' ').replace('\u00A0', ' ')

    @Test
    fun `the US locale shows month, day, year and a twelve-hour clock with PM`() {
        val text = plain(LocalizedTimestampFormat(Locale.US, utc).format(instant))
        assertEquals("history: US short date and time", "1/2/24, 3:04 PM", text)
    }

    @Test
    fun `the German locale shows day month year and a twenty-four-hour clock`() {
        val text = plain(LocalizedTimestampFormat(Locale.GERMANY, utc).format(instant))
        assertEquals("history: German short date and time", "02.01.24, 15:04", text)
    }

    @Test
    fun `the row time is not the fixed yyyy-MM-dd HH-mm pattern`() {
        val fixed = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(utc).format(Instant.ofEpochMilli(instant))
        assertEquals("history: fixed pattern text", "2024-01-02 15:04", fixed)
        assertNotEquals(
            "history: US output must not be the fixed pattern",
            fixed,
            plain(LocalizedTimestampFormat(Locale.US, utc).format(instant)),
        )
        assertNotEquals(
            "history: German output must not be the fixed pattern",
            fixed,
            plain(LocalizedTimestampFormat(Locale.GERMANY, utc).format(instant)),
        )
    }

    @Test
    fun `a non-UTC zone shows the local hour of the same instant`() {
        val text = plain(LocalizedTimestampFormat(Locale.US, chicago).format(instant))
        assertEquals("history: Chicago short date and time", "1/2/24, 9:04 AM", text)
        assertNotEquals(
            "history: the zone must change the hour",
            plain(LocalizedTimestampFormat(Locale.US, utc).format(instant)),
            text,
        )
    }

    @Test
    fun `just after UTC midnight a non-UTC zone shows the previous local date`() {
        val afterMidnight = Instant.parse("2024-01-02T03:04:00Z").toEpochMilli()
        val local = plain(LocalizedTimestampFormat(Locale.US, chicago).format(afterMidnight))
        assertEquals("history: Chicago shows the previous local date", "1/1/24, 9:04 PM", local)
        val inUtc = plain(LocalizedTimestampFormat(Locale.US, utc).format(afterMidnight))
        assertEquals("history: UTC shows the same instant on the next date", "1/2/24, 3:04 AM", inUtc)
    }
}
