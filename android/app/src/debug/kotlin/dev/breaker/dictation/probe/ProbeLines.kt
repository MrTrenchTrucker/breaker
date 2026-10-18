package dev.breaker.dictation.probe

import dev.breaker.dictation.commit.ProbeReading

/** The most lines the recent list shows; the newest ones are kept. */
private const val RECENT_SHOWN: Int = 80

/**
 * The latest block. When the probe is off it is one line and shows no reading. When it is on it is a
 * header line naming the setting, then one line each for the keyboard window, the field focus, the
 * activity inset, the overlay inset and the last event. [clock] formats a time in milliseconds.
 */
fun latestBlock(
    setting: KeyboardSignalSetting,
    reading: ProbeReading?,
    activity: InsetLine?,
    overlay: InsetLine?,
    clock: (Long) -> String,
): List<String> {
    if (setting == KeyboardSignalSetting.OFF) {
        return listOf("Probe is off")
    }
    return listOf(
        "Probe is on (${setting.name})",
        keyboardLine(reading, clock),
        fieldLine(reading, clock),
        insetLine("Activity inset", activity, clock),
        insetLine("Overlay inset", overlay, clock),
        eventLine(reading, clock),
    )
}

/**
 * The recent lines. Empty when the probe is off. When it is on, the readings and the inset lines are
 * merged by time, oldest first and newest last, each as `time source detail`, and at most 80 are kept.
 */
fun recentLines(
    setting: KeyboardSignalSetting,
    readings: List<ProbeReading>,
    insets: List<InsetLine>,
    clock: (Long) -> String,
): List<String> {
    if (setting == KeyboardSignalSetting.OFF) {
        return emptyList()
    }
    val rows: List<Pair<Long, String>> =
        readings.map { reading -> Pair(reading.time, readingRow(reading, clock)) } +
            insets.map { line -> Pair(line.time, insetRow(line, clock)) }
    return rows.sortedBy { it.first }.map { it.second }.takeLast(RECENT_SHOWN)
}

private fun keyboardLine(reading: ProbeReading?, clock: (Long) -> String): String {
    if (reading == null) {
        return "Keyboard window: no reading yet"
    }
    val value: String = if (reading.imeWindow) "present" else "absent"
    return "Keyboard window: $value at ${clock(reading.time)}"
}

private fun fieldLine(reading: ProbeReading?, clock: (Long) -> String): String {
    if (reading == null) {
        return "Field focus: no reading yet"
    }
    val value: String = if (reading.fieldFocus) "editable" else "not editable"
    return "Field focus: $value at ${clock(reading.time)}"
}

private fun insetLine(label: String, line: InsetLine?, clock: (Long) -> String): String {
    if (line == null) {
        return "$label: no reading yet"
    }
    val shown: String = if (line.visible) "visible" else "hidden"
    return "$label: bottom ${line.bottom}, $shown at ${clock(line.time)}"
}

private fun eventLine(reading: ProbeReading?, clock: (Long) -> String): String {
    if (reading == null) {
        return "Last event: no reading yet"
    }
    return "Last event: ${reading.lastEvent} at ${clock(reading.time)}"
}

private fun readingRow(reading: ProbeReading, clock: (Long) -> String): String {
    val field: String = if (reading.fieldFocus) "yes" else "no"
    val keyboard: String = if (reading.imeWindow) "yes" else "no"
    return "${clock(reading.time)} service field focus $field, keyboard window $keyboard, event ${reading.lastEvent}"
}

private fun insetRow(line: InsetLine, clock: (Long) -> String): String {
    val shown: String = if (line.visible) "visible" else "hidden"
    return "${clock(line.time)} ${line.source} bottom ${line.bottom}, $shown"
}
