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
        collapseReadings(readings).map { run ->
            Pair(run.second.time, readingRow(run.second, clock, run.first))
        } +
            insets.map { line -> Pair(line.time, insetRow(line, clock)) }
    return rows.sortedBy { it.first }.map { it.second }.takeLast(RECENT_SHOWN)
}

/** Collapse consecutive identical readings into runs of (count, reading), oldest first. A run is
 * one line; a change of the two signals or of the event name starts a new run. */
internal fun collapseReadings(readings: List<ProbeReading>): List<Pair<Int, ProbeReading>> {
    val runs: MutableList<Pair<Int, ProbeReading>> = mutableListOf()
    for (reading in readings) {
        val last: Pair<Int, ProbeReading>? = runs.lastOrNull()
        if (last != null && sameReading(last.second, reading)) {
            runs[runs.lastIndex] = (last.first + 1) to last.second
        } else {
            runs.add(1 to reading)
        }
    }
    return runs
}

/**
 * Whether two readings are the same change: the two signals and the event name agree, time apart.
 */
private fun sameReading(first: ProbeReading, second: ProbeReading): Boolean =
    first.fieldFocus == second.fieldFocus &&
        first.imeWindow == second.imeWindow &&
        first.lastEvent == second.lastEvent

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

private fun readingRow(reading: ProbeReading, clock: (Long) -> String, count: Int = 1): String {
    val field: String = if (reading.fieldFocus) "yes" else "no"
    val keyboard: String = if (reading.imeWindow) "yes" else "no"
    val base: String =
        "${clock(reading.time)} service field focus $field, keyboard window $keyboard, event ${reading.lastEvent}"
    return if (count > 1) "$base (x$count)" else base
}

private fun insetRow(line: InsetLine, clock: (Long) -> String): String {
    val shown: String = if (line.visible) "visible" else "hidden"
    return "${clock(line.time)} ${line.source} bottom ${line.bottom}, $shown"
}

/**
 * The share text lives in [ProbeShareText], the next file in this folder: the share summary,
 * the share builder and the share rows, with the share's own two-signal collapse and its own
 * line and byte caps. This file keeps the debug page's lines.
 */
