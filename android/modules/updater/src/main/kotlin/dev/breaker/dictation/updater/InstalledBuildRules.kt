package dev.breaker.dictation.updater

/**
 * Turns the platform's version facts into the installed reading.
 *
 * A version code outside 1 to the largest readable code is unreadable and is never cut down to fit. A null or blank
 * name is replaced by the code text.
 */
internal fun installedReadingFrom(code: Long, name: String?): InstalledReading {
    if (code < 1L || code > VersionCode.MAX.toLong()) return InstalledReading.Unreadable
    val text = if (name == null || name.isBlank()) code.toString() else name
    return InstalledReading.Known(InstalledBuild(code.toInt(), text))
}
