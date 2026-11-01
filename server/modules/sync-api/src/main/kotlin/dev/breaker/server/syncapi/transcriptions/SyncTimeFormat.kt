package dev.breaker.server.syncapi.transcriptions

// Formats a stored millisecond clock as the UTC text YYYY-MM-DDTHH:MM:SSZ (whole seconds, no
// fraction). The calendar step is integer arithmetic on the days since 1970-01-01 in the proleptic
// Gregorian calendar, so no date library is involved.
internal fun formatStoredAt(epochMillis: Long): String {
    require(epochMillis >= 0L) { "sync-api: a stored time before 1970 cannot be formatted" }
    val totalSeconds = epochMillis / 1000L
    val days = totalSeconds / 86400L
    val secondOfDay = totalSeconds % 86400L
    val hour = secondOfDay / 3600L
    val minute = (secondOfDay % 3600L) / 60L
    val second = secondOfDay % 60L

    val shiftedDays = days + 719468L
    val era = shiftedDays / 146097L
    val dayOfEra = shiftedDays - era * 146097L
    val yearOfEra = (dayOfEra - dayOfEra / 1460L + dayOfEra / 36524L - dayOfEra / 146096L) / 365L
    val dayOfYear = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L)
    // monthIndex counts from March (0) to February (11).
    val monthIndex = (5L * dayOfYear + 2L) / 153L
    val day = dayOfYear - (153L * monthIndex + 2L) / 5L + 1L
    val month = if (monthIndex < 10L) monthIndex + 3L else monthIndex - 9L
    val yearFromMarch = yearOfEra + era * 400L
    val year = if (month <= 2L) yearFromMarch + 1L else yearFromMarch

    return pad(year, 4) + "-" + pad(month, 2) + "-" + pad(day, 2) +
        "T" + pad(hour, 2) + ":" + pad(minute, 2) + ":" + pad(second, 2) + "Z"
}

private fun pad(value: Long, width: Int): String = value.toString().padStart(width, '0')
