package dev.breaker.dictation.ui.screen.home

import dev.breaker.dictation.ui.screen.history.LocalizedTimestampFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The time of the last dictation on the Home page: the platform's short time style and no date.
 * The date and time format the history rows use is the firing sample: it does carry a date.
 */
class HomeTimeTest {
    private val utc = ZoneId.of("UTC")
    private val instant = 1_760_000_000_000L

    @Test
    fun `the last dictation shows the platform short time style`() {
        val expected = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
            .withLocale(Locale.US)
            .withZone(utc)
            .format(Instant.ofEpochMilli(instant))
        assertEquals("home: the time is the platform's short time", expected, LocalizedTimeFormat(Locale.US, utc).format(instant))
    }

    @Test
    fun `the time shown carries no date`() {
        val text = LocalizedTimeFormat(Locale.US, utc).format(instant)
        assertFalse("home: the time has a slash, so a date came with it: $text", text.contains("/"))
        assertTrue("home: the time has digits: $text", text.any { it.isDigit() })
    }

    @Test
    fun `the date and time format of the history rows does carry a date`() {
        val dateAndTime = LocalizedTimestampFormat(Locale.US, utc).format(instant)
        assertTrue("home: the earlier format carries a date, so the test can tell them apart: $dateAndTime", dateAndTime.contains("/"))
    }
}
