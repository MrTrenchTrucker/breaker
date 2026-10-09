package dev.breaker.dictation.stt.ondevice

import dev.breaker.dictation.core.model.HeardWord

/**
 * The word-start marker of the sentence-piece tokens the model writes: a piece that
 * begins with it starts a new word. It is written as an escape so the source stays ASCII.
 */
internal const val WORD_MARKER: Char = '\u2581'

/** Sample rate the start times are counted on. */
private const val MS_GRID_HZ = 16_000L

/**
 * The start of a piece in whole milliseconds, on the 16 kHz sample grid, with the fraction dropped.
 * The time is first taken to the nearest whole sample, so 0.12 s gives 120 and not 119. A
 * negative time gives 0.
 */
internal fun startMsOf(seconds: Float): Long {
    val samples = Math.floor(seconds.toDouble() * MS_GRID_HZ.toDouble() + 0.5).toLong()
    if (samples < 0L) return 0L
    return samples / (MS_GRID_HZ / 1_000L)
}

/**
 * Joins sub-word pieces into words. The piece at index i has the time at index i; only
 * the indices both lists have are used. A piece opens a word when it is the first piece
 * or when it starts with [WORD_MARKER]; any other piece joins the open word. A word's text
 * is its pieces joined with every [WORD_MARKER] removed, and its start is the start of the
 * piece that opened it. A word whose text is blank is skipped.
 */
internal fun mergeWords(tokens: List<String>, timestampsSeconds: List<Float>): List<HeardWord> {
    val count = minOf(tokens.size, timestampsSeconds.size)
    val words = ArrayList<HeardWord>()
    var open: StringBuilder? = null
    var openStart = 0L
    for (i in 0 until count) {
        val piece = tokens[i]
        if (i == 0 || piece.startsWith(WORD_MARKER)) {
            addWord(open, openStart, words)
            open = StringBuilder()
            openStart = startMsOf(timestampsSeconds[i])
        }
        open!!.append(piece)
    }
    addWord(open, openStart, words)
    return words
}

private fun addWord(text: StringBuilder?, startMs: Long, into: MutableList<HeardWord>) {
    if (text == null) return
    val clean = text.toString().replace(WORD_MARKER.toString(), "")
    if (clean.isNotBlank()) into.add(HeardWord(clean, startMs))
}
