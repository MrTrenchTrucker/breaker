package dev.breaker.dictation.wiring

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The time of a history entry in the form the Home screen shows it. */
internal fun formatTime(epochMs: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))
