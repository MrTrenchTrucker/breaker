package dev.breaker.dictation.ui.screen.history

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/*
 * How a row shows its time.
 *
 * The row shows the phone's own short date and time, in the language and the zone
 * the phone is set to. Nothing here is a fixed pattern, so the order of the date
 * and the clock style follow the locale.
 */

/** Turns an epoch-millisecond time into the text a row shows. */
internal fun interface TimestampFormat {
    fun format(epochMs: Long): String
}

/** The short date and time for [locale], shown in [zone]. */
internal class LocalizedTimestampFormat(
    private val locale: Locale,
    private val zone: ZoneId,
) : TimestampFormat {
    private val formatter: DateTimeFormatter =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale).withZone(zone)

    override fun format(epochMs: Long): String = formatter.format(Instant.ofEpochMilli(epochMs))
}
