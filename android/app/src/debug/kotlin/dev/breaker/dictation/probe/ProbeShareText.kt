package dev.breaker.dictation.probe

import dev.breaker.dictation.commit.ProbeReading

/** The most lines the share text holds; the oldest ones are dropped when one more arrives. */
private const val SHARE_LINE_LIMIT: Int = 200

/** The most UTF-8 bytes the share text holds; the oldest lines are dropped to fit. */
private const val SHARE_BYTE_LIMIT: Int = 16384

/** The summary the share text carries: [latestBlock] without its last event line, so the share
 * text never holds a raw event name. The debug page keeps all six. */
internal fun shareSummary(
    setting: KeyboardSignalSetting,
    reading: ProbeReading?,
    activity: InsetLine?,
    overlay: InsetLine?,
): List<String> {
    if (setting == KeyboardSignalSetting.OFF) {
        return listOf("Probe is off")
    }
    return latestBlock(setting, reading, activity, overlay, { "" }).dropLast(1)
}

/**
 * The share text: the phone model, the Android version and the latest block without its last event
 * line, then one line per kept change - the share readings collapsed on the two signals and the
 * inset lines as one list merged by time, oldest first and newest last, each line with its clock
 * time first and no event name - capped at [SHARE_LINE_LIMIT] lines and [SHARE_BYTE_LIMIT] UTF-8
 * bytes with the oldest by time dropped and the count named in the header.
 */
internal fun buildShareText(
    setting: KeyboardSignalSetting,
    readings: List<ProbeReading>,
    insets: List<InsetLine>,
    model: String,
    androidVersion: String,
    clock: (Long) -> String = { "" },
): String {
    val latest: ProbeReading? = readings.lastOrNull()
    val activity: InsetLine? = insets.lastOrNull { it.source == "activity" }
    val overlay: InsetLine? = insets.lastOrNull { it.source == "overlay" }
    val summary: List<String> = shareSummary(setting, latest, activity, overlay)
    val rows: List<Pair<Long, String>> =
        collapseShareReadings(readings).map { run ->
            Pair(run.second.time, shareReading(run.second, run.first, clock))
        } +
            insets.map { line -> Pair(line.time, shareInset(line, clock)) }
    val ordered: List<String> = rows.sortedBy { it.first }.map { it.second }
    var kept: List<String> = ordered.takeLast(SHARE_LINE_LIMIT)
    var dropped: Int = ordered.size - kept.size

    fun assemble(): String {
        val head: List<String> = buildList {
            add("Phone: $model")
            add("Android: $androidVersion")
            addAll(summary)
            if (dropped > 0) {
                add("Dropped $dropped older lines")
            }
        }
        return (head + kept).joinToString("\n")
    }

    while (assemble().toByteArray(Charsets.UTF_8).size > SHARE_BYTE_LIMIT && kept.isNotEmpty()) {
        kept = kept.drop(1)
        dropped += 1
    }
    return assemble()
}

/** Collapse consecutive identical readings into runs of (count, reading), oldest first. The share
 * list compares the two signals only and never the event name, so two readings that read the same
 * stay one line even when different events produced them; the page keeps event-name runs. */
internal fun collapseShareReadings(readings: List<ProbeReading>): List<Pair<Int, ProbeReading>> {
    val runs: MutableList<Pair<Int, ProbeReading>> = mutableListOf()
    for (reading in readings) {
        val last: Pair<Int, ProbeReading>? = runs.lastOrNull()
        if (last != null && sameShareReading(last.second, reading)) {
            runs[runs.lastIndex] = (last.first + 1) to last.second
        } else {
            runs.add(1 to reading)
        }
    }
    return runs
}

/** Whether two readings read the same for the share list: the two signals agree, the event name and
 * the time apart. */
private fun sameShareReading(first: ProbeReading, second: ProbeReading): Boolean =
    first.fieldFocus == second.fieldFocus && first.imeWindow == second.imeWindow

internal fun shareReading(reading: ProbeReading, count: Int, clock: (Long) -> String): String {
    val field: String = if (reading.fieldFocus) "yes" else "no"
    val keyboard: String = if (reading.imeWindow) "yes" else "no"
    val base: String = "Field focus: $field, ime window: $keyboard"
    val line: String = if (count > 1) "$base (x$count)" else base
    return stamp(clock(reading.time), line)
}

internal fun shareInset(line: InsetLine, clock: (Long) -> String): String {
    val shown: String = if (line.visible) "visible" else "hidden"
    return stamp(clock(line.time), "${line.source} bottom ${line.bottom}, $shown")
}

/** The clock time first, when the builder was given a clock; the bare line when it was not. */
private fun stamp(time: String, line: String): String = if (time.isEmpty()) line else "$time $line"
