package dev.breaker.dictation.updater

/**
 * The integer release code that Android enforces, read from the manifest's version_code text.
 *
 * Limits: [parse] accepts canonical decimal text only: 1 to 10 ASCII digits, no sign, no space, no leading
 * zero, and a value from 1 to 2 100 000 000. Any other text gives null.
 */
internal object VersionCode {
    /** The largest release code the manifest may carry. */
    internal const val MAX: Int = 2_100_000_000

    private const val MAX_LENGTH: Int = 10

    /** Reads [text] as a canonical release code, or returns null when the text is not one. */
    fun parse(text: String): Int? {
        if (text.isEmpty() || text.length > MAX_LENGTH) return null
        if (!text.all { c -> c in '0'..'9' }) return null
        if (text[0] == '0') return null
        val value = text.toLong()
        if (value !in 1L..MAX.toLong()) return null
        return value.toInt()
    }
}

/** Tells whether an offered release code is newer than the installed one; the comparison is on integers only. */
internal fun isNewerRelease(installedVersionCode: Int, offeredVersionCode: Int): Boolean =
    offeredVersionCode > installedVersionCode
