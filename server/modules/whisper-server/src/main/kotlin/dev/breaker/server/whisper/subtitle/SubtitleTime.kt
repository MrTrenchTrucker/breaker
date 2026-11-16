package dev.breaker.server.whisper.subtitle

// Renders a millisecond count as HH:MM:SS<separator>mmm. Hours are NOT wrapped: a span longer
// than a day keeps counting, because a wrapped hour would place the cue at the wrong time.

/** [millis] as HH:MM:SS<separator>mmm; minutes and seconds are two digits, millis three. */
internal fun renderTime(millis: Long, separator: Char): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    val millisOfSecond = millis % 1000
    return hours.toString().padStart(2, '0') + ":" +
        minutes.toString().padStart(2, '0') + ":" +
        seconds.toString().padStart(2, '0') + separator +
        millisOfSecond.toString().padStart(3, '0')
}
