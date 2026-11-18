package dev.breaker.dictation.updater

/**
 * Compares version names that are exactly four numeric parts, A.B.C.D, each part one to nine
 * ASCII digits. Any other name is not comparable and gives null.
 */
internal object VersionName {
    private const val PARTS = 4
    private const val MAX_DIGITS = 9

    /**
     * Returns -1, 0 or 1 when both names are comparable, comparing the parts from left to right
     * as numbers, and null when either name is not comparable. Never throws.
     */
    fun compare(a: String, b: String): Int? {
        val left = parse(a) ?: return null
        val right = parse(b) ?: return null
        for (index in 0 until PARTS) {
            if (left[index] != right[index]) return if (left[index] < right[index]) -1 else 1
        }
        return 0
    }

    /** The four parts as numbers, or null when the name is not four numeric parts. */
    private fun parse(name: String): LongArray? {
        val pieces = name.split('.')
        if (pieces.size != PARTS) return null
        val values = LongArray(PARTS)
        for (index in 0 until PARTS) {
            val piece = pieces[index]
            if (piece.isEmpty() || piece.length > MAX_DIGITS) return null
            if (!piece.all { it in '0'..'9' }) return null
            values[index] = piece.toLong()
        }
        return values
    }
}
