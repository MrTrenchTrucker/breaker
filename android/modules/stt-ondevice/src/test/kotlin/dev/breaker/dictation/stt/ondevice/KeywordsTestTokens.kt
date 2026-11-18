package dev.breaker.dictation.stt.ondevice

/**
 * A made-up tokens file of the real format (one token, a space, then its id, per
 * line) that holds exactly the distinct tokens of the fixed phrases. The ids are
 * invented; the real tokens file of the model is not in this repository.
 */
internal object KeywordsTestTokens {
    /** The word marker U+2581, written here as the literal character. */
    const val MARK = "▁"

    val FILE_TEXT: String = listOf(
        "${MARK}B 11",
        "RE 12",
        "A 13",
        "K 14",
        "ER 15",
        "${MARK}AND 16",
        "${MARK}I 17",
        "' 18",
        "M 19",
        "${MARK}GO 20",
        "NE 21",
    ).joinToString("\n") + "\n"

    /** The token of each line is the part before the last space. */
    fun tokenSet(fileText: String): Set<String> =
        fileText.split('\n').filter { it.isNotEmpty() }.map { it.substringBeforeLast(' ') }.toSet()

    val ALL: Set<String> = tokenSet(FILE_TEXT)
}
