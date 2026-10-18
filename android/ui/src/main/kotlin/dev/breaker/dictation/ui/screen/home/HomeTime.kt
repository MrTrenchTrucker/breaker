package dev.breaker.dictation.ui.screen.home

import dev.breaker.dictation.ui.screen.history.TimestampFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/*
 * How the home screen shows the time of the last dictation.
 *
 * The home screen shows only the phone's own short time, in the language and the zone the
 * phone is set to. The date is left out, so the row carries the time alone.
 */

/** The short time for [locale], shown in [zone]. */
internal class LocalizedTimeFormat(
    private val locale: Locale,
    private val zone: ZoneId,
) : TimestampFormat {
    private val formatter: DateTimeFormatter =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).withZone(zone)

    override fun format(epochMs: Long): String = formatter.format(Instant.ofEpochMilli(epochMs))
}
